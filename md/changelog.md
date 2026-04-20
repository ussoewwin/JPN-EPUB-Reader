# Changelog

All notable changes to JPN-EPUB-Reader are documented in this file.
The project follows a loose `MAJOR.MINOR` numbering scheme with no
semantic-version guarantees yet.

## v1.05

### EpubParser — table of contents wrong when one XHTML has many `#fragment` entries

- **Symptom:** Settings → Table of contents showed the **same title many
  times** (or the wrong chapter name) when the navigation document listed
  many entries that all pointed into **one** XHTML spine item with different
  `#fragment` anchors (typical of older bundled-volume EPUBs).
- **Root cause:** `enrichTocEntryWithBodyHeading` ignored the fragment and
  always took the **first** `<h1>`–`<h6>` in that file, so every entry sharing
  the file inherited that one heading.
- **Fix:** If `href` contains `#` and the NCX/nav **title is already
  non-blank**, skip body-based enrichment and keep the navigation title.

### ContentExtractor — parenthesized footnote numbers in body text

- **Symptom:** Inline markers like full-width `(10)`, `(11)` appeared in the
  middle of paragraphs when the publisher wrapped footnote backlinks in
  `<a href="...#chushaku_N">…</a>` (or similar).
- **Root cause:** Anchor inner text was treated as normal prose; this reader
  does not resolve those jumps, so the markers read as noise.
- **Fix:** Treat EPUB3 `noteref` anchors (`epub:type`, `role`) and common
  footnote fragment prefixes (`chushaku`, `chu_`, `fn`, `note_`, …) as
  **footnote references** and omit text and images inside those `<a>`
  elements. In-book TOC links (`#link_NNN`, etc.) are **not** matched and stay
  visible.

### Files touched

```
app/src/main/java/com/jpnepub/reader/epub/EpubParser.kt
app/src/main/java/com/jpnepub/reader/vrender/ContentExtractor.kt
```

## v1.04

### ContentExtractor — do not strip intentional U+3000 between CJK

- **Regression (from v1.03):** `INTRA_CJK_SPACE_RE` originally included
  **U+3000** in the character class to remove. That also removed
  publisher-intended ideographic space between a chapter label and title
  (e.g. `第五章　見出し…` on an in-book TOC page built from
  `<span>第五章</span><span>　見出し…</span>`), so the line looked
  **cramped** with no gap after `章`.
- **Fix:** The pattern now removes only **ASCII space, NBSP, and
  U+2000–U+200A / U+202F / U+205F** between CJK characters — **not U+3000**.
  Split-ruby orphan `TextRun` removal, ASCII edge trimming, and other
  v1.03 behaviour are unchanged.

### Vertical typesetter — upright Latin (A–Z / a–z) in tategaki

- Half-width Latin letters were classified by `needsRotation` and drawn
  rotated 90°, which looked wrong next to vertical Japanese.
- **Fix:** `normalizeForVertical` now maps **A–Z** and **a–z** to
  full-width Latin (U+FF21–FF3A / U+FF41–FF5A), same idea as half-width
  digits → full-width digits. Ruby **base** text now runs through
  `normalizeForVertical` as well so Latin in `<ruby>` bases matches body
  text.

### Files touched

```
app/src/main/java/com/jpnepub/reader/vrender/ContentExtractor.kt
app/src/main/java/com/jpnepub/reader/vrender/VerticalLayoutEngine.kt
```

## v1.03

### Vertical typesetter — remove spurious one-character gaps (split ruby & CJK spacing)

- **Problem:** In some EPUBs, compound words are split across **multiple
  adjacent `<ruby>` blocks** (one kanji per block with its reading). The
  XHTML pretty-printing between `</ruby>` and `<ruby>` leaves a newline
  and/or spaces that become their own `TextRun`. In vertical layout that
  looks like an extra **blank column** between characters when a compound
  is split across multiple `<ruby>` blocks with junk whitespace between
  closing and opening tags. Separately, **full-width spaces (U+3000)** or narrow
  Unicode spaces inserted between CJK characters for horizontal tracking
  also appeared as one-character gaps when read vertically.
