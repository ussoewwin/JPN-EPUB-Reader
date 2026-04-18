package com.jpnepub.reader.vrender

import android.graphics.Paint
import android.graphics.RectF

/**
 * 縦書き組版エンジン。
 *
 * 入力: ContentNode のリスト + ページサイズ + スタイルパラメータ
 * 出力: Page のリスト (各ページに配置済み PositionedGlyph/PositionedImage)
 *
 * 方針:
 *  - 各文字を em-box (fontSize × fontSize) の中央に配置する
 *  - カラム(縦1列) は右端から左へ進める
 *  - 1カラム内では上から下へ1文字ずつ配置
 *  - Latin/ASCII は 90度時計回りに回転 (縦中横は未対応。v1では単純回転)
 *  - ルビは基字の右側に小さく配置
 *  - 画像は現在カラム位置から左に画像幅分を確保してインライン配置
 *  - <p> の冒頭は1字分字下げ
 *
 * これにより物理ピクセル座標が事前に確定するため、ページネーションに揺れが無い。
 */
data class PositionedGlyph(
    val text: String,
    val x: Float,           // 描画原点 X (textAlign=CENTER のCX)
    val y: Float,           // 描画原点 Y (ベースライン)
    val rotated: Boolean,   // 90度時計回り回転が必要か (Latin等)
    val isRuby: Boolean,    // ルビ小文字として描画するか
    val sizeScale: Float = 1.0f, // フォントサイズ倍率 (見出しで 1.0 より大)
    val bold: Boolean = false,   // 明示的に太字で描画するか (見出し)
)

data class PositionedImage(
    val src: String,
    val bounds: RectF,      // 描画先の矩形 (物理座標)
)

data class Page(
    val glyphs: List<PositionedGlyph>,
    val images: List<PositionedImage>,
)

