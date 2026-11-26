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

sealed interface GRow {
    class Head(val label: String, val paths: List<String>) : GRow
    class Item(val e: Entry) : GRow
}

fun flatRows(es: List<Entry>): List<GRow> = es.map { GRow.Item(it) }

/** Groups entries (already sorted newest first) under Today / Yesterday / date headers. */
fun dateRows(es: List<Entry>): List<GRow> {
    val out = ArrayList<GRow>(es.size + 32)
    var day = Long.MIN_VALUE
    var bucket = ArrayList<Entry>()
    fun flush() {
        if (bucket.isEmpty()) return
        out.add(GRow.Head(Fx.dayLabel(bucket[0].mod), bucket.map { it.path }))
        bucket.forEach { out.add(GRow.Item(it)) }
        bucket = ArrayList()
    }
    for (e in es) {
        val k = Fx.dayKey(e.mod)
        if (k != day) { flush(); day = k }
        bucket.add(e)
    }
    flush()
    return out
}

/** Thin scroll indicator on the right edge: shows how far down the list you are. */
@Composable
fun Modifier.scrollbar(info: () -> Triple<Int, Int, Int>, scrolling: Boolean, color: Color): Modifier {
    val alpha by animateFloatAsState(
        if (scrolling) 1f else 0f,
        tween(durationMillis = if (scrolling) 120 else 500, delayMillis = if (scrolling) 0 else 700), label = "scrollbar"
    )
    return this.drawWithContent {
        drawContent()
        val (first, vis, total) = info()
        if (alpha > 0.01f && vis > 0 && total > vis) {
            val h = size.height
            val tw = 5.dp.toPx()
            val th = maxOf(40.dp.toPx(), h * vis / total)
            val prog = first.toFloat() / (total - vis).coerceAtLeast(1)
            val top = (h - th) * prog.coerceIn(0f, 1f)
            drawRoundRect(color.copy(alpha = alpha * 0.8f), Offset(size.width - tw - 3.dp.toPx(), top), Size(tw, th), CornerRadius(tw / 2))
        }
    }
}

@Composable
fun SelCircle(on: Boolean, onPhoto: Boolean = false) {
    if (on) Box(Modifier.size(24.dp).background(P.primary, CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.Check, null, tint = if (P.dark) Color(0xFF0B1220) else Color.White, modifier = Modifier.size(16.dp))
    } else Icon(Icons.Filled.RadioButtonUnchecked, null, tint = if (onPhoto) Color.White else P.muted, modifier = Modifier.size(24.dp))
}

@Composable
fun DateHead(label: String, selecting: Boolean, all: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = P.ink)
        if (selecting) Box(Modifier.clickable(onClick = onToggle).padding(4.dp)) { SelCircle(all) }
    }
}

@Composable
fun EntryRow(e: Entry, selected: Boolean, selecting: Boolean, showPath: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) P.sel else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(e, 140, Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(e.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, color = P.ink, fontSize = 15.sp)
            Text(if (e.isDir) "Folder  •  ${Fx.ago(e.mod)}" else "${Fx.size(e.size)}  •  ${Fx.ago(e.mod)}", fontSize = 12.sp, color = P.muted, maxLines = 1)
            val extra = e.sub ?: if (showPath) e.file.parent else null
            if (extra != null) Text(extra, fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selecting) { Spacer(Modifier.width(8.dp)); SelCircle(selected) }
    }
}

@Composable
fun GridCell(e: Entry, selected: Boolean, selecting: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(P.chip).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Thumb(e, 360, Modifier.fillMaxSize())
        if (Fx.kind(e.ext) == 3) Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp).background(Color(0x99000000), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        if (selected) Box(Modifier.fillMaxSize().background(P.primary.copy(alpha = 0.28f)))
        if (selecting) Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { SelCircle(selected, onPhoto = true) }
    }
}

@Composable
fun EntryList(
    rows: List<GRow>, grid: Boolean, sel: Set<String>, showPath: Boolean,
    onTap: (Entry) -> Unit, onLong: (Entry) -> Unit, onGroup: (List<String>) -> Unit, modifier: Modifier = Modifier
) {
    val selecting = sel.isNotEmpty()
    if (grid) {
        val st = rememberLazyGridState()
        LazyVerticalGrid(
            GridCells.Fixed(3),
            modifier.scrollbar({ Triple(st.firstVisibleItemIndex, st.layoutInfo.visibleItemsInfo.size, st.layoutInfo.totalItemsCount) }, st.isScrollInProgress, P.muted),
            state = st, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(
                rows, key = { r -> if (r is GRow.Item) r.e.path else "h:" + (r as GRow.Head).label },
                span = { r -> if (r is GRow.Head) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
            ) { r ->
                when (r) {
                    is GRow.Head -> DateHead(r.label, selecting, r.paths.all { it in sel }) { onGroup(r.paths) }
                    is GRow.Item -> GridCell(r.e, r.e.path in sel, selecting, { onTap(r.e) }, { onLong(r.e) })
                }
            }
        }
    } else {
        val st = rememberLazyListState()
        LazyColumn(
            modifier.scrollbar({ Triple(st.firstVisibleItemIndex, st.layoutInfo.visibleItemsInfo.size, st.layoutInfo.totalItemsCount) }, st.isScrollInProgress, P.muted),
            state = st, contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(rows, key = { r -> if (r is GRow.Item) r.e.path else "h:" + (r as GRow.Head).label }) { r ->
                when (r) {
                    is GRow.Head -> DateHead(r.label, selecting, r.paths.all { it in sel }) { onGroup(r.paths) }
                    is GRow.Item -> EntryRow(r.e, r.e.path in sel, selecting, showPath, { onTap(r.e) }, { onLong(r.e) })
                }
            }
        }
    }
}