- **Fix:** `ContentExtractor.sanitizeExtractedText()` now (1) strips spaces
  that sit **only between** hiragana/katakana/CJK ideographs, (2) trims
  leading/trailing **ASCII** spaces from each flushed segment (paragraph
  indent `U+3000` is preserved), and (3) **does not emit** `TextRun`s that
  are blank after normalization — so orphan runs that were only
  formatting glue between split-ruby tags are dropped.

### Files touched

```
app/src/main/java/com/jpnepub/reader/vrender/ContentExtractor.kt
```

## v1.02

### Vertical typesetter — illustration scale (gaiji vs cover vs body art)

- **Problem:** All small bitmaps were treated as inline gaiji whenever
  both sides were ≤ 256 px. Illustrations marked `class="inline_01"`
  (e.g. tall narrow plates around 76 × 128 px in some commercial EPUBs)
  were shrunk to a single em-box and looked like a tiny stamp next to the
  text.
- **Fix:** `ContentNode.Image` now carries the `<img>` / SVG `<image>`
  `class` attribute (`cssClass`). `VerticalLayoutEngine` classifies each
  image into **GAIJI** (character-sized inline), **FULLPAGE** (cover /
  full-bleed plate), or **HALFPAGE** (centered illustration, at most half
  the text area in width and height), using publisher classes first:
  - **GAIJI:** `gaiji`, `gaiji-line`, `gaiji-wide`, or no class with both
    dimensions ≤ 64 px (legacy EPUB fallback).
  - **FULLPAGE:** `fit`, `cover`, `full`, `pagefit*`, `page80*`, or no
    class with short side ≥ 600 px (typical cover bitmaps with no class).
  - **HALFPAGE:** `inline`, `inline_01`, `inline_001`, etc., or remaining
    cases — drawn centred with aspect ratio preserved.
- **Regression addressed:** After an interim change that scaled every
  non-gaiji image to “half page”, bare cover images (e.g. `style="width:100%;
  height:100%"` with no class) were also halved. They are **FULLPAGE**
  again via the short-side ≥ 600 px rule; `class="fit"` plates stay
  full-area as well.

### Files touched

```
app/src/main/java/com/jpnepub/reader/vrender/ContentNode.kt
app/src/main/java/com/jpnepub/reader/vrender/ContentExtractor.kt
app/src/main/java/com/jpnepub/reader/vrender/VerticalLayoutEngine.kt
```

## v1.01

### Bookshelf — fix metadata that was always "Untitled / Unknown"

- `EpubParser` now strips Dublin Core namespace prefixes before
  comparing OPF metadata tag names. The OPF parser was running with
  `isNamespaceAware = false`, so `<dc:title>`, `<dc:creator>` and
  `<dc:language>` came through verbatim as `dc:title`, `dc:creator`,
  `dc:language` and never matched the `title` / `creator` / `language`
  literals. Almost every real-world EPUB 3 ships these tags namespaced,
  so the bookshelf was permanently showing "Untitled / Unknown".
- When the same metadata tag appears multiple times (series titles,
  alternate spellings) the first non-empty value now wins instead of
  being overwritten by a later empty payload.

### Vertical typesetter — real chapter headings

- New `ContentNode.PageBreak` and `ContentNode.Heading(parts, level)`
  nodes. `ContentExtractor` now recognises `<h1>`–`<h6>` as proper
  heading units instead of folding them into the regular paragraph
  stream.
- A `PageBreak` is inserted in front of every heading (suppressed at
  the very start of a chapter to avoid blank pages), so chapter and
  section titles always begin on the first column of a fresh page and
  never get glued onto the previous body paragraph.