class VerticalLayoutEngine(
    private val pageW: Int,
    private val pageH: Int,
    private val paint: Paint,
    private val rubyPaint: Paint,
    private val paddingLeft: Int,
    private val paddingTop: Int,
    private val paddingRight: Int,
    private val paddingBottom: Int,
    private val lineHeightRatio: Float = 1.0f,   // 文字送り量 (em単位, 1.0=詰め)
    private val columnGapRatio: Float = 0.4f,    // カラム間アキ (em単位)
    // 自動段落インデントは 0 にする。日本語EPUBの publisher は通常
    // 段落冒頭に "　" (U+3000 全角空白) を明示的に置いているため、
    // 自動で追加するとインデントが二重になる。
    private val paraIndentEm: Float = 0.0f,
    /**
     * 画像のピクセル寸法を返す。外字(gaiji)インライン判定に使う。
     * null を返した場合は判定不能として全面ページ扱いとする。
     */
    private val imageSizeResolver: (String) -> Pair<Int, Int>? = { null },
) {

    fun layout(content: List<ContentNode>): List<Page> {
        val pages = mutableListOf<Page>()
        val fontSize = paint.textSize
        if (fontSize <= 0f || pageW <= 0 || pageH <= 0) return emptyList()

        val rubySize = rubyPaint.textSize
        val advance = fontSize * lineHeightRatio                // 1文字進む量
        val rubyGap = rubySize * 0.1f                           // 基字とルビの隙間
        val columnWidth = fontSize + rubySize + rubyGap + fontSize * columnGapRatio

        val topEdge = paddingTop.toFloat()
        val bottomEdge = (pageH - paddingBottom).toFloat()
        val rightEdge = (pageW - paddingRight).toFloat()
        val leftEdge = paddingLeft.toFloat()

        // 各カラムの中心Xは rightEdge - fontSize/2 から始め、columnWidth ずつ左へ
        val firstColCenterX = rightEdge - fontSize / 2f

        // 基字ベースライン補正: CENTER揃え + 上端を topEdge に合わせたいので
        // fontMetrics を見てアセント分下げる
        val fm = paint.fontMetrics
        val ascent = -fm.ascent
        val firstRowBaseline = topEdge + ascent

        var currentGlyphs = mutableListOf<PositionedGlyph>()
        var currentImages = mutableListOf<PositionedImage>()
        var colIndex = 0              // 現ページ内の何本目のカラムか (0 = 右端)
        var rowY = firstRowBaseline   // 次に置くベースラインY
        var isFirstGlyphOfColumn = true

        fun currentColumnCenterX(): Float = firstColCenterX - columnWidth * colIndex

        fun startNewColumn() {
            colIndex++
            rowY = firstRowBaseline
            isFirstGlyphOfColumn = true
            // カラムがページ左端を超えたらページ確定して次ページへ
            val nextCenter = firstColCenterX - columnWidth * colIndex
            if (nextCenter - fontSize / 2f < leftEdge) {
                pages.add(Page(currentGlyphs, currentImages))
                currentGlyphs = mutableListOf()
                currentImages = mutableListOf()
                colIndex = 0
                rowY = firstRowBaseline
            }
        }

        fun advanceOneChar() {
            rowY += advance
            isFirstGlyphOfColumn = false
            if (rowY + fm.descent > bottomEdge) {
                startNewColumn()
            }
        }

        fun indentIfParagraphStart(firstCharInPara: Boolean) {
            if (firstCharInPara && isFirstGlyphOfColumn) {
                rowY += advance * paraIndentEm
            }
        }

        var pendingParaIndent = false

        for (node in content) {
            when (node) {
                is ContentNode.TextRun -> {
                    val chars = node.text
                    var firstChar = pendingParaIndent
                    pendingParaIndent = false
                    for (chIdx in chars.indices) {
                        val rawCh = chars[chIdx]
                        // 改行・タブは可視文字ではない。意図的な改行は <br> → LineBreak で
                        // 別経路に入るので、TextRun 内に紛れこんだ \n は無視する方が安全。
                        // (EPUB の XHTML 整形由来の改行が TextRun に残ってカラムが折れる
                        //  事故を防ぐ)
                        if (rawCh == '\n' || rawCh == '\r' || rawCh == '\t') {
                            continue
                        }
                        // 縦書きでは半角 ASCII 数字を全角数字に正規化する。
                        // 半角のままだと em-box 内でグリフが細く浮いて見え、さらに
                        // needsRotation で横倒しになってしまうため、全角へ寄せて縦に
                        // 素直に並べる方が読みやすい。
                        val ch = normalizeForVertical(rawCh)
                        if (firstChar) {
                            indentIfParagraphStart(true)
                            firstChar = false
                        }
                        if (rowY + fm.descent > bottomEdge) {
                            startNewColumn()
                        }
                        val rotated = needsRotation(ch)
                        val cx = currentColumnCenterX()
                        currentGlyphs.add(
                            PositionedGlyph(
                                text = ch.toString(),
                                x = cx,
                                y = rowY,
                                rotated = rotated,
                                isRuby = false,
                            )
                        )
                        advanceOneChar()
                    }
                }
                is ContentNode.Ruby -> {
                    // 基字を1文字ずつ配置しつつ、ルビ文字は基字の右側に小さく
                    val base = node.base
                    val ruby = node.ruby
                    if (base.isEmpty()) continue
                    if (pendingParaIndent) {
                        indentIfParagraphStart(true)
                        pendingParaIndent = false
                    }
                    val baseStartY = rowY
                    // 基字を置く (必要なら途中でカラム折返し)
                    for (ch in base) {
                        if (rowY + fm.descent > bottomEdge) {
                            startNewColumn()
                        }
                        val cx = currentColumnCenterX()
                        currentGlyphs.add(
                            PositionedGlyph(
                                text = ch.toString(),
                                x = cx,
                                y = rowY,
                                rotated = needsRotation(ch),
                                isRuby = false,
                            )
                        )
                        advanceOneChar()
                    }
                    // ルビ文字をカラム右側に均等配置
                    if (ruby.isNotEmpty()) {
                        // baseStartY は「基字1文字目のベースライン」。
                        // 基字の縦占有範囲は [baseStartY - ascent, baseStartY - ascent + advance*base.length]
                        // ルビを基字範囲内で均等割付する。
                        val baseSpanTop = baseStartY - ascent
                        val baseSpanBottom = baseSpanTop + advance * base.length
                        val rubyFm = rubyPaint.fontMetrics
                        val rubyAscent = -rubyFm.ascent
                        val rubyStep = (baseSpanBottom - baseSpanTop) / ruby.length
                        // ルビX位置: カラム中央から右に (fontSize/2 + rubyGap + rubySize/2)
                        // ただし配置のカラム中央Xは、基字1文字目があったカラム基準で。
                        // 簡便化: 現在の colIndex を使う (カラム折返しが起きている場合はズレるが許容)
                        val rubyBaseColX = currentColumnCenterX()
                        val rubyX = rubyBaseColX + fontSize / 2f + rubyGap + rubySize / 2f
                        for ((i, rc) in ruby.withIndex()) {
                            val topOfRubyChar = baseSpanTop + rubyStep * i
                            val rubyBaseline = topOfRubyChar + rubyAscent + (rubyStep - (rubyFm.descent + rubyAscent)) / 2f
                            currentGlyphs.add(
                                PositionedGlyph(
                                    text = rc.toString(),
                                    x = rubyX,
                                    y = rubyBaseline,
                                    rotated = false,
                                    isRuby = true,
                                )
                            )
                        }
                    }
                }
                is ContentNode.Image -> {
                    // 画像サイズを取得し、小さければ外字(gaiji/旧字)として本文中にインライン配置する。
                    // 大きければ挿絵として全面1ページ扱い。
                    val dims = imageSizeResolver(node.src)
                    val isInlineGaiji = if (dims != null) {
                        val (iw, ih) = dims
                        // 閾値: どちらの辺も 256px 以下なら外字扱い。
                        // 日本の電子書籍の外字画像は典型的に 24〜128px 四方。
                        iw in 1..256 && ih in 1..256
                    } else {
                        false
                    }

                    if (isInlineGaiji) {
                        if (pendingParaIndent) {
                            indentIfParagraphStart(true)
                            pendingParaIndent = false
                        }
                        if (rowY + fm.descent > bottomEdge) {
                            startNewColumn()
                        }
                        val (iw, ih) = dims!!
                        val cx = currentColumnCenterX()
                        // em-box に内接するサイズでアスペクト比を保つ。
                        // ほとんどの外字は正方形だが念のため。
                        val aspect = iw.toFloat() / ih.toFloat()
                        val targetH: Float
                        val targetW: Float
                        if (aspect >= 1f) {
                            targetW = fontSize
                            targetH = fontSize / aspect
                        } else {
                            targetH = fontSize
                            targetW = fontSize * aspect
                        }
                        // 現在の文字セルの上端 (ベースラインから ascent 分上)
                        val cellTop = rowY - ascent
                        val drawLeft = cx - targetW / 2f
                        val drawTop = cellTop + (fontSize - targetH) / 2f
                        currentImages.add(
                            PositionedImage(
                                src = node.src,
                                bounds = RectF(
                                    drawLeft,
                                    drawTop,
                                    drawLeft + targetW,
                                    drawTop + targetH
                                )
                            )
                        )
                        advanceOneChar()
                    } else {
                        // 大きい画像 = 挿絵/表紙 → 1ページまるごと
                        if (currentGlyphs.isNotEmpty() || currentImages.isNotEmpty()) {
                            pages.add(Page(currentGlyphs, currentImages))
                            currentGlyphs = mutableListOf()
                            currentImages = mutableListOf()
                            colIndex = 0
                            rowY = firstRowBaseline
                        }
                        currentImages.add(
                            PositionedImage(
                                src = node.src,
                                bounds = RectF(
                                    leftEdge,
                                    topEdge,
                                    rightEdge,
                                    bottomEdge
                                )
                            )
                        )
                        pages.add(Page(currentGlyphs, currentImages))
                        currentGlyphs = mutableListOf()
                        currentImages = mutableListOf()
                        colIndex = 0
                        rowY = firstRowBaseline
                        isFirstGlyphOfColumn = true
                        pendingParaIndent = false
                    }
                }
                is ContentNode.ParaBreak -> {
                    // 段落区切り: 現在カラムを閉じて次カラムへ、次文字で字下げ
                    if (!isFirstGlyphOfColumn) {
                        startNewColumn()
                    }
                    pendingParaIndent = true
                }
                is ContentNode.LineBreak -> {
                    if (!isFirstGlyphOfColumn) {
                        startNewColumn()
                    }
                    pendingParaIndent = false
                }
                is ContentNode.PageBreak -> {
                    // 強制改ページ。既に何か出力されているときのみ現ページを確定し、
                    // まだ1文字も出していない状態では無視する (先頭で空ページ防止)。
                    if (currentGlyphs.isNotEmpty() || currentImages.isNotEmpty()) {
                        pages.add(Page(currentGlyphs, currentImages))
                        currentGlyphs = mutableListOf()
                        currentImages = mutableListOf()
                        colIndex = 0
                        rowY = firstRowBaseline
                        isFirstGlyphOfColumn = true
                    }
                    pendingParaIndent = false
                }
                is ContentNode.Heading -> {
                    // 見出し: 本文より大きく・太字で描画する専用行。
                    // 必ずカラム先頭から開始する (途中から見出しが始まるのは避ける)。
                    if (!isFirstGlyphOfColumn) {
                        startNewColumn()
                    }
                    val scale = headingScale(node.level)
                    val headingAdvance = advance * scale
                    // 拡大字のem-box下端でオーバーフロー判定するための係数
                    val scaledDescent = fm.descent * scale
                    val textChars = node.text.filter { it != '\n' && it != '\r' && it != '\t' }
                    for (rawCh in textChars) {
                        val ch = normalizeForVertical(rawCh)
                        // 次の1文字が今のカラムに収まらなければカラム折返し
                        if (rowY + scaledDescent > bottomEdge) {
                            startNewColumn()
                        }
                        val rotated = needsRotation(ch)
                        val cx = currentColumnCenterX()
                        currentGlyphs.add(
                            PositionedGlyph(
                                text = ch.toString(),
                                x = cx,
                                y = rowY,
                                rotated = rotated,
                                isRuby = false,
                                sizeScale = scale,
                                bold = true,
                            )
                        )
                        rowY += headingAdvance
                        isFirstGlyphOfColumn = false
                    }
                    // 見出し直後は必ず次カラムへ回し、本文と同じカラムに続かないようにする。
                    if (!isFirstGlyphOfColumn) {
                        startNewColumn()
                    }
                    pendingParaIndent = false
                }
            }
        }

        if (currentGlyphs.isNotEmpty() || currentImages.isNotEmpty()) {
            pages.add(Page(currentGlyphs, currentImages))
        }
        if (pages.isEmpty()) {
            pages.add(Page(emptyList(), emptyList()))
        }
        return pages
    }

    /**
     * 見出しレベルに対するフォントサイズ倍率。
     *   h1 : 1.6倍  (巻頭・章タイトル)
     *   h2 : 1.4倍  (章タイトル・節タイトル)
     *   h3 : 1.25倍 (節タイトル)
     *   h4〜h6 : 1.15倍 (小見出し)
     *
     * コラム幅は固定なので倍率を上げすぎると左右に食み出すが、
     * 隣接カラムとのアキ (columnGapRatio + rubySize) に収まる範囲を経験的に選ぶ。
     */
    private fun headingScale(level: Int): Float = when (level.coerceIn(1, 6)) {
        1 -> 1.6f
        2 -> 1.4f
        3 -> 1.25f
        else -> 1.15f
    }

    /**
     * 半角 → 全角への縦書き向け正規化。
     *
     *   0-9           -> ０-９  (U+FF10-FF19)
     *   ! " # ... ~ (ASCII 印字) のうち、縦書き時に全角で並べた方が自然なもの
     *     (! ? % など) も全角化。しかし英字 (A-Z a-z) は縦中では横倒しの方が
     *     一般的なので変換しない。
     */
    private fun normalizeForVertical(ch: Char): Char {
        val code = ch.code
        return when (code) {
            in 0x30..0x39 -> (code - 0x30 + 0xFF10).toChar()   // 0-9 → ０-９
            0x21 -> '！'  // !
            0x3F -> '？'  // ?
            0x25 -> '％'  // %
            else -> ch
        }
    }

    /**
     * 縦書き時に90度回転して描画すべき文字か判定する。
     *   - ASCII 英数字・ASCII記号 → 回転
     *   - 半角カタカナ → 回転 (そもそも縦書きでは全角推奨)
     *   - 全角・CJK・かな → 回転しない (本来の縦書き字形で描画)
     *   - 三点リーダ・二点リーダ・ダッシュ・波ダッシュ等、
     *     「横並び前提のグリフだが縦書きでは縦並びにすべき約物」 → 回転
     *     (Noto Serif CJK JP 等のフォントに vert 置換が用意されていない
     *      ことがあり、フォント任せでは横のまま描画されるため明示指定)
     */
    private fun needsRotation(ch: Char): Boolean {
        val code = ch.code
        // ASCII (スペースは回転不要)
        if (code in 0x21..0x7E) return true
        // 半角記号・半角カナ
        if (code in 0xFF61..0xFFDC) return true
        // Latin-1 Supplement の印字可能
        if (code in 0x00A1..0x00FF) return true
        // 縦書きで縦並びにしたい横書き前提の約物
        when (code) {
            0x2013, // – EN DASH
            0x2014, // — EM DASH
            0x2015, // ― HORIZONTAL BAR (ダッシュ)
            0x2025, // ‥ TWO DOT LEADER
            0x2026, // … HORIZONTAL ELLIPSIS (三点リーダ)
            0x2212, // − MINUS SIGN
            0x22EF, // ⋯ MIDLINE HORIZONTAL ELLIPSIS
            0x3030, // 〰 WAVY DASH
            0x301C, // 〜 WAVE DASH
            0xFF5E, // ～ FULLWIDTH TILDE
            0xFF0D, // － FULLWIDTH HYPHEN-MINUS
            0x2500, // ─ BOX DRAWINGS LIGHT HORIZONTAL
                    //   (金田一耕助ファイル等で em dash 代わりに多用される)
            0x2501, // ━ BOX DRAWINGS HEAVY HORIZONTAL
            0x2010, // ‐ HYPHEN
            0x2011, // ‑ NON-BREAKING HYPHEN
            0x2012, // ‒ FIGURE DASH
            0x2043, // ⁃ HYPHEN BULLET
            0xFE58  // ﹘ SMALL EM DASH
            -> return true
        }
        return false
    }
}
