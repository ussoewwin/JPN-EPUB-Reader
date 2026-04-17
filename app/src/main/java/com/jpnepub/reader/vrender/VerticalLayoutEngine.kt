package com.jpnepub.reader.vrender

import android.graphics.Paint
import android.graphics.RectF

/**
 * Vertical-writing typesetting engine.
 *
 * Input:  a list of [ContentNode]s + page size + style parameters.
 * Output: a list of [Page]s, each holding fully positioned
 *         [PositionedGlyph]s and [PositionedImage]s.
 *
 * Policy:
 *  - Each character is placed at the center of an em-box of size
 *    (fontSize x fontSize).
 *  - Columns (vertical lines of text) start at the right edge and
 *    progress toward the left.
 *  - Within a single column, characters flow top-to-bottom one at a time.
 *  - Latin / ASCII characters are rotated 90 degrees clockwise.
 *    (Tate-chu-yoko "horizontal-in-vertical" is not supported in v1;
 *    single characters are simply rotated.)
 *  - Ruby annotations are drawn at a smaller size on the right-hand
 *    side of their base characters.
 *  - Images are laid out inline, reserving a slot of the image's
 *    width measured from the current column position toward the left.
 *  - The first character of a `<p>` can be indented by one em.
 *
 * Because every character's physical pixel position is finalized up
 * front, pagination is 100% stable: there is no layout drift between
 * measurement passes and no risk of "skipped" pages.
 */
data class PositionedGlyph(
    val text: String,
    val x: Float,           // Drawing origin X (CX when textAlign=CENTER)
    val y: Float,           // Drawing origin Y (baseline)
    val rotated: Boolean,   // Whether to apply 90-deg CW rotation (Latin etc.)
    val isRuby: Boolean,    // Whether to render with the ruby (small) paint
)