- Headings are typeset bold and at a level-dependent enlargement:
  `h1 = 1.6×`, `h2 = 1.4×`, `h3 = 1.25×`, `h4`–`h6 = 1.15×`.
  After the heading the engine forces a column break so the body text
  starts in its own column.
- `PositionedGlyph` gained `sizeScale` and `bold`. `VerticalEpubView`
  saves `paint.textSize` / `isFakeBoldText`, applies the heading
  values, and restores them inside a `try`/`finally` so the rest of
  the page is never affected.

### Gaiji (外字) inside chapter headings

- `Heading` no longer carries a flat string — it now carries a list
  of `HeadingPart.Text` / `HeadingPart.Image` segments, so an inline
  legacy-form character delivered as `<img class="gaiji">` inside an
  `<h*>` survives extraction.
- `VerticalLayoutEngine` lays out heading characters and heading
  images alternately. Heading-mode gaiji are sized to fit a single
  scaled em-box, advance by the same amount as a heading character,
  and stay column-aligned with the surrounding text.

### Table of contents — fix garbled chapter titles

- Many publishers leave a `〓` (U+3013, GETA MARK) in the NCX text
  where the body has a gaiji `<img>`, since plain text inside NCX
  cannot host an image. On Android these placeholder marks render as
  thick black bars and look like mojibake. The TOC parser now strips
  `〓` and `■` runs and collapses the resulting whitespace.
- `EpubParser` additionally re-reads the body XHTML each TOC entry
  points to, picks up the first `<h*>` element, and stores its
  ordered `TextPart` / `ImagePart` segments on the `TocEntry` as
  `titleParts`. The TOC display can therefore reconstruct the real
  chapter title — including the gaiji image — instead of relying on
  whatever the NCX placeholder happened to be.
- The TOC `AlertDialog` now uses a custom `ArrayAdapter` whose
  entries are `SpannableStringBuilder`s with a custom
  `CenteredImageSpan`. The span sizes the gaiji bitmap to the actual
  list-item text size and centres the image vertically against the
  text, so a gaiji character looks like a real character of the same
  size sitting on the same baseline rather than floating above the
  line or collapsing into a tiny square.
- `<rt>` / `<rp>` content is filtered out while extracting the body
  heading, so phonetic text from ruby markup never leaks into the TOC
  entry.

### Files touched

```
app/src/main/java/com/jpnepub/reader/epub/EpubParser.kt
app/src/main/java/com/jpnepub/reader/ui/ReaderActivity.kt
app/src/main/java/com/jpnepub/reader/vrender/ContentExtractor.kt
app/src/main/java/com/jpnepub/reader/vrender/ContentNode.kt
app/src/main/java/com/jpnepub/reader/vrender/VerticalEpubView.kt
app/src/main/java/com/jpnepub/reader/vrender/VerticalLayoutEngine.kt
```

## v1.0

Initial public release of the rewritten native Canvas-based vertical
typesetting engine.

- Complete `ContentNode` / `ContentExtractor` /
  `VerticalLayoutEngine` / `VerticalEpubView` pipeline replacing the
  previous WebView-only renderer.
- Right-to-left columns, top-to-bottom characters, OpenType `vert` /
  `vkrn` / `vpal` / `vhal` features pinned to Japanese locale.
- Latin / dash / ellipsis rotation around the ink bounding box, with
  re-centering to the column axis.
- JLReq / JIS X 4051 bracket positioning (`「『（` shifted into the
  bottom half of the em-box, `」』）` into the top half).
- Half-width digit / punctuation normalisation to full-width for
  vertical reading.
- Inline gaiji rendering for body text (early heuristic: small `<img>`
  bitmaps sized to the em-box; **v1.02** replaced this with class- and
  dimension-based rules — see **v1.02**).
- Tap-zone page turning, dark mode, configurable font size, bold
  toggle, table-of-contents jump, RTL page direction toggle.
- App icon set (mdpi → xxxhdpi mipmaps and a 512 × 512 Play Store
  icon under `assets/`).
