# In-Book TOC Page Chapter Links — Detailed Technical Explanation

## External-Fragment TOC Pages (Fujimi Shinsōban Pattern)

---

## 1. The Problem

### 1.1 Symptom

On **Full Metal Panic! — Fighting Boy Meets Girl (Shinsōban)** and similar Fujimi Fantasy Bunko reprint EPUBs, opening **Settings → Table of contents** showed only three top-level entries:

| # | Title (nav) | href |
|---|-------------|------|
| 1 | Cover | `c9.xhtml` |
| 2 | Table of contents | `c2S.xhtml` |
| 3 | Colophon | `c3.xhtml` |

The **chapter titles** (e.g. *Prologue*, *Chapter 1*, *Chapter 2*, …) did **not** appear as children under “Table of contents”, even though they are visible on the in-book TOC page when reading the book.

Tapping “Table of contents” opened the correct XHTML page, but the dialog offered no way to jump directly to individual chapters.

### 1.2 What the user expected

The TOC dialog should list chapter titles as **indented children** under the nav entry that points at the in-book TOC spine file (`c2S.xhtml`), and each child should navigate to the correct chapter XHTML + fragment anchor (e.g. `c50.xhtml#aCSR`).

### 1.3 Reference EPUB

```
01フルメタル・パニック！戦うボーイ・ミーツ・ガール(新装版) フルメタル・パニック！(新装版) (富士見ファンタジア文庫).epub
```

---

## 2. Root Cause Analysis

### 2.1 EPUB structure (this title)

| Component | Content |
|-----------|---------|
| **nav / NCX** | Only 3 entries: cover, in-book TOC page, colophon |
| **spine** | 39 body XHTML files (actual chapters) |
| **Chapter titles** | Exist **only** inside `OEBPS/c2S.xhtml` as `<a href="c50.xhtml#aCSR">…</a>` links |
| **Body chapters** | Almost no `<h1>`–`<h6>`; almost no `font-1emNN` subheading pattern |

The publisher put the **real chapter list in the in-book TOC XHTML**, not in `nav.xhtml` / `toc.ncx`.

### 2.2 Why the old parser showed nothing

`EpubParser.attachSubheadingChildren()` scans spine XHTML files referenced by nav/NCX parents and calls `findSubheadingsInXhtml()` to collect `RawSubheading` candidates.

For `c2S.xhtml`, the old logic detected:

- Many `<a href="other.xhtml#anchor">` links (external fragment links)
- **No** usable `<h1>`–`<h6>` or `font-1emNN` span subheadings

Since **v1.06**, `findSubheadingsInXhtml()` contained an intentional guard:

> If `externalFragLinks >= 4` **and** at least half of all links are external-fragment links → treat file as an **in-book TOC page** → return **empty list** (do not pollute TOC with bogus children).

That guard fixed **v1.05-era pollution** where `font-1emNN` links inside TOC pages were mistaken for chapter subheadings and produced children that all pointed back at the TOC page itself.

For Fujimi Shinsōban EPUBs, the **same heuristic** correctly identified `c2S.xhtml` as a TOC page — but then **discarded all chapter information** instead of **parsing the links as the chapter list**.

### 2.3 Why `resolvedHref` was missing

Even if subheadings had been parsed, `RawSubheading` only stored `fragId` within the **current** spine file. Child hrefs were built as:

```kotlin
"${sp.href}#${sub.fragId}"   // e.g. c2S.xhtml#aCSR  — WRONG
```

Chapter links on the TOC page point to **other** files (`c50.xhtml#aCSR`). Without a resolved cross-file href, navigation would fail.

### 2.4 Why the byte pre-scan skipped the TOC file

`scanOrCached()` skipped ICU decode unless `bytesMightContainSubheading()` found `font-1em` or `<h1`…`<h6` in the raw bytes.

`c2S.xhtml` has **no** such markers → the file was never decoded → `findSubheadingsInXhtml()` never ran.

---

## 3. Design Goals

