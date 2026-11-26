package com.filespro.app

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

class Pal(
    val dark: Boolean, val bg: Color, val card: Color, val ink: Color, val muted: Color,
    val primary: Color, val sel: Color, val line: Color, val chip: Color
)

// ---- Brand colours (taken from the app icon) ----
val BrandBlue = Color(0xFF33C2FE)    // sky blue  - left paper
val BrandPurple = Color(0xFF8B58F5)  // purple    - right paper
val BrandCream = Color(0xFFEAF4FE)   // light cream - overlap of the two papers
val BrandNavy = Color(0xFF0E1330)    // icon background

val LightPal = Pal(
    false, BrandCream, Color.White, BrandNavy, Color(0xFF5B6385),
    BrandPurple, Color(0xFFD6E9FD), Color(0xFFD3E2F3), Color(0xFFDCEBFA)
)
val DarkPal = Pal(
    true, BrandNavy, Color(0xFF171E45), BrandCream, Color(0xFFA3ACCB),
    BrandBlue, Color(0xFF2A2D66), Color(0xFF272E5C), Color(0xFF1F2650)
)
val LocalPal = staticCompositionLocalOf { LightPal }
val P: Pal @Composable @ReadOnlyComposable get() = LocalPal.current

val Ok = Color(0xFF12924B)
val Err = Color(0xFFD92D20)
val Warn = Color(0xFFB45309)

@Composable
fun AppTheme(forceDark: Boolean = false, content: @Composable () -> Unit) {
    val dark = forceDark || when (AppPrefs.theme) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val pal = if (dark) DarkPal else LightPal
    val view = LocalView.current
    SideEffect {
        val w = (view.context as Activity).window
        w.statusBarColor = pal.bg.toArgb()
        w.navigationBarColor = pal.bg.toArgb()
        val c = WindowCompat.getInsetsController(w, view)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }
    val onP = if (dark) Color(0xFF0B1220) else Color.White
    val scheme = if (dark) darkColorScheme(
        primary = pal.primary, onPrimary = onP, primaryContainer = pal.sel, onPrimaryContainer = pal.ink,
        background = pal.bg, onBackground = pal.ink, surface = pal.card, onSurface = pal.ink,
        surfaceVariant = pal.chip, onSurfaceVariant = pal.muted, outline = pal.line, error = Err,
        surfaceContainer = pal.card, surfaceContainerHigh = pal.card, surfaceContainerHighest = pal.chip,
        surfaceContainerLow = pal.card, surfaceContainerLowest = pal.bg
    ) else lightColorScheme(
        primary = pal.primary, onPrimary = onP, primaryContainer = pal.sel, onPrimaryContainer = pal.ink,
        background = pal.bg, onBackground = pal.ink, surface = pal.card, onSurface = pal.ink,
        surfaceVariant = pal.chip, onSurfaceVariant = pal.muted, outline = pal.line, error = Err,
        surfaceContainer = pal.card, surfaceContainerHigh = pal.card, surfaceContainerHighest = pal.chip,
        surfaceContainerLow = pal.card, surfaceContainerLowest = pal.bg
    )
    CompositionLocalProvider(LocalPal provides pal) { MaterialTheme(colorScheme = scheme, content = content) }
}

enum class Cat(val label: String, val icon: ImageVector, val color: Color) {
    Downloads("Downloads", Icons.Filled.Download, Color(0xFF0EA5E9)),
    Images("Images", Icons.Filled.Image, Color(0xFF22C55E)),
    Audio("Audio", Icons.Filled.AudioFile, Color(0xFFF59E0B)),
    Videos("Videos", Icons.Filled.VideoFile, Color(0xFF8B5CF6)),
    Documents("Documents", Icons.Filled.Description, Color(0xFF3B82F6)),
    Apps("Apps", Icons.Filled.Android, Color(0xFF14B8A6))
}

fun fileIcon(isDir: Boolean, ext: String): Pair<ImageVector, Color> {
    if (isDir) return Icons.Filled.Folder to Color(0xFFF5A524)
    return when {
        ext in Fx.IMG -> Icons.Filled.Image to Cat.Images.color
        ext in Fx.AUD -> Icons.Filled.AudioFile to Cat.Audio.color
        ext in Fx.VID -> Icons.Filled.VideoFile to Cat.Videos.color
        ext == "apk" -> Icons.Filled.Android to Cat.Apps.color
        ext in Fx.ARC -> Icons.Filled.FolderZip to Color(0xFF64748B)
        ext == "pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFEF4444)
        else -> Icons.Filled.Description to Cat.Documents.color
    }
}
