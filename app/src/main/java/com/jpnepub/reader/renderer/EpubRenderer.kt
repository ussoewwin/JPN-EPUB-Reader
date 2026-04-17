package com.jpnepub.reader.renderer

import android.util.Base64
import com.jpnepub.reader.epub.EpubBook
import com.jpnepub.reader.epub.EpubParser
import com.jpnepub.reader.epub.SpineItem

class EpubRenderer(private val book: EpubBook, private val config: ReaderConfig) {

    fun renderChapter(index: Int): String {
        if (index < 0 || index >= book.spine.size) return ""

        val spineItem = book.spine[index]
        val rawData = book.resources[spineItem.href] ?: return ""
        val html = EpubParser.decodeWithDetection(rawData)

        val isImageOnlyPage = isImageOnlyPage(html)
        return processHtml(html, spineItem, isImageOnlyPage)
    }

    /**
     * Returns true for image-dominated pages such as covers or
     * full-page illustrations (pages with almost no body text).
     * For those we skip the vertical-writing CSS injection so that
     * the publisher's image layout is preserved unchanged.
     */
    private fun isImageOnlyPage(html: String): Boolean {
        val bodyMatch = Regex(
            """<body[^>]*>([\s\S]*?)</body>""",
            RegexOption.IGNORE_CASE
        ).find(html) ?: return false
        val bodyContent = bodyMatch.groupValues[1]
        val hasImageOrSvg = bodyContent.contains("<img", ignoreCase = true) ||
            bodyContent.contains("<svg", ignoreCase = true) ||
            bodyContent.contains("<image", ignoreCase = true)
        if (!hasImageOrSvg) return false
        val textOnly = bodyContent
            .replace(Regex("""<[^>]+>"""), "")
            .replace(Regex("""\s+"""), "")
        return textOnly.length < 50
    }

    private fun processHtml(html: String, spineItem: SpineItem, isImageOnlyPage: Boolean): String {
        val chapterDir = spineItem.href.substringBeforeLast('/', "")

        var processed = html

        processed = inlineCssLinks(processed, chapterDir)
        processed = inlineResources(processed, chapterDir)
        processed = convertEpubCssInHtml(processed)
        processed = ensureCharsetMeta(processed)

        val injectedCss = buildFontFallbackCss(isImageOnlyPage)
        processed = injectCssAtEndOfHead(processed, injectedCss)

        return processed
    }

    /**
     * Also translate `-epub-` prefixed CSS properties that appear
     * inside inline <style> tags and style="" attributes within the
     * HTML document body, not only inside external stylesheets.
     */
    private fun convertEpubCssInHtml(html: String): String {
        if (!html.contains("-epub-", ignoreCase = true)) return html
        return convertEpubCssProperties(html)
    }

    private fun buildFontFallbackCss(isImageOnlyPage: Boolean): String {
        // We inject writing-mode + fixed dimensions ONLY for vertical
        // body pages. Without fixing html height the page would
        // scroll infinitely downward and break pagination; so we pin
        // html/body to 100vh/100vw. Image-only pages (covers etc.)
        // are left alone so the publisher's layout survives.
        //
        // Vertical body pages use transform-based pagination:
        //   - html: viewport-sized + overflow:hidden (clipping layer)
        //   - body: writing-mode:vertical-rl, natural width (grows to
        //          the left), safe padding.
        //   - Page turns are done in JS via:
        //        body.transform = translateX(N * clientW)
        //
        // We used to use scrollLeft, but Chromium flipped the sign
        // convention for vertical-rl, and publisher CSS with its own
        // overflow declarations made the approach unreliable, so
        // transform is the new baseline.
        //
        // !important is required so we outrank the EPUB's own CSS.
        val verticalCss = if (book.isVertical && !isImageOnlyPage) """
html {
    -webkit-writing-mode: vertical-rl !important;
    writing-mode: vertical-rl !important;
    height: 100vh !important;
    width: 100vw !important;
    margin: 0 !important;
    padding: 0 !important;
    overflow: hidden !important;
}
body {
    -webkit-writing-mode: vertical-rl !important;
    writing-mode: vertical-rl !important;
    height: 100vh !important;
    margin: 0 !important;
    padding: 20px 16px !important;
    box-sizing: border-box !important;
    /* Pin the transform origin to top-right; this is the natural
       starting corner for vertical-rl pagination. */
    transform-origin: top right !important;
    /* Do not let body itself scroll; we move it via CSS transform. */
    overflow: visible !important;
    /* Intentionally no fixed width: body grows naturally to the left
       as columns flow. */
}
img, svg, image, video {
    -webkit-writing-mode: horizontal-tb !important;
    writing-mode: horizontal-tb !important;
    max-width: 100% !important;
    max-height: 100% !important;
}
""" else ""

        val boldCss = if (config.bold) """
body, body * {
    font-weight: 700 !important;
}
""" else ""

        return """
$verticalCss
@font-face {
    font-family: 'JpnEpubFallback';
    src: local('Noto Sans CJK JP'),
         local('Noto Sans JP'),
         local('Noto Serif CJK JP'),
         local('Noto Serif JP'),
         local('Source Han Sans'),
         local('Source Han Serif'),
         local('Hiragino Sans'),
         local('Hiragino Mincho ProN'),
         local('Yu Gothic'),
         local('Yu Mincho'),
         local('Meiryo');
    font-display: swap;
    unicode-range: U+3000-9FFF, U+F900-FAFF, U+FE30-FE4F, U+20000-2FA1F;
}
$boldCss
"""
    }

