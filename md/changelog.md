# Changelog

All notable changes to JPN-EPUB-Reader are documented in this file.
The project follows a loose `MAJOR.MINOR` numbering scheme with no
semantic-version guarantees yet.

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
  heading, so ruby readings such as "あき" / "こ" never leak into the
  TOC entry.

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
- Inline gaiji rendering for body text (`<img>` ≤ 256 × 256 px sized
  to the em-box, larger images treated as full-page illustrations).
- Tap-zone page turning, dark mode, configurable font size, bold
  toggle, table-of-contents jump, RTL page direction toggle.
- App icon set (mdpi → xxxhdpi mipmaps and a 512 × 512 Play Store
  icon under `assets/`).
