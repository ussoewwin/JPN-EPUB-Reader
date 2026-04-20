package com.jpnepub.reader.epub

import android.content.Context
import android.net.Uri
import com.ibm.icu.text.CharsetDetector
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val spine: List<SpineItem>,
    val toc: List<TocEntry>,
    val resources: Map<String, ByteArray>,
    val isVertical: Boolean
)

data class SpineItem(
    val id: String,
    val href: String,
    val mediaType: String
)

data class TocEntry(
    val title: String,
    val href: String,
    val children: List<TocEntry> = emptyList(),
    /**
     * 章本文の <h*> から拾った「テキスト＋外字画像」断片列。
     * NCX/nav に `〓` (代用記号) が入っている所を、本文側の外字画像で
     * 正しく置き換えて目次に表示するための情報。
     * 取得できなかった場合 null。
     */
    val titleParts: List<TitlePart>? = null,
)

/** 目次タイトルの断片。テキストと外字画像を順序付きで保持する。 */
sealed class TitlePart {
    data class Text(val text: String) : TitlePart()
    /** 画像 src は EPUB ルートからの絶対パス (resources マップで解決可能)。 */
    data class Image(val src: String) : TitlePart()
}

class EpubParser(private val context: Context) {

    fun parse(uri: Uri): EpubBook {
        val resources = mutableMapOf<String, ByteArray>()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val data = zis.readBytes()
                        resources[normalizePath(entry.name)] = data
                    }
                    entry = zis.nextEntry
                }
            }
        } ?: throw IOException("Cannot open EPUB file")

        val containerXml = resources["META-INF/container.xml"]
            ?: throw IOException("Invalid EPUB: missing container.xml")
        val rootFilePath = parseContainer(containerXml)

        val opfData = resources[rootFilePath]
            ?: throw IOException("Invalid EPUB: missing OPF at $rootFilePath")
        val opfDir = rootFilePath.substringBeforeLast('/', "")

        return parseOpf(opfData, opfDir, resources)
    }

    fun parse(file: File): EpubBook {
        return parse(Uri.fromFile(file))
    }

    private fun normalizePath(path: String): String {
        return path.removePrefix("/").removePrefix("./")
    }

    private fun resolveHref(base: String, href: String): String {
        if (base.isEmpty()) return normalizePath(href)
        return normalizePath("$base/$href")
    }

    private fun parseContainer(data: ByteArray): String {
        val parser = newParser()
        parser.setInput(ByteArrayInputStream(data), "UTF-8")

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
                    ?: throw IOException("Invalid container.xml")
            }
        }
        throw IOException("No rootfile found in container.xml")
    }

    private fun parseOpf(data: ByteArray, opfDir: String, resources: Map<String, ByteArray>): EpubBook {
        val xml = decodeWithDetection(data)
        val parser = newParser()
        parser.setInput(StringReader(xml))

        var title = ""
        var author = ""
        var language = ""
        val manifestItems = mutableMapOf<String, Pair<String, String>>() // id -> (href, mediaType)
        val spineIds = mutableListOf<String>()
        var pageProgressionDirection = ""
        var inMetadata = false
        var currentTag = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    // isNamespaceAware=false なので <dc:title> は "dc:title" として
                    // 渡ってくる。Dublin Core 名前空間プレフィックスを剥がして
                    // "title" / "creator" / "language" と比較する。
                    val localName = parser.name?.substringAfter(':') ?: ""
                    when (localName) {
                        "metadata" -> inMetadata = true
                        "title" -> if (inMetadata) currentTag = "title"
                        "creator" -> if (inMetadata) currentTag = "creator"
                        "language" -> if (inMetadata) currentTag = "language"
                        "spine" -> {
                            pageProgressionDirection =
                                parser.getAttributeValue(null, "page-progression-direction") ?: ""
                        }
                        "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            if (id.isNotEmpty()) {
                                manifestItems[id] = Pair(href, mediaType)
                            }
                        }
                        "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            if (idref.isNotEmpty()) spineIds.add(idref)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    // 同じタグが複数回出現するケース (シリーズ名が <dc:title> として
                    // 追加で並ぶ等) で、既に取得済みの値を空文字で上書きしないよう
                    // isEmpty の時だけ代入する。
                    when (currentTag) {
                        "title" -> if (title.isEmpty() && text.isNotEmpty()) title = text
                        "creator" -> if (author.isEmpty() && text.isNotEmpty()) author = text
                        "language" -> if (language.isEmpty() && text.isNotEmpty()) language = text
                    }
                }
                XmlPullParser.END_TAG -> {
                    val localName = parser.name?.substringAfter(':') ?: ""
                    if (localName == "metadata") inMetadata = false
                    if (localName in listOf("title", "creator", "language")) currentTag = ""
                }
            }
        }

        val spine = spineIds.mapNotNull { id ->
            manifestItems[id]?.let { (href, mediaType) ->
                SpineItem(id, resolveHref(opfDir, href), mediaType)
            }
        }

        val isVertical = pageProgressionDirection == "rtl" ||
            detectVerticalWriting(spine, resources)

        val toc = parseToc(manifestItems, opfDir, resources)

        return EpubBook(
            title = title.ifEmpty { "無題" },
            author = author,
            language = language.ifEmpty { "ja" },
            spine = spine,
            toc = toc,
            resources = resources,
            isVertical = isVertical
        )
    }

    private fun detectVerticalWriting(spine: List<SpineItem>, resources: Map<String, ByteArray>): Boolean {
        for (item in spine.take(3)) {
            val content = resources[item.href] ?: continue
            val text = decodeWithDetection(content)
            if (text.contains("writing-mode") &&
                (text.contains("vertical-rl") || text.contains("tb-rl"))) {
                return true
            }
        }

        for ((path, data) in resources) {
            if (path.endsWith(".css")) {
                val css = decodeWithDetection(data)
                if (css.contains("writing-mode") &&
                    (css.contains("vertical-rl") || css.contains("tb-rl"))) {
                    return true
                }
            }
        }
        return false
    }

    private fun parseToc(
        manifestItems: Map<String, Pair<String, String>>,
        opfDir: String,
        resources: Map<String, ByteArray>
    ): List<TocEntry> {
        val baseEntries: List<TocEntry> = run {
            // Try EPUB3 nav first
            for ((_, pair) in manifestItems) {
                val (href, mediaType) = pair
                if (mediaType == "application/xhtml+xml") {
                    val fullPath = resolveHref(opfDir, href)
                    val data = resources[fullPath] ?: continue
                    val text = decodeWithDetection(data)
                    if (text.contains("<nav") && text.contains("epub:type=\"toc\"")) {
                        return@run parseNavToc(text, fullPath)
                    }
                }
            }
            // Fall back to EPUB2 NCX
            val ncxEntry = manifestItems.entries.find {
                it.value.second == "application/x-dtbncx+xml"
            }
            if (ncxEntry != null) {
                val fullPath = resolveHref(opfDir, ncxEntry.value.first)
                val data = resources[fullPath]
                if (data != null) return@run parseNcxToc(data)
            }
            emptyList()
        }

        // 各 TOC エントリの参照先 XHTML から実際の見出しを拾い、
        // `〓` 等で潰れていた外字を画像断片として復元する。
        return baseEntries.map { entry -> enrichTocEntryWithBodyHeading(entry, opfDir, resources) }
    }

    /**
     * TOC エントリの href 先 XHTML を読み、最初の <h1>〜<h6> から
     * テキストと外字画像の混在断片列を取り出して titleParts に詰める。
     * 取得失敗時は元のエントリをそのまま返す。
     *
     * 注意: `entry.href` に `#fragment` が付いている場合、同じ XHTML の
     *   中に複数章が並んでいる形式 (例: `text00008.html#link_004`,
     *   `text00008.html#link_005`, ...) である。fragment を無視して
     *   XHTML の先頭 h* を使うと、その XHTML を共有する全エントリが
     *   同じ見出しに化けるので、fragment 付きで NCX/nav 側のタイトルが
     *   既に空でないなら差し替えを行わない (NCX を信頼)。
     *   これで本来の章タイトルが保たれる。
     */
    private fun enrichTocEntryWithBodyHeading(
        entry: TocEntry,
        opfDir: String,
        resources: Map<String, ByteArray>
    ): TocEntry {
        val hasFragment = entry.href.contains('#')
        if (hasFragment && entry.title.isNotBlank()) {
            return entry
        }
        val hrefNoFrag = entry.href.substringBefore('#')
        if (hrefNoFrag.isEmpty()) return entry
        // hrefNoFrag は parseNavToc 内で resolveHref 済み (basePath基準)
        // または NCX 内では opf 基準の相対。両方を試す。
        val candidatePaths = listOf(
            normalizePath(hrefNoFrag),
            resolveHref(opfDir, hrefNoFrag),
        )
        val data = candidatePaths.asSequence().mapNotNull { resources[it] }.firstOrNull() ?: return entry
        val xhtml = decodeWithDetection(data)
        val chapterDir = (candidatePaths.firstOrNull { resources.containsKey(it) } ?: hrefNoFrag)
            .substringBeforeLast('/', "")
        val parts = extractFirstHeadingParts(xhtml, chapterDir) ?: return entry
        if (parts.isEmpty()) return entry
        // テキストだけを連結して plain title も差し替える (画像箇所は alt や空で詰める)。
        val plainTitle = buildString {
            for (p in parts) {
                when (p) {
                    is TitlePart.Text -> append(p.text)
                    is TitlePart.Image -> { /* 画像は plain には出さない */ }
                }
            }
        }.trim().ifEmpty { entry.title }
        return entry.copy(title = plainTitle, titleParts = parts)
    }

    /**
     * XHTML 文字列から最初の <h1>〜<h6> 要素を見つけ、その中身を
     * TitlePart のリストにして返す。未発見なら null。
     *
     * 軽量実装: 完全な ContentExtractor ほどの正規化は不要 (見出し1行分の
     * テキストと <img>/<image> の順序が取れれば十分)。
     */
    private fun extractFirstHeadingParts(xhtml: String, chapterDir: String): List<TitlePart>? {
        val headingMatch = Regex("""<(h[1-6])\b[^>]*>([\s\S]*?)</\1>""", RegexOption.IGNORE_CASE)
            .find(xhtml) ?: return null
        val inner = headingMatch.groupValues[2]
        val parts = mutableListOf<TitlePart>()
        val textBuf = StringBuilder()

        fun flushText() {
            if (textBuf.isEmpty()) return
            val t = textBuf.toString()
                .replace(Regex("[\\u0009\\u000A\\u000D]+"), "")
                .replace(Regex(" +"), " ")
                .replace(Regex("(?<=[^\\x00-\\x7E]) | (?=[^\\x00-\\x7E])"), "")
            if (t.isNotEmpty()) parts.add(TitlePart.Text(t))
            textBuf.clear()
        }

        // タグ・実体参照を順次走査する単純パーサ。
        // 対象タグ: <img>, <image> は画像断片化。<rt>/<rp> はルビなので捨てる。
        // それ以外のタグは透過してテキストだけ拾う。
        var i = 0
        var skipDepth = 0      // <rt>/<rp> 内
        val n = inner.length
        while (i < n) {
            val c = inner[i]
            if (c == '<') {
                val close = inner.indexOf('>', i)
                if (close < 0) break
                val tagBody = inner.substring(i + 1, close)
                val isEnd = tagBody.startsWith("/")
                val tagName = tagBody.removePrefix("/")
                    .substringBefore(' ')
                    .substringBefore('/')
                    .substringBefore('\t')
                    .substringBefore('\n')
                    .lowercase()
                when (tagName) {
                    "img" -> {
                        if (skipDepth == 0) {
                            val src = Regex("""src\s*=\s*"([^"]*)"""").find(tagBody)?.groupValues?.get(1) ?: ""
                            if (src.isNotEmpty() && !src.startsWith("data:")) {
                                flushText()
                                parts.add(TitlePart.Image(resolveRelativePath(chapterDir, src)))
                            }
                        }
                    }
                    "image" -> {
                        if (skipDepth == 0) {
                            val href = Regex("""(?:xlink:)?href\s*=\s*"([^"]*)"""")
                                .find(tagBody)?.groupValues?.get(1) ?: ""
                            if (href.isNotEmpty() && !href.startsWith("data:")) {
                                flushText()
                                parts.add(TitlePart.Image(resolveRelativePath(chapterDir, href)))
                            }
                        }
                    }
                    "rt", "rp" -> {
                        if (isEnd) {
                            if (skipDepth > 0) skipDepth--
                        } else {
                            // self-closing は無視
                            if (!tagBody.endsWith("/")) skipDepth++
                        }
                    }
                    // それ以外のタグは構造透過 (中のテキストは拾う)
                    else -> { /* no-op */ }
                }
                i = close + 1
            } else {
                // skipDepth > 0 のときは <rt>/<rp> 内なので
                // テキスト本体も丸ごと捨てる (ルビのよみがなを目次に出さない)
                if (skipDepth == 0) {
                    textBuf.append(c)
                }
                i++
            }
        }
        flushText()
        return if (parts.isEmpty()) null else parts
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

    private fun parseNavToc(html: String, basePath: String): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val baseDir = basePath.substringBeforeLast('/', "")

        val linkPattern = Regex("""<a[^>]+href="([^"]*)"[^>]*>([^<]*)</a>""")
        for (match in linkPattern.findAll(html)) {
            val href = resolveHref(baseDir, match.groupValues[1])
            val title = sanitizeTocTitle(match.groupValues[2])
            if (title.isNotEmpty()) {
                entries.add(TocEntry(title, href))
            }
        }
        return entries
    }

    /**
     * 目次タイトルの正規化。
     *
     * 日本の電子書籍では、本文中の外字 (gaiji) <img> に対応する位置に
     * NCX/nav 側で `〓` (U+3013 GETA MARK) や `■` (U+25A0 BLACK SQUARE) を
     * 置いて「ここは活字に無い文字」と示す慣習がある。
     * 表示上はただの黒い四角や横帯になり、ユーザーから見ると文字化けに
     * 見えてしまうため、目次表示用にこれらを取り除く。
     *
     * 取り除いた結果として生じる連続する全角/半角スペースは 1 つに潰す。
     */
    private fun sanitizeTocTitle(raw: String): String {
        if (raw.isEmpty()) return ""
        val stripped = raw
            .replace(Regex("[\u3013\u25A0]+"), "")  // 〓 / ■ を除去
            .replace(Regex("\u3000+"), "\u3000")    // 連続する全角スペースを 1 つに
            .replace(Regex(" +"), " ")              // 連続する半角スペースを 1 つに
            .trim()
        return stripped
    }

    private fun parseNcxToc(data: ByteArray): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val xml = decodeWithDetection(data)
        val parser = newParser()
        parser.setInput(StringReader(xml))

        var inNavPoint = false
        var currentTitle = ""
        var currentHref = ""
        var inText = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "navPoint" -> inNavPoint = true
                        "text" -> if (inNavPoint) inText = true
                        "content" -> if (inNavPoint) {
                            currentHref = parser.getAttributeValue(null, "src") ?: ""
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) currentTitle = parser.text ?: ""
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "text" -> inText = false
                        "navPoint" -> {
                            val cleaned = sanitizeTocTitle(currentTitle)
                            if (cleaned.isNotEmpty()) {
                                entries.add(TocEntry(cleaned, currentHref))
                            }
                            currentTitle = ""
                            currentHref = ""
                            inNavPoint = false
                        }
                    }
                }
            }
        }
        return entries
    }

    private fun newParser(): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newPullParser()
    }

    companion object {
        /**
         * ICU4Jを使ってバイト列のエンコーディングを自動検出し、文字列に変換する。
         * XML/HTML宣言のcharset指定も参照し、CJK文字の正確なデコードを保証する。
         */
        fun decodeWithDetection(data: ByteArray): String {
            // First, check for BOM
            val bomCharset = detectBom(data)
            if (bomCharset != null) {
                return String(data, bomCharset)
            }

            // Check XML/HTML declaration for encoding
            val declaredEncoding = detectDeclaredEncoding(data)
            if (declaredEncoding != null) {
                try {
                    val charset = Charset.forName(declaredEncoding)
                    return String(data, charset)
                } catch (_: Exception) { }
            }

            // Use ICU4J CharsetDetector for automatic detection
            val detector = CharsetDetector()
            detector.setText(data)
            val matches = detector.detectAll()

            if (matches != null && matches.isNotEmpty()) {
                for (match in matches) {
                    if (match.confidence >= 50) {
                        try {
                            val charset = Charset.forName(match.name)
                            return String(data, charset)
                        } catch (_: Exception) { }
                    }
                }
            }

            // Ultimate fallback: UTF-8
            return String(data, Charsets.UTF_8)
        }

        private fun detectBom(data: ByteArray): Charset? {
            if (data.size < 2) return null
            if (data.size >= 3 &&
                data[0] == 0xEF.toByte() &&
                data[1] == 0xBB.toByte() &&
                data[2] == 0xBF.toByte()) {
                return Charsets.UTF_8
            }
            if (data[0] == 0xFE.toByte() && data[1] == 0xFF.toByte()) {
                return Charsets.UTF_16BE
            }
            if (data[0] == 0xFF.toByte() && data[1] == 0xFE.toByte()) {
                return Charsets.UTF_16LE
            }
            return null
        }

        private fun detectDeclaredEncoding(data: ByteArray): String? {
            val head = String(data, 0, minOf(data.size, 1024), Charsets.US_ASCII)

            // XML declaration: <?xml ... encoding="..." ?>
            val xmlMatch = Regex("""encoding\s*=\s*["']([^"']+)["']""").find(head)
            if (xmlMatch != null) return xmlMatch.groupValues[1]

            // HTML meta charset
            val metaMatch = Regex("""<meta[^>]+charset\s*=\s*["']?([^\s"';>]+)""", RegexOption.IGNORE_CASE).find(head)
            if (metaMatch != null) return metaMatch.groupValues[1]

            // HTML meta http-equiv
            val httpEquivMatch = Regex(
                """<meta[^>]+content\s*=\s*["'][^"']*charset=([^\s"';]+)""",
                RegexOption.IGNORE_CASE
            ).find(head)
            if (httpEquivMatch != null) return httpEquivMatch.groupValues[1]

            return null
        }
    }
}
