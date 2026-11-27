package com.example.browser

import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap

data class BlockDecision(
    val blocked: Boolean,
    val reason: String
)

/**
 * Lightweight, zero-overhead integrated Ad & Tracker Blocker for WebView.
 * Supports built-in tracker lists, per-site allowlist, and user-defined custom host block rules.
 */
object AdBlockerEngine {

    private val allowlistedHosts = ConcurrentHashMap.newKeySet<String>()
    private val customBlockedHosts = ConcurrentHashMap.newKeySet<String>()

    private val blockedDomainSuffixes: Set<String> = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "google-analytics.com",
        "googletagmanager.com",
        "googletagservices.com",
        "adservice.google.com",
        "pagead2.googlesyndication.com",
        "adnxs.com",
        "adsrvr.org",
        "advertising.com",
        "amazon-adsystem.com",
        "taboola.com",
        "outbrain.com",
        "criteo.com",
        "criteo.net",
        "pubmatic.com",
        "rubiconproject.com",
        "openx.net",
        "casalemedia.com",
        "indexww.com",
        "scorecardresearch.com",
        "quantserve.com",
        "moatads.com",
        "doubleverify.com",
        "adsafeprotected.com",
        "smartadserver.com",
        "yieldmo.com",
        "sharethrough.com",
        "spotxchange.com",
        "teads.tv",
        "mgid.com",
        "revcontent.com",
        "zergnet.com",
        "bidswitch.net",
        "bluekai.com",
        "krxd.net",
        "exelator.com",
        "demdex.net",
        "omtrdc.net",
        "2o7.net",
        "hotjar.com",
        "fullstory.com",
        "mixpanel.com",
        "segment.io",
        "segment.com",
        "amplitude.com",
        "branch.io",
        "appsflyer.com",
        "adjust.com",
        "chartbeat.com",
        "chartbeat.net",
        "newrelic.com",
        "nr-data.net",
        "bugsnag.com",
        "clarity.ms",
        "bat.bing.com",
        "ads.twitter.com",
        "ads-twitter.com",
        "analytics.twitter.com",
        "ads.linkedin.com",
        "px.ads.linkedin.com",
        "snap.licdn.com",
        "connect.facebook.net",
        "an.facebook.com",
        "pixel.facebook.com",
        "ads.tiktok.com",
        "analytics.tiktok.com",
        "ads.reddit.com",
        "events.redditmedia.com",
        "ads.yahoo.com",
        "analytics.yahoo.com",
        "gemini.yahoo.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "adsterra.com",
        "adcolony.com",
        "unityads.unity3d.com",
        "applovin.com",
        "vungle.com",
        "inmobi.com",
        "mopub.com",
        "flurry.com",
        "tapjoy.com",
        "medialytics.com",
        "mediavine.com",
        "adthrive.com",
        "ezoic.net",
        "buysellads.com",
        "carbonads.net",
        "srv.carbonads.net",
        "adroll.com",
        "perfectaudience.com",
        "mathtag.com",
        "rlcdn.com",
        "crwdcntrl.net",
        "eyeota.net",
        "tapad.com",
        "liveramp.com",
        "id5-sync.com"
    )

    private val blockedHostPrefixes: List<String> = listOf(
        "ads.",
        "ad.",
        "adserver.",
        "adservice.",
        "analytics.",
        "tracker.",
        "tracking.",
        "telemetry.",
        "metrics.",
        "pixel.",
        "beacon.",
        "banners."
    )

    private val blockedPathPatterns: List<String> = listOf(
        "/pagead/",
        "/ads/js/",
        "/adservice/",
        "/gpt/pubads",
        "/adsbygoogle",
        "/tr/?id=",
        "/collect?v=",
        "/beacon.js",
        "/prebid"
    )

    fun setCustomBlockedHosts(hosts: Set<String>) {
        customBlockedHosts.clear()
        hosts.forEach { h ->
            val n = normalizeHost(h)
            if (n.isNotBlank()) customBlockedHosts.add(n)
        }
    }

    fun isCustomBlockedHost(host: String): Boolean {
        val n = normalizeHost(host)
        return n.isNotBlank() && customBlockedHosts.contains(n)
    }

    fun toggleSiteAllowlist(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return false
        return if (allowlistedHosts.contains(normalized)) {
            allowlistedHosts.remove(normalized)
            false
        } else {
            allowlistedHosts.add(normalized)
            true
        }
    }

    fun isSiteAllowlisted(host: String): Boolean {
        val normalized = normalizeHost(host)
        return normalized.isNotBlank() && allowlistedHosts.contains(normalized)
    }

    fun shouldBlock(requestUrl: String, pageHost: String, adBlockEnabled: Boolean): Boolean {
        return evaluateRequest(requestUrl, pageHost, adBlockEnabled).blocked
    }

    fun evaluateRequest(requestUrl: String, pageHost: String, adBlockEnabled: Boolean): BlockDecision {
        if (!adBlockEnabled) return BlockDecision(false, "Shield off")
        val normalizedPageHost = normalizeHost(pageHost)
        if (normalizedPageHost.isNotBlank() && allowlistedHosts.contains(normalizedPageHost)) {
            return BlockDecision(false, "Site allowlisted")
        }

        val uri = try {
            requestUrl.toUri()
        } catch (_: Exception) {
            return BlockDecision(false, "Unparsed URL")
        }

        val reqHost = normalizeHost(uri.host ?: return BlockDecision(false, "No host"))

        if (matchesHostSet(reqHost, customBlockedHosts)) {
            return BlockDecision(true, "Custom rule")
        }

        if (reqHost == normalizedPageHost) {
            val lowerPath = (uri.encodedPath ?: "").lowercase()
            return if (blockedPathPatterns.any { lowerPath.contains(it) }) {
                BlockDecision(true, "Ad script path")
            } else {
                BlockDecision(false, "First-party")
            }
        }

        if (matchesHostSet(reqHost, blockedDomainSuffixes)) {
            return BlockDecision(true, "Tracker domain")
        }

        if (blockedHostPrefixes.any { reqHost.startsWith(it) }) {
            return BlockDecision(true, "Telemetry host")
        }

        val lowerUrl = requestUrl.lowercase()
        if (blockedPathPatterns.any { lowerUrl.contains(it) }) {
            return BlockDecision(true, "Tracking pixel")
        }

        return BlockDecision(false, "Allowed")
    }

    fun createEmptyBlockedResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            ByteArrayInputStream(ByteArray(0))
        )
    }

    const val COSMETIC_AD_CLEANUP_JS: String = """
        (function() {
            if (document.getElementById('velo-adblock-style')) return;
            var style = document.createElement('style');
            style.id = 'velo-adblock-style';
            style.textContent = [
                'ins.adsbygoogle',
                '[id^="google_ads_iframe"]',
                '[id*="div-gpt-ad"]',
                '.ad-container',
                '.ad-banner',
                '.ad-slot',
                '.sponsored-ad',
                '[data-ad-slot]',
                'iframe[src*="doubleclick.net"]',
                'iframe[src*="googlesyndication.com"]'
            ].join(',') + ' { display: none !important; visibility: hidden !important; height: 0 !important; }';
            (document.head || document.documentElement).appendChild(style);
        })();
    """

    const val DARK_WEB_PAGE_ENABLE_JS: String = """
        (function() {
            if (document.getElementById('velo-dark-reader')) return;
            var style = document.createElement('style');
            style.id = 'velo-dark-reader';
            style.textContent = 'html { filter: invert(0.92) hue-rotate(180deg) !important; background: #0b0f14 !important; } ' +
                'img, video, picture, canvas, svg, [style*="background-image"] { filter: invert(1) hue-rotate(180deg) !important; }';
            (document.head || document.documentElement).appendChild(style);
        })();
    """

    const val DARK_WEB_PAGE_DISABLE_JS: String = """
        (function() {
            var el = document.getElementById('velo-dark-reader');
            if (el) el.remove();
        })();
    """

    private fun matchesHostSet(host: String, domainSet: Set<String>): Boolean {
        if (domainSet.contains(host)) return true
        var dotIndex = host.indexOf('.')
        while (dotIndex != -1) {
            val parent = host.substring(dotIndex + 1)
            if (domainSet.contains(parent)) return true
            dotIndex = host.indexOf('.', dotIndex + 1)
        }
        return false
    }

    private fun normalizeHost(host: String): String {
        return host.trim().lowercase().removePrefix("www.")
    }
}
