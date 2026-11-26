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
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken

/** Background music service: keeps playing (with notification controls) after the app is closed. */
class AudioService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}

class PlayerCtl(ctx: Context) {
    var ctl by mutableStateOf<MediaController?>(null)
    var title by mutableStateOf("")
    var curPath by mutableStateOf("")
    var playing by mutableStateOf(false)
    var has by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)

    private val future = MediaController.Builder(ctx, SessionToken(ctx, ComponentName(ctx, AudioService::class.java))).buildAsync()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { title = mediaMetadata.title?.toString() ?: "" }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { curPath = mediaItem?.mediaId ?: "" }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) { has = (ctl?.mediaItemCount ?: 0) > 0 }
    }

    init {
        future.addListener({
            try {
                val c = future.get()
                c.addListener(listener)
                ctl = c
                has = c.mediaItemCount > 0
                title = c.mediaMetadata.title?.toString() ?: ""
                curPath = c.currentMediaItem?.mediaId ?: ""
                playing = c.isPlaying
            } catch (e: Exception) { }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun play(files: List<File>, index: Int): Boolean {
        val c = ctl ?: return false
        c.setMediaItems(
            files.map {
                MediaItem.Builder().setMediaId(it.path).setUri(Uri.fromFile(it))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.nameWithoutExtension).build()).build()
            }, index, 0L
        )
        c.prepare(); c.play()
        return true
    }

    fun stop() { ctl?.run { stop(); clearMediaItems() }; has = false; playing = false }
    fun release() { ctl?.removeListener(listener); MediaController.releaseFuture(future) }
}

@Composable
fun MiniPlayer(p: PlayerCtl) {
    if (!p.has) return
    LaunchedEffect(p.ctl) {
        while (true) {
            p.ctl?.let { p.pos = it.currentPosition.coerceAtLeast(0L); p.dur = it.duration.coerceAtLeast(0L) }
            delay(500)
        }
    }
    val max = maxOf(p.dur, 1L).toFloat()
    Surface(color = P.card, shadowElevation = 10.dp, tonalElevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(Entry(File(p.curPath), p.title, false, 0L, 0L), 140, Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(10.dp))
                Text(p.title.ifEmpty { "Playing" }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = P.ink)
                IconButton({ p.ctl?.seekToPreviousMediaItem() }) { Icon(Icons.Filled.SkipPrevious, "Previous", tint = P.ink) }
                FilledIconButton({ if (p.playing) p.ctl?.pause() else p.ctl?.play() }) {
                    Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (p.playing) "Pause" else "Play")
                }
                IconButton({ p.ctl?.seekToNextMediaItem() }) { Icon(Icons.Filled.SkipNext, "Next", tint = P.ink) }
                IconButton({ p.stop() }) { Icon(Icons.Filled.Close, "Close player", tint = P.muted) }
            }
            Slider(value = p.pos.toFloat().coerceIn(0f, max), onValueChange = { p.ctl?.seekTo(it.toLong()) }, valueRange = 0f..max, modifier = Modifier.padding(end = 6.dp))
        }
    }
}
