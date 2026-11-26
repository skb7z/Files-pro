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

class ListCfg(
    val grid: Boolean = false,
    val search: Boolean = true,
    val defSort: Int = 0,
    val pick: String? = null,
    val chips: List<Pair<String, (Entry) -> Boolean>> = emptyList()
)

private fun entryOf(f: File): Entry { val d = f.isDirectory; return Entry(f, f.name, d, if (d) 0L else f.length(), f.lastModified()) }

/** Folder browser (dir != null) or a ready list of entries (base != null). */
@Composable
fun FileListScreen(ui: Ui, title: String, dir: File?, base: List<Entry>?, cfg: ListCfg = ListCfg()) {
    val ctx = ui.act
    var q by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(cfg.defSort) }
    var chip by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf<List<Entry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var gone by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sortMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }

    LaunchedEffect(dir?.path, base?.let { System.identityHashCode(it) }, q, tick, sort, chip) {
        loading = true
        if (q.isNotBlank()) delay(250)
        shown = withContext(Dispatchers.IO) {
            var l: List<Entry> = when {
                dir != null && q.isBlank() -> dir.listFiles().orEmpty().filter { !it.name.startsWith(".") }.map { entryOf(it) }
                dir != null -> dir.walkTopDown().filter { it != dir && !it.name.startsWith(".") && it.name.contains(q, true) }.take(500).map { entryOf(it) }.toList()
                else -> (base ?: emptyList()).let { b -> if (q.isBlank()) b else b.filter { it.name.contains(q, true) } }
            }
            if (cfg.chips.isNotEmpty() && chip > 0) l = l.filter(cfg.chips[chip].second)
            l = l.distinctBy { it.path }
            when (sort) {
                1 -> l.sortedByDescending { it.mod }
                2 -> l.sortedByDescending { if (it.isDir) -1L else it.size }
                else -> l.sortedWith(compareBy<Entry>({ !it.isDir }, { it.name.lowercase() }))
            }
        }
        loading = false
    }
    BackHandler(sel.isNotEmpty()) { sel = emptySet() }

    val visible = remember(shown, gone) { if (gone.isEmpty()) shown else shown.filter { it.path !in gone } }
    val rows = remember(visible, sort) { if (dir == null && sort == 1) dateRows(visible) else flatRows(visible) }
    val selSize = remember(sel, visible) { if (sel.isEmpty()) 0L else visible.filter { it.path in sel }.sumOf { it.size } }
    fun picked(): List<Entry> = visible.filter { it.path in sel }

    Column(Modifier.fillMaxSize()) {
        if (sel.isEmpty()) {
            TopBar(title, ui::back, if (dir == null) "${visible.size} items" else null) {
                if (dir != null) IconButton({ text = ""; dialog = "folder" }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
                Box {
                    IconButton({ sortMenu = true }) { Icon(Icons.Filled.Sort, "Sort") }
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf("Name", "Date (newest)", "Size (largest)").forEachIndexed { i, n ->
                            DropdownMenuItem(text = { Text(n + if (sort == i) "   ✓" else "") }, onClick = { sort = i; sortMenu = false })
                        }
                    }
                }
            }
        } else {
            SelectBar(sel.size, Fx.size(selSize), { sel = emptySet() }) {
                IconButton({ sel = if (sel.size == visible.size) emptySet() else visible.map { it.path }.toSet() }) { Icon(Icons.Filled.SelectAll, "Select all") }
                IconButton({ ui.share(picked().filter { !it.isDir }.map { it.file }) }) { Icon(Icons.Filled.Share, "Share") }
                IconButton({ dialog = "delete" }) { Icon(Icons.Filled.Delete, "Delete", tint = Err) }
                Box {
                    IconButton({ more = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(more, { more = false }) {
                        DropdownMenuItem(text = { Text("Copy") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) }, onClick = {
                            more = false; Clip.files = picked().map { it.file }; Clip.move = false
                            ui.ok("${sel.size} item(s) copied. Open a folder and tap Paste."); sel = emptySet()
                        })
                        DropdownMenuItem(text = { Text("Move") }, leadingIcon = { Icon(Icons.Filled.DriveFileMove, null) }, onClick = {
                            more = false; Clip.files = picked().map { it.file }; Clip.move = true
                            ui.ok("${sel.size} item(s) ready to move. Open a folder and tap Paste."); sel = emptySet()
                        })
                        if (sel.size == 1) DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = {
                            more = false; text = picked()[0].name; dialog = "rename"
                        })
                        DropdownMenuItem(text = { Text("Compress to ZIP") }, leadingIcon = { Icon(Icons.Filled.FolderZip, null) }, onClick = {
                            more = false; dialog = "zip"
                        })
                        DropdownMenuItem(text = { Text("Move to Safe Folder") }, leadingIcon = { Icon(Icons.Filled.Lock, null) }, onClick = {
                            more = false
                            val fs = picked().filter { !it.isDir }.map { it.file }
                            if (!Safe.isSet(ctx)) ui.err("Create a Safe Folder password first")
                            else if (fs.isEmpty()) ui.err("Select files, not folders")
                            else {
                                val ps = fs.map { it.path }.toSet()
                                ui.work("${fs.size} file(s) moved to Safe Folder", { gone = gone + ps; sel = emptySet(); tick++; Index.invalidate() }) { fs.forEach { Safe.encrypt(ctx, it) } }
                            }
                        })
                    }
                }
            }
        }
        if (cfg.pick != null) Text(
            if (cfg.pick == "zip") "Tap a ZIP file to see what is inside" else "Tap a file to move it to the Safe Folder",
            Modifier.fillMaxWidth().background(P.sel).padding(12.dp), fontWeight = FontWeight.Medium, fontSize = 14.sp, color = P.ink
        )
        if (cfg.search) SearchBox(q, if (dir != null) "Search in this folder" else "Search") { q = it }
        if (cfg.chips.isNotEmpty()) ChipRow(cfg.chips.map { it.first }, chip) { chip = it }
        if (dir != null && Clip.files.isNotEmpty()) Row(
            Modifier.fillMaxWidth().background(P.sel).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${Clip.files.size} item(s) to ${if (Clip.move) "move" else "copy"}", Modifier.weight(1f), fontWeight = FontWeight.Medium, color = P.ink)
            TextButton({ Clip.files = emptyList() }) { Text("Cancel") }
            Button({
                val fs = Clip.files; val mv = Clip.move
                ui.work("${fs.size} item(s) ${if (mv) "moved" else "copied"} here", { Clip.files = emptyList(); tick++; Index.invalidate() }) {
                    fs.forEach { if (mv) Fx.move(it, dir) else Fx.copy(it, dir) }
                    Fx.scan(ctx, fs.map { it.path })
                }
            }) { Text("Paste here") }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            EntryList(
                rows, cfg.grid, sel, dir == null || q.isNotBlank(),
                onTap = { e ->
                    when {
                        sel.isNotEmpty() -> sel = if (e.path in sel) sel - e.path else sel + e.path
                        cfg.pick != null && !e.isDir -> ui.picked(cfg.pick, e.file)
                        e.isDir -> ui.go(Screen.Browse(e.file, cfg.pick))
                        else -> ui.open(e.file, visible.filter { !it.isDir }.map { it.file })
                    }
                },
                onLong = { e -> sel = if (e.path in sel) sel - e.path else sel + e.path },
                onGroup = { ps -> sel = if (ps.all { it in sel }) sel - ps.toSet() else sel + ps },
                modifier = Modifier.fillMaxSize()
            )
            if (loading && visible.isEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (!loading && visible.isEmpty()) Text("Nothing here", Modifier.align(Alignment.Center), color = P.muted)
        }
    }

    if (dialog == "zip") CompressDialog(ui, picked().map { it.file }, { dialog = "" }) { sel = emptySet(); tick++; Index.invalidate() }
    if (dialog == "delete") AlertDialog(
        onDismissRequest = { dialog = "" },
        title = { Text("Delete ${sel.size} item(s)?") },
        text = { Text("They move to the Recycle Bin. You can restore them there.") },
        confirmButton = {
            Button({
                dialog = ""
                val fs = picked().map { it.file }
                val ps = fs.map { it.path }.toSet()
                ui.work("${fs.size} item(s) moved to Recycle Bin", { gone = gone + ps; sel = emptySet(); Index.invalidate() }, { tick++ }) { Trash.addAll(ctx, fs) }
            }, colors = ButtonDefaults.buttonColors(containerColor = Err, contentColor = Color.White)) { Text("Delete") }
        },
        dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } }
    )
    if (dialog == "rename") AlertDialog(
        onDismissRequest = { dialog = "" },
        title = { Text("Rename") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true) },
        confirmButton = {
            Button({
                val f = picked().firstOrNull()?.file; val n = text.trim(); dialog = ""
                if (f == null || n.isEmpty() || n.contains('/')) ui.err("Invalid name")
                else {
                    val t = File(f.parentFile, n)
                    if (t.exists()) ui.err("A file with that name already exists")
                    else if (f.renameTo(t)) { ui.ok("Renamed to $n"); sel = emptySet(); tick++; Index.invalidate(); Fx.scan(ctx, listOf(f.path, t.path)) }
                    else ui.err("Could not rename")
                }
            }) { Text("Rename") }
        },
        dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } }
    )
    if (dialog == "folder" && dir != null) AlertDialog(
        onDismissRequest = { dialog = "" },
        title = { Text("New folder") },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("Folder name") }) },
        confirmButton = {
            Button({
                val n = text.trim(); dialog = ""
                if (n.isEmpty() || n.contains('/')) ui.err("Invalid name")
                else if (File(dir, n).mkdirs()) { ui.ok("Folder \"$n\" created"); tick++ }
                else ui.err("Could not create folder")
            }) { Text("Create") }
        },
        dismissButton = { TextButton({ dialog = "" }) { Text("Cancel") } }
    )
}

