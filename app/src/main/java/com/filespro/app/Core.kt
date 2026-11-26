package com.filespro.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.Properties
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object AppPrefs {
    /** 0 = regular (follow phone), 1 = light, 2 = dark */
    var theme by mutableIntStateOf(0)
}

sealed interface Screen {
    data object Home : Screen
    data class Browse(val dir: File, val pick: String? = null) : Screen
    data class Listing(val kind: String) : Screen
    data object Tools : Screen
    data object Bin : Screen
    data object SafeBox : Screen
    data object Dupes : Screen
    data object Apps : Screen
    data class Zip(val f: File) : Screen
    class Img(val files: List<File>, val index: Int) : Screen
    data class Pdf(val f: File) : Screen
    data class Txt(val f: File) : Screen
}

/** One row shown in a list. Everything is measured in the background so scrolling stays smooth. */
class Entry(val file: File, val name: String, val isDir: Boolean, val size: Long, val mod: Long, val sub: String? = null) {
    val path: String get() = file.path
    val ext: String = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
}

/** Files waiting to be pasted (copy / move). */
object Clip {
    var files by mutableStateOf<List<File>>(emptyList())
    var move = false
}

class FI(val f: File, val size: Long, val mod: Long)

/**
 * Index of the user's own files.
 * Images / videos / audio come from the phone's media library (what the Gallery shows), so cache and
 * auto-downloaded media of chat apps that hide it with a .nomedia file never appear.
 */
object Index {
    @Volatile var all: List<FI> = emptyList()
    var version by mutableIntStateOf(0)
    @Volatile var ready = false
    @Volatile private var builtAt = 0L
    private val busy = AtomicBoolean(false)

    fun invalidate() { builtAt = 0L }

    /** নতুন তৈরি হওয়া ফাইল সঙ্গে সঙ্গে তালিকায় যোগ করে (পুরো index আবার বানাতে হয় না)। */
    fun add(files: List<File>) {
        val extra = files.filter { it.isFile }.map { FI(it, it.length(), it.lastModified()) }
        if (extra.isEmpty()) return
        val paths = extra.map { it.f.path }.toSet()
        all = extra + all.filter { it.f.path !in paths }
        ready = true; version++
    }

    private fun okPath(p: String) = !p.contains("/Android/") && !p.contains("/.")

    private fun media(c: Context, uri: Uri): List<FI> {
        val out = ArrayList<FI>()
        try {
            c.contentResolver.query(
                uri, arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
                null, null, null
            )?.use { cur ->
                while (cur.moveToNext()) {
                    val p = cur.getString(0) ?: continue
                    if (!okPath(p)) continue
                    val f = File(p)
                    if (f.exists()) out.add(FI(f, cur.getLong(1), cur.getLong(2) * 1000L))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    fun build(c: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - builtAt < 90_000L) return
        if (!busy.compareAndSet(false, true)) return
        try {
            val out = ArrayList<FI>()
            val m = media(c, MediaStore.Images.Media.EXTERNAL_CONTENT_URI) +
                media(c, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) +
                media(c, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
            out.addAll(m.distinctBy { it.f.path })
            val walkMedia = m.isEmpty()
            for (r in listOf(Fx.root()) + Fx.volumes(c)) {
                r.walkTopDown()
                    .onEnter { d -> d == r || (!d.name.startsWith(".") && !(d.name == "Android" && d.parentFile == r) && !File(d, ".nomedia").exists()) }
                    .forEach { f ->
                        if (f.isFile && !f.name.startsWith(".")) {
                            val k = Fx.kind(f.extension.lowercase())
                            if (walkMedia || k == 0 || k == 4) out.add(FI(f, f.length(), f.lastModified()))
                        }
                    }
            }
            all = out; ready = true; builtAt = System.currentTimeMillis(); version++
        } finally { busy.set(false) }
    }

    fun sizes(): Map<Cat, Long> {
        val m = HashMap<Cat, Long>()
        val dl = Fx.downloads().absolutePath + "/"
        for (x in all) {
            val c = Fx.cat(x.f.extension.lowercase())
            m[c] = (m[c] ?: 0L) + x.size
            if (x.f.path.startsWith(dl)) m[Cat.Downloads] = (m[Cat.Downloads] ?: 0L) + x.size
        }
        return m
    }

    fun listFor(kind: String): List<Entry> {
        val src = if (kind == "large") all.filter { it.size > 50_000_000L }.sortedByDescending { it.size }.take(100)
        else { val c = Cat.valueOf(kind); all.filter { Fx.cat(it.f.extension.lowercase()) == c } }
        return src.map { Entry(it.f, it.f.name, false, it.size, it.mod) }
    }
}

object Fx {
    val IMG = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic")
    val AUD = setOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "amr")
    val VID = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov")
    val ARC = setOf("zip", "rar", "7z", "tar", "gz")
    val TXT = setOf("txt", "log", "md", "json", "xml", "csv", "html", "js", "kt", "java", "py", "ini", "cfg", "srt")

    fun ext(f: File) = f.extension.lowercase()
    /** 0 other, 1 image, 2 audio, 3 video, 4 apk */
    fun kind(ext: String) = when { ext in IMG -> 1; ext in AUD -> 2; ext in VID -> 3; ext == "apk" -> 4; else -> 0 }
    fun cat(ext: String): Cat = when (kind(ext)) { 1 -> Cat.Images; 2 -> Cat.Audio; 3 -> Cat.Videos; else -> Cat.Documents }

    fun root(): File = Environment.getExternalStorageDirectory()
    fun downloads(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** SD card / USB volumes other than the primary storage. */
    fun volumes(c: Context): List<File> = c.getExternalFilesDirs(null).drop(1).mapNotNull { d ->
        val p = d?.absolutePath ?: return@mapNotNull null
        val i = p.indexOf("/Android/data")
        if (i > 0) File(p.substring(0, i)).takeIf { it.canRead() } else null
    }

    fun space(f: File): Pair<Long, Long> = try {
        val s = StatFs(f.path); s.totalBytes to s.availableBytes
    } catch (e: Exception) { 0L to 0L }

    fun size(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB")
        var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < 3) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }

    fun ago(ms: Long): String =
        DateUtils.getRelativeTimeSpanString(ms, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    fun dayKey(ms: Long): Long = (ms + TimeZone.getDefault().getOffset(ms)) / 86_400_000L

    fun dayLabel(ms: Long): String {
        val today = dayKey(System.currentTimeMillis()); val k = dayKey(ms)
        if (k == today) return "Today"
        if (k == today - 1) return "Yesterday"
        val y = Calendar.getInstance().apply { timeInMillis = ms }.get(Calendar.YEAR)
        val same = y == Calendar.getInstance().get(Calendar.YEAR)
        return SimpleDateFormat(if (same) "EEE, d MMM" else "d MMM yyyy", Locale.getDefault()).format(Date(ms))
    }

    fun scan(c: Context, paths: List<String>) {
        if (paths.isNotEmpty()) try { MediaScannerConnection.scanFile(c, paths.toTypedArray(), null, null) } catch (e: Exception) { }
    }

    fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            f = File(dir, "$base ($i)$ext")
            if (!f.exists()) return f
            i++
        }
    }

    private fun inside(child: File, parent: File) =
        child.canonicalPath == parent.canonicalPath || child.canonicalPath.startsWith(parent.canonicalPath + File.separator)

    fun copy(src: File, dir: File): File {
        val dst = unique(dir, src.name)
        if (src.isDirectory) {
            if (inside(dir, src)) throw IOException("Cannot copy a folder into itself")
            src.copyRecursively(dst)
        } else src.copyTo(dst)
        return dst
    }

    fun moveTo(src: File, dst: File) {
        if (src.renameTo(dst)) return
        if (src.isDirectory) src.copyRecursively(dst) else src.copyTo(dst)
        if (!src.deleteRecursively()) throw IOException("Could not remove the original")
    }

    fun move(src: File, dir: File): File {
        if (src.isDirectory && inside(dir, src)) throw IOException("Cannot move a folder into itself")
        val dst = unique(dir, src.name)
        moveTo(src, dst)
        return dst
    }

    fun zip(files: List<File>, out: File) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun add(f: File, path: String) {
                if (f.isDirectory) f.listFiles()?.forEach { add(it, "$path/${it.name}") }
                else {
                    z.putNextEntry(ZipEntry(path))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                }
            }
            files.forEach { add(it, it.name) }
        }
    }