1. **Parse** in-book TOC pages whose chapter list is a dense grid of `other.xhtml#anchor` links.
2. **Preserve** v1.06 protection for `p-toc` / `font-1emNN`-style TOC pages (Kadokawa / bundled-volume pattern).
3. **Preserve** existing paths: nav-complete books, `font-1em` subheadings, heavily fragmented NCX (`spineUsage >= 2`), manga.
4. **Minimal decode cost**: only enter the new path when byte-level hints suggest TOC-page links.

---

## 4. The Fix (Overview)

| Change | Purpose |
|--------|---------|
| `RawSubheading.resolvedHref` | Store fully resolved `OEBPS/c50.xhtml#aCSR` for cross-file links |
| `bytesMightContainTocPageLinks()` | Pre-scan: ≥4 occurrences of `.xhtml#` in raw bytes |
| `scanOrCached()` guard | Decode if **either** subheading markers **or** TOC link pattern present |
| `parseTocPageChapterLinks()` | Parse `<a href="file.xhtml#id">title</a>` into `RawSubheading` list |
| `findSubheadingsInXhtml()` reorder | **External-link TOC** → parse links; **class-based TOC** → still skip |
| `attachSubheadingChildren()` child href | Prefer `resolvedHref` over `spineHref#fragId` |

**Activation path:** Only when a nav parent entry’s scan window includes the in-book TOC spine file (typically the single “目次” / “Table of contents” entry).

---

## 5. Files Modified

```
app/src/main/java/com/jpnepub/reader/epub/EpubParser.kt
```

`ReaderActivity.kt` (`flattenToc()`, `showTocDialog()`) — **no changes required**. Children attach under the parent `TocEntry` and display with indentation as before.

---

## 6. Added and Modified Code (Full Text)

### 6.1 `RawSubheading` — new field

```kotlin
    private data class RawSubheading(
        val plainTitle: String,
        val parts: List<TitlePart>,
        val fragId: String,
        /** 見出しレベル。h1=1, h2=2, ..., h6=6。font-1em 小見出しは 2。 */
        val level: Int,
        /** 目次ページの `<a href="other.xhtml#id">` など、別ファイルを指す解決済み href。 */
        val resolvedHref: String? = null,
    )
```

**Meaning:** When a subheading comes from an in-book TOC link, `fragId` alone is insufficient. `resolvedHref` holds the OPF-relative path after `resolveRelativePath()` (e.g. `OEBPS/c50.xhtml#aCSR`). Default `null` keeps all existing subheading types unchanged.

---

### 6.2 `scanOrCached()` — extended pre-scan gate

**Before:**

```kotlin
            if (!bytesMightContainSubheading(data)) {
                return emptyList<RawSubheading>().also { scanCache[sp.href] = it }
            }
```

**After:**

```kotlin
            if (!bytesMightContainSubheading(data) && !bytesMightContainTocPageLinks(data)) {
                return emptyList<RawSubheading>().also { scanCache[sp.href] = it }
            }
```

**Meaning:** Files like `c2S.xhtml` lack `font-1em` / `<h*>` but contain many `.xhtml#` literals. They now pass the cheap byte gate and proceed to decode + `findSubheadingsInXhtml()`.

---

### 6.3 `attachSubheadingChildren()` — child href resolution

**Before:**

```kotlin
                    val prev = subs.lastOrNull()
                    if (prev != null &&
                        prev.title == sub.plainTitle &&
                        prev.href == sp.href) continue
                    val childHref = if (sub.fragId.isNotEmpty()) {
                        "${sp.href}#${sub.fragId}"
                    } else sp.href
```

**After:**

```kotlin
                    val childHref = sub.resolvedHref ?: if (sub.fragId.isNotEmpty()) {
                        "${sp.href}#${sub.fragId}"
                    } else {
                        sp.href
                    }
                    val prev = subs.lastOrNull()
                    if (prev != null &&
                        prev.title == sub.plainTitle &&
                        prev.href == childHref) continue
```

**Meaning:**

- TOC-page children use `resolvedHref` (cross-file).
- Same-file subheadings unchanged (`sp.href#fragId`).
- Deduplication now compares the **actual** navigation target (`childHref`), not only `sp.href` — prevents dropping distinct chapters that share a title pattern.

---

### 6.4 `bytesMightContainTocPageLinks()` — new function (full)

