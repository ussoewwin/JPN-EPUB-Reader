package com.jpnepub.reader.vrender

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.LocaleList
import android.util.AttributeSet
import android.view.View
import java.util.Locale

/**
 * Custom [View] that draws EPUB chapter content directly onto a [Canvas].
 *
 * The WebView-based renderer had a list of hard-to-debug problems:
 * skipped pages, flaky `scrollLeft` values, unsupported `writing-mode`
 * on some Android versions, and general layout drift between measurement
 * passes. This view sidesteps all of that by pre-computing the exact
 * pixel position of every glyph and drawing them one by one with
 * `Canvas.drawText`.
 *
 * Usage:
 *   val v = VerticalEpubView(ctx)
 *   v.onPageChanged     = { page, total -> /* UI update */ }
 *   v.onChapterBoundary = { direction   -> /* load next/prev chapter */ }
 *   v.setStyle(fontSizePx, darkMode, bold)
 *   v.setChapter(contentNodes, imageResolver)
 *   v.nextPage() / v.prevPage() / v.jumpToPage(n)
 */
class VerticalEpubView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * OpenType feature settings required for proper Japanese vertical
     * typesetting.
     *
     *  vert : vertical glyph substitution (tategaki forms for brackets,
     *         commas, long vowel marks, etc.)
     *  vkrn : vertical kerning
     *  vpal : vertical proportional metrics (Western spacing in tategaki)
     *  vhal : vertical half-width forms (half-width punctuation)
     *  palt : horizontal proportional metrics (used when rotating Latin)
     *
     * Without these, brackets like `「」『』（）` and `ー` keep their
     * horizontal forms and the result is unusable as Japanese vertical
     * text. See W3C JLReq sections 2.1 and 2.3.
     */
    private val verticalFontFeatures = "'vert' 1, 'vkrn' 1, 'vpal' 1, 'vhal' 1"

    private var boldEnabled: Boolean = false

    private fun applyJpStyle(p: Paint) {
        p.textAlign = Paint.Align.CENTER
        p.isSubpixelText = true
        p.isAntiAlias = true
        try {
            val style = if (boldEnabled) Typeface.BOLD else Typeface.NORMAL
            p.typeface = Typeface.create(Typeface.SERIF, style)
            // If the loaded typeface does not contain a real bold glyph
            // the platform will auto-synthesize a faux-bold, but the
            // strength can be inconsistent across sizes. Forcing
            // isFakeBoldText on top gives a more predictable result
            // while still letting fonts that *do* have bold glyphs
            // (like Noto Serif CJK JP) use them.
            p.isFakeBoldText = boldEnabled
        } catch (_: Throwable) { }
        // Pin the locale to Japan so the shaper doesn't fall back to
        // Chinese glyph variants.
        try {
            p.textLocales = LocaleList(Locale.JAPAN)
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                p.textLocale = Locale.JAPAN
            } catch (_: Throwable) { }
        }
        // Enable vertical OpenType features.
        try { p.fontFeatureSettings = verticalFontFeatures } catch (_: Throwable) { }
    }

    private val mainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 40f
    }.also { applyJpStyle(it) }

    private val rubyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 20f
    }.also { applyJpStyle(it) }

    private val bgPaint = Paint().apply { color = Color.parseColor("#F5F0E8") }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    // --- State ---
    private var content: List<ContentNode> = emptyList()
    private var pages: List<Page> = emptyList()
    private var currentPage: Int = 0
    private var imageResolver: (String) -> ByteArray? = { null }
    // Cache of image pixel dimensions. The layout engine hits this very
    // frequently when classifying images as inline-gaiji vs full-page.
    private val imageSizeCache = HashMap<String, Pair<Int, Int>?>()

    private fun resolveImageSize(src: String): Pair<Int, Int>? {
        imageSizeCache[src]?.let { return it }
        if (imageSizeCache.containsKey(src)) return null
        val bytes = imageResolver(src)
        val size: Pair<Int, Int>? = if (bytes != null) {
            try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        imageSizeCache[src] = size
        return size
    }

    // Style knobs
    private var fontSizePx: Float = 40f
    private var lineHeightRatio: Float = 1.1f
    private var paddingH: Int = 48  // Left/right padding (px)
    private var paddingV: Int = 48  // Top/bottom padding (px)
    private var darkMode: Boolean = false

    // --- Callbacks ---
    /** Invoked on page change: (currentPageIndex, totalPagesInChapter). */
    var onPageChanged: ((Int, Int) -> Unit)? = null
    /** Invoked when the user tries to cross a chapter boundary.
     *  +1 for next, -1 for previous. */
    var onChapterBoundary: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setStyle(
        fontSizePx: Float,
        darkMode: Boolean,
        lineHeightRatio: Float = 1.1f,
        paddingH: Int = 48,
        paddingV: Int = 48,
        bold: Boolean = false,
    ) {
        this.fontSizePx = fontSizePx
        this.lineHeightRatio = lineHeightRatio
        this.paddingH = paddingH
        this.paddingV = paddingV
        this.darkMode = darkMode
        this.boldEnabled = bold

        // Re-apply typeface / fakeBold with the new bold state.
        applyJpStyle(mainPaint)
        applyJpStyle(rubyPaint)
        mainPaint.textSize = fontSizePx
        rubyPaint.textSize = fontSizePx * 0.5f

        if (darkMode) {
            mainPaint.color = Color.parseColor("#E0E0E0")
            rubyPaint.color = Color.parseColor("#B0B0B0")
            bgPaint.color = Color.parseColor("#1E1E1E")
        } else {
            mainPaint.color = Color.parseColor("#1A1A1A")
            rubyPaint.color = Color.parseColor("#4A4A4A")
            bgPaint.color = Color.parseColor("#F5F0E8")
        }
        relayout()
    }

    /**
     * Replace the chapter content and reset to the first page.
     * When [startFromEnd] is true, after re-pagination the view will
     * show the last page (used when stepping *backwards* across a
     * chapter boundary).
     */
    fun setChapter(
        content: List<ContentNode>,
        imageResolver: (String) -> ByteArray?,
        startFromEnd: Boolean = false,
    ) {
        this.content = content
        this.imageResolver = imageResolver
        imageSizeCache.clear()
        relayout()
        currentPage = if (startFromEnd && pages.isNotEmpty()) pages.size - 1 else 0
        invalidate()
        onPageChanged?.invoke(currentPage, pages.size.coerceAtLeast(1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            pages = emptyList()
            return
        }
        val engine = VerticalLayoutEngine(
            pageW = w,
            pageH = h,
            paint = mainPaint,
            rubyPaint = rubyPaint,
            paddingLeft = paddingH,
            paddingTop = paddingV,
            paddingRight = paddingH,
            paddingBottom = paddingV,
            lineHeightRatio = lineHeightRatio,
            imageSizeResolver = { src -> resolveImageSize(src) },
        )
        pages = engine.layout(content)
        if (currentPage >= pages.size) currentPage = (pages.size - 1).coerceAtLeast(0)
        onPageChanged?.invoke(currentPage, pages.size.coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (pages.isEmpty()) return
        val page = pages[currentPage.coerceIn(0, pages.size - 1)]

        for (img in page.images) {
            drawImage(canvas, img)
        }
        for (g in page.glyphs) {
            drawGlyph(canvas, g)
        }
    }

    private val tmpInkBounds = Rect()

    /**
     * Em-box placement correction for Japanese opening / closing brackets
     * in vertical writing.
     *
     * According to JLReq / JIS X 4051, opening brackets `「『（【〔〈《〖`
     * should sit in the lower half of the em-box (with ~half-width aki
     * on top) and closing brackets `」』）】〕〉》〗` in the upper half
     * (with aki on the bottom). Fonts like Noto CJK JP provide proper
     * tategaki glyph *shapes* via the `vert` feature, but Android's
     * `Paint` doesn't fully apply the `vpal` / `vhal` vertical
     * positioning, so the glyph ends up centered in the em-box and
     * visually collides with the previous character.
     *
     * We measure the actual ink bounding box and shift the drawing
     * baseline so that the ink's center lands at the desired vertical
     * fraction of the em-box.
     *
     *   opening bracket: ink center at 3/4 of the em-box (pushed down)
     *   closing bracket: ink center at 1/4 of the em-box (pushed up)
     */
    private fun bracketYShift(text: String, paint: Paint): Float {
        if (text.length != 1) return 0f
        val code = text[0].code
        val targetFraction = when {
            isOpenBracket(code)  -> 0.75f
            isCloseBracket(code) -> 0.25f
            else                 -> return 0f
        }
        val fm = paint.fontMetrics
        val emTop = fm.ascent         // Relative to baseline (negative)
        val emBottom = fm.descent     // Relative to baseline (positive)
        val emHeight = emBottom - emTop
        val desiredInkCenterOffset = emTop + emHeight * targetFraction
        paint.getTextBounds(text, 0, text.length, tmpInkBounds)
        val currentInkCenterOffset = (tmpInkBounds.top + tmpInkBounds.bottom) / 2f
        return desiredInkCenterOffset - currentInkCenterOffset
    }

    private fun isOpenBracket(code: Int): Boolean = when (code) {
        0xFF08, // (
        0x300C, // [
        0x300E, // [
        0x3010, // [
        0x3014, // [
        0x3008, // <
        0x300A, // <<
        0x3016, // [
        0xFF3B, // [
        0xFF5B, // {
        0xFE35, // (presentation form)
        0xFE37,
        0xFE39,
        0xFE3B,
        0xFE3D,
        0xFE3F,
        0xFE41,
        0xFE43
        -> true
        else -> false
    }

    private fun isCloseBracket(code: Int): Boolean = when (code) {
        0xFF09, // )
        0x300D, // ]
        0x300F, // ]
        0x3011, // ]
        0x3015, // ]
        0x3009, // >
        0x300B, // >>
        0x3017, // ]
        0xFF3D, // ]
        0xFF5D, // }
        0xFE36,
        0xFE38,
        0xFE3A,
        0xFE3C,
        0xFE3E,
        0xFE40,
        0xFE42,
        0xFE44
        -> true
        else -> false
    }

    private fun drawGlyph(canvas: Canvas, g: PositionedGlyph) {
        val paint = if (g.isRuby) rubyPaint else mainPaint
        if (g.rotated) {
            // Glyphs that must be rotated 90 deg clockwise in tategaki.
            //
            // If we rotate around the em-box center (the midpoint of
            // ascent/descent), characters whose ink clusters near the
            // baseline -- such as an ellipsis "..." -- end up
            // horizontally offset from the column center after rotation.
            //
            // So instead we:
            //   1. Measure the actual ink bounding box.
            //   2. Rotate around that ink's center.
            //   3. Translate so the ink center aligns with the em-box
            //      vertical center.
            // This way both Latin "A" and the ellipsis end up on the
            // column's visual axis.
            paint.getTextBounds(g.text, 0, g.text.length, tmpInkBounds)
            val inkCenterY = g.y + (tmpInkBounds.top + tmpInkBounds.bottom) / 2f
            val fm = paint.fontMetrics
            val emCenterY = g.y + (fm.ascent + fm.descent) / 2f
            canvas.save()
            canvas.translate(0f, emCenterY - inkCenterY)
            canvas.rotate(90f, g.x, inkCenterY)
            canvas.drawText(g.text, g.x, g.y, paint)
            canvas.restore()
        } else {
            val yShift = bracketYShift(g.text, paint)
            canvas.drawText(g.text, g.x, g.y + yShift, paint)
        }
    }

    private fun drawImage(canvas: Canvas, img: PositionedImage) {
        val bytes = imageResolver(img.src) ?: return
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            return
        } ?: return
        // Fit the bitmap inside the target bounds while preserving
        // aspect ratio.
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        if (bw <= 0 || bh <= 0) return
        val tw = img.bounds.width()
        val th = img.bounds.height()
        val scale = minOf(tw / bw, th / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = img.bounds.left + (tw - dw) / 2f
        val top = img.bounds.top + (th - dh) / 2f
        val dst = RectF(left, top, left + dw, top + dh)
        canvas.drawBitmap(bmp, null, dst, imagePaint)
    }

    // --- Public pagination API ---
    fun pageCount(): Int = pages.size.coerceAtLeast(1)
    fun currentPageIndex(): Int = currentPage

    fun nextPage() {
        if (currentPage < pages.size - 1) {
            currentPage++
            invalidate()
            onPageChanged?.invoke(currentPage, pages.size)
        } else {
            onChapterBoundary?.invoke(+1)
        }
    }

    fun prevPage() {
        if (currentPage > 0) {
            currentPage--
            invalidate()
            onPageChanged?.invoke(currentPage, pages.size)
        } else {
            onChapterBoundary?.invoke(-1)
        }
    }

    fun jumpToPage(page: Int) {
        currentPage = page.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        invalidate()
        onPageChanged?.invoke(currentPage, pages.size.coerceAtLeast(1))
    }
}
