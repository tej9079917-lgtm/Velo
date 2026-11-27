package com.example.browser

import java.util.UUID

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Alabaster Light"),
    DARK("Midnight OLED");

    companion object {
        fun fromWire(raw: String?): ThemeMode =
            entries.firstOrNull { it.name == raw } ?: DARK
    }
}

enum class SearchEngine(val label: String, val searchUrlPrefix: String) {
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
    STARTPAGE("Startpage", "https://www.startpage.com/sp/search?query="),
    BRAVE("Brave Search", "https://search.brave.com/search?q="),
    GOOGLE("Google", "https://www.google.com/search?q=");

    companion object {
        fun fromWire(raw: String?): SearchEngine =
            entries.firstOrNull { it.name == raw } ?: DUCKDUCKGO
    }
}

enum class SplitPane {
    PRIMARY,
    SECONDARY
}

data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New Tab",
    val url: String = "",
    val isPrivate: Boolean = false,
    val isLoading: Boolean = false,
    val progress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val isSecureHttps: Boolean = true,
    val blockedCount: Int = 0,
    val isDesktopMode: Boolean = false,
    val isShowingOfflineSnapshot: Boolean = false,
    val insecureUpgradeBlockedUrl: String? = null,
    val focusBlockedHost: String? = null
)

data class ShortcutItem(
    val id: String,
    val title: String,
    val url: String,
    val accentHex: String,
    val orderIndex: Int
)

data class VaultCredential(
    val id: String,
    val siteName: String,
    val siteUrl: String,
    val username: String,
    val password: String,
    val notes: String,
    val updatedAtMillis: Long
)

data class ReadingListItem(
    val id: String,
    val urlHash: String,
    val title: String,
    val url: String,
    val excerpt: String,
    val cachedByteSize: Long,
    val archived: Boolean,
    val isCachedLocally: Boolean,
    val savedAtMillis: Long
)

data class BrowserSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val adBlockEnabled: Boolean = true,
    val httpsOnlyEnabled: Boolean = true,
    val darkWebPagesEnabled: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val searchEngine: SearchEngine = SearchEngine.DUCKDUCKGO,
    val trackersBlockedTotal: Long = 0L
)

data class NetworkRequestLog(
    val id: String = UUID.randomUUID().toString(),
    val tabId: String,
    val host: String,
    val url: String,
    val resourceType: String,
    val wasBlocked: Boolean,
    val reason: String,
    val timestampMillis: Long = System.currentTimeMillis()
)

data class ReaderArticle(
    val title: String,
    val host: String,
    val url: String,
    val paragraphs: List<String>,
    val readingMinutes: Int
)

data class ScratchpadClip(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val content: String,
    val tag: String,
    val updatedAtMillis: Long
)

data class FocusSessionState(
    val isActive: Boolean = false,
    val isPaused: Boolean = false,
    val remainingSeconds: Int = 25 * 60,
    val totalSeconds: Int = 25 * 60,
    val blockedAttempts: Int = 0
)

data class FindInPageState(
    val isOpen: Boolean = false,
    val query: String = "",
    val activeMatch: Int = 0,
    val totalMatches: Int = 0
)

enum class ActiveOverlay {
    NONE,
    TAB_SWITCHER,
    PASSWORD_VAULT,
    OFFLINE_READING,
    SETTINGS_SHIELD,
    POWER_TOOLS,
    READER_MODE,
    SCRATCHPAD,
    NETWORK_INSPECTOR,
    FOCUS_TIMER,
    CLOUD_SIGN_IN
}