    private fun inlineCssLinks(html: String, chapterDir: String): String {
        val linkPattern = Regex(
            """<link[^>]*\brel\s*=\s*["']stylesheet["'][^>]*>""",
            RegexOption.IGNORE_CASE
        )
        return linkPattern.replace(html) { match ->
            val hrefMatch = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(match.value)
            val href = hrefMatch?.groupValues?.get(1)
            if (href != null) {
                val cssPath = resolveRelativePath(chapterDir, href)
                val cssData = book.resources[cssPath]
                if (cssData != null) {
                    val cssText = EpubParser.decodeWithDetection(cssData)
                    val cssDir = cssPath.substringBeforeLast('/', "")
                    var processedCss = inlineCssUrls(cssText, cssDir)
                    processedCss = convertEpubCssProperties(processedCss)
                    "<style>\n$processedCss\n</style>"
                } else {
                    match.value
                }
            } else {
                match.value
            }
        }
    }

    /**
     * Convert `-epub-` prefixed CSS properties (EPUB3 standard) into
     * the unprefixed properties that WebView actually understands.
     * The original property is left in place and the standard one is
     * appended, so nothing breaks for renderers that do honor the
     * prefixed form.
     */
    private fun convertEpubCssProperties(css: String): String {
        var result = css
        result = result.replace(
            Regex("""-epub-writing-mode\s*:\s*([^;}\s]+)"""),
            "-epub-writing-mode: $1; writing-mode: $1"
        )
        result = result.replace(
            Regex("""-epub-text-orientation\s*:\s*([^;}\s]+)"""),
            "-epub-text-orientation: $1; text-orientation: $1"
        )
        result = result.replace(
            Regex("""-epub-text-combine\s*:\s*([^;}\s]+)"""),
            "-epub-text-combine: $1; -webkit-text-combine: $1"
        )
        return result
    }

    private fun inlineCssUrls(css: String, cssDir: String): String {
        return css.replace(Regex("""url\s*\(\s*["']?([^"')]+)["']?\s*\)""")) { match ->
            val url = match.groupValues[1]
            if (url.startsWith("data:") || url.startsWith("http")) {
                match.value
            } else {
                val resourcePath = resolveRelativePath(cssDir, url)
                val resourceData = book.resources[resourcePath]
                if (resourceData != null) {
                    val mimeType = guessMimeType(resourcePath)
                    val base64 = Base64.encodeToString(resourceData, Base64.NO_WRAP)
                    "url(data:$mimeType;base64,$base64)"
                } else {
                    match.value
                }
            }
        }
    }

    private fun inlineResources(html: String, chapterDir: String): String {
        return html.replace(Regex("""(src)\s*=\s*["']([^"']+)["']""")) { match ->
            val attr = match.groupValues[1]
            val url = match.groupValues[2]

            if (url.startsWith("data:") || url.startsWith("http") || url.startsWith("#")) {
                match.value
            } else {
                val resourcePath = resolveRelativePath(chapterDir, url)
                val resourceData = book.resources[resourcePath]
                if (resourceData != null && isEmbeddable(resourcePath)) {
                    val mimeType = guessMimeType(resourcePath)
                    val base64 = Base64.encodeToString(resourceData, Base64.NO_WRAP)
                    """$attr="data:$mimeType;base64,$base64""""
                } else {
                    match.value
                }
            }
        }
    }

    private fun injectCssAtEndOfHead(html: String, css: String): String {
        val styleTag = "<style id=\"jpnepub-font-fallback\">\n$css\n</style>"
        return when {
            html.contains("</head>", ignoreCase = true) ->
                html.replaceFirst("</head>", "$styleTag\n</head>", ignoreCase = true)
            html.contains("<body", ignoreCase = true) ->
                html.replaceFirst(Regex("<body", RegexOption.IGNORE_CASE), "$styleTag\n<body")
            else -> "$styleTag\n$html"
        }
    }

    private fun ensureCharsetMeta(html: String): String {
        if (html.contains("charset", ignoreCase = true)) return html

        val meta = """<meta charset="UTF-8">"""
        return when {
            html.contains("<head>", ignoreCase = true) ->
                html.replaceFirst("<head>", "<head>\n$meta", ignoreCase = true)
            html.contains("<head ", ignoreCase = true) -> {
                val headMatch = Regex("<head([^>]*)>", RegexOption.IGNORE_CASE).find(html)
                if (headMatch != null) {
                    html.substring(0, headMatch.range.last + 1) + "\n$meta" +
                        html.substring(headMatch.range.last + 1)
                } else html
            }
            else -> "$meta\n$html"
        }
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

    private fun isEmbeddable(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "gif", "svg", "webp", "bmp",
            "otf", "ttf", "woff", "woff2")
    }

    private fun guessMimeType(path: String): String {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "webp" -> "image/webp"
            "css" -> "text/css"
            "otf" -> "font/otf"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "xhtml", "html", "htm" -> "application/xhtml+xml"
            else -> "application/octet-stream"
        }
    }
}
