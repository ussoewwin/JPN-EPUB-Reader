package com.jpnepub.reader.vrender

import com.jpnepub.reader.epub.EpubParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * XHTML コンテンツを ContentNode のリストに変換する。
 *
 * XHTML は整形式 XML なので XmlPullParser で堅牢にパースする。
 * 扱う要素:
 *   <p> / <div>       → 段落 (ParaBreak)
 *   <br>              → 改行 (LineBreak)
 *   <ruby><rt>..</rt></ruby> → Ruby
 *   <img src="..">    → Image
 *   <h1>..<h6>        → 段落扱い (ParaBreak 前後)
 *   <script> <style>  → 内容を破棄
 *   それ以外のタグ     → 透過 (子孫のテキストは拾う)
 */
class ContentExtractor {

    /**
     * [flushText] / [flushHeadingText] 直前のテキストを正規化する。
     *
     * - タグ整形由来の改行・CJK 隣接の半角スペースは従来どおり除去。
     * - ひらがな・カタカナ・漢字どうしのあいだの全角スペース (U+3000) や欧文スペースは、
     *   縦組みで 1 字分の空列に見えるため除去する (横組用トラッキングの残り)。
     * - 複合ルビを `</ruby> <ruby>` のように分割した XHTML では、字のあいだに
     *   半角スペースだけが単独で [TextRun] 化することがあり、それも同様に空列になる。
     *   → [isNotBlank] でそのようなランを捨てるのと、先頭末尾の半角スペースを削る。
     */
    private fun sanitizeExtractedText(raw: String): String {
        var s = raw
            .replace(Regex("[\\u0009\\u000A\\u000D]+"), "")
            .replace(Regex(" +"), " ")
            .replace(Regex("(?<=[^\\x00-\\x7E]) | (?=[^\\x00-\\x7E])"), "")
        s = s.replace(INTRA_CJK_SPACE_RE, "")
        var start = 0
        var end = s.length
        while (start < end && s[start] == ' ') start++
        while (end > start && s[end - 1] == ' ') end--
        return if (start == 0 && end == s.length) s else s.substring(start, end)
    }

