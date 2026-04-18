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
    val children: List<TocEntry> = emptyList()
)

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
        // Try EPUB3 nav first
        for ((_, pair) in manifestItems) {
            val (href, mediaType) = pair
            if (mediaType == "application/xhtml+xml") {
                val fullPath = resolveHref(opfDir, href)
                val data = resources[fullPath] ?: continue
                val text = decodeWithDetection(data)
                if (text.contains("<nav") && text.contains("epub:type=\"toc\"")) {
                    return parseNavToc(text, fullPath)
                }
            }
        }

        // Fall back to EPUB2 NCX
        val ncxEntry = manifestItems.entries.find { it.value.second == "application/x-dtbncx+xml" }
        if (ncxEntry != null) {
            val fullPath = resolveHref(opfDir, ncxEntry.value.first)
            val data = resources[fullPath] ?: return emptyList()
            return parseNcxToc(data)
        }

        return emptyList()
    }

    private fun parseNavToc(html: String, basePath: String): List<TocEntry> {
        val entries = mutableListOf<TocEntry>()
        val baseDir = basePath.substringBeforeLast('/', "")

        val linkPattern = Regex("""<a[^>]+href="([^"]*)"[^>]*>([^<]*)</a>""")
        for (match in linkPattern.findAll(html)) {
            val href = resolveHref(baseDir, match.groupValues[1])
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                entries.add(TocEntry(title, href))
            }
        }
        return entries
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
                    if (inText) currentTitle = parser.text?.trim() ?: ""
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "text" -> inText = false
                        "navPoint" -> {
                            if (currentTitle.isNotEmpty()) {
                                entries.add(TocEntry(currentTitle, currentHref))
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
