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
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.theme = getSharedPreferences("app", 0).getInt("theme", 0)
        setContent { Root(this) }
    }
}

private fun hasAccess(a: FragmentActivity): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

@Composable
fun Root(act: FragmentActivity) {
    var granted by remember { mutableStateOf(hasAccess(act)) }
    val lc = LocalLifecycleOwner.current
    DisposableEffect(lc) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = hasAccess(act) }
        lc.lifecycle.addObserver(o)
        onDispose { lc.lifecycle.removeObserver(o) }
    }
    // The permission (welcome) screen is always dark; the real app follows the chosen theme
    AppTheme(forceDark = !granted) { if (granted) MainApp(act) else PermissionScreen(act) }
}

@Composable
fun MainApp(act: FragmentActivity) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val snack = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val player = remember { PlayerCtl(act) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val ui = remember { Ui(act, snack, scope, stack, player) }
    BackHandler(stack.size > 1) { ui.back() }

    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) perms.launch(arrayOf(
            Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO
        ))
    }
    var showTick by remember { mutableStateOf(false) }
    LaunchedEffect(ui.flash) { if (ui.flash > 0) { showTick = true; delay(700); showTick = false } }

    Scaffold(
        containerColor = P.bg,
        snackbarHost = {
            SnackbarHost(snack) { d ->
                val good = d.visuals.message.startsWith("✓")
                Snackbar(Modifier.padding(12.dp), containerColor = if (good) Ok else Err, contentColor = Color.White) {
                    Text(d.visuals.message, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        },
        bottomBar = { MiniPlayer(player) }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val s = stack.last()
            // key(s): every screen / folder gets fresh state, so opening a folder always shows its own files
            key(s) {
                when (s) {
                    is Screen.Home -> Home(ui)
                    is Screen.Browse -> FileListScreen(
                        ui, if (s.dir == Fx.root()) "Internal storage" else s.dir.name.ifEmpty { "Storage" }, s.dir, null,
                        ListCfg(pick = s.pick)
                    )
                    is Screen.Listing -> ListingScreen(ui, s.kind)
                    is Screen.Tools -> ToolsScreen(ui)
                    is Screen.Bin -> BinScreen(ui)
                    is Screen.SafeBox -> SafeScreen(ui)
                    is Screen.Dupes -> DupesScreen(ui)
                    is Screen.Apps -> AppsScreen(ui)
                    is Screen.Zip -> ZipScreen(ui, s.f)
                    is Screen.Img -> ImgScreen(ui, s.files, s.index)
                    is Screen.Pdf -> PdfScreen(ui, s.f)
                    is Screen.Txt -> TxtScreen(ui, s.f)
                }
            }
            if (ui.busy) Dialog(onDismissRequest = {}) {
                Row(Modifier.clip(RoundedCornerShape(20.dp)).background(P.card).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(30.dp)); Spacer(Modifier.width(16.dp)); Text("Working…", fontSize = 16.sp, color = P.ink)
                }
            }
            AnimatedVisibility(showTick, Modifier.align(Alignment.Center), enter = scaleIn() + fadeIn(), exit = fadeOut()) {
                Surface(shape = CircleShape, color = Ok, shadowElevation = 8.dp) { Icon(Icons.Filled.Check, null, Modifier.padding(18.dp).size(52.dp), tint = Color.White) }
            }
        }
    }
}
