@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
package com.filespro.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.*
import java.io.File
import android.view.HapticFeedbackConstants
import android.webkit.MimeTypeMap
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.FileProvider

/** Shared helper passed to every screen: navigation, feedback messages, background work, opening files. */
class Ui(
    val act: FragmentActivity,
    val snack: SnackbarHostState,
    val scope: CoroutineScope,
    val stack: SnapshotStateList<Screen>,
    val player: PlayerCtl
) {
    var busy by mutableStateOf(false)
    var flash by mutableIntStateOf(0)
    var safeOpen by mutableStateOf(false)
    var keep = false

    fun go(s: Screen) { stack.add(s) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    private fun buzz(good: Boolean) {
        val c = if (good) (if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        else (if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
        act.window.decorView.performHapticFeedback(c)
    }
    private var snackJob: Job? = null
    private fun say(m: String, ms: Long) {
        snackJob?.cancel()
        snackJob = scope.launch {
            snack.currentSnackbarData?.dismiss()
            val j = launch { snack.showSnackbar(m, duration = SnackbarDuration.Indefinite) }
            delay(ms)
            j.cancel()
        }
    }
    fun ok(m: String) { buzz(true); flash++; say("✓  $m", 1200L) }
    fun err(m: String) { buzz(false); say("✗  $m", 1800L) }

    /** Runs [block] in the background, then shows a clear success or error message. */
    fun work(okMsg: String, done: () -> Unit = {}, fail: () -> Unit = {}, block: () -> Unit) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { block() }
                ok(okMsg); done()
            } catch (e: Exception) {
                err(e.message ?: "Something went wrong"); fail()
            } finally { busy = false }
        }
    }

    /** ভারী কাজ (যেমন পাসওয়ার্ড hash) background এ চালায়, শেষে main থ্রেডে [then] চালায়। */
    fun <T> bg(block: () -> T, then: (T) -> Unit) {
        scope.launch {
            busy = true
            val r = try { withContext(Dispatchers.Default) { block() } } catch (e: Exception) { busy = false; err(e.message ?: "Something went wrong"); return@launch }
            busy = false
            then(r)
        }
    }

    private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(Fx.ext(f)) ?: "*/*"
    private fun uri(f: File): Uri = FileProvider.getUriForFile(act, "${act.packageName}.provider", f)

    fun external(f: File) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), mime(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { err("No app found to open this file") }
    }

    fun share(files: List<File>) {
        if (files.isEmpty()) { err("Select files (not folders) to share"); return }
        val i = if (files.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri(files[0]))
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(it) }))
        i.type = if (files.size == 1) mime(files[0]) else "*/*"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { act.startActivity(Intent.createChooser(i, "Share")) } catch (e: Exception) { err("Cannot share") }
    }

    fun open(f: File, siblings: List<File> = emptyList()) {
        val e = Fx.ext(f)
        when {
            e == "zip" -> go(Screen.Zip(f))
            e in Fx.IMG -> {
                val l = siblings.filter { Fx.ext(it) in Fx.IMG }.ifEmpty { listOf(f) }
                go(Screen.Img(l, l.indexOf(f).coerceAtLeast(0)))
            }
            e == "pdf" -> go(Screen.Pdf(f))
            e in Fx.TXT -> go(Screen.Txt(f))
            e in Fx.VID -> go(Screen.Video(f))
            e in Fx.AUD -> {
                val q = siblings.filter { Fx.ext(it) in Fx.AUD }.ifEmpty { listOf(f) }
                if (!player.play(q, q.indexOf(f).coerceAtLeast(0))) err("Player is starting, try again")
            }
            else -> external(f)
        }
    }

    fun picked(kind: String, f: File) {
        when (kind) {
            "zip" -> go(Screen.Zip(f))
            "safe" -> {
                if (!Safe.isSet(act)) err("Create a Safe Folder password first")
                else work("${f.name} moved to Safe Folder") { Safe.encrypt(act, f) }
            }
        }
    }
}

@Composable
fun TopBar(title: String, onBack: (() -> Unit)?, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Surface(color = P.bg) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = P.ink) }
            else Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = P.muted, maxLines = 1)
            }
            CompositionLocalProvider(LocalContentColor provides P.ink) { actions() }
        }
    }
}

@Composable
fun SelectBar(count: Int, sizeText: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit) {
    Surface(color = P.sel) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Cancel selection", tint = P.ink) }
            Column(Modifier.weight(1f)) {
                Text("$count selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
                Text(sizeText, fontSize = 12.sp, color = P.muted)
            }
            CompositionLocalProvider(LocalContentColor provides P.ink) { actions() }
        }
    }
}

@Composable
fun SearchBox(q: String, hint: String, onChange: (String) -> Unit) {
    TextField(
        q, onChange, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        placeholder = { Text(hint, color = P.muted) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = P.muted) },
        trailingIcon = { if (q.isNotEmpty()) IconButton({ onChange("") }) { Icon(Icons.Filled.Close, "Clear", tint = P.muted) } },
        singleLine = true, shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = P.chip, unfocusedContainerColor = P.chip,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent
        )
    )
}

@Composable
fun ChipRow(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) P.primary else P.chip).clickable { onPick(i) }.padding(horizontal = 16.dp, vertical = 8.dp)
            ) { Text(l, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) (if (P.dark) Color(0xFF0B1220) else Color.White) else P.ink) }
        }
    }
}

@Composable
fun Tile(icon: ImageVector, color: Color, title: String, sub: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.height(76.dp).clip(shape).background(P.card).border(1.dp, P.line, shape).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(42.dp).background(color, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 12.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun SectionTitle(t: String) = Text(t, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = P.ink, modifier = Modifier.padding(top = 6.dp))

@Composable
fun PwField(label: String, v: String, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
