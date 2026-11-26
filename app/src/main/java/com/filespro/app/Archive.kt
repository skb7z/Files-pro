package com.filespro.app

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.lingala.zip4j.model.ExcludeFileFilter
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.AesKeyStrength
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import net.lingala.zip4j.ZipFile as Z4
import net.lingala.zip4j.exception.ZipException as Z4Exception

/** ZIP তৈরি (ঐচ্ছিক AES-256 পাসওয়ার্ড সহ) এবং খোলা। পাসওয়ার্ড কোথাও save বা log হয় না। */
object Arc {
    fun docsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).also { it.mkdirs() }

    private fun destFor(name: String): File {
        val type = when (Fx.kind(name.substringAfterLast('.', "").lowercase())) {
            1 -> Environment.DIRECTORY_PICTURES
            2 -> Environment.DIRECTORY_MUSIC
            3 -> Environment.DIRECTORY_MOVIES
            else -> Environment.DIRECTORY_DOCUMENTS
        }
        return Environment.getExternalStoragePublicDirectory(type)
    }

    /** নাম ঠিক থাকলে ".zip" ছাড়া পরিষ্কার নাম ফেরত দেয়, নাহলে null। */
    fun cleanName(raw: String): String? {
        var n = raw.trim()
        if (n.endsWith(".zip", true)) n = n.dropLast(4).trim()
        if (n.isEmpty() || n.startsWith(".") || n.any { it == '/' || it == '\\' || it == ':' || it == '*' || it == '?' || it == '"' || it == '<' || it == '>' || it == '|' }) return null
        return n
    }

    fun isEncrypted(zip: File): Boolean = try { Z4(zip).isEncrypted } catch (e: Exception) { false }

    private fun open(zip: File, pw: CharArray?): Z4 = if (pw != null && pw.isNotEmpty()) Z4(zip, pw) else Z4(zip)

    private fun friendly(e: Exception): Exception =
        if (e is Z4Exception && e.type == Z4Exception.Type.WRONG_PASSWORD) IOException("Wrong password") else e

    private fun uniqueIn(used: MutableSet<String>, name: String): String {
        if (used.add(name)) return name
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            val n = "$base ($i)$ext"
            if (used.add(n)) return n
            i++
        }
    }

    /** আগে .part ফাইলে বানায়, যাচাই করে, তারপর নাম বদলায়। তাই ব্যর্থ হলে আগের ফাইল বা অর্ধেক ফাইল পড়ে থাকে না। */
    fun create(ctx: Context, files: List<File>, out: File, password: CharArray?, replace: Boolean) {
        if (files.isEmpty()) throw IOException("Nothing selected")
        out.parentFile?.mkdirs()
        if (out.exists() && !replace) throw IOException("A file with that name already exists")
        val tmp = File(out.parentFile, out.name + ".part")
        tmp.delete()
        try {
            val base = ZipParameters().apply {
                setCompressionMethod(CompressionMethod.DEFLATE)
                setCompressionLevel(CompressionLevel.NORMAL)
                if (password != null) {
                    setEncryptFiles(true)
                    setEncryptionMethod(EncryptionMethod.AES)
                    setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256)
                }
                val self = tmp.canonicalPath
                setExcludeFileFilter(ExcludeFileFilter { f -> f.canonicalPath == self })
            }
            val z = if (password != null) Z4(tmp, password) else Z4(tmp)
            val used = HashSet<String>()
            for (f in files) {
                val p = ZipParameters(base)
                val n = uniqueIn(used, f.name)
                if (f.isDirectory) {
                    p.setRootFolderNameInZip(n)
                    z.addFolder(f, p)
                } else {
                    p.setFileNameInZip(n)
                    z.addFile(f, p)
                }
            }
            if (!Z4(tmp).isValidZipFile) throw IOException("The new ZIP could not be verified")
            if (out.exists() && !out.delete()) throw IOException("Could not replace the old file")
            if (!tmp.renameTo(out)) throw IOException("Could not save the ZIP")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        scanWait(ctx, listOf(out.path))
        Index.add(listOf(out))
    }

    /** ফাইলের ধরন অনুযায়ী Pictures / Movies / Music / Documents এ রাখে। নতুন ফাইল তালিকার সবার উপরে দেখায়। */
    fun extractSorted(ctx: Context, zip: File, pw: CharArray?) {
        val z = open(zip, pw)
        val made = ArrayList<File>()
        try {
            val now = System.currentTimeMillis()
            for (h in z.fileHeaders) {
                if (h.isDirectory) continue
                val name = h.fileName.substringAfterLast('/').substringAfterLast('\\')
                if (name.isBlank()) continue
                val dir = destFor(name); dir.mkdirs()
                val out = Fx.unique(dir, name)
                made.add(out)
                z.getInputStream(h).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                out.setLastModified(now)
            }
            if (made.isEmpty()) throw IOException("This ZIP has no files")
        } catch (e: Exception) {
            made.forEach { it.delete() }
            throw friendly(e)
        }
        scanWait(ctx, made.map { it.path })
        Index.add(made)
    }

    /** ZIP এর পাশে ZIP এর নামে একটি ফোল্ডারে খোলে। */
    fun extractToFolder(ctx: Context, zip: File, pw: CharArray?) {
        val dest = Fx.unique(zip.parentFile ?: Fx.downloads(), zip.nameWithoutExtension)
        try {
            dest.mkdirs()
            open(zip, pw).extractAll(dest.path)
        } catch (e: Exception) {
            dest.deleteRecursively()
            throw friendly(e)
        }
        val made = dest.walkTopDown().filter { it.isFile }.toList()
        scanWait(ctx, made.map { it.path })
        Index.add(made)
    }

    private fun scanWait(ctx: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        val latch = CountDownLatch(paths.size)
        try {
            MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), null) { _, _ -> latch.countDown() }
            latch.await(15, TimeUnit.SECONDS)
        } catch (e: Exception) { }
    }
}

