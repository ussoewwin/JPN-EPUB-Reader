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

    fun extract(xhtml: String, chapterDir: String, href: String): List<ContentNode> {
        val nodes = mutableListOf<ContentNode>()
        // 合成アンカー ID のファイル間衝突を防ぐため、href をプレフィックスに使う。
        // EpubParser.findSubheadingsInXhtml と同じ規則で生成し、両者が必ず一致する。
        val anchorIdPrefix = href
            .replace('/', '_')
            .replace('\\', '_')
            .replace('.', '_')

        // XML宣言などで壊れる場合があるので、<html から始まる形に正規化
        val cleaned = preprocess(xhtml)

        val parser = newParser()
        parser.setInput(StringReader(cleaned))

        val textBuffer = StringBuilder()
        var skipDepth = 0        // <script> / <style> 内はスキップ
        // ルビは表示しない方針。<rt>/<rp> の内容だけは破棄したいので、そこだけフラグ管理する。
        var inRt = false
        var inRp = false
        // 脚注参照リンク (例: <a href="text00018.html#chushaku_013">（13）</a>) は
        // 読み手がジャンプできない本リーダーでは単なるノイズなので、その <a> 配下の
        // テキスト・画像をまるごと捨てる。
        // 本の中の目次ページ (<a href="text00005.html#link_002">はしがき</a>) は
        // 本文として残したいので、フラグメント名・epub:type・role で区別する。
        // HTML では <a> のネストは許されないため、通常はスタック深さ 0 か 1。
        val anchorFootnoteStack = ArrayDeque<Boolean>()
        var footnoteAnchorDepth = 0
        // 見出し収集: <h1>〜<h6> の中身は、通常の本文 TextRun とは別に貯めて
        // <hN> 終了時に Heading ノードとして1回だけ吐く。ネストは想定しない。
        // テキスト断片と画像 (gaiji) 断片を順序保持で集める。
        var headingLevel = 0
        val headingBuffer = StringBuilder()
        val headingParts = mutableListOf<ContentNode.HeadingPart>()
        // h1〜h6 の文書内出現順。id を持たない見出しについて、章内ジャンプ用の
        // 合成アンカー `__h<N>` を発行するためのカウンタ。EpubParser 側の
        // findSubheadingsInXhtml でも同じ規則で fragId を割り当てる。
        var headingOrdinal = 0
        // `<p>…<span class="font-1emNN">…</span>…</p>` パターンの小見出し候補。
        // 1.15em〜1.49em (emNum 15〜49) の最初に現れた span で、その `<p>` を
        // 「subheading 候補」として 1 回だけ合成アンカー `__ps<N>` を発行する。
        // EpubParser.findSubheadingsInXhtml 側と同じ順序規則なので、TOC からの
        // 章内ジャンプがファイル先頭ではなく該当ページに飛ぶようになる。
        val pStack = ArrayDeque<PFrame>()
        var pSpanOrdinal = 0

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

        /** `id` / `xml:id` のどちらでもアンカーを拾う (EPUB3 XHTML の慣習差吸収)。 */
        fun readElementId(p: XmlPullParser): String {
            p.getAttributeValue(null, "id")?.takeIf { it.isNotEmpty() }?.let { return it }
            p.getAttributeValue("http://www.w3.org/XML/1998/namespace", "id")
                ?.takeIf { it.isNotEmpty() }?.let { return it }
            p.getAttributeValue(null, "xml:id")?.takeIf { it.isNotEmpty() }?.let { return it }
            return ""
        }

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        // 全ての開始タグで `id="..."` をアンカーマーカーとして記録する。
                        // TOC の `#fragment` 付きリンクから章内の正しいページへ
                        // ジャンプするためのランドマーク。0 幅なのでレイアウトには影響しない。
                        val elemId = readElementId(parser)
                        if (elemId.isNotEmpty() && skipDepth == 0 && footnoteAnchorDepth == 0) {
                            flushText()
                            nodes.add(ContentNode.Anchor(elemId))
                        }
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
                            "a" -> {
                                val href = parser.getAttributeValue(null, "href") ?: ""
                                val epubType = parser.getAttributeValue(null, "epub:type")
                                    ?: parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                                    ?: ""
                                val role = parser.getAttributeValue(null, "role") ?: ""
                                val isFootnote = isFootnoteRefAnchor(href, epubType, role)
                                anchorFootnoteStack.addLast(isFootnote)
                                if (isFootnote) footnoteAnchorDepth++
                            }
                            "img" -> {
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                val cssClass = parser.getAttributeValue(null, "class") ?: ""
                                if (footnoteAnchorDepth == 0 && src.isNotEmpty() && !src.startsWith("data:")) {
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
                                if (footnoteAnchorDepth == 0 && href.isNotEmpty() && !href.startsWith("data:")) {
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
                                    // 明示的な id が無い見出しには `__h<N>` の合成アンカーを打ち、
                                    // TOC からの章内ジャンプ (#__h3 等) を成立させる。
                                    // Anchor は PageBreak の**後**に配置し、VerticalLayoutEngine の
                                    // PageBreak 処理後に空ページがあっても新しいページを開始して
                                    // pendingAnchorIds を pages.size (次のページ) で解決する。
                                    // PageBreak の前に置くと Anchor が前のページにマッピングされる。
                                    val hOrd = headingOrdinal++
                                    if (elemId.isEmpty()) {
                                        nodes.add(ContentNode.Anchor("__${anchorIdPrefix}_h$hOrd"))
                                    }
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
                                if (name == "p") {
                                    val pClass = parser.getAttributeValue(null, "class") ?: ""
                                    val pEmNum = extractFontEmNum(pClass)
                                    val frame = PFrame(hasExplicitId = elemId.isNotEmpty())
                                    // <p class="font-1emNN"> パターン (例: Kadokawa の小見出し)
                                    // EpubParser.findSubheadingsInXhtml と同じ範囲 (15〜49) を
                                    // 採用し、同じ pSpanOrdinal で __ps<N> を割り当てる。
                                    if (pEmNum != null && pEmNum in 15..49 &&
                                        !frame.hasExplicitId &&
                                        skipDepth == 0 && footnoteAnchorDepth == 0
                                    ) {
                                        val ord = pSpanOrdinal++
                                        flushText()
                                        nodes.add(ContentNode.PageBreak)
                                        nodes.add(ContentNode.Anchor("__${anchorIdPrefix}_ps$ord"))
                                        frame.anchored = true
                                    }
                                    pStack.addLast(frame)
                                }
                            }
                            "span" -> {
                                // `<p>...<span class="font-1emNN">タイトル</span>...</p>` の
                                // 小見出しパターン。最初の qualifying span で合成アンカーを 1 つだけ
                                // emit し、TOC の子エントリ (`#__ps<N>` 等) が該当ページに着地する
                                // ようにする。em 範囲は EpubParser 側と完全に一致させる (15〜49)。
                                val cssClass = parser.getAttributeValue(null, "class") ?: ""
                                val emNum = extractFontEmNum(cssClass)
                                if (emNum != null && emNum in 15..49 &&
                                    pStack.isNotEmpty() && !pStack.last().anchored
                                ) {
                                    val ord = pSpanOrdinal++
                                    val frame = pStack.last()
                                    val spanHasId = elemId.isNotEmpty()
                                    if (!frame.hasExplicitId && !spanHasId &&
                                        skipDepth == 0 && footnoteAnchorDepth == 0
                                    ) {
                                        flushText()
                                        nodes.add(ContentNode.PageBreak)
                                        nodes.add(ContentNode.Anchor("__${anchorIdPrefix}_ps$ord"))
                                    }
                                    frame.anchored = true
                                }
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
                            "a" -> {
                                if (anchorFootnoteStack.isNotEmpty()) {
                                    val wasFootnote = anchorFootnoteStack.removeLast()
                                    if (wasFootnote && footnoteAnchorDepth > 0) {
                                        footnoteAnchorDepth--
                                    }
                                }
                            }
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
                                if (pStack.isNotEmpty()) pStack.removeLast()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth > 0) {
                            // <script>/<style>/<head> 内はスキップ
                        } else if (footnoteAnchorDepth > 0) {
                            // 脚注参照リンクの中身 ("（13）" など) は捨てる
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

    /**
     * その `<a>` が脚注・注釈への参照リンクかどうかを判定する。
     *
     * 判定基準:
     *  - EPUB3 の `epub:type="noteref"` / `role="doc-noteref"` は標準どおり noteref とみなす。
     *  - それ以外は `href` のフラグメント (#以降) が、日本語 EPUB でよく使われる
     *    脚注アンカーの命名規則で始まっていれば noteref とみなす
     *    (`chushaku_*`, `chu_*`, `fn*`, `note_*`, `footnote*`, `endnote*` ほか)。
     *  - 本の中の目次ページが章タイトルをラップする `<a href="...#link_NNN">` は
     *    この命名規則に当たらないので、本文として残る。
     */
    private fun isFootnoteRefAnchor(href: String, epubType: String, role: String): Boolean {
        if (epubType.contains("noteref", ignoreCase = true)) return true
        if (role.contains("doc-noteref", ignoreCase = true)) return true
        val hashIdx = href.indexOf('#')
        if (hashIdx < 0) return false
        val frag = href.substring(hashIdx + 1).lowercase()
        if (frag.isEmpty()) return false
        return frag.startsWith("chushaku") ||
            frag.startsWith("chu_") ||
            frag.startsWith("chu-") ||
            frag.startsWith("fn_") ||
            frag.startsWith("fn-") ||
            (frag.startsWith("fn") && frag.length >= 3 && frag[2].isDigit()) ||
            frag.startsWith("footnote") ||
            frag.startsWith("endnote") ||
            frag.startsWith("note_") ||
            frag.startsWith("note-") ||
            frag.startsWith("noteref") ||
            frag.startsWith("rfn_") ||
            frag.startsWith("rfn-") ||
            frag.startsWith("nt_") ||
            frag.startsWith("nt-")
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

    /**
     * `<p>` のスタックフレーム。`<p>` 内で最初に現れる `font-1em` 小見出し span
     * によって合成アンカーを 1 回だけ発行するために使う。
     */
    private class PFrame(val hasExplicitId: Boolean) {
        var anchored: Boolean = false
        /** この <p> 内に <a> タグが含まれるか。EpubParser と同じく <a> を含む段落は
         * 小見出し候補から除外するためのフラグ。 */
        var hasAnchor: Boolean = false
    }

    /**
     * `class="... font-1emNN ..."` から NN (1 桁または 2 桁) を取り出して
     * 2 桁ゼロ埋めの整数で返す。EpubParser.findSubheadingsInXhtml と同じ規則。
     * 例: `font-1em2` → 20, `font-1em20` → 20, `font-1em50` → 50。
     */
    private fun extractFontEmNum(cssClass: String): Int? {
        val m = FONT_EM_RE.find(cssClass) ?: return null
        return m.groupValues[1].padEnd(2, '0').toIntOrNull()
    }

    companion object {
        private val FONT_EM_RE = Regex("""\bfont-1em(\d{1,2})""")

        /**
         * ひらがな・カタカナ・CJK 統合・拡張A の字と字のあいだだけに挟まる、
         * 「タグ整形や横組トラッキング由来とみなして良い空白」を除去する。
         *
         * 含めるのは ASCII space / NBSP / U+2000–200A / U+202F / U+205F のみ。
         * **U+3000 (全角スペース) は除外**: 章タイトルの "第N章　題名" のように
         * 著者・編集が意図して入れたものを消してしまうと、目次ページや本文で
         * 章番号と題名が詰まって表示される。
         */
        private val INTRA_CJK_SPACE_RE = Regex(
            "(?<=[\\u3040-\\u309f\\u30a0-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff])" +
                "[\\u0020\\u00a0\\u2000-\\u200a\\u202f\\u205f]+" +
                "(?=[\\u3040-\\u309f\\u30a0-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff])"
        )

        /**
         * EPUB の spine アイテム data (ByteArray) からテキストを抽出する便利関数。
         */
        fun extractFromBytes(data: ByteArray, chapterDir: String, href: String): List<ContentNode> {
            val text = EpubParser.decodeWithDetection(data)
            return ContentExtractor().extract(text, chapterDir, href)
        }
    }
}