data class PositionedImage(
    val src: String,
    val bounds: RectF,      // Destination rectangle in physical coordinates
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
    /** Character advance per glyph, in em units. 1.0 = tight. */
    private val lineHeightRatio: Float = 1.0f,
    /** Gap between columns, in em units. */
    private val columnGapRatio: Float = 0.4f,
    /**
     * Automatic paragraph indent. Defaulted to 0 because Japanese EPUB
     * publishers already place an explicit U+3000 (ideographic space) at
     * the start of paragraphs; adding more would double-indent.
     */
    private val paraIndentEm: Float = 0.0f,
    /**
     * Returns the pixel dimensions of an image, or null if unknown.
     * Used to decide whether an image is an inline gaiji character or a
     * full-page illustration; null is treated as "full-page".
     */
    private val imageSizeResolver: (String) -> Pair<Int, Int>? = { null },
) {

    fun layout(content: List<ContentNode>): List<Page> {
        val pages = mutableListOf<Page>()
        val fontSize = paint.textSize
        if (fontSize <= 0f || pageW <= 0 || pageH <= 0) return emptyList()

        val rubySize = rubyPaint.textSize
        val advance = fontSize * lineHeightRatio                // advance per glyph
        val rubyGap = rubySize * 0.1f                           // gap between base and ruby
        val columnWidth = fontSize + rubySize + rubyGap + fontSize * columnGapRatio

        val topEdge = paddingTop.toFloat()
        val bottomEdge = (pageH - paddingBottom).toFloat()
        val rightEdge = (pageW - paddingRight).toFloat()
        val leftEdge = paddingLeft.toFloat()

        // Column center-X starts at (rightEdge - fontSize/2) and moves left
        // by columnWidth for each subsequent column.
        val firstColCenterX = rightEdge - fontSize / 2f

        // Base-glyph baseline adjustment: with textAlign=CENTER we want the
        // top of the first em-box to sit at topEdge, so we push down by the
        // ascent.
        val fm = paint.fontMetrics
        val ascent = -fm.ascent
        val firstRowBaseline = topEdge + ascent

        var currentGlyphs = mutableListOf<PositionedGlyph>()
        var currentImages = mutableListOf<PositionedImage>()
        var colIndex = 0              // Column index within the current page (0 = rightmost)
        var rowY = firstRowBaseline   // Baseline Y for the next glyph
        var isFirstGlyphOfColumn = true

        fun currentColumnCenterX(): Float = firstColCenterX - columnWidth * colIndex

        fun startNewColumn() {
            colIndex++
            rowY = firstRowBaseline
            isFirstGlyphOfColumn = true
            // If the new column would fall off the left edge of the page,
            // finalize the current page and start a new one.
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
                        // Control whitespace (tab/CR/LF) carries no visual intent;
                        // intentional line breaks arrive as separate LineBreak
                        // nodes via <br>. Stripping them here avoids accidental
                        // column breaks caused by pretty-printed XHTML source.
                        if (rawCh == '\n' || rawCh == '\r' || rawCh == '\t') {
                            continue
                        }
                        // For vertical writing, normalize half-width ASCII
                        // digits to full-width. Left as half-width they look
                        // visually thin inside an em-box and would hit
                        // needsRotation() (tilting them sideways), neither of
                        // which is desirable here.
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
                    // Lay out the base characters one by one, then distribute
                    // the ruby string evenly along the right-hand side of that
                    // span in a smaller paint.
                    val base = node.base
                    val ruby = node.ruby
                    if (base.isEmpty()) continue
                    if (pendingParaIndent) {
                        indentIfParagraphStart(true)
                        pendingParaIndent = false
                    }
                    val baseStartY = rowY
                    // Place the base characters (column wrap if needed).
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
                    // Evenly distribute the ruby characters on the right side
                    // of the base span.
                    if (ruby.isNotEmpty()) {
                        // baseStartY is the baseline of the first base glyph.
                        // Base-character span: [baseStartY - ascent,
                        //  baseStartY - ascent + advance*base.length].
                        // We distribute ruby chars evenly within that span.
                        val baseSpanTop = baseStartY - ascent
                        val baseSpanBottom = baseSpanTop + advance * base.length
                        val rubyFm = rubyPaint.fontMetrics
                        val rubyAscent = -rubyFm.ascent
                        val rubyStep = (baseSpanBottom - baseSpanTop) / ruby.length
                        // Ruby X position: column center + (fontSize/2 + rubyGap + rubySize/2)
                        // Simplification: we use the current colIndex; if a
                        // column break happened while placing the base this
                        // may drift slightly, which is acceptable.
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
                    // Look up the image's pixel dimensions. Small ones are
                    // treated as inline "gaiji" (external/old-form characters)
                    // and laid out as a single glyph; large ones become a
                    // full-page illustration on their own page.
                    val dims = imageSizeResolver(node.src)
                    val isInlineGaiji = if (dims != null) {
                        val (iw, ih) = dims
                        // Threshold: treat as a gaiji if neither side exceeds
                        // 256 px. Japanese e-book gaiji images are typically
                        // 24-128 px square.
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
                        // Fit the image inside the em-box while preserving the
                        // aspect ratio. (Most gaiji are square but be safe.)
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
                        // Top of the current em-box (baseline minus ascent).
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
                        // Large image = illustration / cover -> its own page.
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
                    // Paragraph break: close the current column and mark the
                    // next glyph for leading indentation.
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
     * Half-width -> full-width normalization for vertical writing.
     *
     *   0-9           -> U+FF10..U+FF19
     *   A subset of ASCII punctuation that looks more natural in its
     *   full-width form when stacked vertically (!, ?, %).  Latin letters
     *   (A-Z, a-z) are intentionally NOT converted because the standard
     *   Japanese convention is to rotate them 90deg within a vertical
     *   column, not to present them as full-width.
     */
    private fun normalizeForVertical(ch: Char): Char {
        val code = ch.code
        return when (code) {
            in 0x30..0x39 -> (code - 0x30 + 0xFF10).toChar()   // 0-9 -> 0xFF10..0xFF19
            0x21 -> '\uFF01'  // ! -> !
            0x3F -> '\uFF1F'  // ? -> ?
            0x25 -> '\uFF05'  // % -> %
            else -> ch
        }
    }

    /**
     * True if this character should be rotated 90 degrees clockwise when
     * drawn vertically.
     *   - ASCII alphanumeric / punctuation -> rotate
     *   - Half-width kana -> rotate (full-width is preferred anyway)
     *   - Full-width / CJK / kana -> do not rotate (use native tategaki form)
     *   - Horizontally-oriented punctuation marks (ellipses, dashes, wave
     *     dashes, etc.) that should read vertically in tategaki but whose
     *     fonts frequently lack a proper `vert` substitution -> rotate
     *     explicitly so we do not rely on the font.
     */
    private fun needsRotation(ch: Char): Boolean {
        val code = ch.code
        // Printable ASCII (space doesn't need rotation)
        if (code in 0x21..0x7E) return true
        // Half-width symbols / half-width katakana
        if (code in 0xFF61..0xFFDC) return true
        // Printable Latin-1 Supplement
        if (code in 0x00A1..0x00FF) return true
        // Horizontally-oriented punctuation that should stack vertically
        when (code) {
            0x2013, // EN DASH
            0x2014, // EM DASH
            0x2015, // HORIZONTAL BAR (dash)
            0x2025, // TWO DOT LEADER
            0x2026, // HORIZONTAL ELLIPSIS
            0x2212, // MINUS SIGN
            0x22EF, // MIDLINE HORIZONTAL ELLIPSIS
            0x3030, // WAVY DASH
            0x301C, // WAVE DASH
            0xFF5E, // FULLWIDTH TILDE
            0xFF0D, // FULLWIDTH HYPHEN-MINUS
            0x2500, // BOX DRAWINGS LIGHT HORIZONTAL
                    //   (commonly abused as an em-dash in Japanese e-books)
            0x2501, // BOX DRAWINGS HEAVY HORIZONTAL
            0x2010, // HYPHEN
            0x2011, // NON-BREAKING HYPHEN
            0x2012, // FIGURE DASH
            0x2043, // HYPHEN BULLET
            0xFE58  // SMALL EM DASH
            -> return true
        }
        return false
    }
}
