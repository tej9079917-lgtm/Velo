package com.example.browser

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

enum class WebAppIntegrationMode(val badge: String) {
    MOBILE_WEB("WEB APP"),
    DESKTOP_MOBILE_CSS("DESKTOP + MOBILE CSS"),
    DRM_BACKGROUND_AUDIO("DRM + BG AUDIO"),
    HYBRID_NATIVE("HYBRID HD/4K")
}

/**
 * Unified All-in-One Web Apps Hub defining per-site User-Agent spoofing,
 * site-specific CSS/JS mobile adaptations, background audio support, and
 * hybrid native Android app Intents for Widevine L1 streaming apps (Netflix & Prime Video).
 */
enum class WebAppService(
    val id: String,
    val label: String,
    val shortLabel: String,
    val url: String,
    val hostMatch: String,
    val accentHex: String,
    val mode: WebAppIntegrationMode,
    val nativePackageName: String?,
    val description: String
) {
    INSTAGRAM(
        id = "app_instagram",
        label = "Instagram",
        shortLabel = "Insta",
        url = "https://www.instagram.com/",
        hostMatch = "instagram.com",
        accentHex = "#E1306C",
        mode = WebAppIntegrationMode.MOBILE_WEB,
        nativePackageName = "com.instagram.android",
        description = "Mobile web Reels, Stories, DMs & Feed with 'Open in App' banners auto-hidden."
    ),
    WHATSAPP(
        id = "app_whatsapp",
        label = "WhatsApp",
        shortLabel = "WhatsApp",
        url = "https://web.whatsapp.com/",
        hostMatch = "web.whatsapp.com",
        accentHex = "#25D366",
        mode = WebAppIntegrationMode.DESKTOP_MOBILE_CSS,
        nativePackageName = "com.whatsapp",
        description = "Desktop User-Agent + responsive single-column mobile CSS for phone screens."
    ),
    SPOTIFY(
        id = "app_spotify",
        label = "Spotify",
        shortLabel = "Spotify",
        url = "https://open.spotify.com/",
        hostMatch = "open.spotify.com",
        accentHex = "#1DB954",
        mode = WebAppIntegrationMode.DRM_BACKGROUND_AUDIO,
        nativePackageName = "com.spotify.music",
        description = "Desktop Web Player + EME protected media + Background Audio Foreground Service."
    ),
    NETFLIX(
        id = "app_netflix",
        label = "Netflix",
        shortLabel = "Netflix",
        url = "https://www.netflix.com/",
        hostMatch = "netflix.com",
        accentHex = "#E50914",
        mode = WebAppIntegrationMode.HYBRID_NATIVE,
        nativePackageName = "com.netflix.mediaclient",
        description = "Hybrid launcher: opens official Netflix app for hardware Widevine L1 HD/4K, or web catalog."
    ),
    PRIME_VIDEO(
        id = "app_prime",
        label = "Prime Video",
        shortLabel = "Prime",
        url = "https://www.primevideo.com/",
        hostMatch = "primevideo.com",
        accentHex = "#00A8E1",
        mode = WebAppIntegrationMode.HYBRID_NATIVE,
        nativePackageName = "com.amazon.avod.thirdpartyclient",
        description = "Hybrid launcher: opens official Prime Video app for hardware Widevine L1 HD/4K, or web view."
    );

    companion object {
        const val DESKTOP_LINUX_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        const val MOBILE_ANDROID_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun matchForUrl(url: String): WebAppService? {
            val host = try {
                url.toUri().host.orEmpty().lowercase().removePrefix("www.")
            } catch (_: Exception) {
                return null
            }
            if (host.isBlank()) return null
            return entries.firstOrNull { service ->
                host == service.hostMatch || host.endsWith(".${service.hostMatch}")
            }
        }

        fun requiredUserAgentForUrl(url: String, manualDesktopMode: Boolean): String? {
            if (manualDesktopMode) return DESKTOP_LINUX_UA
            return when (matchForUrl(url)) {
                WHATSAPP, SPOTIFY, NETFLIX, PRIME_VIDEO -> DESKTOP_LINUX_UA
                INSTAGRAM -> MOBILE_ANDROID_UA
                null -> null
            }
        }

        fun isNativeAppInstalled(context: Context, service: WebAppService): Boolean {
            val pkg = service.nativePackageName ?: return false
            return try {
                context.packageManager.getLaunchIntentForPackage(pkg) != null
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Launches the official Android app cleanly via Intent.ACTION_VIEW / package launch
         * so Widevine L1 HD/4K playback works seamlessly for Netflix & Prime Video.
         * Returns true if the native app launched, false if not installed.
         */
        fun launchNativeAppIfInstalled(context: Context, service: WebAppService): Boolean {
            val pkg = service.nativePackageName ?: return false
            return try {
                val intent = Intent(Intent.ACTION_VIEW, service.url.toUri()).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    true
                } else {
                    val launch = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    if (launch != null) {
                        context.startActivity(launch)
                        true
                    } else {
                        false
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Returns site-specific CSS/JS enhancements injected on page load for an APK-like UI:
         * - Instagram: hides 'Open in App' / 'Get the Instagram app' banners.
         * - WhatsApp Web: collapses desktop 2-column layout into a clean single-column mobile chat UI
         *   with an automatic Back-to-Chats button.
         * - Spotify Web: hides 'Open in App' / download-app banners cleanly.
         */
        fun getEnhancementScriptForUrl(url: String): String? {
            return when (matchForUrl(url)) {
                INSTAGRAM -> INSTAGRAM_ENHANCE_JS
                WHATSAPP -> WHATSAPP_MOBILE_ADAPT_JS
                SPOTIFY -> SPOTIFY_ENHANCE_JS
                else -> null
            }
        }

        private val INSTAGRAM_ENHANCE_JS = """
            (function() {
                if (document.getElementById('velo-ig-css')) return;
                var style = document.createElement('style');
                style.id = 'velo-ig-css';
                style.textContent =
                    'a[href*="instagram://"], div[role="banner"], .x1n2onr6 [href*="play.google.com"] { display: none !important; } ' +
                    'body { overscroll-behavior-y: contain; }';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()

        private val WHATSAPP_MOBILE_ADAPT_JS = """
            (function() {
                if (document.getElementById('velo-wa-css')) return;
                var style = document.createElement('style');
                style.id = 'velo-wa-css';
                style.textContent =
                    'html, body, #app, .app-wrapper-web { min-width: 100vw !important; width: 100vw !important; overflow-x: hidden !important; } ' +
                    'div.two, div._aigs, div[data-testid="chat-list"] { min-width: 100vw !important; max-width: 100vw !important; } ' +
                    '#side { width: 100vw !important; max-width: 100vw !important; flex: 0 0 100vw !important; } ' +
                    '#main { width: 100vw !important; max-width: 100vw !important; flex: 0 0 100vw !important; } ' +
                    'body.velo-wa-chat-open #side { display: none !important; } ' +
                    'body:not(.velo-wa-chat-open) #main { display: none !important; } ' +
                    '#velo-wa-back-btn { position: fixed; bottom: 78px; right: 14px; z-index: 99999; ' +
                    'background: #25D366; color: #042F2E; border: none; border-radius: 999px; ' +
                    'padding: 10px 14px; font-weight: 700; font-size: 13px; box-shadow: 0 4px 12px rgba(0,0,0,0.4); }';
                (document.head || document.documentElement).appendChild(style);

                var btn = document.createElement('button');
                btn.id = 'velo-wa-back-btn';
                btn.textContent = '← Chats';
                btn.style.display = 'none';
                btn.onclick = function() {
                    document.body.classList.remove('velo-wa-chat-open');
                    btn.style.display = 'none';
                };
                document.body.appendChild(btn);

                document.addEventListener('click', function(e) {
                    var paneSide = document.getElementById('side');
                    if (paneSide && paneSide.contains(e.target)) {
                        setTimeout(function() {
                            if (document.getElementById('main')) {
                                document.body.classList.add('velo-wa-chat-open');
                                btn.style.display = 'block';
                            }
                        }, 180);
                    }
                }, true);
            })();
        """.trimIndent()

        private val SPOTIFY_ENHANCE_JS = """
            (function() {
                if (document.getElementById('velo-sp-css')) return;
                var style = document.createElement('style');
                style.id = 'velo-sp-css';
                style.textContent =
                    '[data-testid="download-app-banner"], a[href*="/download"], .Root__top-bar a[href*="download"] { display: none !important; }';
                (document.head || document.documentElement).appendChild(style);
            })();
        """.trimIndent()
    }
}