/** বেছে নেওয়া ফাইল/ফোল্ডার ZIP করার ডায়ালগ: নাম, ঐচ্ছিক পাসওয়ার্ড, Documents এ save। */
@Composable
fun CompressDialog(ui: Ui, files: List<File>, onClose: () -> Unit, onDone: () -> Unit) {
    var name by remember { mutableStateOf(if (files.size == 1) files[0].let { if (it.isDirectory) it.name else it.nameWithoutExtension } else "Archive") }
    var usePw by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf<File?>(null) }
    var cleanN by remember { mutableStateOf("") }

    fun run(out: File, replace: Boolean) {
        val p = if (usePw) pw.toCharArray() else null
        ui.work("Saved ${out.name} in Documents", { onDone(); onClose() }) { Arc.create(ui.act, files, out, p, replace) }
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Compress to ZIP") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    name, { name = it }, Modifier.fillMaxWidth(), label = { Text("ZIP name") }, singleLine = true,
                    suffix = { Text(".zip") }, shape = RoundedCornerShape(14.dp)
                )
                Text("${files.size} item(s)  •  Saved in Documents", fontSize = 12.sp, color = P.muted)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(usePw, { usePw = it })
                    Text("Add a password (optional)", color = P.ink)
                }
                if (usePw) {
                    val vt = if (show) VisualTransformation.None else PasswordVisualTransformation()
                    val eye: @Composable () -> Unit = {
                        IconButton({ show = !show }) { Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show or hide password") }
                    }
                    OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = vt, trailingIcon = eye, shape = RoundedCornerShape(14.dp))
                    OutlinedTextField(pw2, { pw2 = it }, Modifier.fillMaxWidth(), label = { Text("Confirm password") }, singleLine = true, visualTransformation = vt, shape = RoundedCornerShape(14.dp))
                    Text("AES-256 encryption. If you forget the password, the ZIP cannot be opened.", fontSize = 12.sp, color = P.muted)
                }
            }
        },
        confirmButton = {
            Button({
                val n = Arc.cleanName(name)
                when {
                    n == null -> ui.err("Enter a valid name")
                    usePw && pw.length < 4 -> ui.err("Password must be at least 4 characters")
                    usePw && pw != pw2 -> ui.err("Passwords do not match")
                    else -> {
                        cleanN = n
                        val target = File(Arc.docsDir(), "$n.zip")
                        if (target.exists()) conflict = target else run(target, false)
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClose) { Text("Cancel") } }
    )

    conflict?.let { t ->
        AlertDialog(
            onDismissRequest = { conflict = null },
            title = { Text("${t.name} already exists") },
            text = { Text("Replace the old file, or keep both?") },
            confirmButton = {
                Row {
                    TextButton({ conflict = null; run(Fx.unique(Arc.docsDir(), "$cleanN.zip"), false) }) { Text("Keep both") }
                    Button({ conflict = null; run(t, true) }, colors = ButtonDefaults.buttonColors(containerColor = Err, contentColor = androidx.compose.ui.graphics.Color.White)) { Text("Replace") }
                }
            },
            dismissButton = { TextButton({ conflict = null }) { Text("Cancel") } }
        )
    }
}
