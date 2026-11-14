package com.punjabibooktranslator.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders PDF pages as bitmaps with the platform [PdfRenderer]. PdfRenderer allows only one open page at a
 * time, so every render is serialised behind a mutex. Rendered pages are cached in memory.
 */
class PdfPageRenderer(private val file: File) {
    private val mutex = Mutex()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(1 shl 20)) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val fileAvailable: Boolean get() = file.exists()

    private fun ensureOpen(): PdfRenderer? {
        renderer?.let { return it }
        if (!file.exists()) return null
        val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd = p
        return PdfRenderer(p).also { renderer = it }
    }

    /** [page] is 0-based. Returns null if the file is missing or the page cannot be rendered. */
    suspend fun render(page: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$page@$widthPx"
        val hit = cache.get(key)
        if (hit != null) return@withContext hit
        mutex.withLock {
            val again = cache.get(key)
            if (again != null) return@withLock again
            try {
                val r = ensureOpen()
                if (r == null || page < 0 || page >= r.pageCount) {
                    null
                } else {
                    r.openPage(page).use { pg ->
                        val h = (widthPx.toFloat() * pg.height / pg.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bmp)
                        bmp
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun close() {
        mutex.withLock {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            renderer = null
            pfd = null
            cache.evictAll()
        }
    }
}
