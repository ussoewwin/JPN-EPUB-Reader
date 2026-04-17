package com.jpnepub.reader.vrender

/**
 * A semantically meaningful unit of content extracted from an EPUB chapter.
 * XHTML tags and styles are stripped away, keeping only what the typesetter
 * needs.
 *
 * [VerticalLayoutEngine] consumes a list of these and pre-computes the
 * physical pixel position of every glyph, and [VerticalEpubView] draws them
 * on a Canvas.
 */
sealed class ContentNode {
    /** A run of plain flowing body text. */
    data class TextRun(val text: String) : ContentNode()

    /** Ruby annotation: [ruby] characters are annotated over [base]. */
    data class Ruby(val base: String, val ruby: String) : ContentNode()

    /** An image, as an absolute path resolved against the chapter directory
     *  so it can be looked up directly from `book.resources`. */
    data class Image(val src: String) : ContentNode()

    /** Paragraph break (starts a new column in vertical writing, with
     *  leading indentation). */
    object ParaBreak : ContentNode()

    /** Explicit line break `<br>` (advances to the next column without
     *  adding a leading indent). */
    object LineBreak : ContentNode()
}
