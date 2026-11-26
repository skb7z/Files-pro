package com.filespro.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** Small cached thumbnails (photos, video frames, album art, APK icons, app icons). */
object Thumbs {
    private val cache = object : LruCache<String, Bitmap>(maxOf(8 * 1024, (Runtime.getRuntime().maxMemory() / 1024 / 7).toInt())) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }
    private val pool = Executors.newFixedThreadPool(3).asCoroutineDispatcher()

    fun peek(key: String): Bitmap? = cache.get(key)

    suspend fun load(ctx: Context, e: Entry, px: Int, key: String): Bitmap? = withContext(pool) {
        cache.get(key) ?: run {
            val b = try { make(ctx, e, px) } catch (t: Throwable) { null }
            if (b != null) cache.put(key, b)
            b
        }
    }

    suspend fun app(ctx: Context, pkg: String, px: Int): Bitmap? = withContext(pool) {
        val k = "app:$pkg|$px"
        cache.get(k) ?: run {
            val b = try { ctx.packageManager.getApplicationIcon(pkg).toBitmap(px, px) } catch (t: Throwable) { null }
            if (b != null) cache.put(k, b)
            b
        }
    }

    private fun make(ctx: Context, e: Entry, px: Int): Bitmap? = when (Fx.kind(e.ext)) {
        1 -> image(e.file, px)
        3 -> video(e.file, px)
        2 -> audio(e.file, px)
        4 -> apk(ctx, e.file, px)
        else -> null
    }

    private fun image(f: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ThumbnailUtils.createImageThumbnail(f, Size(px, px), null) } catch (e: Exception) { }
        }
        return sampled(f, px)
    }

    @Suppress("DEPRECATION")
    private fun video(f: File, px: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            try { return ThumbnailUtils.createVideoThumbnail(f, Size(px, px), null) } catch (e: Exception) { }
        }
        return ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
    }

    private fun audio(f: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            val b = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (e: Exception) { null } finally { try { r.release() } catch (e: Exception) { } }
    }

    private fun apk(ctx: Context, f: File, px: Int): Bitmap? {
        val pm = ctx.packageManager
        val info = pm.getPackageArchiveInfo(f.path, 0) ?: return null
        val ai = info.applicationInfo ?: return null
        ai.sourceDir = f.path; ai.publicSourceDir = f.path
        return ai.loadIcon(pm).toBitmap(px, px)
    }

    private fun sampled(f: File, px: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val ext = f.extension.lowercase()
        if (ext != "jpg" && ext != "jpeg") return b
        val rot = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        } catch (e: Exception) { 0f }
        return if (rot == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(rot) }, true)
    }
}

/** Shows the thumbnail of a file, or a coloured file-type icon while it loads / when it has none. */
@Composable
fun Thumb(e: Entry, px: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val k = "${e.path}|${e.mod}|$px"
    val has = Fx.kind(e.ext) != 0
    val bmp by produceState<Bitmap?>(if (has) Thumbs.peek(k) else null, k) {
        if (has && value == null) value = Thumbs.load(ctx, e, px, k)
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop)
    else {
        val (ic, col) = fileIcon(e.isDir, e.ext)
        Box(modifier.background(col.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { Icon(ic, null, tint = col) }
    }
}

@Composable
fun AppIcon(pkg: String, px: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(Thumbs.peek("app:$pkg|$px"), pkg) { if (value == null) value = Thumbs.app(ctx, pkg, px) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, modifier, contentScale = ContentScale.Fit)
    else Box(modifier.fillMaxSize())
}
