package com.jpnepub.reader.vrender

import com.jpnepub.reader.epub.EpubParser
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Converts XHTML content into a list of [ContentNode]s.
 *
 * XHTML is well-formed XML, so we use [XmlPullParser] which is robust
 * against incidental noise. Elements handled:
 *
 *   <p> / <div>       -> paragraph (ParaBreak)
 *   <br>              -> line break (LineBreak)
 *   <ruby><rt>..</rt></ruby> -> Ruby (annotation currently discarded)
 *   <img src="..">    -> Image
 *   <h1>..<h6>        -> treated as a paragraph (ParaBreak around it)
 *   <script> <style>  -> contents discarded
 *   anything else     -> transparent (descendants' text is still picked up)
 */
class ContentExtractor {

    fun extract(xhtml: String, chapterDir: String): List<ContentNode> {
        val nodes = mutableListOf<ContentNode>()

        // Normalize to "starts with <html...>" to dodge fragile XML
        // declarations / DOCTYPEs.
        val cleaned = preprocess(xhtml)

        val parser = newParser()
        parser.setInput(StringReader(cleaned))

        val textBuffer = StringBuilder()
        var skipDepth = 0        // Inside <script> / <style> / <head>
        // Ruby annotations are not rendered. We still need to know when
        // we're inside <rt> / <rp> so that their text gets discarded.
        var inRt = false
        var inRp = false

        fun flushText() {
            if (textBuffer.isEmpty()) return
            // 1) Strip control whitespace (tab / CR / LF). Those come
            //    from XHTML pretty-printing and have no display intent.
            // 2) Collapse runs of ASCII spaces to a single space.
            // 3) Drop any ASCII space directly adjacent to a non-ASCII
            //    character (CJK, Japanese punctuation, full-width, etc.).
            //    Real EPUB files routinely have newlines / indentation
            //    around <img/> and <ruby>...</ruby>, and those spaces
            //    should not consume horizontal glyphs in vertical
            //    Japanese text. Spaces *between* ASCII letters remain,
            //    because those ARE genuine word separators.
            val t = textBuffer.toString()
                .replace(Regex("[\\u0009\\u000A\\u000D]+"), "")
                .replace(Regex(" +"), " ")
                .replace(Regex("(?<=[^\\x00-\\x7E]) | (?=[^\\x00-\\x7E])"), "")
            if (t.isNotEmpty()) nodes.add(ContentNode.TextRun(t))
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
                                // We no longer attempt to render ruby. The
                                // base characters are just flowed into the
                                // main text buffer as-is; only <rt>/<rp>
                                // contents are discarded below.
                                //
                                // An earlier version kept base characters
                                // in a separate `rubyBase` buffer, which
                                // broke ordering and allowed \n from XHTML
                                // formatting to leak through.
                                flushText()
                            }
                            "rt" -> inRt = true
                            "rp" -> inRp = true
                            "rb" -> { /* Base chars flow straight into textBuffer */ }
                            "br" -> {
                                flushText()
                                nodes.add(ContentNode.LineBreak)
                            }
                            "img" -> {
                                flushText()
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                if (src.isNotEmpty() && !src.startsWith("data:")) {
                                    val resolved = resolveRelativePath(chapterDir, src)
                                    nodes.add(ContentNode.Image(resolved))
                                }
                            }
                            "image" -> {
                                flushText()
                                // SVG's <image xlink:href="..." />. With
                                // namespace-aware=false we have to look up
                                // the attribute by its literal prefixed
                                // name.
                                val href = parser.getAttributeValue(null, "href")
                                    ?: parser.getAttributeValue(null, "xlink:href")
                                    ?: parser.getAttributeValue("http://www.w3.org/1999/xlink", "href")
                                    ?: ""
                                if (href.isNotEmpty() && !href.startsWith("data:")) {
                                    val resolved = resolveRelativePath(chapterDir, href)
                                    nodes.add(ContentNode.Image(resolved))
                                }
                            }
                            // div / section / article are semantically
                            // generic grouping elements. If we treated
                            // them as paragraph breaks, EPUBs that wrap
                            // every character in its own <div> for
                            // decorative reasons would end up with each
                            // character in its own column. So we limit
                            // paragraph semantics to <p>, headings, list
                            // items, etc.
                            "p", "h1", "h2", "h3", "h4", "h5", "h6",
                            "li", "blockquote", "pre" -> {
                                emitParaBreak()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name?.lowercase() ?: ""
                        when (name) {
                            "script", "style", "head" -> if (skipDepth > 0) skipDepth--
                            "ruby" -> {
                                // Flushing at </ruby> also strips any
                                // formatting whitespace that lived
                                // inside the ruby element -- for
                                // example the EPUB3 compound form:
                                //   <ruby>\n<rb>...</rb> <rt>...</rt> <rb>...</rb>\n</ruby>
                                // whose inter-tag spaces would otherwise
                                // leak into the flow.
                                flushText()
                            }
                            "rt" -> inRt = false
                            "rp" -> inRp = false
                            "p", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                emitParaBreak()
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth > 0) {
                            // Inside <script>/<style>/<head>
                        } else if (inRt || inRp) {
                            // Inside <rt> (ruby text) / <rp> (ruby parenthesis)
                        } else {
                            textBuffer.append(parser.text ?: "")
                        }
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            // If the parser blows up mid-document, return whatever we
            // already collected rather than failing the whole chapter.
        }

        flushText()
        // Trim redundant trailing ParaBreak nodes.
        while (nodes.isNotEmpty() && nodes.last() is ContentNode.ParaBreak) {
            nodes.removeAt(nodes.size - 1)
        }
        return nodes
    }

    /**
     * Extract the substring starting at the first `<html ...>` tag.
     * This sidesteps breakage caused by namespace declarations and
     * DOCTYPE boilerplate above it.
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
        // Accept HTML-style entity references leniently.
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
         * Convenience: extract nodes directly from the raw bytes of an
         * EPUB spine item.
         */
        fun extractFromBytes(data: ByteArray, chapterDir: String): List<ContentNode> {
            val text = EpubParser.decodeWithDetection(data)
            return ContentExtractor().extract(text, chapterDir)
        }
    }
}
