package com.jpnepub.reader.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ReplacementSpan
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jpnepub.reader.R
import com.jpnepub.reader.databinding.ActivityReaderBinding
import com.jpnepub.reader.databinding.DialogSettingsBinding
import com.jpnepub.reader.epub.EpubBook
import com.jpnepub.reader.epub.EpubParser
import com.jpnepub.reader.epub.TitlePart
import com.jpnepub.reader.renderer.EpubRenderer
import com.jpnepub.reader.renderer.ReaderConfig
import com.jpnepub.reader.vrender.ContentExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding
    private var book: EpubBook? = null
    private var renderer: EpubRenderer? = null
    private var currentChapter = 0
    private var config = ReaderConfig()
    private var barsVisible = false

    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    /** 縦書きEPUBは Canvas ベースのネイティブ描画を使う */
    private var useNativeVertical = false
    /** チャプター切替時、次チャプターを末尾から表示する指示 (前ページ操作で境界を越えた場合) */
    private var pendingStartFromEnd = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        config = ReaderConfig.load(this)
        setupWebView()
        setupNativeView()
        setupControls()

        intent.data?.let { uri ->
            binding.progressBar.visibility = View.VISIBLE
            lifecycleScope.launch {
                try {
                    val parsed = withContext(Dispatchers.IO) {
                        EpubParser(this@ReaderActivity).parse(uri)
                    }
                    book = parsed
                    renderer = EpubRenderer(parsed, config)
                    binding.tvTitle.text = parsed.title
                    useNativeVertical = parsed.isVertical && config.verticalWriting
                    showActiveRenderer()
                    loadChapter(0)
                } catch (e: Exception) {
                    Toast.makeText(
                        this@ReaderActivity,
                        "${getString(R.string.error_open_file)}: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                } finally {
                    binding.progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun showActiveRenderer() {
        if (useNativeVertical) {
            binding.webView.visibility = View.GONE
            binding.verticalView.visibility = View.VISIBLE
        } else {
            binding.webView.visibility = View.VISIBLE
            binding.verticalView.visibility = View.GONE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downTime = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_UP -> {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                val dt = SystemClock.elapsedRealtime() - downTime
                if (dx < 30 && dy < 30 && dt < 300) {
                    if (barsVisible) {
                        val handled = super.dispatchTouchEvent(ev)
                        val w = binding.root.width
                        if (ev.x > w / 4f && ev.x < w * 3f / 4f) toggleBars()
                        return handled
                    }
                    val w = binding.root.width
                    when {
                        ev.x < w / 3f -> onLeftTap()
                        ev.x > w * 2f / 3f -> onRightTap()
                        else -> toggleBars()
                    }
                    return true
                }
            }
        }
        if (barsVisible) return super.dispatchTouchEvent(ev)
        return true
    }

    private fun onLeftTap() {
        if (config.rtlPageTurn) goNext() else goPrev()
    }

    private fun onRightTap() {
        if (config.rtlPageTurn) goPrev() else goNext()
    }

    private fun goNext() {
        if (useNativeVertical) binding.verticalView.nextPage()
        else binding.webView.evaluateJavascript("JpnEpubPager.next();", null)
    }

    private fun goPrev() {
        if (useNativeVertical) binding.verticalView.prevPage()
        else binding.webView.evaluateJavascript("JpnEpubPager.prev();", null)
    }

    // ================================================================
    //   ネイティブ縦書き View のセットアップ
    // ================================================================
    private fun setupNativeView() {
        val fontSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            config.fontSizePx.toFloat(),
            resources.displayMetrics
        )
        binding.verticalView.setStyle(
            fontSizePx = fontSizePx,
            darkMode = config.darkMode,
            bold = config.bold,
        )
        binding.verticalView.onPageChanged = { page, total ->
            updateProgress(page, total)
        }
        binding.verticalView.onChapterBoundary = { direction ->
            if (direction > 0) navigateNext()
            else {
                pendingStartFromEnd = true
                navigatePrev()
            }
        }
    }

    // ================================================================
    //   WebView のセットアップ (横書き用にのみ使用)
    // ================================================================
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupWebView() {
        binding.webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = true
            defaultTextEncodingName = "UTF-8"
            defaultFontSize = config.fontSizePx
            standardFontFamily = "serif"
            serifFontFamily = "Noto Serif CJK JP"
            sansSerifFontFamily = "Noto Sans CJK JP"
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
        }

        binding.webView.isVerticalScrollBarEnabled = false
        binding.webView.isHorizontalScrollBarEnabled = false
        binding.webView.setOnTouchListener { _, _ -> true }

        binding.webView.addJavascriptInterface(JsBridge(), "JpnEpubBridge")

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.visibility = View.GONE
                view?.evaluateJavascript(pagerJs(), null)
            }
        }
    }

    /**
     * 横書き用ページネーション JS (FolioReader 方式: CSS columns + scrollLeft)。
     * 縦書きは Canvas ネイティブレンダリングを使うので WebView 経路は横書き専用。
     */
    private fun pagerJs(): String {
        return """
(function() {
    function L(m){ try { JpnEpubBridge.log(String(m)); } catch(e){} }
    function setup() {
        var html = document.documentElement;
        var body = document.body;
        if (!body) { setTimeout(setup, 100); return; }
        var clientW = html.clientWidth;
        var clientH = html.clientHeight;
        var bs = getComputedStyle(body);
        var pT = parseInt(bs.paddingTop, 10) || 0;
        var pR = parseInt(bs.paddingRight, 10) || 0;
        var pB = parseInt(bs.paddingBottom, 10) || 0;
        var pL = parseInt(bs.paddingLeft, 10) || 0;
        var pageW = clientW - (pL + pR);
        var pageH = clientH - (pT + pB);
        body.style.webkitColumnGap = (pL + pR) + 'px';
        body.style.webkitColumnWidth = pageW + 'px';
        body.style.columnFill = 'auto';
        html.style.height = clientH + 'px';
        body.style.height = pageH + 'px';
        setTimeout(function() {
            var sw = html.scrollWidth;
            if (sw > clientW && sw > html.offsetWidth) sw += pR;
            html.style.width = sw + 'px';
            body.style.width = (sw - pL - pR) + 'px';
            var total = Math.max(1, Math.round(sw / clientW));
            L('H total='+total+' sw='+sw);
            window.JpnEpubPager = {
                p: 0, n: total,
                next: function() {
                    if (this.p < this.n - 1) { this.p++; this.go(); }
                    else { JpnEpubBridge.nextChapter(); }
                },
                prev: function() {
                    if (this.p > 0) { this.p--; this.go(); }
                    else { JpnEpubBridge.prevChapter(); }
                },
                go: function() {
                    var se = document.scrollingElement || body;
                    se.scrollLeft = this.p * clientW;
                    JpnEpubBridge.onPage(this.p, this.n);
                }
            };
            JpnEpubBridge.onPage(0, total);
        }, 300);
    }
    if (document.readyState === 'complete' || document.readyState === 'interactive') {
        setTimeout(setup, 50);
    } else {
        window.addEventListener('DOMContentLoaded', setup);
    }
})();
"""
    }

    private fun setupControls() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnPrev.setOnClickListener { goPrev() }
        binding.btnNext.setOnClickListener { goNext() }
        binding.btnToc.setOnClickListener { showTocDialog() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun loadChapter(index: Int, anchorId: String? = null) {
        val b = book ?: return
        if (index < 0 || index >= b.spine.size) return

        currentChapter = index
        binding.progressBar.visibility = View.VISIBLE

        if (useNativeVertical) {
            loadChapterNative(index, b, anchorId)
        } else {
            loadChapterWebView(index)
        }
    }

    private fun loadChapterNative(index: Int, b: EpubBook, anchorId: String?) {
        val spineItem = b.spine[index]
        val chapterDir = spineItem.href.substringBeforeLast('/', "")
        val startFromEnd = pendingStartFromEnd
        pendingStartFromEnd = false

        lifecycleScope.launch {
            val nodes = withContext(Dispatchers.Default) {
                val data = b.resources[spineItem.href] ?: return@withContext emptyList()
                ContentExtractor.extractFromBytes(data, chapterDir)
            }
            binding.verticalView.setChapter(
                content = nodes,
                imageResolver = { path -> b.resources[path] },
                startFromEnd = startFromEnd,
                targetAnchorId = anchorId,
            )
            binding.progressBar.visibility = View.GONE
        }
    }

    private fun loadChapterWebView(index: Int) {
        val r = renderer ?: return
        lifecycleScope.launch {
            val html = withContext(Dispatchers.Default) {
                r.renderChapter(index)
            }
            binding.webView.loadDataWithBaseURL(
                null, html, "text/html", "UTF-8", null
            )
        }
    }

    private fun navigateNext() {
        val b = book ?: return
        if (currentChapter < b.spine.size - 1) loadChapter(currentChapter + 1)
    }

    private fun navigatePrev() {
        if (currentChapter > 0) loadChapter(currentChapter - 1)
    }

    private fun updateProgress(page: Int, totalPages: Int) {
        val b = book ?: return
        binding.tvProgress.text = "${page + 1}/$totalPages (${currentChapter + 1}/${b.spine.size}章)"
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        val v = if (barsVisible) View.VISIBLE else View.GONE
        binding.topBar.visibility = v
        binding.bottomBar.visibility = v
    }

    private fun showTocDialog() {
        val b = book ?: return
        if (b.toc.isEmpty()) {
            Toast.makeText(this, "目次がありません", Toast.LENGTH_SHORT).show()
            return
        }
        // 行内に外字画像 (gaiji) を表示できるよう SpannableStringBuilder を使う。
        // 行高に合わせて画像を等倍縮小する ImageSpan を組み立てる。
        // リストアイテムの実フォントサイズ (px) を一度測ってから本文と同じ大きさで
        // 外字画像を生成する。simple_list_item_1 のテキストサイズは端末・テーマで
        // 変わるため、一度 dummy TextView を作って実測する。
        val measureTv = TextView(this).apply {
            setTextAppearance(android.R.style.TextAppearance_Material_Subhead)
        }
        val itemTextPx = measureTv.textSize.toInt().coerceAtLeast(
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics
            ).toInt()
        )
        // 親 → 子の木構造を深さ優先で平坦化。
        // 子は 1 段全角スペースでインデントする。
        val flat = flattenToc(b.toc)
        val titles: List<CharSequence> = flat.map { (entry, depth) ->
            buildTocTitleSpannable(b, entry, itemTextPx, depth)
        }
        val adapter = object : ArrayAdapter<CharSequence>(
            this,
            android.R.layout.simple_list_item_1,
            android.R.id.text1,
            titles
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v.findViewById<TextView>(android.R.id.text1))?.apply {
                    setLineSpacing(0f, 1.15f)
                    text = titles[position]
                }
                return v
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.toc)
            .setAdapter(adapter) { _, which ->
                val fullHref = flat[which].first.href
                val href = fullHref.substringBefore('#')
                val fragment = if (fullHref.contains('#')) {
                    fullHref.substringAfter('#').takeIf { it.isNotEmpty() }?.let { raw ->
                        Uri.decode(raw).trim().takeIf { it.isNotEmpty() }
                    }
                } else null
                val idx = b.spine.indexOfFirst {
                    it.href == href || it.href.endsWith(href)
                }
                if (idx >= 0) loadChapter(idx, anchorId = fragment)
            }
            .setOnDismissListener {
                if (barsVisible) toggleBars()
            }
            .show()
    }

    /**
     * TOC の木を深さ優先で平坦化し、(エントリ, 深さ) の列にする。
     * 深さはダイアログ表示時のインデント段数として使う。
     */
    private fun flattenToc(
        entries: List<com.jpnepub.reader.epub.TocEntry>,
        depth: Int = 0,
        out: MutableList<Pair<com.jpnepub.reader.epub.TocEntry, Int>> =
            mutableListOf(),
    ): List<Pair<com.jpnepub.reader.epub.TocEntry, Int>> {
        for (e in entries) {
            out.add(e to depth)
            if (e.children.isNotEmpty()) flattenToc(e.children, depth + 1, out)
        }
        return out
    }

    /**
     * TOC エントリの「テキスト＋外字画像」断片列を、CenteredImageSpan を埋め込んだ
     * SpannableStringBuilder に組み立てる。titleParts が無いエントリは
     * 単純なテキストにフォールバックする。
     *
     * 画像は実テキストサイズ (px) を 1em として、em-box に内接する正方形に縮小する。
     * 描画は CenteredImageSpan によりテキストの x-height 中心に画像中心が来るよう
     * 調整され、和文 1 文字とほぼ同じ大きさ・位置に並ぶ。
     */
    private fun buildTocTitleSpannable(
        book: EpubBook,
        entry: com.jpnepub.reader.epub.TocEntry,
        emPx: Int,
        depth: Int = 0,
    ): CharSequence {
        val indent = if (depth > 0) "\u3000".repeat(depth) else ""
        val parts = entry.titleParts
        if (parts == null) {
            return if (indent.isEmpty()) entry.title else indent + entry.title
        }
        val sb = SpannableStringBuilder()
        if (indent.isNotEmpty()) sb.append(indent)

        for (part in parts) {
            when (part) {
                is TitlePart.Text -> sb.append(part.text)
                is TitlePart.Image -> {
                    val drawable = loadGaijiDrawable(book, part.src, emPx)
                    if (drawable != null) {
                        val placeholderStart = sb.length
                        sb.append("\uFFFC")
                        sb.setSpan(
                            CenteredImageSpan(drawable),
                            placeholderStart,
                            sb.length,
                            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
        }
        return if (sb.isNotEmpty()) sb else (indent + entry.title)
    }

    /**
     * 外字画像を 1 文字分 (em × em) に縮小した Drawable を返す。
     * resources マップに無い場合は null。
     */
    private fun loadGaijiDrawable(book: EpubBook, src: String, emPx: Int): Drawable? {
        val bytes = book.resources[src] ?: return null
        val bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Throwable) {
            null
        } ?: return null
        val w = bmp.width.coerceAtLeast(1)
        val h = bmp.height.coerceAtLeast(1)
        val scale = minOf(emPx.toFloat() / w, emPx.toFloat() / h)
        val drawW = (w * scale).toInt().coerceAtLeast(1)
        val drawH = (h * scale).toInt().coerceAtLeast(1)
        return BitmapDrawable(resources, bmp).apply {
            setBounds(0, 0, drawW, drawH)
        }
    }

    /**
     * 画像をテキスト行の垂直中心 (ascent と descent の中間) に揃える ReplacementSpan。
     * 標準の ImageSpan(ALIGN_BASELINE/BOTTOM) は和文中心で見ると
     * 画像が上にはみ出したり下に詰まったりして並びが崩れるので、
     * 行ボックスの中央に置くことで「1 文字相当」として違和感なく並ぶ。
     */
    private class CenteredImageSpan(private val drawable: Drawable) : ReplacementSpan() {
        override fun getSize(
            paint: Paint,
            text: CharSequence?,
            start: Int,
            end: Int,
            fm: Paint.FontMetricsInt?
        ): Int {
            val rect = drawable.bounds
            if (fm != null) {
                val metrics = paint.fontMetricsInt
                val textTop = metrics.ascent
                val textBottom = metrics.descent
                val textCenter = (textTop + textBottom) / 2
                val half = rect.height() / 2
                fm.ascent = minOf(textTop, textCenter - half)
                fm.descent = maxOf(textBottom, textCenter + half)
                fm.top = fm.ascent
                fm.bottom = fm.descent
            }
            return rect.width()
        }

        override fun draw(
            canvas: Canvas,
            text: CharSequence?,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: Paint
        ) {
            // y はテキストベースライン
            val metrics = paint.fontMetricsInt
            val textCenter = y + (metrics.ascent + metrics.descent) / 2
            val half = drawable.bounds.height() / 2
            val transY = textCenter - half
            canvas.save()
            canvas.translate(x, transY.toFloat())
            drawable.draw(canvas)
            canvas.restore()
        }
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogSettingsBinding.inflate(layoutInflater)
        dialogBinding.seekFontSize.progress = config.fontSizePx
        dialogBinding.tvFontSizeValue.text = config.fontSizePx.toString()
        dialogBinding.switchVertical.isChecked = config.verticalWriting
        dialogBinding.switchRtlPage.isChecked = config.rtlPageTurn
        dialogBinding.switchDarkMode.isChecked = config.darkMode
        dialogBinding.switchBold.isChecked = config.bold

        dialogBinding.seekFontSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                dialogBinding.tvFontSizeValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("適用") { _, _ ->
                config = config.copy(
                    fontSizePx = dialogBinding.seekFontSize.progress,
                    verticalWriting = dialogBinding.switchVertical.isChecked,
                    rtlPageTurn = dialogBinding.switchRtlPage.isChecked,
                    darkMode = dialogBinding.switchDarkMode.isChecked,
                    bold = dialogBinding.switchBold.isChecked
                ).withDarkMode()
                ReaderConfig.save(this, config)
                renderer = book?.let { EpubRenderer(it, config) }
                useNativeVertical = (book?.isVertical == true) && config.verticalWriting
                val fontSizePx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    config.fontSizePx.toFloat(),
                    resources.displayMetrics
                )
                binding.verticalView.setStyle(
                    fontSizePx = fontSizePx,
                    darkMode = config.darkMode,
                    bold = config.bold,
                )
                showActiveRenderer()
                loadChapter(currentChapter)
            }
            .setNegativeButton("キャンセル", null)
            .setOnDismissListener {
                // 設定を開くには barsVisible=true の状態から入るため、
                // ダイアログ終了時にもそのままだと左右タップで頁送りが効かない
                // (barsVisible 中は super に委譲されるだけでページ操作が走らない)。
                // ここで自動的に bars を閉じて通常の閲覧モードへ戻す。
                if (barsVisible) toggleBars()
            }
            .show()
    }

    override fun onPause() {
        super.onPause()
        ReaderConfig.save(this, config)
    }

    inner class JsBridge {
        @JavascriptInterface
        fun nextChapter() { runOnUiThread { navigateNext() } }

        @JavascriptInterface
        fun prevChapter() { runOnUiThread { navigatePrev() } }

        @JavascriptInterface
        fun onPage(page: Int, total: Int) { runOnUiThread { updateProgress(page, total) } }

        @JavascriptInterface
        fun log(msg: String) { Log.d("JpnEpubPager", msg) }
    }
}
