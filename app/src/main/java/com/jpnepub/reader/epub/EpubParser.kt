package com.jpnepub.reader.epub

import android.content.Context
import android.net.Uri
import com.ibm.icu.text.CharsetDetector
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.*
import java.nio.charset.Charset
import java.util.zip.ZipFile

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
        val tmp = File.createTempFile("jpnepub_", ".epub", context.cacheDir)
        try {
            // content:// を一旦ローカルへ退避し、ZipFile で中央ディレクトリから読む。
            // 一部 provider で ZipInputStream 直読みだとエントリ欠落が起きるため。
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { out -> input.copyTo(out) }
            } ?: throw IOException("Cannot open EPUB file")

            ZipFile(tmp).use { zip ->
                val en = zip.entries()
                while (en.hasMoreElements()) {
                    val entry = en.nextElement()
                    if (entry.isDirectory) continue
                    zip.getInputStream(entry).use { ins ->
                        resources[normalizePath(entry.name)] = ins.readBytes()
                    }
                }
            }
        } finally {
            tmp.delete()
        }

        val containerXml = resources["META-INF/container.xml"]
            ?: throw IOException("Invalid EPUB: missing container.xml")
        val rootFilePath = parseContainer(containerXml)
        val resolvedOpfPath = resolveResourcePath(rootFilePath, resources)
            ?: throw IOException("Invalid EPUB: missing OPF at $rootFilePath")
        val opfData = resources[resolvedOpfPath]
            ?: throw IOException("Invalid EPUB: missing OPF at $rootFilePath")
        val opfDir = resolvedOpfPath.substringBeforeLast('/', "")

        return parseOpf(opfData, opfDir, resources)
    }

    fun parse(file: File): EpubBook {
        return parse(Uri.fromFile(file))
    }

    private fun normalizePath(path: String): String {
        return path.replace('\\', '/').removePrefix("/").removePrefix("./")
    }

    /**
     * `container.xml` の full-path と ZIP エントリキーのゆらぎを吸収する。
     * - 区切り文字 (`\` / `/`)
     * - 先頭 `./` / `/`
     * - 大文字小文字差
     */
    private fun resolveResourcePath(path: String, resources: Map<String, ByteArray>): String? {
        val normalized = normalizePath(path).trim()
        if (normalized.isEmpty()) return null

        resources[normalized]?.let { return normalized }
        resources.keys.firstOrNull { it.equals(normalized, ignoreCase = true) }?.let { return it }

        // 一部 EPUB では rootfile 側が相対差分を含むことがあるため、
        // 最後のパス断片一致でもフォールバック探索する。
        val tail = normalized.substringAfterLast('/')
        resources.keys.firstOrNull { it.substringAfterLast('/').equals(tail, ignoreCase = true) }?.let {
            return it
        }
        return null
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
                val raw = parser.getAttributeValue(null, "full-path")
                    ?: throw IOException("Invalid container.xml")
                return normalizePath(raw)
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
        /** 見出しレベル。h1=1, h2=2, ..., h6=6。font-1em 小見出しは 2。 */
        val level: Int,
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
            val result = findSubheadingsInXhtml(xhtml, chapterDir, sp.href)
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
            // 次のエントリが同じファイルを指していて fragId を持つ場合、
            // 子の範囲は parentFragId 〜 nextEntrySameFileFragId に限定する。
            val nextEntrySameFileFragId = if (
                nextEntry != null && nextStartIdx == startIdx
            ) {
                nextEntry.href.substringAfter('#', "")
            } else ""

            val subs = mutableListOf<TocEntry>()
            var bestFragId = parentFragId
            for (idx in startIdx until endIdx) {
                val sp = spine[idx]
                val cached = scanOrCached(sp)

                // NCX 側が既にこのファイルを複数回 fragment で分割している場合
                // (spineUsage >= 2)、文書順で「自分の parentFragId の直後から
                // 次のエントリの fragId の前まで」だけを子に含める。
                val isHeavilyFragmented = (spineUsage[sp.href] ?: 0) >= 2

                // この sp 内で走査する RawSubheading の範囲 [subStart, subEnd)
                val subStart: Int
                val subEnd: Int
                val skipFragId: String
                if (isHeavilyFragmented && idx == startIdx) {
                    // 文書順で自分の親 fragId が出現した位置の直後から、
                    // 次の親 fragId が出現する直前までが「自分の子」。
                    val parentIdx = if (parentFragId.isNotEmpty()) {
                        val p = cached.indexOfFirst { it.fragId == parentFragId }
                        if (p < 0) 0 else p + 1
                    } else 0
                    val nextIdx = if (nextEntrySameFileFragId.isNotEmpty()) {
                        val n = cached.indexOfFirst { it.fragId == nextEntrySameFileFragId }
                        if (n < 0) cached.size else n
                    } else cached.size
                    subStart = parentIdx
                    subEnd = nextIdx
                    skipFragId = ""
                } else if (isHeavilyFragmented) {
                    // startIdx 以外の sp は走査しない (同じファイルの別エントリの守備範囲)
                    subStart = 0
                    subEnd = 0
                    skipFragId = ""
                } else {
                    subStart = 0
                    subEnd = cached.size
                    skipFragId = if (idx == startIdx) parentFragId else ""
                }

                for (j in subStart until subEnd) {
                    val sub = cached[j]
                    // 親と同じ fragId はスキップ
                    if (skipFragId.isNotEmpty() && sub.fragId == skipFragId) continue
                    // 親と同じタイトルはスキップ
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

                    // 親の href に fragId が無い場合の補正候補 (最初の子の fragId)
                    if (bestFragId.isEmpty() && sub.fragId.isNotEmpty()) {
                        bestFragId = sub.fragId
                    }
                }
            }

            // 親の href を補正 (フラグメントが欠けていた場合)
            val correctedEntry = if (bestFragId.isNotEmpty() && parentFragId.isEmpty()) {
                val baseHref = entry.href.substringBefore('#')
                entry.copy(href = "$baseHref#$bestFragId")
            } else entry

            result.add(if (subs.isNotEmpty()) correctedEntry.copy(children = subs) else correctedEntry)
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
        href: String,
    ): List<RawSubheading> {
        val anchorIdPrefix = href
            .replace('/', '_')
            .replace('\\', '_')
            .replace('.', '_')
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

        fun tryAdd(id: String, parts: List<TitlePart>, level: Int) {
            val plain = buildString {
                for (p in parts) if (p is TitlePart.Text) append(p.text)
            }.trim()
            if (plain.isEmpty() || plain.length > 60) return
            // 同一ファイル内で同じ文字列・同じ fragId の見出しのみ畳む。
            // タイトルが同じでも fragId が異なれば別エントリとして残す
            // (同じファイルに「一」「二」等が複数回現れるケース)。
            val key = "$plain|$id"
            if (!seen.add(key)) return
            out.add(RawSubheading(plain, parts, id, level))
        }

        // (a) <h1>〜<h6>
        //   id="..." を持たない見出し (`<h3 class="gfont1">一</h3>` のような
        //   節番号ヘッダ) はそのままでは章内ジャンプできないため、文書内の
        //   h* 出現順で合成 id `__h<N>` を付ける。ContentExtractor 側は同順で
        //   `ContentNode.Anchor("__h<N>")` を emit するため、両者が必ず一致する。
        val headingRe = Regex(
            """<(h[1-6])\b([^>]*)>([\s\S]*?)</\1>""",
            RegexOption.IGNORE_CASE
        )
        var hOrdinal = 0
        for (m in headingRe.findAll(xhtml)) {
            val ord = hOrdinal++
            val tagName = m.groupValues[1]
            val attrs = m.groupValues[2]
            val inner = m.groupValues[3]
            val explicitId = extractAttr(attrs, "id")
            val id = if (explicitId.isNotEmpty()) explicitId else "__${anchorIdPrefix}_h$ord"
            val parts = parseHeadingInner(inner, chapterDir) ?: continue
            val level = tagName[1].digitToIntOrNull() ?: 1
            tryAdd(id, parts, level)
        }

        // (b) <p class="font-1emNN">title</p> または
        //     <p>…<span class="font-1emNN">title</span>…</p>
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
            // 出現順に「font-1em 小見出し候補」の連番を振り、id が無い小見出しにも
            // 合成 fragId `__ps<N>` を割り当てる。ContentExtractor 側では同じ規則で
            // ContentNode.Anchor を emit するため、小タイトルから章内の正しいページへ
            // ジャンプできる。<a> 絡みの `<p>` は TOC エントリ化はしないが、順序だけは
            // ContentExtractor と合わせるためカウンタは必ず消費する。
            var pOrdinal = 0
            for (pMatch in pRe.findAll(xhtml)) {
                val pAttrs = pMatch.groupValues[1]
                val pInner = pMatch.groupValues[2]

                // <p> 自体に font-1emNN クラスを持つパターンを先に判定。
                // <p class="font-1em30">頬に傷のある男</p> 等。
                val pClass = extractAttr(pAttrs, "class")
                val pEmNum = extractFontEmNum(pClass)
                if (pEmNum != null && pEmNum in 15..49) {
                    val ord = pOrdinal++
                    if (pInner.contains("<a ", ignoreCase = true) ||
                        pInner.contains("<a\t", ignoreCase = true) ||
                        pInner.contains("<a\n", ignoreCase = true)
                    ) {
                        continue
                    }
                    val explicitPId = extractAttr(pAttrs, "id")
                    val id = if (explicitPId.isNotEmpty()) explicitPId else "__${anchorIdPrefix}_ps$ord"
                    val parts = parseHeadingInner(pInner, chapterDir) ?: continue
                    tryAdd(id, parts, level = 2)
                    continue
                }

                if (!pInner.contains("font-1em")) continue
                val sMatch = spanSizeRe.find(pInner) ?: continue
                val emDigits = sMatch.groupValues[2]
                val emNum = emDigits.padEnd(2, '0').toIntOrNull() ?: continue
                // emNum 15 → 1.15em, 20 → 1.20em, ..., 50 → 1.50em
                if (emNum < 15 || emNum >= 50) continue

                val ord = pOrdinal++

                // `<p>...<a href="...">...</a>...</p>` のように外向きリンクを含む段落は、
                // 目次ページ側のリンクか脚注バックリンク等の可能性が高い。
                // その `<p>` 由来の subheading は採らない (正しい章には NCX 側の本エントリで飛ぶ)。
                if (pInner.contains("<a ", ignoreCase = true) ||
                    pInner.contains("<a\t", ignoreCase = true) ||
                    pInner.contains("<a\n", ignoreCase = true)
                ) {
                    continue
                }
                val explicitPId = extractAttr(pAttrs, "id")
                val explicitSpanId = extractAttr(sMatch.groupValues[1], "id")
                val id = when {
                    explicitPId.isNotEmpty() -> explicitPId
                    explicitSpanId.isNotEmpty() -> explicitSpanId
                    else -> "__${anchorIdPrefix}_ps$ord"
                }
                val inner = sMatch.groupValues[3]
                val parts = parseHeadingInner(inner, chapterDir) ?: continue
                tryAdd(id, parts, level = 2)
            }
        }

        return out
    }

    /**
     * `class="... font-1emNN ..."` から NN (1 桁または 2 桁) を取り出して
     * 2 桁ゼロ埋めの整数で返す。ContentExtractor.extractFontEmNum と同じ規則。
     * 例: `font-1em2` → 20, `font-1em20` → 20, `font-1em50` → 50。
     */
    private fun extractFontEmNum(cssClass: String): Int? {
        val m = Regex("""\bfont-1em(\d{1,2})""").find(cssClass) ?: return null
        return m.groupValues[1].padEnd(2, '0').toIntOrNull()
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

        // EPUB3 の nav.xhtml には `<nav epub:type="toc">` のほか
        // `<nav epub:type="page-list">` (ページ番号一覧) や
        // `<nav epub:type="landmarks">` (Cover/Beginning 等) が並ぶ。
        // html 全体から <a> を拾うとページ番号まで目次に混入してしまうので、
        // epub:type="toc" の <nav> 要素の内側だけをスキャン対象にする。
        val tocBlock = extractTocNavBlock(html) ?: html
        val linkPattern = Regex("""<a[^>]+href="([^"]*)"[^>]*>([\s\S]*?)</a>""")
        for (match in linkPattern.findAll(tocBlock)) {
            val href = resolveHref(baseDir, match.groupValues[1])
            // <a> 内に <span> や画像断片を許容するため、まず HTML タグ/実体参照を剥がしてから正規化する
            val inner = match.groupValues[2]
                .replace(Regex("<[^>]+>"), "")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
            val title = sanitizeTocTitle(inner)
            if (title.isNotEmpty()) {
                entries.add(TocEntry(title, href))
            }
        }
        return entries
    }

    /**
     * HTML から `<nav ... epub:type="toc" ...>...</nav>` の内側だけを切り出す。
     * 見つからなければ null を返し、呼び出し側で HTML 全体にフォールバックさせる。
     */
    private fun extractTocNavBlock(html: String): String? {
        // <nav ...> を先頭から順に見て、epub:type="toc" を含むものを採用する。
        val navOpenRe = Regex("""<nav\b([^>]*)>""", RegexOption.IGNORE_CASE)
        var searchFrom = 0
        while (true) {
            val open = navOpenRe.find(html, searchFrom) ?: return null
            val attrs = open.groupValues[1]
            val isToc = Regex(
                """epub:type\s*=\s*"[^"]*\btoc\b[^"]*"""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(attrs)
            // epub:type="landmarks" / "page-list" 等も `toc` を部分文字列として
            // 含みうるので単純な contains("toc") ではなく語境界で判定する。
            if (isToc && !Regex(
                    """epub:type\s*=\s*"[^"]*\b(landmarks|page-list)\b[^"]*"""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(attrs)
            ) {
                val bodyStart = open.range.last + 1
                val closeIdx = html.indexOf("</nav>", bodyStart, ignoreCase = true)
                return if (closeIdx < 0) html.substring(bodyStart) else html.substring(bodyStart, closeIdx)
            }
            searchFrom = open.range.last + 1
        }
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
         *
         * XML宣言の `encoding` は信頼するが、宣言値でデコードした結果が明らかな
         * 文字化け（連続する U+FFFD や制御文字）の場合は、ICU4J 検出結果に
         * フォールバックする。実際のバイト列と宣言が不一致の EPUB (Shift_JIS
         * ファイルを utf-8 と宣言している等) を防ぐ。
         */
        fun decodeWithDetection(data: ByteArray): String {
            // First, check for BOM
            val bomCharset = detectBom(data)
            if (bomCharset != null) {
                return String(data, bomCharset)
            }

            // Try declared encoding, but validate the result
            val declaredEncoding = detectDeclaredEncoding(data)
            if (declaredEncoding != null) {
                try {
                    val charset = Charset.forName(declaredEncoding)
                    val decoded = String(data, charset)
                    if (!looksLikeMojibake(decoded)) {
                        return decoded
                    }
                    // Declared encoding produced mojibake; fall through to detection
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
                            val decoded = String(data, charset)
                            if (!looksLikeMojibake(decoded)) {
                                return decoded
                            }
                        } catch (_: Exception) { }
                    }
                }
            }

            // Ultimate fallback: UTF-8
            return String(data, Charsets.UTF_8)
        }

        /**
         * デコード結果が文字化けかどうかを簡易判定する。
         * 連続する U+FFFD (replacement character) や、多量の制御文字が含まれる
         * 場合に true を返す。
         */
        private fun looksLikeMojibake(text: String): Boolean {
            var replacementCount = 0
            var controlCount = 0
            for (c in text) {
                when {
                    c == '\uFFFD' -> replacementCount++
                    c.code in 0x0001..0x001F && c !in "\t\n\r" -> controlCount++
                }
            }
            // 短い文字列でも 1 個の U+FFFD は怪しい。長い文字列では相対的に判断。
            return replacementCount >= 2 ||
                (replacementCount >= 1 && text.length < 200) ||
                controlCount >= 5
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