private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "rtf", "odt", "epub", "md")

/** Images / Videos / Audio / Documents / Large files, fed from the file index. */
@Composable
fun ListingScreen(ui: Ui, kind: String) {
    val lctx = ui.act
    // তালিকা খোলার সময় index পুরোনো (invalidate করা) থাকলে নতুন করে বানায়, তাই নতুন ফাইল সবার উপরে দেখা যায়
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Index.build(lctx) } }
    val v = Index.version
    val base by produceState<List<Entry>?>(null, v) {
        value = withContext(Dispatchers.Default) { if (!Index.ready) null else Index.listFor(kind) }
    }
    val title = if (kind == "large") "Large files" else Cat.valueOf(kind).label
    val b = base
    if (b == null) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, ui::back)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val cfg = when (kind) {
        "Images" -> ListCfg(grid = true, defSort = 1)
        "Videos" -> ListCfg(grid = true, search = false, defSort = 1)
        "Audio" -> ListCfg(defSort = 1)
        "large" -> ListCfg(defSort = 2)
        else -> ListCfg(
            defSort = 1,
            chips = listOf<Pair<String, (Entry) -> Boolean>>(
                "All" to { _ -> true },
                "Documents" to { e -> e.ext in DOC_EXT },
                "ZIP" to { e -> e.ext in Fx.ARC },
                "APK" to { e -> e.ext == "apk" },
                "Other" to { e -> e.ext !in DOC_EXT && e.ext !in Fx.ARC && e.ext != "apk" }
            )
        )
    }
    FileListScreen(ui, title, null, b, cfg)
}
