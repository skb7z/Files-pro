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
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.provider.Settings
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import java.util.zip.ZipFile

data class ZE(val name: String, val size: Long, val dir: Boolean)

@Composable
fun ZipScreen(ui: Ui, f: File) {
    var all by remember { mutableStateOf<List<ZE>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(f) {
        withContext(Dispatchers.IO) {
            try { ZipFile(f).use { z -> all = z.entries().asSequence().map { ZE(it.name, it.size, it.isDirectory) }.toList() } }
            catch (e: Exception) { error = e.message ?: "Cannot read this ZIP" }
        }
    }
    val shown = remember(all, q) { all?.filter { q.isBlank() || it.name.contains(q, true) } }
    Column(Modifier.fillMaxSize()) {
        TopBar(f.name, ui::back)
        if (error != null) Text(error!!, Modifier.padding(20.dp), color = Err)
        else if (shown == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            val files = all!!.filter { !it.dir }
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${files.size} files  •  ${Fx.size(files.sumOf { it.size })}", Modifier.weight(1f), color = P.muted, fontSize = 13.sp)
                Button({
                    val dest = Fx.unique(f.parentFile ?: Fx.downloads(), f.nameWithoutExtension)
                    ui.work("Extracted to ${dest.name}", { Index.invalidate() }) { Fx.unzip(f, dest) }
                }) { Text("Extract all") }
            }
            SearchBox(q, "Search inside this ZIP") { q = it }
            val st = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().scrollbar({ Triple(st.firstVisibleItemIndex, st.layoutInfo.visibleItemsInfo.size, st.layoutInfo.totalItemsCount) }, st.isScrollInProgress, P.muted), state = st) {
                items(shown) { e ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        val (ic, col) = fileIcon(e.dir, e.name.substringAfterLast('.', "").lowercase())
                        Icon(ic, null, tint = col)
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, Modifier.weight(1f), fontSize = 14.sp, color = P.ink)
                        if (!e.dir) Text(Fx.size(e.size), fontSize = 12.sp, color = P.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun DupesScreen(ui: Ui) {
    var groups by remember { mutableStateOf<List<List<File>>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tick) {
        groups = null
        groups = withContext(Dispatchers.IO) {
            Index.all.filter { it.size > 1024 && it.f.exists() }.groupBy { it.size }.values.filter { it.size > 1 }
                .flatMap { g -> g.groupBy { Fx.sha256(it.f) }.values.filter { it.size > 1 }.map { x -> x.map { it.f } } }
                .sortedByDescending { it[0].length() * it.size }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Duplicate finder", ui::back)
        val g = groups
        if (g == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(); Spacer(Modifier.height(10.dp)); Text("Comparing files…", color = P.muted) }
        }
        else if (g.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No duplicates found", color = P.muted) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(g) { grp ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card).padding(12.dp)) {
                    Text("${grp.size} copies  •  ${Fx.size(grp[0].length())} each", fontWeight = FontWeight.Bold, color = P.ink)
                    grp.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Thumb(Entry(f, f.name, false, 0L, f.lastModified()), 140, Modifier.padding(vertical = 4.dp).size(44.dp).clip(RoundedCornerShape(10.dp)))
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(f.name, fontSize = 14.sp, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(f.parent ?: "", fontSize = 11.sp, color = P.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton({ ui.work("Moved to Recycle Bin", { tick++; Index.invalidate() }) { Trash.addAll(ui.act, listOf(f)) } }) { Text("Delete", color = Err) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BinScreen(ui: Ui) {
    val ctx = ui.act
    var items by remember { mutableStateOf<List<Trash.Item>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dlg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(tick) { loading = true; items = withContext(Dispatchers.IO) { Trash.items(ctx) }; loading = false }
    BackHandler(sel.isNotEmpty()) { sel = emptySet() }
    val list = remember(items, q) { items.filter { q.isBlank() || it.shownName.contains(q, true) } }
    val entries = remember(list) {
        list.map { Entry(it.file, it.shownName, it.isDir, it.size, it.time, "From " + (File(it.orig).parent ?: "")) }
    }
    val rows = remember(entries) { dateRows(entries) }
    val selItems = remember(sel, items) { items.filter { it.file.path in sel } }

    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) {
            TopBar("Recycle Bin", ui::back, "${items.size} items") {
                if (items.isNotEmpty()) TextButton({ dlg = "empty" }) { Text("Empty", color = Err, fontWeight = FontWeight.Bold) }
            }
        } else {
            SelectBar(sel.size, Fx.size(selItems.sumOf { it.size }), { sel = emptySet() }) {
                IconButton({ sel = if (sel.size == list.size) emptySet() else list.map { it.file.path }.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                TextButton({
                    val s = selItems
                    ui.work("${s.size} item(s) restored", { sel = emptySet(); tick++; Index.invalidate() }, { tick++ }) { Trash.restoreAll(ctx, s) }
                }) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(4.dp)); Text("Restore") }
                TextButton({ dlg = "delete" }) { Text("Delete", color = Err, fontWeight = FontWeight.Bold) }
            }
        }
        SearchBox(q, "Search deleted files") { q = it }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EntryList(
                rows, false, sel, false,
                onTap = { e -> sel = if (e.path in sel) sel - e.path else sel + e.path },
                onLong = { e -> sel = if (e.path in sel) sel - e.path else sel + e.path },
                onGroup = { ps -> sel = if (ps.all { it in sel }) sel - ps.toSet() else sel + ps },
                modifier = Modifier.fillMaxSize()
            )
            if (!loading && list.isEmpty()) Text(if (q.isBlank()) "Recycle Bin is empty" else "No match", Modifier.align(Alignment.Center), color = P.muted)
        }
    }
    if (dlg == "delete" || dlg == "empty") {
        val target = if (dlg == "empty") items else selItems
        AlertDialog(
            onDismissRequest = { dlg = "" },
            title = { Text(if (dlg == "empty") "Empty Recycle Bin?" else "Delete ${target.size} item(s) forever?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                Button({
                    dlg = ""
                    ui.work("${target.size} item(s) deleted", { sel = emptySet(); tick++ }, { tick++ }) { Trash.purgeAll(ctx, target) }
                }, colors = ButtonDefaults.buttonColors(containerColor = Err, contentColor = Color.White)) { Text("Delete") }
            },
            dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
        )
    }
}

class AppRow(val pkg: String, val label: String, val size: Long, val system: Boolean, val updated: Long)

object AppsRepo {
    fun userCount(c: Context): Int = try {
        c.packageManager.getInstalledApplications(0).count { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
    } catch (e: Exception) { 0 }

    fun hasUsageAccess(c: Context): Boolean = try {
        val ops = c.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) { false }

    fun load(c: Context): List<AppRow> {
        val pm = c.packageManager
        val exact = Build.VERSION.SDK_INT >= 26 && hasUsageAccess(c)
        val ssm = if (exact) c.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager else null
        return pm.getInstalledApplications(0).map { ai ->
            val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            var size = 0L
            if (ssm != null) {
                try {
                    val s = ssm.queryStatsForPackage(android.os.storage.StorageManager.UUID_DEFAULT, ai.packageName, android.os.Process.myUserHandle())
                    size = s.appBytes + s.dataBytes + s.cacheBytes
                } catch (e: Exception) { }
            }
            if (size == 0L) {
                size = File(ai.sourceDir).length()
                ai.splitSourceDirs?.forEach { size += File(it).length() }
            }
            val upd = try { pm.getPackageInfo(ai.packageName, 0).lastUpdateTime } catch (e: Exception) { 0L }
            AppRow(ai.packageName, pm.getApplicationLabel(ai).toString(), size, sys, upd)
        }
    }
}

@Composable
fun AppsScreen(ui: Ui) {
    val ctx = ui.act
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var system by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var exact by remember { mutableStateOf(true) }
    val lc = LocalLifecycleOwner.current
    DisposableEffect(lc) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lc.lifecycle.addObserver(o)
        onDispose { lc.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { exact = Build.VERSION.SDK_INT < 26 || AppsRepo.hasUsageAccess(ctx); apps = AppsRepo.load(ctx) }
    }
    val list = remember(apps, q, system, sort) {
        (apps ?: emptyList()).filter { it.system == (system == 1) && (q.isBlank() || it.label.contains(q, true)) }
            .let { l -> when (sort) { 1 -> l.sortedBy { it.label.lowercase() }; 2 -> l.sortedByDescending { it.updated }; else -> l.sortedByDescending { it.size } } }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Apps", ui::back, "${list.size} apps  •  ${Fx.size(list.sumOf { it.size })}") {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("Size (largest)", "Name", "Recently updated").forEachIndexed { i, n ->
                        DropdownMenuItem(text = { Text(n + if (sort == i) "   ✓" else "") }, onClick = { sort = i; menu = false })
                    }
                }
            }
        }
        SearchBox(q, "Search apps") { q = it }
        ChipRow(listOf("Installed", "System"), system) { system = it }
        if (!exact) Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(P.sel).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Allow usage access to see full app sizes", Modifier.weight(1f), fontSize = 13.sp, color = P.ink)
            TextButton({ try { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } catch (e: Exception) { ui.err("Cannot open settings") } }) { Text("Allow") }
        }
        if (apps == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else {
            val st = rememberLazyListState()
            LazyColumn(Modifier.fillMaxSize().scrollbar({ Triple(st.firstVisibleItemIndex, st.layoutInfo.visibleItemsInfo.size, st.layoutInfo.totalItemsCount) }, st.isScrollInProgress, P.muted), state = st) {
                items(list, key = { it.pkg }) { a ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).clickable {
                            try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))) } catch (e: Exception) { }
                        }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(a.pkg, 120, Modifier.size(46.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.label, fontWeight = FontWeight.Medium, color = P.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Fx.size(a.size), fontSize = 12.sp, color = P.muted)
                        }
                        if (!a.system) OutlinedButton({
                            try { ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))) } catch (e: Exception) { ui.err("Cannot uninstall") }
                        }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) { Text("Uninstall", color = Err, fontSize = 13.sp) }
                    }
                }
            }
        }
    }
}
