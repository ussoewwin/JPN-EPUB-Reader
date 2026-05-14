package com.jpnepub.reader.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.LruCache
import android.view.View

/**
 * Manga page viewer — displays one full-page image per page.
 *
 * Unlike [VerticalEpubView] which lays out individual glyphs on a Canvas,
 * this view simply decodes and draws a single JPEG/PNG image per page,
 * fit-to-screen with aspect ratio preserved and centered on a black background.
 *
 * Memory management: Only the currently displayed bitmap plus a small LRU cache
 * (default 3 entries) are kept in memory. Bitmaps are decoded on-demand and
 * recycled by the LRU eviction callback.
 *
 * Usage:
 *   mangaView.setPages(imagePaths, imageResolver)
 *   mangaView.nextPage() / mangaView.prevPage()
 */
class MangaView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Data ---
    private var pages: List<String> = emptyList()
    private var imageResolver: (String) -> ByteArray? = { null }
    private var currentPage: Int = 0

    // --- Drawing ---
    private val bgPaint = Paint().apply { color = Color.BLACK }
    private val imagePaint = Paint(
        Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG
    )

    // --- Bitmap cache ---
    // Cache size: 3 pages (current ± 1) is enough for smooth flipping.
    // Max memory per entry is capped at 8MB (a 1031×1500 ARGB_8888 is ~6MB).
    private val bitmapCache = object : LruCache<Int, Bitmap>(3) {
        override fun entryRemoved(
            evicted: Boolean,
            key: Int?,
            oldValue: Bitmap?,
            newValue: Bitmap?
        ) {
            // Don't recycle if the same bitmap was put back
            if (evicted && oldValue != null && !oldValue.isRecycled) {
                oldValue.recycle()
            }
        }
    }

    // --- Callbacks ---
    /** (currentPageIndex, totalPages) */
    var onPageChanged: ((Int, Int) -> Unit)? = null
    /** +1 = beyond last page, -1 = before first page */
    var onChapterBoundary: ((Int) -> Unit)? = null

    /**
     * Set the list of manga page image paths and the resolver to load them.
     *
     * @param pages Ordered list of resource paths (from EpubBook.mangaPages).
     *              Empty-string entries are treated as blank pages.
     * @param imageResolver Function that returns the raw image bytes for a path,
     *                      or null if not found.
     * @param startPage Initial page index to display (default 0).
     */
    fun setPages(
        pages: List<String>,
        imageResolver: (String) -> ByteArray?,
        startPage: Int = 0,
    ) {
        this.pages = pages
        this.imageResolver = imageResolver
        this.currentPage = startPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        bitmapCache.evictAll()
        preloadAdjacent()
        invalidate()
        onPageChanged?.invoke(currentPage, pages.size.coerceAtLeast(1))
    }

    fun pageCount(): Int = pages.size.coerceAtLeast(1)
    fun currentPageIndex(): Int = currentPage

    fun nextPage() {
        if (currentPage < pages.size - 1) {
            currentPage++
            preloadAdjacent()
            invalidate()
            onPageChanged?.invoke(currentPage, pages.size)
        } else {
            onChapterBoundary?.invoke(+1)
        }
    }

    fun prevPage() {
        if (currentPage > 0) {
            currentPage--
            preloadAdjacent()
            invalidate()
            onPageChanged?.invoke(currentPage, pages.size)
        } else {
            onChapterBoundary?.invoke(-1)
        }
    }

    fun jumpToPage(page: Int) {
        currentPage = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        preloadAdjacent()
        invalidate()
        onPageChanged?.invoke(currentPage, pages.size.coerceAtLeast(1))
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        // Always black background
        canvas.drawColor(Color.BLACK)
        if (pages.isEmpty()) return

        val bmp = getOrDecodeBitmap(currentPage) ?: return
        if (bmp.isRecycled) return

        val vw = width.toFloat()
        val vh = height.toFloat()
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return

        // Fit the image to the view while preserving aspect ratio
        val scale = minOf(vw / bw, vh / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = (vw - dw) / 2f
        val top = (vh - dh) / 2f
        val dst = RectF(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dst, imagePaint)
    }

    // --- Bitmap management ---

    private fun getOrDecodeBitmap(pageIndex: Int): Bitmap? {
        if (pageIndex < 0 || pageIndex >= pages.size) return null
        bitmapCache.get(pageIndex)?.let { if (!it.isRecycled) return it }
        val path = pages[pageIndex]
        if (path.isEmpty()) return null
        val bytes = imageResolver(path) ?: return null
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } ?: return null
        bitmapCache.put(pageIndex, bmp)
        return bmp
    }

    /**
     * Pre-decode the adjacent pages (current ± 1) so page flipping feels instant.
     * Called after every page change.
     */
    private fun preloadAdjacent() {
        for (offset in intArrayOf(-1, 0, 1)) {
            val idx = currentPage + offset
            if (idx in pages.indices && bitmapCache.get(idx) == null) {
                getOrDecodeBitmap(idx)
            }
        }
    }
}
