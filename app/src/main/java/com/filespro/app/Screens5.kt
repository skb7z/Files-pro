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
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

fun bio(act: FragmentActivity, onOk: () -> Unit) {
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock Safe Folder").setNegativeButtonText("Use password")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK).build()
    BiometricPrompt(act, ContextCompat.getMainExecutor(act), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    }).authenticate(info)
}

private fun isCode(s: String) = s.length == 6 && s.all { it.isDigit() }

@Composable
fun CodeField(label: String, v: String, onChange: (String) -> Unit) =
    OutlinedTextField(
        v, { if (it.length <= 6 && it.all { c -> c.isDigit() }) onChange(it) }, Modifier.fillMaxWidth(), label = { Text(label) },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(14.dp)
    )

@Composable
fun SafeHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(76.dp).background(P.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = P.primary, modifier = Modifier.size(36.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = P.ink)
    }
}

@Composable
fun BigButton(text: String, enabled: Boolean = true, danger: Boolean = false, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(16.dp),
        colors = if (danger) ButtonDefaults.buttonColors(containerColor = Err, contentColor = Color.White) else ButtonDefaults.buttonColors()
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }

@Composable
fun SafeScreen(ui: Ui) {
    val ctx = ui.act
    // Screenshots and screen recording show black while the Safe Folder is open.
    DisposableEffect(Unit) {
        ctx.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { ctx.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    LaunchedEffect(Unit) { ui.keep = false }
    DisposableEffect(Unit) { onDispose { if (!ui.keep) ui.safeOpen = false } }
    var set by remember { mutableStateOf(Safe.isSet(ctx)) }
    var mode by remember { mutableStateOf("lock") }   // lock, forgot, code, newpw, ecode
    val lc = LocalLifecycleOwner.current
    DisposableEffect(lc) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) { ui.safeOpen = false; mode = "lock" } }
        lc.lifecycle.addObserver(o)
        onDispose { lc.lifecycle.removeObserver(o) }
    }
    BackHandler(set && !ui.safeOpen && mode != "lock") { mode = "lock" }
    Column(Modifier.fillMaxSize()) {
        when {
            ui.safeOpen && set -> VaultPane(ui)
            else -> {
                TopBar("Safe Folder", { if (mode != "lock" && set) mode = "lock" else ui.back() })
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when {
                        !set -> SetupPane(ui) { set = true; ui.safeOpen = true }
                        mode == "lock" -> LockPane(ui, { ui.safeOpen = true }, { mode = "forgot" })
                        mode == "forgot" -> ForgotPane(ui, { mode = "code" }) { set = false; mode = "lock" }
                        mode == "code" -> CodePane(ui) { mode = "newpw" }
                        mode == "newpw" -> NewPwPane(ui, { mode = "ecode" }) { ui.safeOpen = true; mode = "lock" }
                        else -> ECodePane(ui) { ui.safeOpen = true; mode = "lock" }
                    }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.SetupPane(ui: Ui, onDone: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var c by remember { mutableStateOf("") }
    SafeHeader("Create Safe Folder")
    PwField("Password", a) { a = it }
    PwField("Repeat password", b) { b = it }
    CodeField("Emergency code (6 digits)", c) { c = it }
    Text("The emergency code lets you reset your password if you forget it.", fontSize = 12.sp, color = P.muted)
    BigButton("Create") {
        if (a.length < 4) ui.err("Password must be at least 4 characters")
        else if (a != b) ui.err("Passwords do not match")
        else if (!isCode(c)) ui.err("Emergency code must be 6 digits")
        else ui.bg({ Safe.setPassword(ui.act, a); Safe.setEmergency(ui.act, c) }) { ui.ok("Safe Folder created"); onDone() }
    }
}

@Composable
fun ColumnScope.LockPane(ui: Ui, onUnlocked: () -> Unit, onForgot: () -> Unit) {
    val ctx = ui.act
    var pw by remember { mutableStateOf("") }
    var left by remember { mutableLongStateOf(Safe.lockLeft(ctx)) }
    LaunchedEffect(left > 0L) { while (left > 0L) { delay(500); left = Safe.lockLeft(ctx) } }
    val bioOk = remember { BiometricManager.from(ctx).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS }
    fun tryUnlock() {
        if (left > 0L) return
        val typed = pw
        ui.bg({ Safe.check(ctx, typed) }) { ok ->
            if (ok) { pw = ""; onUnlocked() }
            else {
                left = Safe.lockLeft(ctx)
                ui.err(if (left > 0L) "Too many attempts. Try again in ${left / 1000 + 1}s" else "Wrong password")
            }
        }
    }
    SafeHeader("Safe Folder")
    PwField("Password", pw) { pw = it }
    if (left > 0L) Text("Try again in ${left / 1000 + 1} seconds", color = Err, fontWeight = FontWeight.Medium)
    BigButton("Unlock", enabled = left == 0L) { tryUnlock() }
    if (bioOk) OutlinedButton({ bio(ctx, onUnlocked) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) {
        Icon(Icons.Filled.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Use fingerprint / face")
    }
    TextButton(onForgot, Modifier.align(Alignment.CenterHorizontally)) { Text("Forgot password?") }
}

@Composable
fun ColumnScope.ForgotPane(ui: Ui, onUseCode: () -> Unit, onErased: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    SafeHeader("Forgot password?")
    Tile(Icons.Filled.Key, Color(0xFF6366F1), "Use emergency code", "Reset your password, keep your files", Modifier.fillMaxWidth(), onUseCode)
    Tile(Icons.Filled.DeleteForever, Err, "Erase everything", "Delete all files and start over", Modifier.fillMaxWidth()) { confirm = true }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Erase Safe Folder?") },
        text = { Text("All files inside will be deleted permanently.") },
        confirmButton = { Button({ confirm = false; Safe.reset(ui.act); ui.ok("Safe Folder erased"); onErased() }, colors = ButtonDefaults.buttonColors(containerColor = Err, contentColor = Color.White)) { Text("Erase") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } }
    )
}

@Composable
fun ColumnScope.CodePane(ui: Ui, onOk: () -> Unit) {
    val ctx = ui.act
    var c by remember { mutableStateOf("") }
    var left by remember { mutableLongStateOf(Safe.emergencyLockLeft(ctx)) }
    LaunchedEffect(left > 0L) { while (left > 0L) { delay(500); left = Safe.emergencyLockLeft(ctx) } }
    SafeHeader("Emergency code")
    if (!Safe.hasEmergency(ctx)) Text("No emergency code was set. Go back and erase to start over.", color = Err)
    CodeField("6-digit code", c) { c = it }
    if (left > 0L) Text("Try again in ${left / 1000 + 1} seconds", color = Err, fontWeight = FontWeight.Medium)
    BigButton("Continue", enabled = left == 0L && Safe.hasEmergency(ctx)) {
        val typed = c
        ui.bg({ Safe.checkEmergency(ctx, typed) }) { ok ->
            if (ok) onOk()
            else { left = Safe.emergencyLockLeft(ctx); ui.err(if (left > 0L) "Too many attempts. Please wait" else "Wrong code") }
        }
    }
}

@Composable
fun ColumnScope.NewPwPane(ui: Ui, onAskCode: () -> Unit, onSkip: () -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf(false) }
    SafeHeader("New password")
    PwField("New password", a) { a = it }
    PwField("Repeat password", b) { b = it }
    BigButton("Save password") {
        if (a.length < 4) ui.err("Password must be at least 4 characters")
        else if (a != b) ui.err("Passwords do not match")
        else ui.bg({ Safe.setPassword(ui.act, a) }) { ui.ok("Password changed"); ask = true }
    }
    if (ask) AlertDialog(
        onDismissRequest = { },
        title = { Text("Change emergency code too?") },
        text = { Text("You can keep your current code or set a new one.") },
        confirmButton = { Button({ ask = false; onAskCode() }) { Text("Yes, change") } },
        dismissButton = { TextButton({ ask = false; onSkip() }) { Text("No, keep it") } }
    )
}

@Composable
fun ColumnScope.ECodePane(ui: Ui, onDone: () -> Unit) {
    var c by remember { mutableStateOf("") }
    SafeHeader("New emergency code")
    CodeField("6-digit code", c) { c = it }
    BigButton("Save code") {
        if (!isCode(c)) ui.err("Emergency code must be 6 digits")
        else ui.bg({ Safe.setEmergency(ui.act, c) }) { ui.ok("Emergency code changed"); onDone() }
    }
}

@Composable
fun VaultPane(ui: Ui) {
    val ctx = ui.act
    var list by remember { mutableStateOf<List<SafeItem>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var dlg by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<SafeItem?>(null) }
    var o by remember { mutableStateOf("") }
    var n by remember { mutableStateOf("") }
    var n2 by remember { mutableStateOf("") }
    var needCode by remember { mutableStateOf(!Safe.hasEmergency(ctx)) }
    LaunchedEffect(tick) { list = withContext(Dispatchers.IO) { Safe.items(ctx) } }
    Column(Modifier.fillMaxSize()) {
        TopBar("Safe Folder", ui::back, "${list.size} files") {
            Box {
                IconButton({ menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Change password") }, onClick = { menu = false; o = ""; n = ""; n2 = ""; dlg = "change" })
                    DropdownMenuItem(text = { Text("Change emergency code") }, onClick = { menu = false; o = ""; n = ""; dlg = "ecode" })
                    DropdownMenuItem(text = { Text("Lock now") }, onClick = { menu = false; ui.safeOpen = false })
                }
            }
        }
        Button({ ui.keep = true; ui.go(Screen.Browse(Fx.root(), "safe")) }, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp).height(50.dp), shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); Text("Add files", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (list.isEmpty()) Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Nothing here yet", color = P.muted, textAlign = TextAlign.Center)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { it ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(P.card).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val (ic, col) = fileIcon(false, it.name.substringAfterLast('.', "").lowercase())
                        Box(Modifier.size(44.dp).background(col.copy(alpha = 0.16f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(ic, null, tint = col) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(it.name, fontWeight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(Fx.size(it.size), fontSize = 12.sp, color = P.muted)
                        }
                    }
                    Row {
                        TextButton({
                            ui.work("Restored to Downloads/Restored", { tick++; Index.invalidate() }) {
                                val d = File(Fx.downloads(), "Restored").also { x -> x.mkdirs() }
                                val out = Fx.unique(d, it.name)
                                Safe.decrypt(ctx, it.id, out)
                                Safe.remove(ctx, it.id)
                                Fx.scan(ctx, listOf(out.path))
                            }
                        }) { Icon(Icons.Filled.Restore, null); Spacer(Modifier.width(6.dp)); Text("Restore") }
                        TextButton({ target = it; dlg = "delete" }) { Text("Delete", color = Err) }
                    }
                }
            }
        }
    }
    if (needCode) AlertDialog(
        onDismissRequest = { },
        title = { Text("Set emergency code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Choose a 6-digit code to reset your password if you forget it."); CodeField("6-digit code", n) { n = it } } },
        confirmButton = { Button({ if (isCode(n)) { val code = n; ui.bg({ Safe.setEmergency(ctx, code) }) { n = ""; needCode = false; ui.ok("Emergency code saved") } } else ui.err("Emergency code must be 6 digits") }) { Text("Save") } }
    )
    if (dlg == "delete" && target != null) AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text("Delete permanently?") }, text = { Text("${target!!.name} will be deleted forever.") },
        confirmButton = { Button({ val t = target!!; dlg = ""; ui.work("Deleted", { tick++ }) { Safe.remove(ctx, t.id) } }, colors = ButtonDefaults.buttonColors(containerColor = Err, contentColor = Color.White)) { Text("Delete") } },
        dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
    )
    if (dlg == "change") AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text("Change password") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PwField("Current password", o) { o = it }; PwField("New password", n) { n = it }; PwField("Repeat new password", n2) { n2 = it } } },
        confirmButton = {
            Button({
                if (n.length < 4) ui.err("New password must be at least 4 characters")
                else if (n != n2) ui.err("New passwords do not match")
                else { val oo = o; val nn = n; ui.bg({ Safe.change(ctx, oo, nn) }) { ok -> if (ok) { dlg = ""; ui.ok("Password changed") } else ui.err("Current password is wrong") } }
            }) { Text("Change") }
        },
        dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
    )
    if (dlg == "ecode") AlertDialog(
        onDismissRequest = { dlg = "" },
        title = { Text("Change emergency code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { PwField("Password", o) { o = it }; CodeField("New 6-digit code", n) { n = it } } },
        confirmButton = {
            Button({
                if (!isCode(n)) ui.err("Emergency code must be 6 digits")
                else { val oo = o; val nn = n; ui.bg({ if (Safe.check(ctx, oo)) { Safe.setEmergency(ctx, nn); true } else false }) { ok -> if (ok) { dlg = ""; ui.ok("Emergency code changed") } else ui.err("Password is wrong") } }
            }) { Text("Save") }
        },
        dismissButton = { TextButton({ dlg = "" }) { Text("Cancel") } }
    )
}