```kotlin
    /** 富士見新装版など、目次 XHTML 内の `other.xhtml#anchor` リンクから章名を拾う候補。 */
    private fun bytesMightContainTocPageLinks(data: ByteArray): Boolean {
        var count = 0
        var from = 0
        while (count < 4) {
            val idx = indexOfAsciiFrom(data, ".xhtml#", from)
            if (idx < 0) break
            count++
            from = idx + 1
        }
        return count >= 4
    }
```

**Meaning:** ASCII search on raw bytes (no ICU decode). Stops after finding 4 matches for efficiency. The string `.xhtml#` is identical in UTF-8, Shift_JIS, and EUC-JP for ASCII-range content. Threshold `>= 4` matches the runtime heuristic in `findSubheadingsInXhtml()`.

---

### 6.5 `indexOfAsciiFrom()` — new helper (full)

```kotlin
    private fun indexOfAsciiFrom(haystack: ByteArray, needle: String, start: Int): Int {
        if (needle.isEmpty()) return start.coerceAtMost(haystack.size)
        val n = needle.length
        val h = haystack.size
        if (h < n || start > h - n) return -1
        val first = needle[0].code.toByte()
        var i = start.coerceAtLeast(0)
        while (i <= h - n) {
            if (haystack[i] == first) {
                var ok = true
                var j = 1
                while (j < n) {
                    if (haystack[i + j] != needle[j].code.toByte()) {
                        ok = false
                        break
                    }
                    j++
                }
                if (ok) return i
            }
            i++
        }
        return -1
    }
```

**Meaning:** Same algorithm as existing `indexOfAscii()` but with a `start` offset so `bytesMightContainTocPageLinks()` can count non-overlapping occurrences without re-scanning from index 0 each time.

---

### 6.6 `parseTocPageChapterLinks()` — new function (full)

```kotlin
    /**
     * 目次 XHTML 内の `<a href="chapter.xhtml#anchor">章名</a>` から章 TOC を構築する。
     */
    private fun parseTocPageChapterLinks(
        xhtml: String,
        chapterDir: String,
    ): List<RawSubheading> {
        val linkPattern = Regex(
            """<a\b[^>]*href\s*=\s*"([^"]+)"[^>]*>([\s\S]*?)</a>""",
            RegexOption.IGNORE_CASE
        )
        val out = mutableListOf<RawSubheading>()
        val seen = HashSet<String>()
        for (match in linkPattern.findAll(xhtml)) {
            val rawHref = match.groupValues[1].trim()
            if (!rawHref.contains('#') || rawHref.startsWith("#")) continue
            if (rawHref.startsWith("http:") || rawHref.startsWith("https:") ||
                rawHref.startsWith("mailto:")
            ) {
                continue
            }
            val resolved = resolveRelativePath(chapterDir, rawHref)
            val parts = parseHeadingInner(match.groupValues[2], chapterDir) ?: continue
            val plain = buildString {
                for (p in parts) if (p is TitlePart.Text) append(p.text)
            }.trim()
            if (plain.isEmpty() || plain.length > 80) continue
            val fragId = rawHref.substringAfter('#', "")
            val key = "$resolved|$plain"
            if (!seen.add(key)) continue
            out.add(
                RawSubheading(
                    plainTitle = plain,
                    parts = parts,
                    fragId = fragId,
                    level = 2,
                    resolvedHref = resolved,
                )
            )
        }
        return out
    }
```

**Meaning:**

| Step | Behavior |
|------|----------|
| Regex | Match `<a href="…">…</a>` (non-greedy inner HTML) |
| Filter href | Must be `file.xhtml#id`; skip `#only`, `http(s):`, `mailto:` |
| `resolveRelativePath` | Turn relative href into OPF path from `chapterDir` |
| `parseHeadingInner` | Reuse existing title/rubi/image parsing for link text |
| Title length | Skip empty or >80 chars (noise / boilerplate) |
| Dedup key | `resolved\|plain` — same target + title once |
| `level = 2` | Display as sub-entry (same as `font-1em` subheadings) |
| `resolvedHref` | Full navigation target for `attachSubheadingChildren()` |

