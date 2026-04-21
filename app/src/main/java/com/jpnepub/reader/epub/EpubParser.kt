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

        val toc = parseToc(manifestItems, opfDir, resources, spine)

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
        resources: Map<String, ByteArray>,
        spine: List<SpineItem>,
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
        val enriched = baseEntries.map { enrichTocEntryWithBodyHeading(it, opfDir, resources) }
        // NCX/nav が平坦で「部」だけを列挙しているが、本文に小タイトルが
        // 独自スタイルで埋め込まれている EPUB (Kadokawa など) のために、
        // 各エントリの間の spine を走査して小見出しを拾い、children に詰める。
        return attachSubheadingChildren(enriched, spine, resources)
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
        return parseHeadingInner(inner, chapterDir)
    }

    /**
     * `<h*>` や `<span>` の innerHTML を走査して TitlePart のリストに変換する。
     * - `<img>` / `<image>` は画像断片化。
     * - `<rt>` / `<rp>` (ルビのよみがな) は中身ごと破棄。
     * - それ以外のタグは構造透過。
     */
    private fun parseHeadingInner(inner: String, chapterDir: String): List<TitlePart>? {
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
                            if (!tagBody.endsWith("/")) skipDepth++
                        }
                    }
                    else -> { /* 構造透過 */ }
                }
                i = close + 1
            } else {
                if (skipDepth == 0) {
                    textBuf.append(c)
                }
                i++
            }
        }
        flushText()
        return if (parts.isEmpty()) null else parts
    }

    private data class RawSubheading(
        val plainTitle: String,
        val parts: List<TitlePart>,
        val fragId: String,
    )

    /**
     * 平坦な TOC に、対応する spine 区間の XHTML 内の小見出しを children として
     * 付ける。
     *
     * 小見出しの検出対象:
     *   (a) 親に使われていない `<h1>`〜`<h6>` 要素 (id 一致で除外)。
     *   (b) Kadokawa 等の商業 EPUB 慣習である
     *       `<p>…<span class="font-1emNN">…</span>…</p>` パターン。
     *       NN は `font-1emNN` クラスの数字で、1.15em〜1.49em の範囲のもののみ
     *       採用する (1.50em 以上は「部」クラスの大見出しで既に親が持っている)。
     *
     * 走査範囲: entry の href が指す spine ファイルから、次の entry の
     * href が指す spine ファイルの直前まで (最大 MAX_SUBHEADING_SCAN_FILES 件)。
     *
     * パフォーマンス対策:
     *   - 各ファイルについて、ICU4J でデコードする前に**バイト列のまま**に
     *     `font-1em`・`<h1..6` のいずれも含まないか高速判定し、該当しなければ
     *     その spine ファイルはスキップする (ICU4J 検出と正規表現スキャンを
     *     丸ごと省ける)。
     *   - spine 件数が多い EPUB でもエントリあたりの走査ファイル数を
     *     MAX_SUBHEADING_SCAN_FILES に制限する。
     */
    private fun attachSubheadingChildren(
        entries: List<TocEntry>,
        spine: List<SpineItem>,
        resources: Map<String, ByteArray>,
    ): List<TocEntry> {
        if (entries.size < 2 || spine.isEmpty()) return entries

        fun matchSpineIdx(rawHref: String): Int {
            val file = rawHref.substringBefore('#')
            if (file.isEmpty()) return -1
            return spine.indexOfFirst { it.href == file || it.href.endsWith(file) }
        }

        // NCX/nav が同じ XHTML ファイルを `#fragment` で既に何度も指している
        // (= そのファイルの中の章はすでに TOC に全部載っている) なら、
        // そのファイルに対して小見出しを探しに行く必要はない。
        // 江戸川乱歩全集のような合本形式では 1 ファイルに 10〜20 の
        // NCX エントリがぶら下がっているため、これを外すと決定的に重い。
        val spineUsage = HashMap<String, Int>()
        for (e in entries) {
            val spIdx = matchSpineIdx(e.href)
            if (spIdx >= 0) {
                val h = spine[spIdx].href
                spineUsage[h] = (spineUsage[h] ?: 0) + 1
            }
        }

        // 同じ spine ファイルを複数エントリが走査区間に入れる場合があるので、
        // デコード + 正規表現スキャン結果をファイル単位で memoize する。
        val scanCache = HashMap<String, List<RawSubheading>>()
        fun scanOrCached(sp: SpineItem): List<RawSubheading> {
            scanCache[sp.href]?.let { return it }
            val data = resources[sp.href] ?: return emptyList<RawSubheading>().also {
                scanCache[sp.href] = it
            }
            if (!bytesMightContainSubheading(data)) {
                return emptyList<RawSubheading>().also { scanCache[sp.href] = it }
            }
            val xhtml = decodeWithDetection(data)
            val chapterDir = sp.href.substringBeforeLast('/', "")
            val result = findSubheadingsInXhtml(xhtml, chapterDir)
            scanCache[sp.href] = result
            return result
        }

        val result = mutableListOf<TocEntry>()
        for (i in entries.indices) {
            val entry = entries[i]
            val nextEntry = entries.getOrNull(i + 1)
            val startIdx = matchSpineIdx(entry.href)
            // 走査範囲の上限 endIdx は以下で決める:
            //   - 次エントリが別ファイルを指す: 次エントリのファイルまで (その直前まで走査)
            //   - 次エントリが同じファイルを指す / 次エントリが無い / 前に戻る:
            //     このエントリの自ファイルだけを見る。遠方のファイルを覗きに行くと、
            //     巻末「自作解説」等の無関係な見出しを全部の子にぶら下げる事故になる。
            val nextStartIdx = nextEntry?.let { matchSpineIdx(it.href) } ?: -1
            val endIdxRaw = if (nextStartIdx > startIdx) nextStartIdx else startIdx + 1
            val endIdx = minOf(endIdxRaw, startIdx + MAX_SUBHEADING_SCAN_FILES)

            if (startIdx < 0 || startIdx >= spine.size) {
                result.add(entry)
                continue
            }

            val parentFragId = entry.href.substringAfter('#', "")
            val subs = mutableListOf<TocEntry>()
            for (idx in startIdx until endIdx) {
                val sp = spine[idx]
                // NCX 側が既にこのファイルを複数回 fragment で分割していれば、
                // 本文を改めて走査せず NCX を信頼する (合本対策)。
                if ((spineUsage[sp.href] ?: 0) >= 2) continue
                val skipFragId = if (idx == startIdx) parentFragId else ""
                val cached = scanOrCached(sp)
                for (sub in cached) {
                    if (skipFragId.isNotEmpty() && sub.fragId == skipFragId) continue
                    if (sub.plainTitle.trim() == entry.title.trim()) continue
                    val prev = subs.lastOrNull()
                    if (prev != null &&
                        prev.title == sub.plainTitle &&
                        prev.href == sp.href) continue
                    val childHref = if (sub.fragId.isNotEmpty()) {
                        "${sp.href}#${sub.fragId}"
                    } else sp.href
                    subs.add(
                        TocEntry(
                            title = sub.plainTitle,
                            href = childHref,
                            titleParts = sub.parts,
                        )
                    )
                }
            }
            result.add(if (subs.isNotEmpty()) entry.copy(children = subs) else entry)
        }
        return result
    }

    /**
     * spine ファイルのバイト列に小見出し候補 (`font-1em`, `<h1>`〜`<h6>`) が
     * いずれも含まれなければ true を返さない。ASCII スライドで十分で、
     * UTF-8 / Shift_JIS / EUC-JP いずれでもこれらのリテラルは同じ 1 バイト列で
     * 出現するためデコード前でよい。
     */
    private fun bytesMightContainSubheading(data: ByteArray): Boolean {
        if (indexOfAscii(data, "font-1em") >= 0) return true
        for (lvl in '1'..'6') {
            if (indexOfAscii(data, "<h$lvl") >= 0) return true
            if (indexOfAscii(data, "<H$lvl") >= 0) return true
        }
        return false
    }

    private fun indexOfAscii(haystack: ByteArray, needle: String): Int {
        if (needle.isEmpty()) return 0
        val n = needle.length
        val h = haystack.size
        if (h < n) return -1
        val first = needle[0].code.toByte()
        var i = 0
        while (i <= h - n) {
            if (haystack[i] == first) {
                var ok = true
                var j = 1
                while (j < n) {
                    if (haystack[i + j] != needle[j].code.toByte()) { ok = false; break }
                    j++
                }
                if (ok) return i
            }
            i++
        }
        return -1
    }

    /**
     * XHTML 全体を 1 回だけ走査して、取りうる小見出し候補をすべて返す。
     * 親 TOC エントリとの突き合わせ (id スキップ・重複除去) は呼び側で行う。
     * 結果は `attachSubheadingChildren` の `scanCache` で memoize される。
     */
    private fun findSubheadingsInXhtml(
        xhtml: String,
        chapterDir: String,
    ): List<RawSubheading> {
        val out = mutableListOf<RawSubheading>()
        val seen = HashSet<String>()

        // 本の中の「目次ページ」は `<p><a href="..."><span class="font-1emNN">タイトル</span></a></p>`
        // のように、本物の章 h タグと見分けが付かないことがある。そこから小見出しを拾うと、
        // 子エントリ全部が「目次ページ自身」へのリンクに化けて、タップしても正しい章へ飛ばない。
        // 判定ヒントとして:
        //   1. `<body class="p-toc">` / "p-mokuji" / "p-contents" のような body クラス
        //   2. `<body>` 内の `<a href="...">` のうち `#fragment` 付きリンクが 4 本以上ある、
        //      かつ半数以上が「今読んでいるファイル以外」を指している
        // のどちらかに該当すれば、このファイルからの小見出し抽出は丸ごと諦める。
        val bodyClassRe = Regex("""<body\b[^>]*class\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val bodyClass = bodyClassRe.find(xhtml)?.groupValues?.get(1)?.lowercase() ?: ""
        val looksLikeTocByClass = bodyClass.contains("p-toc") ||
            bodyClass.contains("p-mokuji") ||
            bodyClass.contains("p-contents") ||
            bodyClass.contains("mokuji") ||
            bodyClass.contains("toc")
        if (looksLikeTocByClass) return out
        val aHrefRe = Regex("""<a\b[^>]*href\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        var totalLinks = 0
        var externalFragLinks = 0
        for (m in aHrefRe.findAll(xhtml)) {
            val h = m.groupValues[1]
            if (h.startsWith("#")) continue
            if (h.startsWith("http:") || h.startsWith("https:") || h.startsWith("mailto:")) continue
            totalLinks++
            if (h.contains('#')) externalFragLinks++
        }
        if (externalFragLinks >= 4 && externalFragLinks * 2 >= totalLinks) {
            // 本の目次ページと判断。本文由来の小見出しは 0 件で返す。
            return out
        }

        fun tryAdd(id: String, parts: List<TitlePart>) {
            val plain = buildString {
                for (p in parts) if (p is TitlePart.Text) append(p.text)
            }.trim()
            if (plain.isEmpty() || plain.length > 60) return
            // 同一ページ内で同じ文字列の見出しは 1 つだけに畳む
            if (!seen.add(plain)) return
            out.add(RawSubheading(plain, parts, id))
        }

        // (a) <h1>〜<h6>
        val headingRe = Regex(
            """<(h[1-6])\b([^>]*)>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE
        )
        for (m in headingRe.findAll(xhtml)) {
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val id = extractAttr(attrs, "id")
            val parts = parseHeadingInner(inner, chapterDir) ?: continue
            tryAdd(id, parts)
        }

        // (b) <p>…<span class="font-1emNN">title</span>…</p>
        //   1.15em〜1.49em の範囲のもののみ採用。部レベル (1.50em 以上) は
        //   既に親 TOC エントリが持っているはずなのでここでは拾わない。
        //   軽く事前フィルタ: 文書全体に `font-1em` が 1 回も出てこなければ
        //   パターン (b) の正規表現スキャン自体をまるごと省略する。
        if (xhtml.contains("font-1em")) {
            val pRe = Regex(
                """<p\b([^>]*)>([\s\S]*?)</p>""",
                RegexOption.IGNORE_CASE
            )
            val spanSizeRe = Regex(
                """<span\b([^>]*?class\s*=\s*"[^"]*\bfont-1em(\d{1,2})\b[^"]*"[^>]*)>([\s\S]*?)</span>""",
                RegexOption.IGNORE_CASE
            )
            for (pMatch in pRe.findAll(xhtml)) {
                val pAttrs = pMatch.groupValues[1]
                val pInner = pMatch.groupValues[2]
                if (!pInner.contains("font-1em")) continue
                // `<p>...<a href="...">...</a>...</p>` のように外向きリンクを含む段落は、
                // 目次ページ側のリンクか脚注バックリンク等の可能性が高い。
                // その `<p>` 由来の subheading は採らない (正しい章には NCX 側の本エントリで飛ぶ)。
                if (pInner.contains("<a ", ignoreCase = true) ||
                    pInner.contains("<a\t", ignoreCase = true) ||
                    pInner.contains("<a\n", ignoreCase = true)
                ) {
                    continue
                }
                val pId = extractAttr(pAttrs, "id")
                val sMatch = spanSizeRe.find(pInner) ?: continue
                val emDigits = sMatch.groupValues[2]
                val emNum = emDigits.padEnd(2, '0').toIntOrNull() ?: continue
                // emNum 15 → 1.15em, 20 → 1.20em, ..., 50 → 1.50em
                if (emNum < 15 || emNum >= 50) continue
                val inner = sMatch.groupValues[3]
                val parts = parseHeadingInner(inner, chapterDir) ?: continue
                tryAdd(pId, parts)
            }
        }

        return out
    }

    private fun extractAttr(attrs: String, name: String): String {
        val r = Regex(
            """\b$name\s*=\s*"([^"]*)"""",
            RegexOption.IGNORE_CASE
        )
        return r.find(attrs)?.groupValues?.get(1) ?: ""
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
         * 1 つの TOC エントリあたりに小見出しを探して走査する spine ファイル数の
         * 上限。spine が何百もある電子書籍で、TOC 木生成だけで数十秒かかる
         * ような事態を避けるための安全弁。
         */
        private const val MAX_SUBHEADING_SCAN_FILES = 6

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
