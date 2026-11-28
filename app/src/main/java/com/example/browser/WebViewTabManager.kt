package com.example.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.Keep
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
    val onFocusDistractionBlocked: (tabId: String, host: String) -> Unit,
    val onVideoDetected: (tabId: String, media: DetectedVideoMedia) -> Unit = { _, _ -> },
    val onImagesDetected: (tabId: String, images: List<DetectedImageMedia>) -> Unit = { _, _ -> },
    val onDownloadRequested: (
        tabId: String,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        referer: String?
    ) -> Unit = { _, _, _, _, _, _ -> },
    val onInlineDataDownload: (
        fileName: String,
        mimeType: String,
        base64Data: String
    ) -> Unit = { _, _, _ -> },
    val onImageLongPressed: (
        imageUrl: String,
        referer: String?
    ) -> Unit = { _, _ -> }
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
                mediaPlaybackRequiresUserGesture = false
                if (tab.isPrivate) {
                    domStorageEnabled = false
                    cacheMode = WebSettings.LOAD_NO_CACHE
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                        @Suppress("DEPRECATION")
                        saveFormData = false
                    }
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

                override fun onPermissionRequest(request: PermissionRequest?) {
                    val req = request ?: return
                    val originScheme = req.origin?.scheme?.lowercase()
                    if (originScheme != "https") {
                        req.deny()
                        return
                    }
                    // Grant EME/Protected Media ID on HTTPS origins (required for Spotify Web playback)
                    val allowed = req.resources.filter { res ->
                        res == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    if (allowed.isNotEmpty()) {
                        req.grant(allowed)
                    } else {
                        req.deny()
                    }
                }
            }

            // Minimal, non-privileged bridge strictly for blob: download handoff
            addJavascriptInterface(
                VeloBlobDownloadBridge { fileName, mimeType, base64Data ->
                    mainHandler.post {
                        callbacks.onInlineDataDownload(fileName, mimeType, base64Data)
                    }
                },
                BLOB_BRIDGE_NAME
            )

            // Universal WebView Download Listener (handles HTTP/HTTPS, blob:, and data: downloads)
            setDownloadListener { downloadUrl, userAgent, contentDisposition, mimetype, _ ->
                val raw = downloadUrl?.trim().orEmpty()
                if (raw.isEmpty()) return@setDownloadListener
                val safeMime = mimetype.orEmpty()
                val safeDisposition = contentDisposition.orEmpty()
                val safeUa = userAgent.orEmpty().ifBlank { settings.userAgentString.orEmpty() }
                val referer = this.url

                when {
                    raw.startsWith("data:", ignoreCase = true) -> {
                        val header = raw.substringAfter("data:").substringBefore(",", "")
                        val inferredMime = header.substringBefore(";").ifBlank { safeMime.ifBlank { "application/octet-stream" } }
                        val payload = raw.substringAfter(",", "")
                        val guessed = try {
                            URLUtil.guessFileName("velo_download", safeDisposition, inferredMime)
                        } catch (_: Exception) {
                            "velo_download"
                        }
                        if (payload.isNotBlank()) {
                            callbacks.onInlineDataDownload(guessed, inferredMime, payload)
                        }
                    }

                    raw.startsWith("blob:", ignoreCase = true) -> {
                        val guessed = try {
                            URLUtil.guessFileName("velo_blob", safeDisposition, safeMime)
                        } catch (_: Exception) {
                            "velo_download"
                        }
                        val quotedUrl = JSONObject.quote(raw)
                        val quotedName = JSONObject.quote(guessed)
                        val quotedMime = JSONObject.quote(safeMime)
                        val blobFetchJs = """
                            (function() {
                                fetch($quotedUrl)
                                    .then(function(r) { return r.blob(); })
                                    .then(function(blob) {
                                        var reader = new FileReader();
                                        reader.onloadend = function() {
                                            if (reader.result && window.$BLOB_BRIDGE_NAME) {
                                                window.$BLOB_BRIDGE_NAME.onBlobReady($quotedName, blob.type || $quotedMime || 'application/octet-stream', String(reader.result));
                                            }
                                        };
                                        reader.readAsDataURL(blob);
                                    })
                                    .catch(function(){});
                            })();
                        """.trimIndent()
                        evaluateJavascript(blobFetchJs, null)
                    }

                    raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true) -> {
                        callbacks.onDownloadRequested(tab.id, raw, safeUa, safeDisposition, safeMime, referer)
                    }
                }
            }

            // Long-press on webpage images -> prompt to download or open in Velo Image Viewer
            setOnLongClickListener {
                val hit = hitTestResult
                val extra = hit.extra?.trim().orEmpty()
                val isImage = hit.type == WebView.HitTestResult.IMAGE_TYPE ||
                    hit.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                if (isImage && (extra.startsWith("http://", ignoreCase = true) ||
                        extra.startsWith("https://", ignoreCase = true) ||
                        extra.startsWith("data:image/", ignoreCase = true))
                ) {
                    callbacks.onImageLongPressed(extra, this.url)
                    true
                } else {
                    false
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
                    // Direct downloadable file links: hand off cleanly to Velo Downloader so files never stall in WebView
                    val pathLower = uri.path?.lowercase().orEmpty()
                    val matchedDownloadMime = DIRECT_DOWNLOAD_EXTENSIONS.entries.firstOrNull { (ext, _) ->
                        pathLower.endsWith(ext)
                    }?.value
                    if ((scheme == "http" || scheme == "https") && matchedDownloadMime != null) {
                        callbacks.onDownloadRequested(
                            tab.id,
                            uri.toString(),
                            view?.settings?.userAgentString.orEmpty(),
                            "",
                            matchedDownloadMime,
                            view?.url
                        )
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
                        } else {
                            detectVideoFromRequestUrl(reqUrl, tab.title)?.let { media ->
                                callbacks.onVideoDetected(tab.id, media)
                            }
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
                        WebAppService.getEnhancementScriptForUrl(safeUrl)?.let { appScript ->
                            view.evaluateJavascript(appScript, null)
                        }
                        applyDarkPageStyle(view, darkWebPagesEnabled)
                        scanPageForVideos(tab.id)
                        scanPageForImages(tab.id)
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
        val targetUa = WebAppService.requiredUserAgentForUrl(url, tab.isDesktopMode)
        if (wv.settings.userAgentString != targetUa) {
            wv.settings.userAgentString = targetUa
        }
        // Enable 3rd-party cookies selectively for Spotify/WhatsApp/Instagram auth flows when not private
        val matchedApp = WebAppService.matchForUrl(url)
        if (!tab.isPrivate) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, matchedApp != null)
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

    // --- PLAYABLE VIDEO DETECTOR & WEBVIEW ACCESSOR ---

    fun getWebView(tabId: String): WebView? = webViews[tabId]

    fun scanPageForVideos(tabId: String) {
        val wv = webViews[tabId] ?: return
        val fallbackTitle = wv.title.orEmpty()
        val js = """
            (function() {
                var results = [];
                var seen = {};
                function pushUrl(u, q, playing) {
                    if (!u || seen[u]) return;
                    if (u.indexOf('http://') !== 0 && u.indexOf('https://') !== 0) return;
                    seen[u] = true;
                    results.push({ url: u, quality: q || 'Auto', playing: !!playing });
                }
                function scanDoc(doc) {
                    if (!doc) return;
                    var videos = doc.querySelectorAll('video');
                    for (var i = 0; i < videos.length; i++) {
                        var v = videos[i];
                        var h = v.videoHeight || 0;
                        var q = h >= 2160 ? '4K' : (h >= 1080 ? '1080p' : (h >= 720 ? '720p' : (h >= 480 ? '480p' : (h > 0 ? (h + 'p') : 'Auto'))));
                        var playing = !v.paused && !v.ended && v.readyState > 1;
                        if (v.currentSrc) pushUrl(v.currentSrc, q, playing);
                        if (v.src) pushUrl(v.src, q, playing);
                        var sources = v.querySelectorAll('source');
                        for (var j = 0; j < sources.length; j++) {
                            var s = sources[j];
                            var label = s.getAttribute('label') || s.getAttribute('size') || q;
                            if (s.src) pushUrl(s.src, label, playing);
                        }
                    }
                    var metas = doc.querySelectorAll('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]');
                    for (var m = 0; m < metas.length; m++) {
                        var c = metas[m].getAttribute('content');
                        if (c) pushUrl(c, 'HD', false);
                    }
                }
                scanDoc(document);
                try {
                    var frames = document.querySelectorAll('iframe');
                    for (var f = 0; f < frames.length; f++) {
                        try { scanDoc(frames[f].contentDocument); } catch (e) {}
                    }
                } catch (e) {}
                try {
                    if (window.performance && performance.getEntriesByType) {
                        var entries = performance.getEntriesByType('resource') || [];
                        for (var k = 0; k < entries.length; k++) {
                            var name = (entries[k] && entries[k].name) ? String(entries[k].name) : '';
                            var low = name.toLowerCase().split('?')[0];
                            if (low.endsWith('.mp4') || low.endsWith('.m3u8') || low.endsWith('.webm') || low.endsWith('.mkv') || low.endsWith('.mov') || name.toLowerCase().indexOf('mime=video') !== -1) {
                                pushUrl(name, 'Stream', true);
                            }
                        }
                    }
                } catch (e) {}
                return JSON.stringify({ title: document.title || '', items: results.slice(0, 20) });
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { rawResult ->
            try {
                val decoded = JSONTokenerUnwrap(rawResult)
                if (decoded.isBlank()) return@evaluateJavascript
                val obj = JSONObject(decoded)
                val pageTitle = obj.optString("title", "").ifBlank { fallbackTitle }
                val arr = obj.optJSONArray("items") ?: return@evaluateJavascript
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val mediaUrl = item.optString("url", "")
                    if ((mediaUrl.startsWith("http://") || mediaUrl.startsWith("https://")) &&
                        !com.example.media.VideoDownloadEngine.isRestrictedVideoHost(mediaUrl)
                    ) {
                        val quality = item.optString("quality", "Auto").ifBlank { "Auto" }
                        val playing = item.optBoolean("playing", false)
                        val format = detectFormatLabel(mediaUrl)
                        callbacks.onVideoDetected(
                            tabId,
                            DetectedVideoMedia(
                                url = mediaUrl,
                                title = pageTitle.ifBlank { mediaUrl.toUri().host ?: "Web Video" },
                                formatLabel = format,
                                qualityLabel = quality,
                                isPlaying = playing
                            )
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun scanPageForImages(tabId: String) {
        val wv = webViews[tabId] ?: return
        val fallbackTitle = wv.title.orEmpty()
        val js = """
            (function() {
                var results = [];
                var seen = {};
                function resolveUrl(u) {
                    if (!u) return '';
                    u = u.trim();
                    if (u.indexOf('data:image/') === 0) return u;
                    try { return new URL(u, document.baseURI).href; } catch (e) { return u; }
                }
                function bestFromSrcset(srcset) {
                    if (!srcset) return '';
                    var parts = srcset.split(',');
                    var bestUrl = '';
                    var bestScore = -1;
                    for (var i = 0; i < parts.length; i++) {
                        var seg = parts[i].trim().split(/\s+/);
                        if (!seg[0]) continue;
                        var score = 1;
                        if (seg[1]) {
                            var num = parseFloat(seg[1]);
                            if (!isNaN(num)) score = seg[1].indexOf('x') !== -1 ? num * 1000 : num;
                        }
                        if (score >= bestScore) {
                            bestScore = score;
                            bestUrl = seg[0];
                        }
                    }
                    return bestUrl;
                }
                function pushImg(rawUrl, alt, w, h) {
                    var abs = resolveUrl(rawUrl);
                    if (!abs || seen[abs]) return;
                    if (abs.indexOf('http://') !== 0 && abs.indexOf('https://') !== 0 && abs.indexOf('data:image/') !== 0) return;
                    if (abs.indexOf('data:image/') === 0 && abs.length < 400) return;
                    seen[abs] = true;
                    results.push({ url: abs, alt: (alt || '').trim().slice(0, 60), w: w || 0, h: h || 0 });
                }
                var ogImg = document.querySelector('meta[property="og:image"], meta[name="twitter:image"]');
                if (ogImg && ogImg.content) pushImg(ogImg.content, document.title || 'Featured Image', 0, 0);

                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length && results.length < 40; i++) {
                    var img = imgs[i];
                    var w = img.naturalWidth || img.width || 0;
                    var h = img.naturalHeight || img.height || 0;
                    if ((w > 0 && w < 48) || (h > 0 && h < 48)) continue;
                    var fullCandidate = img.getAttribute('data-original') ||
                        img.getAttribute('data-full-url') ||
                        img.getAttribute('data-large-file') ||
                        img.getAttribute('data-hires') ||
                        bestFromSrcset(img.getAttribute('srcset')) ||
                        img.getAttribute('data-src') ||
                        img.currentSrc ||
                        img.src;
                    var parentLink = img.closest ? img.closest('a[href]') : null;
                    if (parentLink && parentLink.href) {
                        var hrefLow = parentLink.href.toLowerCase().split('?')[0];
                        if (hrefLow.endsWith('.jpg') || hrefLow.endsWith('.jpeg') || hrefLow.endsWith('.png') || hrefLow.endsWith('.webp')) {
                            fullCandidate = parentLink.href;
                        }
                    }
                    pushImg(fullCandidate, img.alt || img.title || '', w, h);
                }

                var sources = document.querySelectorAll('picture source[srcset]');
                for (var s = 0; s < sources.length && results.length < 45; s++) {
                    var best = bestFromSrcset(sources[s].getAttribute('srcset'));
                    if (best) pushImg(best, document.title || 'Web Image', 0, 0);
                }

                return JSON.stringify({ title: document.title || '', items: results.slice(0, 40) });
            })();
        """.trimIndent()
        wv.evaluateJavascript(js) { rawResult ->
            try {
                val decoded = JSONTokenerUnwrap(rawResult)
                if (decoded.isBlank()) return@evaluateJavascript
                val obj = JSONObject(decoded)
                val pageTitle = obj.optString("title", "").ifBlank { fallbackTitle }
                val arr = obj.optJSONArray("items") ?: return@evaluateJavascript
                val detected = mutableListOf<DetectedImageMedia>()
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val imgUrl = item.optString("url", "")
                    if (imgUrl.isBlank()) continue
                    val alt = item.optString("alt", "").ifBlank {
                        if (imgUrl.startsWith("data:")) "Inline Image ${i + 1}"
                        else imgUrl.toUri().lastPathSegment?.substringBeforeLast('.')?.take(36)
                            ?.ifBlank { pageTitle.ifBlank { "Image ${i + 1}" } }
                            ?: "Image ${i + 1}"
                    }
                    val w = item.optInt("w", 0)
                    val h = item.optInt("h", 0)
                    val fmt = detectImageFormatLabel(imgUrl)
                    detected.add(
                        DetectedImageMedia(
                            url = imgUrl,
                            title = alt,
                            width = w,
                            height = h,
                            formatLabel = fmt
                        )
                    )
                }
                callbacks.onImagesDetected(tabId, detected)
            } catch (_: Exception) {
            }
        }
    }

    private fun detectImageFormatLabel(url: String): String {
        val low = url.substringBefore('?').lowercase()
        return when {
            low.endsWith(".png") || low.startsWith("data:image/png") -> "PNG"
            low.endsWith(".webp") || low.startsWith("data:image/webp") -> "WEBP"
            low.endsWith(".gif") || low.startsWith("data:image/gif") -> "GIF"
            low.endsWith(".svg") || low.startsWith("data:image/svg") -> "SVG"
            low.endsWith(".jpg") || low.endsWith(".jpeg") || low.startsWith("data:image/jpeg") -> "JPG"
            else -> "IMG"
        }
    }

    private fun detectVideoFromRequestUrl(url: String, pageTitle: String): DetectedVideoMedia? {
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            return null
        }
        if (com.example.media.VideoDownloadEngine.isRestrictedVideoHost(url)) {
            return null
        }
        val lower = url.lowercase()
        val path = lower.substringBefore('?').substringBefore('#')
        val isMedia = path.endsWith(".mp4") ||
            path.endsWith(".webm") ||
            path.endsWith(".m3u8") ||
            path.endsWith(".mkv") ||
            path.endsWith(".mov") ||
            path.endsWith(".m4v") ||
            lower.contains("mime=video")
        if (!isMedia) return null
        val quality = when {
            lower.contains("2160") || lower.contains("4k") -> "4K"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            else -> "HD"
        }
        return DetectedVideoMedia(
            url = url,
            title = pageTitle.ifBlank { url.toUri().host ?: "Detected Video" },
            formatLabel = detectFormatLabel(url),
            qualityLabel = quality,
            isPlaying = true
        )
    }

    private fun detectFormatLabel(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "HLS"
            path.endsWith(".webm") -> "WEBM"
            path.endsWith(".mkv") -> "MKV"
            path.endsWith(".mov") -> "MOV"
            path.endsWith(".m4v") -> "M4V"
            else -> "MP4"
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

    @Keep
    private class VeloBlobDownloadBridge(
        private val onReady: (fileName: String, mimeType: String, base64Data: String) -> Unit
    ) {
        @JavascriptInterface
        fun onBlobReady(fileName: String?, mimeType: String?, dataUrl: String?) {
            val payload = dataUrl?.trim().orEmpty()
            if (payload.isBlank() || payload.length > MAX_BRIDGE_CHARS) return
            onReady(
                fileName?.trim().orEmpty().ifBlank { "velo_download" },
                mimeType?.trim().orEmpty().ifBlank { "application/octet-stream" },
                payload
            )
        }
    }

    companion object {
        private const val BLOB_BRIDGE_NAME = "VeloBlobBridge"
        private const val MAX_BRIDGE_CHARS = 40 * 1024 * 1024
        private val DIRECT_DOWNLOAD_EXTENSIONS = mapOf(
            ".pdf" to "application/pdf",
            ".zip" to "application/zip",
            ".rar" to "application/vnd.rar",
            ".7z" to "application/x-7z-compressed",
            ".tar" to "application/x-tar",
            ".gz" to "application/gzip",
            ".apk" to "application/vnd.android.package-archive",
            ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            ".doc" to "application/msword",
            ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            ".xls" to "application/vnd.ms-excel",
            ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            ".ppt" to "application/vnd.ms-powerpoint",
            ".epub" to "application/epub+zip",
            ".mp3" to "audio/mpeg",
            ".m4a" to "audio/mp4",
            ".wav" to "audio/wav",
            ".flac" to "audio/flac",
            ".ogg" to "audio/ogg",
            ".aac" to "audio/aac",
            ".csv" to "text/csv"
        )
    }
}