    fun unzip(zip: File, dest: File) {
        dest.mkdirs()
        val base = dest.canonicalPath + File.separator
        ZipFile(zip).use { z ->
            z.entries().asSequence().forEach { e ->
                val out = File(dest, e.name)
                if (!out.canonicalPath.startsWith(base)) return@forEach
                if (e.isDirectory) out.mkdirs()
                else {
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                }
            }
        }
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val b = ByteArray(65536)
            while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Recycle bin: deleted files stay here until you delete them for good. */
object Trash {
    class Item(val file: File, val orig: String, val time: Long, val isDir: Boolean, val size: Long) {
        val shownName: String get() = file.name.substringAfter('_')
    }

    private fun dir(c: Context) = (c.getExternalFilesDir("trash") ?: File(c.filesDir, "trash")).also { it.mkdirs() }
    private fun idxFile(c: Context) = File(c.filesDir, "trash_index.properties")
    private fun load(c: Context) = Properties().also { p -> idxFile(c).takeIf { it.exists() }?.inputStream()?.use { p.load(it) } }
    private fun save(c: Context, p: Properties) { idxFile(c).outputStream().use { p.store(it, null) } }

    /** Moves many files at once (index is read and written only once, so it is fast). */
    @Synchronized fun addAll(c: Context, files: List<File>) {
        val p = load(c); val d = dir(c); val now = System.currentTimeMillis()
        val scan = ArrayList<String>()
        try {
            files.forEachIndexed { i, f ->
                val name = "${now}${i}_${f.name}"
                Fx.moveTo(f, File(d, name))
                p.setProperty(name, "${f.absolutePath}|$now")
                scan.add(f.absolutePath)
            }
        } finally {
            save(c, p)
            Fx.scan(c, scan)
        }
    }

    @Synchronized fun items(c: Context): List<Item> {
        val p = load(c)
        return dir(c).listFiles().orEmpty().mapNotNull { f ->
            val v = p.getProperty(f.name) ?: return@mapNotNull null
            val d = f.isDirectory
            Item(f, v.substringBeforeLast('|'), v.substringAfterLast('|').toLongOrNull() ?: 0L, d, if (d) 0L else f.length())
        }.sortedByDescending { it.time }
    }

    @Synchronized fun restoreAll(c: Context, items: List<Item>) {
        val p = load(c); val scan = ArrayList<String>()
        try {
            for (i in items) {
                val orig = File(i.orig)
                val parent = orig.parentFile ?: Fx.downloads()
                parent.mkdirs()
                val dst = Fx.unique(parent, orig.name)
                Fx.moveTo(i.file, dst)
                p.remove(i.file.name); scan.add(dst.absolutePath)
            }
        } finally { save(c, p); Fx.scan(c, scan) }
    }

    @Synchronized fun purgeAll(c: Context, items: List<Item>) {
        val p = load(c)
        try { for (i in items) { i.file.deleteRecursively(); p.remove(i.file.name) } } finally { save(c, p) }
    }
}
