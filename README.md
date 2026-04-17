# JPN-EPUB-Reader — 文字化けしない Android EPUB リーダー

CJK文字（漢字・ひらがな・カタカナ）の文字化けを防止することに特化した、Android向けEPUBリーダーアプリです。

## 特徴

### 文字化け防止
- **ICU4J によるエンコーディング自動検出**: BOM検出 → XML/HTML宣言のcharset → ICU4J統計的検出 の3段階で文字コードを正確に判定
- **CJKフォントフォールバック**: `Noto Sans/Serif CJK JP`, `Source Han Sans/Serif`, `Hiragino`, `Yu Gothic/Mincho` 等の日本語フォントを優先的に使用
- **Unicode範囲指定**: CJK統合漢字・拡張領域（U+2E80-9FFF, U+20000-2FA1F）を明示的にフォント指定
- **WebView の defaultTextEncodingName を UTF-8 に強制**

### 縦書き対応
- EPUB内のCSS `writing-mode: vertical-rl` を自動検出
- 設定で縦書き/横書きを切り替え可能
- 縦書き時の句読点・約物の正しい配置

### リーダー機能
- 章ナビゲーション（前/次、目次ジャンプ）
- フォントサイズ調整（12px〜32px）
- ダークモード
- タップゾーン操作（左1/4: 前ページ、右1/4: 次ページ、中央: メニュー表示）
- EPUB2/EPUB3 両対応

## ビルド方法

### 必要環境
- Android Studio Hedgehog (2023.1.1) 以降
- JDK 17
- Android SDK 34

### 手順

1. プロジェクトを Android Studio で開く
2. Gradle Sync を実行
3. デバイスまたはエミュレータで実行

```bash
# コマンドラインからビルドする場合
./gradlew assembleDebug
```

## プロジェクト構成

```
app/src/main/java/com/jpnepub/reader/
├── epub/
│   └── EpubParser.kt          # EPUB解析・エンコーディング検出
├── renderer/
│   ├── EpubRenderer.kt        # WebView用HTML生成・CSSインジェクション
│   └── ReaderConfig.kt        # リーダー設定（永続化）
└── ui/
    ├── MainActivity.kt         # 本棚画面
    ├── BookshelfAdapter.kt     # 本棚リスト
    └── ReaderActivity.kt       # リーダー画面
```

## 文字化けの技術的原因と対策

| 原因 | 対策 |
|------|------|
| EPUBファイルがShift_JIS/EUC-JPで記述されている | ICU4Jで文字コードを自動検出し正しくデコード |
| デバイスフォントに該当グリフがない | CJK Unicode範囲を明示した@font-face定義で複数フォントへフォールバック |
| XML宣言のencodingが不正/欠落 | BOM → XML宣言 → ICU4J検出 の3段階フォールバック |
| WebViewのデフォルトエンコーディング問題 | `defaultTextEncodingName = "UTF-8"` + `<meta charset="UTF-8">` 強制注入 |
| CSSでLatin系フォントが優先されている | `font-family: inherit !important` で全要素のフォントを統一 |

## ライセンス

MIT License
