# JPN-EPUB-Reader

A **vertical-writing EPUB reader for Android**, purpose-built for
Japanese text and obsessive about not producing garbled characters
(*mojibake*).

The app ships with a custom native Canvas-based typesetting engine
that replaces the usual WebView approach, so Japanese books render
the way they should: right-to-left columns, proper em-box positioning
of brackets and punctuation, correct rotation of Latin / dashes /
ellipses, and pixel-perfect page boundaries that never skip.

## Features

### No more mojibake
- **ICU4J charset auto-detection.** Byte stream is classified by
  (1) BOM, (2) the `encoding` declared in the XML/HTML prolog, and
  (3) ICU4J statistical detection.  Shift_JIS and EUC-JP inputs
  decode as reliably as UTF-8.
- **CJK font fallback.** `Noto Sans/Serif CJK JP`, `Source Han
  Sans/Serif`, `Hiragino`, `Yu Gothic/Mincho`, `Meiryo` — tried in
  that order before ever touching a Latin-only fallback.
- **Explicit Unicode ranges.** CJK unified ideographs and their
  extensions (U+2E80-9FFF, U+F900-FAFF, U+FE30-FE4F, U+20000-2FA1F)
  are wired to the CJK fallback via `@font-face unicode-range`.
- **WebView `defaultTextEncodingName` forced to UTF-8** as a safety
  net for books that ship without a charset declaration.

### Real Japanese vertical writing
The vertical renderer is NOT a WebView with `writing-mode: vertical-rl`
slapped on. It is a custom `View` that:
- Pre-computes the exact pixel position of every glyph before drawing,
  so pagination is deterministic and never skips a page.
- Lays out right-to-left columns, top-to-bottom characters.
- Enables the OpenType features required for tategaki (`vert`, `vkrn`,
  `vpal`, `vhal`) and pins the text locale to Japan so the shaper
  cannot fall back to Chinese glyph variants.
- Rotates Latin letters, half-width kana, dashes, and horizontal
  ellipses 90 degrees clockwise **around the actual ink bounding box**,
  then re-centers them to the column axis — so `A`, `...`, `--`, and
  `─` all sit cleanly on the vertical line.
- Shifts Japanese opening / closing brackets (`「『（`, `」』）`) into
  their proper half of the em-box per JLReq / JIS X 4051, eliminating
  the usual "brackets collide with the preceding character" bug.
- Normalizes half-width ASCII digits (0-9) and common punctuation
  (!, ?, %) to their full-width counterparts so numerals read
  naturally upright in a vertical column.
- Supports inline gaiji (external / legacy-form kanji) images that
  most Japanese e-books use: any image at most 256x256 px is treated as
  an inline character and sized to the em-box; anything larger is
  treated as a full-page illustration.

### Reader features
- EPUB 2 and EPUB 3.
- Chapter navigation: previous / next chapter and a table-of-contents
  jump.
- Font size: 12-32 sp, persisted across sessions.
- Dark mode.
- Bold text toggle (uses real bold glyphs from Noto Serif CJK JP when
  available, with a synthesized fake-bold fallback).
- Tap-zone page turning: left third = previous page, right third =
  next page, center = toggle menu bars. Direction is swappable to
  match right-to-left reading of Japanese books.
- EPUB spine boundary handling: tapping "previous" from the first
  page of a chapter transparently loads the previous chapter and
  starts you on its last page.

## Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34+

### Steps
1. Open the project in Android Studio.
2. Let Gradle sync.
3. Run on a device or emulator.

```bash
# Command line:
./gradlew assembleDebug
```

The resulting APK is named `JPN-EPUB-Reader-1.0.0-debug.apk` under
`app/build/outputs/apk/debug/`.

## Project layout

```
app/src/main/java/com/jpnepub/reader/
+-- epub/
|   +-- EpubParser.kt          EPUB parsing, encoding detection
+-- renderer/
|   +-- EpubRenderer.kt        HTML preparation for the WebView
|   +-- ReaderConfig.kt        Reader settings (persisted)
+-- vrender/
|   +-- ContentNode.kt         Semantic content unit (TextRun / Ruby / Image / breaks)
|   +-- ContentExtractor.kt    XHTML -> ContentNode conversion
|   +-- VerticalLayoutEngine.kt  Pure typesetter (ContentNode -> positioned glyphs)
|   +-- VerticalEpubView.kt    Custom View that draws positioned glyphs on a Canvas
+-- ui/
    +-- MainActivity.kt        Bookshelf screen
    +-- BookshelfAdapter.kt    Bookshelf list
    +-- ReaderActivity.kt      Reader screen
```

## Root causes of mojibake and how this project fixes each one

| Cause | Fix |
|---|---|
| EPUB file is encoded as Shift_JIS / EUC-JP | ICU4J statistical detection after BOM and declared-charset checks |
| Device has no glyph for the requested character | `@font-face` with explicit CJK Unicode ranges that fan out to Noto CJK, Source Han, Hiragino, Yu Gothic, Meiryo |
| XML prolog declares a wrong or missing encoding | BOM -> prolog -> ICU4J chain, any one of which can recover |
| WebView default encoding fight | `defaultTextEncodingName = "UTF-8"` plus forced `<meta charset="UTF-8">` injection |
| Publisher CSS prefers a Latin-only family | Font fallback stylesheet is injected with `!important` so CJK families win |
| Punctuation / dashes / ellipses rendered horizontally in vertical text | Explicit 90-degree rotation with ink-bbox-centered pivot in the native renderer |
| Brackets collide with the previous character in vertical text | Per-glyph y-shift based on the actual ink bounds, targeting JLReq / JIS X 4051 positions |

## Changelog

See [`md/changelog.md`](md/changelog.md) for the full release history.
The most recent release is **v1.07**.

## License

MIT License.
