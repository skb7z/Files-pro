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
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.exifinterface.media.ExifInterface

fun loadBmp(f: File, maxEdge: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxEdge * 0.9) s *= 2
    val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
    val rot = try {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    } catch (e: Exception) { 0f }
    return if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
}

@Composable
fun ZoomImage(f: File, onZoom: (Boolean) -> Unit) {
    val bmp by produceState<Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { try { loadBmp(f, 3600) } catch (t: Throwable) { null } } }
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(scale) { onZoom(scale > 1.01f) }
    Box(
        Modifier.fillMaxSize().onSizeChanged { box = it }.clipToBounds()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { if (scale > 1f) { scale = 1f; off = Offset.Zero } else scale = 2.5f }) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.count { it.pressed }
                        if (pressed >= 2 || scale > 1f) {
                            val z = ev.calculateZoom(); val p = ev.calculatePan()
                            val ns = (scale * z).coerceIn(1f, 6f)
                            scale = ns
                            val mx = box.width * (ns - 1f) / 2f; val my = box.height * (ns - 1f) / 2f
                            off = if (ns <= 1f) Offset.Zero else Offset((off.x + p.x).coerceIn(-mx, mx), (off.y + p.y).coerceIn(-my, my))
                            ev.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (ev.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b == null) CircularProgressIndicator(color = Color.White)
        else Image(b.asImageBitmap(), f.name, Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = off.x, translationY = off.y), contentScale = ContentScale.Fit)
    }
}

@Composable
fun ImgScreen(ui: Ui, files: List<File>, start: Int) {
    val pager = rememberPagerState(initialPage = start) { files.size }
    var zoomed by remember { mutableStateOf(false) }
    val cur = files[pager.currentPage.coerceIn(0, files.size - 1)]
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(ui::back) { Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White) }
            Column(Modifier.weight(1f)) {
                Text(cur.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pager.currentPage + 1} / ${files.size}", color = Color(0xFFB0B8C8), fontSize = 12.sp)
            }
            IconButton({ ui.share(listOf(cur)) }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
        }
        HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = !zoomed) { page ->
            ZoomImage(files[page]) { z -> if (pager.currentPage == page) zoomed = z }
        }
    }
}

@Composable
fun PdfScreen(ui: Ui, f: File) {
    val r = remember(f) { try { PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) } catch (e: Exception) { null } }
    DisposableEffect(r) { onDispose { try { r?.close() } catch (e: Exception) { } } }
    Column(Modifier.fillMaxSize()) {
        TopBar(f.name, ui::back) { IconButton({ ui.share(listOf(f)) }) { Icon(Icons.Filled.Share, "Share") } }
        if (r == null) Text("Could not open this PDF", Modifier.padding(20.dp), color = Err)
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(r.pageCount) { i ->
                val page by produceState<Bitmap?>(null, i) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            synchronized(r) {
                                val p = r.openPage(i)
                                try {
                                    val w = 1100
                                    val h = (w.toFloat() * p.height / p.width).toInt()
                                    val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                    b.eraseColor(android.graphics.Color.WHITE)
                                    p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    b
                                } finally { p.close() }
                            }
                        } catch (e: Exception) { null }
                    }
                }
                val b = page
                if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                else Box(Modifier.fillMaxWidth().height(400.dp).background(Color.White))
            }
        }
    }
}

@Composable
fun TxtScreen(ui: Ui, f: File) {
    val t by produceState("Loading…", f) {
        value = withContext(Dispatchers.IO) {
            try { f.inputStream().use { i -> val buf = ByteArray(300_000); val n = i.read(buf).coerceAtLeast(0); String(buf, 0, n, Charsets.UTF_8) } }
            catch (e: Exception) { "Cannot read this file" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(f.name, ui::back)
        Text(t, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = P.ink)
    }
}
