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
 * EPUBチャプターを Canvas で直接描画する独自 View。
 *
 * WebView ベースで起きていた問題 (ページ飛ばし、scrollLeft の不定値、
 * writing-mode 非対応、layout ゆらぎ) を根本から回避するため、
 * テキスト位置を事前に物理ピクセルで確定させ、描画は Canvas.drawText だけで行う。
 *
 * 使い方:
 *   val v = VerticalEpubView(ctx)
 *   v.onPageChanged = { page, total -> /* UI update */ }
 *   v.onChapterBoundary = { direction -> /* load next/prev chapter */ }
 *   v.setStyle(fontSizePx, darkMode)
 *   v.setChapter(contentNodes, imageResolver)
 *   v.nextPage() / v.prevPage() / v.jumpToPage(n)
 */
class VerticalEpubView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /**
     * 縦書き日本語用の OpenType フィーチャ設定。
     *  vert : 縦書き用グリフ置換 (「」『』（）、。ー 等を縦書き字形に)
     *  vkrn : 縦書き用カーニング
     *  vpal : 縦書きプロポーショナル幅 (縦書きでの欧文詰め)
     *  vhal : 縦書き用半角字形 (句読点の半角化)
     *  palt : 横書きプロポーショナル幅 (回転Latinで使われる)
     *
     * これを設定しないと 「」『』 括弧や ー 長音が横書き字形のまま描画され、
     * 日本語縦書きとして全く体裁が成立しない。W3C JLReq §2.1/§2.3 要件。
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
            // フォントに真の太字グリフが入っていない場合、システムが自動で
            // フェイクボールドを当てるがサイズによっては効きが弱いので、
            // isFakeBoldText で追加の太らせを明示的に当てる。Noto Serif CJK JP
            // は Bold 字形を持っているがメトリクスが揃っている方が挙動が安定。
            p.isFakeBoldText = boldEnabled
        } catch (_: Throwable) { }
        // 中国語字形に化けるのを防ぐため日本語ロケールを明示
        try {
            p.textLocales = LocaleList(Locale.JAPAN)
        } catch (_: Throwable) {
            try {
                @Suppress("DEPRECATION")
                p.textLocale = Locale.JAPAN
            } catch (_: Throwable) { }
        }
        // 縦書き OpenType フィーチャを有効化
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

    // --- 状態 ---
    private var content: List<ContentNode> = emptyList()
    private var pages: List<Page> = emptyList()
    private var currentPage: Int = 0
    private var imageResolver: (String) -> ByteArray? = { null }
    // 画像ピクセル寸法キャッシュ。外字(gaiji)判定でレイアウト時に頻繁に問い合わせが入るため。
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

    // スタイル設定
    private var fontSizePx: Float = 40f
    private var lineHeightRatio: Float = 1.1f
    private var paddingH: Int = 48  // 左右パディング (px)
    private var paddingV: Int = 48  // 上下パディング (px)
    private var darkMode: Boolean = false

    // --- コールバック ---
    /** ページ変更時: (currentPageIndex, totalPagesInChapter) */
    var onPageChanged: ((Int, Int) -> Unit)? = null
    /** チャプター境界を越えようとしたとき: +1 = 次, -1 = 前 */
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

        // typeface / fakeBold を新しい bold 状態で再設定
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
     * チャプターのコンテンツを差し替え、先頭ページに戻す。
     * startFromEnd=true のとき、ページネーション後に最終ページを表示する。
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
     * 縦書き日本語の始め括弧・終わり括弧の em-box 内配置補正。
     *
     * JLReq / JIS X 4051 では始め括弧 (「『（【〔〈《〖) は em-box の下半分寄せ
     * (上に半角アキ)、終わり括弧 (」』）】〕〉》〗) は上半分寄せ (下に半角アキ)
     * となる。Noto CJK JP 等では vert フィーチャでグリフ形状は縦書き用に
     * 置換されるものの、Android の Paint では vpal/vhal の垂直位置補正が
     * 完全には効かず em-box 中央に鎮座してしまい、先行文字と密着する。
     *
     * ここでインクの実バウンディングボックスを測定し、望ましい縦位置に
     * インク中心が来るよう描画ベースラインをシフトする。
     *
     *   開き括弧: ink center を em-box の 3/4 位置 (下寄せ)
     *   閉じ括弧: ink center を em-box の 1/4 位置 (上寄せ)
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
        val emTop = fm.ascent         // 基線からの相対 (負値)
        val emBottom = fm.descent     // 基線からの相対 (正値)
        val emHeight = emBottom - emTop
        val desiredInkCenterOffset = emTop + emHeight * targetFraction
        paint.getTextBounds(text, 0, text.length, tmpInkBounds)
        val currentInkCenterOffset = (tmpInkBounds.top + tmpInkBounds.bottom) / 2f
        return desiredInkCenterOffset - currentInkCenterOffset
    }

    private fun isOpenBracket(code: Int): Boolean = when (code) {
        0xFF08, // （
        0x300C, // 「
        0x300E, // 『
        0x3010, // 【
        0x3014, // 〔
        0x3008, // 〈
        0x300A, // 《
        0x3016, // 〖
        0xFF3B, // ［
        0xFF5B, // ｛
        0xFE35, // ︵ (presentation form)
        0xFE37, // ︷
        0xFE39, // ︹
        0xFE3B, // ︻
        0xFE3D, // ︽
        0xFE3F, // ︿
        0xFE41, // ﹁
        0xFE43  // ﹃
        -> true
        else -> false
    }

    private fun isCloseBracket(code: Int): Boolean = when (code) {
        0xFF09, // ）
        0x300D, // 」
        0x300F, // 』
        0x3011, // 】
        0x3015, // 〕
        0x3009, // 〉
        0x300B, // 》
        0x3017, // 〗
        0xFF3D, // ］
        0xFF5D, // ｝
        0xFE36, // ︶
        0xFE38, // ︸
        0xFE3A, // ︺
        0xFE3C, // ︼
        0xFE3E, // ︾
        0xFE40, // ﹀
        0xFE42, // ﹂
        0xFE44  // ﹄
        -> true
        else -> false
    }

    private fun drawGlyph(canvas: Canvas, g: PositionedGlyph) {
        val paint = if (g.isRuby) rubyPaint else mainPaint
        if (g.rotated) {
            // 縦書き中で 90度時計回り描画する文字。
            //
            // フォントメトリクスの em-box 中心 (ascent/descent の中点) を回転軸にすると、
            // "…" のようにインクがベースライン近辺に集中している字は、回転後にカラム中央から
            // 横に大きくズレる (ベースラインと em-box 中心の落差分ぶん左にシフト)。
            //
            // そこで、glyph の実際のインクバウンディングボックスを取り、その中心を回転軸にした
            // 上で、インク中心がカラム中央 (em-box 垂直中心) に来るように追加の縦シフトを当てる。
            // これで Latin の A も三点リーダ … も同じ論理で正しくカラム中央に乗る。
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
        // アスペクト比を保って bounds にフィット
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

    // --- 公開ページ操作 API ---
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