    fun extract(xhtml: String, chapterDir: String): List<ContentNode> {
        val nodes = mutableListOf<ContentNode>()

        // XML宣言などで壊れる場合があるので、<html から始まる形に正規化
        val cleaned = preprocess(xhtml)

        val parser = newParser()
        parser.setInput(StringReader(cleaned))

        val textBuffer = StringBuilder()
        var skipDepth = 0        // <script> / <style> 内はスキップ
        // ルビは表示しない方針。<rt>/<rp> の内容だけは破棄したいので、そこだけフラグ管理する。
        var inRt = false
        var inRp = false
        // 見出し収集: <h1>〜<h6> の中身は、通常の本文 TextRun とは別に貯めて
        // <hN> 終了時に Heading ノードとして1回だけ吐く。ネストは想定しない。
        // テキスト断片と画像 (gaiji) 断片を順序保持で集める。
        var headingLevel = 0
        val headingBuffer = StringBuilder()
        val headingParts = mutableListOf<ContentNode.HeadingPart>()

        fun flushHeadingText() {
            if (headingBuffer.isEmpty()) return
            val t = sanitizeExtractedText(headingBuffer.toString())
            // ルビを複数 <ruby> に分割したあいだの整形空白だけが残る TextRun は
            // 縦組みで 1 字分の空列に見えるので捨てる。
            if (t.isNotBlank()) headingParts.add(ContentNode.HeadingPart.Text(t))
            headingBuffer.setLength(0)
        }

        fun flushText() {
            if (textBuffer.isEmpty()) return
            val t = sanitizeExtractedText(textBuffer.toString())
            if (t.isNotBlank()) nodes.add(ContentNode.TextRun(t))
            textBuffer.clear()
        }

        fun emitParaBreak() {
            flushText()
            if (nodes.isNotEmpty() && nodes.last() !is ContentNode.ParaBreak) {
                nodes.add(ContentNode.ParaBreak)
            }
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        when (name) {
                            "script", "style", "head" -> skipDepth++
                            "ruby" -> {
                                // ルビはレイアウトしない。ruby 内の基字は通常の流し込み
                                // (textBuffer) にそのまま乗せ、<rt>/<rp> だけ破棄する。
                                // 旧実装は別バッファ rubyBase に溜めて \n 混入や順序問題を
                                // 引き起こしていたので完全にやめた。
                                flushText()
                            }
                            "rt" -> inRt = true
                            "rp" -> inRp = true
                            "rb" -> { /* 基字はそのまま textBuffer に流れる */ }
                            "br" -> {
                                if (headingLevel == 0) {
                                    flushText()
                                    nodes.add(ContentNode.LineBreak)
                                }
                                // 見出し内の <br> は単に無視 (1行の見出しとして整形)
                            }
                            "img" -> {
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                val cssClass = parser.getAttributeValue(null, "class") ?: ""
                                if (src.isNotEmpty() && !src.startsWith("data:")) {
                                    val resolved = resolveRelativePath(chapterDir, src)
                                    if (headingLevel == 0) {
                                        flushText()
                                        nodes.add(ContentNode.Image(resolved, cssClass))
                                    } else {
                                        flushHeadingText()
                                        headingParts.add(ContentNode.HeadingPart.Image(resolved))
                                    }
                                }
                            }
                            "image" -> {
                                // SVG内の <image xlink:href="..." /> (namespace-aware=false のため
                                // 属性名は "xlink:href" の literal で引く)
                                val href = parser.getAttributeValue(null, "href")
                                    ?: parser.getAttributeValue(null, "xlink:href")
                                    ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                                    ?: ""
                                val cssClass = parser.getAttributeValue(null, "class") ?: ""
                                if (href.isNotEmpty() && !href.startsWith("data:")) {
                                    val resolved = resolveRelativePath(chapterDir, href)
                                    if (headingLevel == 0) {
                                        flushText()
                                        nodes.add(ContentNode.Image(resolved, cssClass))
                                    } else {
                                        flushHeadingText()
                                        headingParts.add(ContentNode.HeadingPart.Image(resolved))
                                    }
                                }
                            }
                            // 見出し要素。中身は通常の textBuffer ではなく、headingBuffer に
                            // 集約して、閉じタグのときに Heading ノードとして吐く。
                            // 直前に PageBreak を置くことで、章タイトル・節タイトルが
                            // 必ずページ先頭に来るようにする (本文と混在させない)。
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                flushText()
                                if (headingLevel == 0) {
                                    nodes.add(ContentNode.PageBreak)
                                    headingLevel = (name[1].digitToIntOrNull() ?: 1).coerceIn(1, 6)
                                    headingBuffer.setLength(0)
                                    headingParts.clear()
                                }
                            }
                            // div / section / article は semantic には grouping 用途であり
                            // 段落区切りに使うと、<div>駒</div><div>形</div>... のような
                            // 1文字ずつ装飾目的で div 化された EPUB が各文字独立カラムに
                            // なってしまう。段落扱いは p / 箇条書き等に限定する。
                            "p", "li", "blockquote", "pre" -> {
                                if (headingLevel == 0) emitParaBreak()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        when (name) {
                            "script", "style", "head" -> if (skipDepth > 0) skipDepth--
                            "ruby" -> {
                                // ruby 閉じ時は基字側を flush する。flushText の
                                // 「非 ASCII 隣接スペース除去」規則で、EPUB3 複合ルビの
                                //   <ruby>\n<rb>桟</rb> <rt>さん</rt> <rb>橋</rb>\n</ruby>
                                // のようにタグ間にある整形空白が自動で落ちる。
                                flushText()
                            }
                            "rt" -> inRt = false
                            "rp" -> inRp = false
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                if (headingLevel > 0) {
                                    // 残テキストを断片化してから Heading を吐く。
                                    flushHeadingText()
                                    if (headingParts.isNotEmpty()) {
                                        nodes.add(
                                            ContentNode.Heading(
                                                headingParts.toList(),
                                                headingLevel
                                            )
                                        )
                                    }
                                    headingBuffer.setLength(0)
                                    headingParts.clear()
                                    headingLevel = 0
                                    // 見出しの後は段落扱いで次カラムへ (次の <p> との間を空ける)
                                    if (nodes.isNotEmpty() && nodes.last() !is ContentNode.ParaBreak) {
                                        nodes.add(ContentNode.ParaBreak)
                                    }
                                }
                            }
                            "p" -> {
                                if (headingLevel == 0) emitParaBreak()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth > 0) {
                            // <script>/<style>/<head> 内はスキップ
                        } else if (inRt || inRp) {
                            // ルビのよみがな (<rt>) / 括弧 (<rp>) は表示しない
                        } else if (headingLevel > 0) {
                            // 見出しは別バッファへ
                            headingBuffer.append(parser.text ?: "")
                        } else {
                            textBuffer.append(parser.text ?: "")
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // パーサ途中で壊れても、そこまでのノードを返す
        }

        flushText()
        // 末尾の冗長 ParaBreak / PageBreak を落とす
        while (nodes.isNotEmpty() &&
            (nodes.last() is ContentNode.ParaBreak || nodes.last() is ContentNode.PageBreak)
        ) {
            nodes.removeAt(nodes.size - 1)
        }
        return nodes
    }

    /**
     * <html ...> で始まる部分を抽出。名前空間やDOCTYPEで失敗するケースを緩和。
     */
    private fun preprocess(xhtml: String): String {
        val htmlStart = Regex("<html\\b", RegexOption.IGNORE_CASE).find(xhtml)
        val body = if (htmlStart != null) xhtml.substring(htmlStart.range.first) else xhtml
        return body
    }

    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        // HTML由来の実体参照を寛容に扱う
        try {
            parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true)
        } catch (_: Exception) { }
        return parser
    }

    private fun resolveRelativePath(base: String, relative: String): String {
        if (relative.startsWith("/")) return relative.removePrefix("/")
        val parts = if (base.isEmpty()) mutableListOf() else base.split("/").toMutableList()
        for (segment in relative.split("/")) {
            when (segment) {
                "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(segment)
            }
        }
        return parts.joinToString("/")
    }

    companion object {
        /**
         * ひらがな・カタカナ・CJK 統合・拡張A の字と字のあいだだけに挟まる空白を除去する。
         */
        private val INTRA_CJK_SPACE_RE = Regex(
            "(?<=[\\u3040-\\u309f\\u30a0-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff])" +
                "[\\u0020\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]+" +
                "(?=[\\u3040-\\u309f\\u30a0-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff])"
        )

        /**
         * EPUB の spine アイテム data (ByteArray) からテキストを抽出する便利関数。
         */
        fun extractFromBytes(data: ByteArray, chapterDir: String): List<ContentNode> {
            val text = EpubParser.decodeWithDetection(data)
            return ContentExtractor().extract(text, chapterDir)
        }
    }
}
