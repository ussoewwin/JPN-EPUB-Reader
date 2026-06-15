package com.jpnepub.reader.vrender

/**
 * EPUB 内 <img> の用途を推定する。
 *
 * 単一の class 名ホワイトリストではなく、複数の弱い手がかりをスコアリングして
 * 外字 (GAIJI) / 全面挿絵 (FULLPAGE) / 半面挿絵 (HALFPAGE) / 非表示 (SKIP) を決める。
 *
 * 手がかりの例:
 *  - 出版社 CSS の慣習 class (gaiji, pagefit, inline, class_s3X, class_sN-* 等)
 *  - 直近の親 [div] の class (class_sN-0 ラッパー + class_sN-1 画像 等)
 *  - 本文テキストに挟まれたインライン配置か
 *  - ピクセル寸法・アスペクト比・面積
 */
internal object ImageRoleClassifier {

    enum class Role { SKIP, GAIJI, FULLPAGE, HALFPAGE }

    data class Input(
        val cssClass: String,
        val wrapperClass: String = "",
        val inlineInText: Boolean = false,
        val dims: Pair<Int, Int>?,
        val prevNode: ContentNode?,
        val nextNode: ContentNode?,
    )

    private val CLASS_S_SUFFIX_RE = Regex("^class_s([a-z0-9]+)-(\\d+)$")
    /** 富士見系 EPUB: 外字 img は class_s3T 等 suffix 無し。挿絵は class_sKW-1 等。 */
    private val CLASS_S_GAIJI_IMG_RE = Regex("^class_s3[a-z0-9]+$")

    fun classify(input: Input): Role {
        val tokens = tokenize(input.cssClass)
        val wrapperTokens = tokenize(input.wrapperClass)

        if (isTracking(tokens, wrapperTokens, input.dims)) {
            return Role.SKIP
        }

        val scores = ScoreBoard()
        val explicitGaiji = scoreExplicitClasses(tokens, input.dims, scores)
        scoreWrapperPair(wrapperTokens, tokens, input.dims, scores)
        scoreFlowContext(input, scores)
        scoreDimensions(input.dims, scores, explicitGaiji)
        return scores.resolve()
    }

    private class ScoreBoard {
        var gaiji = 0
        var full = 0
        var half = 0

        fun addGaiji(n: Int) {
            gaiji += n
        }

        fun addFull(n: Int) {
            full += n
        }

        fun addHalf(n: Int) {
            half += n
        }

        fun resolve(): Role = when {
            full >= gaiji && full >= half && full > 0 -> Role.FULLPAGE
            gaiji > half && gaiji > 0 -> Role.GAIJI
            else -> Role.HALFPAGE
        }
    }

    private fun tokenize(cssClass: String): List<String> =
        cssClass.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun isTracking(
        tokens: List<String>,
        wrapperTokens: List<String>,
        dims: Pair<Int, Int>?,
    ): Boolean {
        if (tokens.any { it.startsWith("class_sh-") || it.startsWith("class_s28-") }) {
            return true
        }
        if (wrapperTokens.any { it.startsWith("class_sh-") || it.startsWith("class_s28-") }) {
            return true
        }
        if (dims != null && dims.first <= 2 && dims.second <= 2) {
            return true
        }
        return false
    }

    private fun scoreExplicitClasses(
        tokens: List<String>,
        dims: Pair<Int, Int>?,
        scores: ScoreBoard,
    ): Boolean {
        if (tokens.any { it == "gaiji" || it.startsWith("gaiji-") }) {
            scores.addGaiji(100)
        }
        val explicitGaijiClass = tokens.any {
            CLASS_S_GAIJI_IMG_RE.matches(it) ||
                it == "class_s3x" ||
                it.startsWith("class_s3x-")
        }
        if (explicitGaijiClass) {
            scores.addGaiji(100)
        }
        if (tokens.any { it == "class_s1w" || it.startsWith("class_s1w-") }) {
            scores.addHalf(75)
            scores.addGaiji(-60)
        }
        if (tokens.any { it.startsWith("class_sn-") }) {
            scores.addFull(95)
        }
        tokens.forEach { token ->
            val pair = parseClassSPair(token) ?: return@forEach
            if (pair.second != 1 || isTrackingStem(pair.first)) return@forEach
            when (pair.first) {
                "n" -> scores.addFull(90)
                "3x", "1w" -> Unit
                else -> {
                    val short = dims?.let { minOf(it.first, it.second) }
                    when {
                        short != null && short >= 500 -> scores.addFull(55)
                        short != null && short >= 280 -> scores.addFull(35)
                        short != null && short >= 120 -> scores.addHalf(25)
                        else -> scores.addHalf(12)
                    }
                }
            }
        }
        if (tokens.any {
                it == "fit" || it == "cover" || it == "full" ||
                    it.startsWith("pagefit") || it.startsWith("page80") ||
                    it.startsWith("page-") || it == "fullpage" || it == "full-page"
            }
        ) {
            scores.addFull(100)
        }
        if (tokens.any { it == "inline" || it.startsWith("inline_") || it.startsWith("inline-") }) {
            scores.addHalf(85)
        }
        return explicitGaijiClass || tokens.any { it == "gaiji" || it.startsWith("gaiji-") }
    }