---

### 6.7 `findSubheadingsInXhtml()` — branch reorder (full changed section)

**Before (simplified):**

```kotlin
        // body class p-toc / mokuji → return empty FIRST
        if (looksLikeTocByClass) return out

        // count external fragment links
        if (externalFragLinks >= 4 && externalFragLinks * 2 >= totalLinks) {
            return out   // ← intentional skip (v1.06)
        }

        // ... h1-h6, font-1em scanning ...
```

**After (full replacement at start of function body):**

```kotlin
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
            return parseTocPageChapterLinks(xhtml, chapterDir)
        }

        // 本の中の「目次ページ」は `<p><a href="..."><span class="font-1emNN">タイトル</span></a></p>`
        // のように、本物の章 h タグと見分けが付かないことがある。そこから小見出しを拾うと、
        // 子エントリ全部が「目次ページ自身」へのリンクに化けて、タップしても正しい章へ飛ばない。
        val bodyClassRe = Regex("""<body\b[^>]*class\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
        val bodyClass = bodyClassRe.find(xhtml)?.groupValues?.get(1)?.lowercase() ?: ""
        val looksLikeTocByClass = bodyClass.contains("p-toc") ||
            bodyClass.contains("p-mokuji") ||
            bodyClass.contains("p-contents") ||
            bodyClass.contains("mokuji") ||
            bodyClass.contains("toc")
        if (looksLikeTocByClass) return out

        // ... unchanged h1-h6, font-1em scanning continues below ...
```

**Meaning:**

| Pattern | `body` class | Link shape | Old result | New result |
|---------|--------------|------------|------------|------------|
| Fujimi Shinsōban (`c2S.xhtml`) | Not `p-toc` | Many `other.xhtml#` | Empty | **Chapter list from links** |
| Kadokawa / bundled `p-toc` + `font-1em` | `p-toc` etc. | May also have many links | Empty (skip) | **Still empty** (class guard after link check — see note) |
| Normal chapter XHTML | — | Few external `#` links | `h*` / `font-1em` scan | Unchanged |

**Note on `p-toc` + external links:** If a file matches **both** the external-link threshold **and** `looksLikeTocByClass`, the external-link branch runs **first** and returns parsed links. Files with `p-toc` that **fail** the external-link ratio still hit `looksLikeTocByClass` and return empty — preserving v1.06 for the classic pollution case. Fujimi `c2S.xhtml` typically has **no** `p-toc` body class, so it takes the new parse path only.

---

## 7. End-to-End Flow (FMP example)

```
nav entry "Table of contents" → href c2S.xhtml
        ↓
attachSubheadingChildren: scan window = [c2S.xhtml only]
        ↓
scanOrCached(c2S.xhtml)
  bytesMightContainTocPageLinks → true (many .xhtml#)
  decodeWithDetection → UTF-8 XHTML string
        ↓
findSubheadingsInXhtml
  externalFragLinks >= 4 && >= 50% of links → true
  parseTocPageChapterLinks → [Prologue, Ch.1, Ch.2, …]
        ↓
each RawSubheading: resolvedHref = OEBPS/c50.xhtml#aCSR, etc.
        ↓
TocEntry children under "Table of contents"
        ↓
ReaderActivity.flattenToc / showTocDialog → indented chapter rows
        ↓
tap → VerticalEpubView resolves OEBPS/c50.xhtml#aCSR → correct page
```

---

## 8. Relationship to Prior Changelog Entries

### v1.06 — “TOC extraction stability — skip in-book TOC pages”

- **Problem fixed:** `font-1emNN` rows inside `<a>` on TOC pages became bogus child TOC entries pointing at the TOC page.
- **Mechanism:** `looksLikeTocByClass` + external-link empty return.
- **This fix:** Splits the external-link case into two behaviors:
  - **Link-dominated, non-class-tagged TOC pages (Fujimi)** → parse links.
  - **`p-toc` + span-in-link pattern** → still skip `font-1em` extraction.

### v1.11 — Fragment-range subheading grouping

- Orthogonal. Applies when `spineUsage >= 2` on the **same** XHTML file. FMP nav has `spineUsage == 1` for `c2S.xhtml`; fragment-range logic does not apply to the TOC page file itself.

