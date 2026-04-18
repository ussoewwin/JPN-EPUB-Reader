package com.jpnepub.reader.vrender

/**
 * EPUBチャプターから抽出した「意味のあるコンテンツ単位」。
 * XHTML のタグ・スタイルを剥がし、組版に必要な情報だけ保持する。
 *
 * VerticalLayoutEngine はこのリストを受け取って各文字の物理ピクセル位置を
 * 事前計算し、VerticalEpubView が Canvas に描画する。
 */
sealed class ContentNode {
    /** 通常の地の文。連続した文字列。 */
    data class TextRun(val text: String) : ContentNode()

    /** ルビ: base の文字列に対し、ruby の文字列を振る。 */
    data class Ruby(val base: String, val ruby: String) : ContentNode()

    /** 画像。chapterDir基準で解決済みの絶対パス。resources から引ける前提。 */
    data class Image(val src: String) : ContentNode()

    /** 段落区切り (新しい行=縦書きでは新しいカラムを開始、行頭字下げあり)。 */
    object ParaBreak : ContentNode()

    /** 明示的な改行 <br> (字下げなしで次カラム)。 */
    object LineBreak : ContentNode()

    /**
     * 強制改ページ。現在ページを確定して次ページの先頭から以降のコンテンツを流す。
     * 主に見出し (章タイトル・節タイトル) の直前に挿入し、本文と見出しが
     * 同一ページに混在しないようにする。
     * なお、チャプター先頭やまだ1文字も出していない状態では無視される
     * (空ページを作らないため)。
     */
    object PageBreak : ContentNode()

    /**
     * 見出し。<h1>〜<h6> 由来の単独テキスト。
     * level は 1 (最上位) 〜 6 (最下位)。
     * レイアウト時は本文より大きく・太字で描画され、前後にページ/段落区切りが付く。
     */
    data class Heading(val text: String, val level: Int) : ContentNode()
}
