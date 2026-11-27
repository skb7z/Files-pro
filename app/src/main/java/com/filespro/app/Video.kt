package com.filespro.app

import android.content.pm.ActivityInfo
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.io.File

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** In-app video player: tap = show/hide controls, double tap left/right = -10s / +10s. */
@Composable
fun VideoScreen(ui: Ui, file: File) {
    val act = ui.act
    val view = LocalView.current
    var cur by remember { mutableStateOf(file) }
    val exo = remember { ExoPlayer.Builder(act).build() }
    var playing by remember { mutableStateOf(true) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var speed by remember { mutableFloatStateOf(1f) }
    var bars by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var dlg by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var buffering by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    var land by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        ui.player.ctl?.pause()
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) bars = true
            }
            override fun onPlayerError(error: PlaybackException) { failed = true }
        }
        exo.addListener(l)
        exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        exo.prepare()
        exo.playWhenReady = true
        val c = WindowCompat.getInsetsController(act.window, view)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        onDispose {
            exo.removeListener(l)
            exo.release()
            c.show(WindowInsetsCompat.Type.systemBars())
            view.keepScreenOn = false
            act.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!dragging) { pos = exo.currentPosition; dur = exo.duration.coerceAtLeast(0L) }
            delay(300)
        }
    }
    LaunchedEffect(speed) { exo.setPlaybackSpeed(speed) }
    LaunchedEffect(bars, playing, dragging, menu, dlg) {
        if (bars && playing && !dragging && !menu && dlg.isEmpty()) { delay(3500); bars = false }
    }
    LaunchedEffect(hint) { if (hint.isNotEmpty()) { delay(700); hint = "" } }

    fun seekBy(ms: Long) {
        val d = exo.duration
        val t = (exo.currentPosition + ms).coerceAtLeast(0L)
        exo.seekTo(if (d > 0) minOf(t, d) else t)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; player = exo } },
            modifier = Modifier.fillMaxSize()
        )
        // touch layer
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { bars = !bars },
                    onDoubleTap = { o ->
                        if (o.x < size.width / 2f) { seekBy(-10_000L); hint = "−10 s" } else { seekBy(10_000L); hint = "+10 s" }
                    }
                )
            }
        )
        if (buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
        if (hint.isNotEmpty()) Text(
            hint, Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color(0x99000000)).padding(horizontal = 18.dp, vertical = 10.dp),
            color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
        )
        if (failed) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This video cannot be played here", color = Color.White, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            Button({ ui.external(cur) }) { Text("Open with another app") }
        }

        // top bar
        AnimatedVisibility(bars, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(ui::back) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(cur.name, Modifier.weight(1f), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Color.White) }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; ui.share(listOf(cur)) })
                        DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; text = cur.name; dlg = "rename" })
                        DropdownMenuItem(text = { Text("Playback speed  (${speedLabel(speed)})") }, leadingIcon = { Icon(Icons.Filled.Speed, null) }, onClick = { menu = false; dlg = "speed" })
                    }
                }
            }
        }

        // bottom controls
        AnimatedVisibility(bars, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                val maxF = maxOf(dur, 1L).toFloat()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(fmt(pos), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = pos.toFloat().coerceIn(0f, maxF),
                        onValueChange = { dragging = true; pos = it.toLong() },
                        onValueChangeFinished = { exo.seekTo(pos); dragging = false },
                        valueRange = 0f..maxF, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color(0x55FFFFFF))
                    )
                    Text(fmt(dur), color = Color.White, fontSize = 12.sp)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ dlg = "speed" }) { Text(speedLabel(speed), color = Color.White, fontWeight = FontWeight.Bold) }
                    IconButton({ seekBy(-10_000L) }) { Icon(Icons.Filled.Replay10, "Back 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    Box(
                        Modifier.size(60.dp).clip(CircleShape).background(Color.White).clickable { if (exo.isPlaying) exo.pause() else { if (exo.playbackState == Player.STATE_ENDED) exo.seekTo(0L); exo.play() } },
                        contentAlignment = Alignment.Center
                    ) { Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.Black, modifier = Modifier.size(34.dp)) }
                    IconButton({ seekBy(10_000L) }) { Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(30.dp)) }
                    IconButton({
                        land = !land
                        act.requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    }) { Icon(Icons.Filled.ScreenRotation, "Rotate", tint = Color.White) }
                }
            }
        }
    }

    if (dlg == "speed") AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text("Playback speed") },
        text = {
            Column {
                listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f).forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { speed = v; dlg = "" }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(speed == v, null)
                        Spacer(Modifier.width(12.dp))
                        Text(if (v == 1f) "Normal" else speedLabel(v), fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton({ dlg = "" }) { Text("Close") } }
    )
    if (dlg == "rename") AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            Button({
                val n = text.trim(); dlg = ""
                if (n.isEmpty() || n.contains('/')) ui.err("Invalid name")
                else {
                    val t = File(cur.parentFile, n)
                    if (t.exists()) ui.err("A file with that name already exists")
                    else if (cur.renameTo(t)) { Fx.scan(act, listOf(cur.path, t.path)); cur = t; Index.invalidate(); ui.ok("Renamed to $n") }
                    else ui.err("Could not rename")
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
    )
}

private fun speedLabel(v: Float): String = (if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()) + "x"