---

## 9. Risk Assessment

### 9.1 Paths that should be unchanged

| Scenario | Why unaffected |
|----------|----------------|
| Nav/NCX lists every chapter | No subheading scan needed; children come from nav |
| `font-1emNN` subheadings in body | Normal `tryAdd` path; external-link ratio usually low |
| Heavily fragmented NCX (`spineUsage >= 2`) | Fragment-range windowing unchanged |
| Manga (image spine) | No XHTML subheading scan |
| `resolvedHref == null` subheadings | Same `sp.href#fragId` as before |

### 9.2 Dual guard (defense in depth)

| Stage | Condition | Effect if false |
|-------|-----------|-----------------|
| Byte pre-scan | `font-1em` / `<h*` **OR** ≥4× `.xhtml#` | No decode (fast skip) |
| Parse branch | ≥4 external `#` links **and** ≥50% of all links | Falls through to class skip / normal scan |

Both must align for the new parser to run **and** the file must be in a parent’s scan window.

### 9.3 Theoretical false positives

| Risk | Likelihood | Impact | Mitigation |
|------|------------|--------|------------|
| Body XHTML with many internal cross-ref links (`other.xhtml#`) | Low | Extra TOC children under wrong parent; taps may still work if hrefs valid | 50% link-ratio + parent scan window limits scope |
| Decode cost on large XHTML with ≥4 `.xhtml#` but not a TOC page | Low | Slower TOC open; display may be unchanged if ratio fails | Byte gate avoids most files; result cached in `scanCache` |
| `p-toc` file that also passes external-link threshold | Very low | Would parse link list instead of skipping | Rare; would need dense `file.xhtml#` without wanting TOC children |
| Title noise (long anchor text) | Low | Skipped by `plain.length > 80` | — |
| Duplicate links | Low | Second copy dropped by `seen` key | — |

### 9.4 Theoretical false negatives

| Risk | Likelihood | Impact |
|------|------------|--------|
| TOC page with &lt;4 chapter links | Low | No parse; same as before |
| TOC links without `.xhtml` suffix (e.g. `.html#`) | Medium for nonstandard EPUBs | Byte gate and parser both miss |
| Chapter list only in NCX fragments (Edogawa Ranpo style) | N/A | Already handled by nav/NCX + v1.11 fragment-range |

### 9.5 Regression testing matrix (recommended)

| Test case | Expected |
|-----------|----------|
| FMP Fighting Boy Meets Girl (Shinsōban) | Chapter titles under “Table of contents”; tap jumps to chapter |
| Bundled volume with `p-toc` + `font-1em` TOC page | No polluted children (v1.06 behavior) |
| Nav-complete commercial EPUB | Unchanged top-level TOC |
| `font-1em` subheading novel | Subheadings under correct parents |
| Heavily fragmented NCX (Ranpo-style) | Fragment-scoped children only |
| Manga EPUB | Image reader; TOC unchanged |

**Status:** Logic reviewed by static analysis and `assembleDebug` build success. **On-device regression not yet recorded** in this document.

---

## 10. Summary

| Item | Detail |
|------|--------|
| **Problem** | Fujimi-style EPUBs store chapter titles only as cross-file links on an in-book TOC XHTML; nav has 3 entries; TOC dialog showed no chapters |
| **Root cause** | v1.06 “skip TOC page” heuristic returned empty for link-dominated TOC files; no `resolvedHref`; byte pre-scan skipped TOC file |
| **Fix** | Detect link-dominated TOC pages → `parseTocPageChapterLinks()`; store `resolvedHref`; extend byte pre-scan |
| **Scope** | `EpubParser.kt` only |
| **Safety** | `p-toc` class skip retained for classic pollution; dual threshold; scan window limits which parent triggers parsing |

---

## 11. Version / Commit Note

This change is present in the working tree as modifications to `EpubParser.kt` (not yet tied to a released version tag at the time this document was written). After release, add a matching entry to `md/changelog.md`.

---

*Document generated for JPN-EPUB-Reader TOC in-book page chapter-link support.*
