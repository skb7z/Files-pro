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
import android.Manifest
import android.provider.Settings
import androidx.compose.ui.res.painterResource
import androidx.core.app.ActivityCompat

@Composable
fun Home(ui: Ui) {
    val ctx = ui.act
    val root = remember { Fx.root() }
    val vols = remember { Fx.volumes(ctx) }
    val (total, free) = remember { Fx.space(root) }
    var apps by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Index.build(ctx); apps = AppsRepo.userCount(ctx) } }
    val sizes by produceState<Map<Cat, Long>>(emptyMap(), Index.version) { value = withContext(Dispatchers.Default) { Index.sizes() } }
    val used = total - free

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.app_logo), null, Modifier.size(38.dp))
            Spacer(Modifier.width(10.dp))
            Text("Files Pro", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = P.ink)
        }
        LazyVerticalGrid(
            GridCells.Fixed(2), Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(2) }) {
                val shape = RoundedCornerShape(24.dp)
                Column(
                    Modifier.fillMaxWidth().clip(shape)
                        .background(Brush.linearGradient(listOf(Color(0xFF2FB4F8), BrandPurple)))
                        .clickable { ui.go(Screen.Browse(root)) }.padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Internal storage", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
                        Icon(Icons.Filled.ChevronRight, null, tint = Color.White)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(Fx.size(used) + " used", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { if (total > 0) used.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = Color.White, trackColor = Color(0x55FFFFFF)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${Fx.size(free)} free of ${Fx.size(total)}", fontSize = 13.sp, color = Color(0xDDFFFFFF))
                }
            }
            vols.forEach { v ->
                item(span = { GridItemSpan(2) }) {
                    val (t, f) = Fx.space(v)
                    Tile(Icons.Filled.SdCard, Color(0xFF0E7490), "SD card / USB", "${Fx.size(t - f)} used of ${Fx.size(t)}", Modifier.fillMaxWidth()) { ui.go(Screen.Browse(v)) }
                }
            }
            item(span = { GridItemSpan(2) }) { SectionTitle("Categories") }
            Cat.values().forEach { c ->
                item {
                    val sub = when {
                        c == Cat.Apps -> if (apps < 0) "…" else "$apps apps"
                        !Index.ready -> "…"
                        else -> Fx.size(sizes[c] ?: 0L)
                    }
                    Tile(c.icon, c.color, c.label, sub, Modifier.fillMaxWidth()) {
                        when (c) {
                            Cat.Downloads -> ui.go(Screen.Listing("Downloads"))
                            Cat.Apps -> ui.go(Screen.Apps)
                            else -> ui.go(Screen.Listing(c.name))
                        }
                    }
                }
            }
            item(span = { GridItemSpan(2) }) { SectionTitle("Manage") }
            item { Tile(Icons.Filled.Lock, Color(0xFF6366F1), "Safe Folder", "Locked files", Modifier.fillMaxWidth()) { ui.go(Screen.SafeBox) } }
            item { Tile(Icons.Filled.Delete, Color(0xFF64748B), "Recycle Bin", "Deleted files", Modifier.fillMaxWidth()) { ui.go(Screen.Bin) } }
            item(span = { GridItemSpan(2) }) { Tile(Icons.Filled.Build, Color(0xFFEC4899), "Tools", "ZIP viewer, duplicates, theme", Modifier.fillMaxWidth()) { ui.go(Screen.Tools) } }
        }
    }
}

private const val DEV_EMAIL = "skbh7z@gmail.com"

fun sendFeedback(ui: Ui) {
    val body = "Hello Files Pro team,\n\n" +
        "1) What is the problem?\n" +
        "   (Write here)\n\n\n" +
        "2) What did you expect to happen?\n" +
        "   (Write here)\n\n\n" +
        "3) How can we repeat it? (optional)\n" +
        "   Step 1:\n   Step 2:\n\n\n" +
        "──────────────────────────\n" +
        "DEVICE DETAILS (added automatically, please keep)\n" +
        "App version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
        "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
        "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n"
    val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
        putExtra(Intent.EXTRA_EMAIL, arrayOf(DEV_EMAIL))
        putExtra(Intent.EXTRA_SUBJECT, "Files Pro feedback")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    try { ui.act.startActivity(i) } catch (e: Exception) { ui.err("No email app found") }
}

@Composable
fun ToolsScreen(ui: Ui) {
    var dialog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBar("Tools", ui::back)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Icons.Filled.FolderZip, Color(0xFF64748B), "ZIP viewer", "See inside a ZIP without extracting", Modifier.fillMaxWidth()) { ui.go(Screen.Browse(Fx.root(), "zip")) }
            Tile(Icons.Filled.ContentCopy, Color(0xFF8B5CF6), "Duplicate finder", "Find identical files", Modifier.fillMaxWidth()) { ui.go(Screen.Dupes) }
            Tile(Icons.Filled.Storage, Color(0xFFF59E0B), "Large files", "Biggest files first", Modifier.fillMaxWidth()) { ui.go(Screen.Listing("large")) }
            Tile(Icons.Filled.Palette, Color(0xFF0EA5E9), "Appearance", when (AppPrefs.theme) { 1 -> "Light"; 2 -> "Dark"; else -> "Regular" }, Modifier.fillMaxWidth()) { dialog = true }
            Tile(Icons.Filled.SupportAgent, Color(0xFFEF4444), "Help & feedback", null, Modifier.fillMaxWidth()) { sendFeedback(ui) }
        }
    }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = { Text("Appearance") },
        text = {
            Column {
                listOf(2 to "Dark", 1 to "Light", 0 to "Regular").forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            AppPrefs.theme = v
                            ui.act.getSharedPreferences("app", 0).edit().putInt("theme", v).apply()
                            dialog = false
                        }.padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(AppPrefs.theme == v, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(label, fontSize = 16.sp, color = P.ink)
                            if (v == 0) Text("Follows your phone (dark mode, battery saver)", fontSize = 12.sp, color = P.muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ dialog = false }) { Text("Close") } }
    )
}

@Composable
fun PermissionScreen(act: FragmentActivity) {
    Column(
        Modifier.fillMaxSize().background(P.bg).padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.app_logo), null, Modifier.size(112.dp))
        Spacer(Modifier.height(20.dp))
        Text("Welcome to Files Pro", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = P.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Allow file access to get started", fontSize = 15.sp, color = P.muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            listOf(
                Triple(Icons.Filled.FolderOpen, "Browse everything", "Photos, videos, music, documents and more"),
                Triple(Icons.Filled.Bolt, "Manage with ease", "Copy, move, share and clean up files fast"),
                Triple(Icons.Filled.Lock, "Private by design", "Your files never leave your phone")
            ).forEach { (ic, t, s) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(44.dp).background(P.primary.copy(alpha = 0.14f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(ic, null, tint = P.primary)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column { Text(t, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = P.ink); Text(s, fontSize = 13.sp, color = P.muted) }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        Button({
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    act.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${act.packageName}")))
                } catch (e: Exception) { act.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            } else ActivityCompat.requestPermissions(act, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Continue", fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
    }
}
