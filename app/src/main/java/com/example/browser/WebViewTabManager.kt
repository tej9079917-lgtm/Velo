package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

data class TabWebCallbacks(
    val onPageStarted: (tabId: String, url: String) -> Unit,
    val onPageFinished: (tabId: String, url: String, title: String, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    val onProgressChanged: (tabId: String, progress: Int) -> Unit,
    val onTitleReceived: (tabId: String, title: String) -> Unit,
    val onTrackerBlocked: (tabId: String, host: String) -> Unit,
    val onInsecureHttpIntercepted: (tabId: String, originalHttpUrl: String) -> Unit,
    val onNetworkRequestLogged: (NetworkRequestLog) -> Unit,
    val onFocusDistractionBlocked: (tabId: String, host: String) -> Unit
)

/**
 * Manages live Android WebView instances per browser tab (including simultaneous Split-Screen panes).
 * Supports Ad & Tracker blocking, live Network Request inspection, Focus distraction blocking,
 * Find-in-Page, Reader Mode DOM extraction, and Web Clipper text capture.
 */
class WebViewTabManager(
    private val context: Context,
    private val callbacks: TabWebCallbacks
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViews = ConcurrentHashMap<String, WebView>()
    private val currentHosts = ConcurrentHashMap<String, String>()
    private val httpBypassUrls = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var adBlockEnabled: Boolean = true

    @Volatile
    var httpsOnlyEnabled: Boolean = true

    @Volatile
    var darkWebPagesEnabled: Boolean = false

    @Volatile
    var focusModeActive: Boolean = false

    @Volatile
    var focusBlocklist: Set<String> = emptySet()

    @SuppressLint("SetJavaScriptEnabled")
    fun getOrCreateWebView(tab: BrowserTab): WebView {
        return webViews.getOrPut(tab.id) {
            createConfiguredWebView(tab)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createConfiguredWebView(tab: BrowserTab): WebView {
        val wv = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                useWideViewPort = true
                loadWithOverviewMode = true
                builtInZoomControls = true
                displayZoomControls = false
                setSupportZoom(true)
                if (tab.isPrivate) {
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    @Suppress("DEPRECATION")
                    saveFormData = false
                } else {
                    domStorageEnabled = true
                    cacheMode = WebSettings.LOAD_DEFAULT
                }
            }

            if (tab.isPrivate) {
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            } else {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    callbacks.onProgressChanged(tab.id, newProgress)
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    if (!title.isNullOrBlank()) {
                        callbacks.onTitleReceived(tab.id, title)
                    }
                }
            }

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    val scheme = uri.scheme?.lowercase() ?: return true
                    if (scheme != "http" && scheme != "https" && scheme != "about") {
                        return true
                    }
                    val host = (uri.host ?: "").lowercase().removePrefix("www.")
                    if (isHostBlockedByFocus(host)) {
                        callbacks.onFocusDistractionBlocked(tab.id, host)
                        return true
                    }
                    if (scheme == "http" && httpsOnlyEnabled && !httpBypassUrls.contains(uri.toString())) {
                        callbacks.onInsecureHttpIntercepted(tab.id, uri.toString())
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return null
                    if (request.isForMainFrame) return null
                    val pageHost = currentHosts[tab.id].orEmpty()
                    val decision = AdBlockerEngine.evaluateRequest(reqUrl, pageHost, adBlockEnabled)
                    val reqHost = request.url?.host.orEmpty()
                    val resType = classifyResourceType(reqUrl)

                    mainHandler.post {
                        callbacks.onNetworkRequestLogged(
                            NetworkRequestLog(
                                tabId = tab.id,
                                host = reqHost.ifBlank { "unknown" },
                                url = reqUrl,
                                resourceType = resType,
                                wasBlocked = decision.blocked,
                                reason = decision.reason
                            )
                        )
                        if (decision.blocked) {
                            callbacks.onTrackerBlocked(tab.id, reqHost)
                        }
                    }

                    return if (decision.blocked) {
                        AdBlockerEngine.createEmptyBlockedResponse()
                    } else {
                        super.shouldInterceptRequest(view, request)
                    }
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    val safeUrl = url.orEmpty()
                    currentHosts[tab.id] = safeUrl.toUri().host.orEmpty()
                    callbacks.onPageStarted(tab.id, safeUrl)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val safeUrl = url.orEmpty()
                    currentHosts[tab.id] = safeUrl.toUri().host.orEmpty()
                    if (view != null && (safeUrl.startsWith("http://") || safeUrl.startsWith("https://"))) {
                        if (adBlockEnabled) {
                            view.evaluateJavascript(AdBlockerEngine.COSMETIC_AD_CLEANUP_JS, null)
                        }
                        applyDarkPageStyle(view, darkWebPagesEnabled)
                    }
                    callbacks.onPageFinished(
                        tab.id,
                        safeUrl,
                        view?.title.orEmpty().ifBlank { safeUrl.toUri().host ?: safeUrl },
                        view?.canGoBack() == true,
                        view?.canGoForward() == true
                    )
                }
            }
        }
        return wv
    }

    fun isHostBlockedByFocus(rawHost: String): Boolean {
        if (!focusModeActive || focusBlocklist.isEmpty()) return false
        val host = rawHost.trim().lowercase().removePrefix("www.")
        if (host.isBlank()) return false
        return focusBlocklist.any { blocked ->
            host == blocked || host.endsWith(".$blocked")
        }
    }

    fun loadUrl(tab: BrowserTab, url: String, bypassHttpsCheck: Boolean = false): Boolean {
        val host = url.toUri().host.orEmpty().lowercase().removePrefix("www.")
        if (isHostBlockedByFocus(host)) {
            callbacks.onFocusDistractionBlocked(tab.id, host)
            return false
        }
        val wv = getOrCreateWebView(tab)
        if (bypassHttpsCheck) {
            httpBypassUrls.add(url)
        }
        currentHosts[tab.id] = host
        wv.loadUrl(url)
        return true
    }

    fun loadOfflineHtml(tab: BrowserTab, baseUrl: String, html: String) {
        val wv = getOrCreateWebView(tab)
        currentHosts[tab.id] = baseUrl.toUri().host.orEmpty()
        wv.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    fun applyDesktopMode(tabId: String, desktop: Boolean) {
        val wv = webViews[tabId] ?: return
        wv.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        } else {
            null
        }
        wv.reload()
    }

    fun applyDarkPagesToAll(enabled: Boolean) {
        darkWebPagesEnabled = enabled
        webViews.values.forEach { wv ->
            applyDarkPageStyle(wv, enabled)
        }
    }

    private fun applyDarkPageStyle(webView: WebView, enabled: Boolean) {
        webView.evaluateJavascript(
            if (enabled) AdBlockerEngine.DARK_WEB_PAGE_ENABLE_JS else AdBlockerEngine.DARK_WEB_PAGE_DISABLE_JS,
            null
        )
    }

    // --- FIND IN PAGE ---

    fun findInPage(tabId: String, query: String, onResults: (activeMatch: Int, totalMatches: Int) -> Unit) {
        val wv = webViews[tabId] ?: return
        if (query.isBlank()) {
            wv.clearMatches()
            onResults(0, 0)
            return
        }
        wv.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            val displayActive = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
            onResults(displayActive, numberOfMatches)
        }
        wv.findAllAsync(query)
    }

    fun findNext(tabId: String, forward: Boolean) {
        webViews[tabId]?.findNext(forward)
    }

    fun clearFindMatches(tabId: String) {
        webViews[tabId]?.clearMatches()
    }

    // --- READER MODE DOM EXTRACTION ---

    fun extractReadableArticle(tabId: String, fallbackUrl: String, fallbackTitle: String, onExtracted: (ReaderArticle) -> Unit) {
        val wv = webViews[tabId]
        val host = fallbackUrl.toUri().host.orEmpty().removePrefix("www.")
        if (wv == null) {
            onExtracted(
                ReaderArticle(
                    title = fallbackTitle.ifBlank { host },
                    host = host,
                    url = fallbackUrl,
                    paragraphs = listOf("Open a web article first to use Reader Mode."),
                    readingMinutes = 1
                )
            )
            return
        }
        val js = """
            (function() {
                var title = document.title || '';
                var root = document.querySelector('article') || document.querySelector('main') || document.body;
                var nodes = root ? root.querySelectorAll('h1, h2, h3, p, li, blockquote') : [];
                var out = [];
                for (var i = 0; i < nodes.length && out.length < 180; i++) {
                    var t = (nodes[i].innerText || '').replace(/\s+/g, ' ').trim();
                    if (t.length >= 28) out.push(t);
                }
                if (out.length === 0 && document.body) {
                    var raw = (document.body.innerText || '').split(/\n+/).map(function(s){return s.trim();}).filter(function(s){return s.length >= 24;});
                    out = raw.slice(0, 120);
                }
                return JSON.stringify({ title: title, paragraphs: out });
            })();
        """.trimIndent()

        wv.evaluateJavascript(js) { rawResult ->
            try {
                val decoded = JSONTokenerUnwrap(rawResult)
                val obj = JSONObject(decoded)
                val title = obj.optString("title", "").ifBlank { fallbackTitle.ifBlank { host } }
                val arr: JSONArray = obj.optJSONArray("paragraphs") ?: JSONArray()
                val paras = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i).trim()
                    if (p.isNotBlank()) paras.add(p)
                }
                val finalParas = if (paras.isEmpty()) {
                    listOf("No structured article paragraphs were detected on this page.")
                } else {
                    paras
                }
                val wordCount = finalParas.sumOf { it.split(Regex("\\s+")).size }
                val minutes = max(1, wordCount / 210)
                onExtracted(
                    ReaderArticle(
                        title = title,
                        host = host,
                        url = fallbackUrl,
                        paragraphs = finalParas,
                        readingMinutes = minutes
                    )
                )
            } catch (_: Exception) {
                onExtracted(
                    ReaderArticle(
                        title = fallbackTitle.ifBlank { host },
                        host = host,
                        url = fallbackUrl,
                        paragraphs = listOf("Unable to extract readable text from this page."),
                        readingMinutes = 1
                    )
                )
            }
        }
    }

    // --- WEB CLIPPER SELECTION EXTRACTION ---

    fun extractClipSnippet(tabId: String, onResult: (selectedOrSummary: String) -> Unit) {
        val wv = webViews[tabId]
        if (wv == null) {
            onResult("")
            return
        }
        val js = """
            (function() {
                var sel = window.getSelection ? window.getSelection().toString().trim() : '';
                if (sel && sel.length > 0) return sel.slice(0, 1200);
                var meta = document.querySelector('meta[name="description"]');
                if (meta && meta.content) return meta.content.trim().slice(0, 600);
                var p = document.querySelector('article p, main p, p');
                return p ? (p.innerText || '').trim().slice(0, 600) : '';
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { rawResult ->
            onResult(JSONTokenerUnwrap(rawResult))
        }
    }

    private fun JSONTokenerUnwrap(rawJsReturn: String?): String {
        if (rawJsReturn.isNullOrBlank() || rawJsReturn == "null") return ""
        return try {
            JSONObject("{\"v\":$rawJsReturn}").optString("v", "")
        } catch (_: Exception) {
            rawJsReturn.trim('"')
        }
    }

    private fun classifyResourceType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".js") -> "SCRIPT"
            path.endsWith(".css") -> "STYLE"
            path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") ||
                path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".svg") -> "IMAGE"
            path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf") -> "FONT"
            path.contains("/api/") || path.endsWith(".json") -> "XHR"
            else -> "SUB"
        }
    }

    fun goBack(tabId: String): Boolean {
        val wv = webViews[tabId] ?: return false
        return if (wv.canGoBack()) {
            wv.goBack()
            true
        } else {
            false
        }
    }

    fun goForward(tabId: String) {
        val wv = webViews[tabId] ?: return
        if (wv.canGoForward()) wv.goForward()
    }

    fun reload(tabId: String) {
        webViews[tabId]?.reload()
    }

    fun stopLoading(tabId: String) {
        webViews[tabId]?.stopLoading()
    }

    fun autofillCredentials(tabId: String, username: String, password: String) {
        val wv = webViews[tabId] ?: return
        val safeUser = JSONObject.quote(username)
        val safePass = JSONObject.quote(password)
        val js = """
            (function() {
                var u = $safeUser;
                var p = $safePass;
                var passInputs = document.querySelectorAll('input[type="password"]');
                var userInputs = document.querySelectorAll('input[type="email"], input[type="text"], input[name*="user"], input[name*="login"], input[name*="email"]');
                function setNativeValue(el, val) {
                    if (!el) return;
                    el.focus();
                    el.value = val;
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
                if (userInputs.length > 0) setNativeValue(userInputs[0], u);
                if (passInputs.length > 0) setNativeValue(passInputs[0], p);
            })();
        """.trimIndent()
        wv.evaluateJavascript(js, null)
    }

    fun destroyTab(tabId: String, wasPrivate: Boolean) {
        webViews.remove(tabId)?.let { wv ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            if (wasPrivate) {
                wv.clearCache(true)
                wv.clearHistory()
                wv.clearFormData()
            }
            wv.destroy()
        }
        currentHosts.remove(tabId)
    }

    fun wipePrivateSessionData(privateTabIds: List<String>) {
        privateTabIds.forEach { destroyTab(it, wasPrivate = true) }
        CookieManager.getInstance().removeSessionCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    fun destroyAll() {
        val keys = webViews.keys.toList()
        keys.forEach { destroyTab(it, wasPrivate = false) }
        currentHosts.clear()
        httpBypassUrls.clear()
    }
}
