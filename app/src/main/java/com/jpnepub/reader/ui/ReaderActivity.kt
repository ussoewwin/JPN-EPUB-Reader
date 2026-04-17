package com.jpnepub.reader.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jpnepub.reader.R
import com.jpnepub.reader.databinding.ActivityReaderBinding
import com.jpnepub.reader.databinding.DialogSettingsBinding
import com.jpnepub.reader.epub.EpubBook
import com.jpnepub.reader.epub.EpubParser
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

    /** Vertical-writing EPUBs are rendered through the native Canvas
     *  pipeline instead of the WebView. */
    private var useNativeVertical = false
    /** Request flag: when switching chapters via "previous page" from
     *  the first page of a chapter, start the next chapter on its last
     *  page rather than its first. */
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
    //   Native vertical View setup
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
    //   WebView setup (used only for horizontal writing)
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
     * Pagination JavaScript for horizontal writing (FolioReader-style:
     * CSS multi-column layout + scrollLeft). Vertical writing uses the
     * native Canvas renderer, so this WebView path is horizontal-only.
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

    private fun loadChapter(index: Int) {
        val b = book ?: return
        if (index < 0 || index >= b.spine.size) return

        currentChapter = index
        binding.progressBar.visibility = View.VISIBLE

        if (useNativeVertical) {
            loadChapterNative(index, b)
        } else {
            loadChapterWebView(index)
        }
    }

    private fun loadChapterNative(index: Int, b: EpubBook) {
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
        binding.tvProgress.text = getString(
            R.string.progress_format,
            page + 1,
            totalPages,
            currentChapter + 1,
            b.spine.size
        )
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
            Toast.makeText(this, R.string.toc_not_available, Toast.LENGTH_SHORT).show()
            return
        }
        val titles = b.toc.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.toc)
            .setItems(titles) { _, which ->
                val href = b.toc[which].href.substringBefore('#')
                val idx = b.spine.indexOfFirst {
                    it.href == href || it.href.endsWith(href)
                }
                if (idx >= 0) loadChapter(idx)
            }
            .setOnDismissListener {
                if (barsVisible) toggleBars()
            }
            .show()
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
            .setPositiveButton(R.string.action_apply) { _, _ ->
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
            .setNegativeButton(R.string.action_cancel, null)
            .setOnDismissListener {
                // The settings dialog is opened from the "bars visible"
                // state. While bars are visible the touch handler above
                // delegates to super() without driving page turns, so
                // we auto-hide the bars here to return to a plain
                // reading mode.
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