    private fun scoreWrapperPair(
        wrapperTokens: List<String>,
        imgTokens: List<String>,
        dims: Pair<Int, Int>?,
        scores: ScoreBoard,
    ) {
        val wrapperPair = wrapperTokens.firstNotNullOfOrNull { parseClassSPair(it) } ?: return
        val imgPair = imgTokens.firstNotNullOfOrNull { parseClassSPair(it) } ?: return
        if (wrapperPair.first != imgPair.first) return
        if (wrapperPair.second != 0 || imgPair.second != 1) return

        when {
            isTrackingStem(wrapperPair.first) -> { /* SKIP は上流で処理済み */ }
            wrapperPair.first == "n" -> scores.addFull(90)
            dims != null && minOf(dims.first, dims.second) >= 400 -> scores.addFull(70)
            dims != null && minOf(dims.first, dims.second) >= 200 -> scores.addHalf(40)
            else -> scores.addHalf(25)
        }
    }

    private fun scoreFlowContext(
        input: Input,
        scores: ScoreBoard,
    ) {
        val sandwiched = input.prevNode is ContentNode.TextRun &&
            input.nextNode is ContentNode.TextRun
        if (input.inlineInText || sandwiched) {
            scores.addGaiji(40)
            scores.addFull(-25)
        }

        val blockStandalone = input.prevNode is ContentNode.ParaBreak ||
            input.prevNode is ContentNode.LineBreak ||
            input.prevNode is ContentNode.PageBreak ||
            input.prevNode == null
        val littleAfter = input.nextNode is ContentNode.TextRun &&
            (input.nextNode as ContentNode.TextRun).text.length <= 8
        if (blockStandalone && (input.nextNode == null || littleAfter || input.nextNode is ContentNode.ParaBreak)) {
            scores.addFull(15)
            scores.addHalf(10)
        }
    }

    private fun scoreDimensions(
        dims: Pair<Int, Int>?,
        scores: ScoreBoard,
        explicitGaiji: Boolean,
    ) {
        if (dims == null) return
        val (iw, ih) = dims
        if (iw <= 0 || ih <= 0) return

        val short = minOf(iw, ih)
        val long = maxOf(iw, ih)
        val area = iw.toLong() * ih

        when {
            short <= 64 && area <= 96L * 96 -> scores.addGaiji(30)
            short >= 900 -> scores.addFull(45)
            short >= 600 -> scores.addFull(35)
            short >= 400 -> scores.addFull(20)
            short >= 250 -> {
                scores.addHalf(20)
                scores.addFull(10)
            }
            else -> scores.addHalf(10)
        }

        if (long > 0) {
            val aspect = short.toFloat() / long.toFloat()
            if (aspect in 0.52f..0.88f && short >= 280) {
                scores.addFull(18)
            }
        }

        if (!explicitGaiji) {
            if (short >= 128 || area >= 32_000L) {
                scores.addGaiji(-120)
            } else if (short >= 96 || area >= 12_000L) {
                scores.addGaiji(-60)
            }
        }
    }

    private fun parseClassSPair(token: String): Pair<String, Int>? {
        val m = CLASS_S_SUFFIX_RE.matchEntire(token) ?: return null
        val suffix = m.groupValues[2].toIntOrNull() ?: return null
        return m.groupValues[1] to suffix
    }

    private fun isTrackingStem(stem: String): Boolean =
        stem == "h" || stem == "28" || stem.startsWith("28")
}
