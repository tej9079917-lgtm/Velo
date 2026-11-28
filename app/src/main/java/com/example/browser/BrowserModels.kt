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

// --- NEW MEDIA, PDF, SPEED TEST & SECRET MEDIA VAULT MODELS ---

data class DetectedVideoMedia(
    val url: String,
    val title: String,
    val formatLabel: String,
    val qualityLabel: String,
    val isPlaying: Boolean = false,
    val detectedAtMillis: Long = System.currentTimeMillis()
)

data class DownloadedMediaItem(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val localPath: String,
    val mimeType: String,
    val formatLabel: String,
    val qualityLabel: String,
    val byteSize: Long,
    val createdAtMillis: Long
)

data class ActiveDownloadProgress(
    val url: String,
    val title: String,
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0L) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else -1f
}

data class VideoPlayerRequest(
    val title: String,
    val primarySource: String,
    val isLocalFile: Boolean,
    val isAudioOnly: Boolean = false,
    val qualityVariants: List<Pair<String, String>> = emptyList(),
    val deleteTempFileOnClose: Boolean = false
)

data class PdfDocumentItem(
    val id: String,
    val title: String,
    val filePath: String,
    val pageCount: Int,
    val byteSize: Long,
    val createdAtMillis: Long,
    val deleteTempFileOnClose: Boolean = false
)

enum class SpeedTestPhase(val label: String) {
    IDLE("Ready"),
    PING("Measuring Latency"),
    DOWNLOAD("Testing Download"),
    UPLOAD("Testing Upload"),
    COMPLETE("Complete")
}

data class SpeedTestLiveState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val currentMbps: Double = 0.0,
    val pingMs: Int = 0,
    val jitterMs: Int = 0,
    val downloadMbps: Double = 0.0,
    val uploadMbps: Double = 0.0,
    val progress: Float = 0f,
    val samples: List<Float> = emptyList(),
    val errorMessage: String? = null
)

data class SpeedTestRecord(
    val id: String,
    val pingMs: Int,
    val jitterMs: Int,
    val downloadMbps: Double,
    val uploadMbps: Double,
    val connectionType: String,
    val testedAtMillis: Long
)

enum class SecretMediaType(val label: String) {
    VIDEO("Videos"),
    IMAGE("Images"),
    PDF("PDFs"),
    AUDIO("Audios");

    companion object {
        fun fromWire(raw: String): SecretMediaType =
            entries.firstOrNull { it.name == raw } ?: IMAGE
    }
}

data class SecretMediaItem(
    val id: String,
    val displayName: String,
    val type: SecretMediaType,
    val mimeType: String,
    val encryptedFileName: String,
    val ivBase64: String,
    val byteSize: Long,
    val addedAtMillis: Long
)

data class ExternalOpenRequest(
    val uriString: String,
    val mimeType: String?
)

data class ImageViewerRequest(
    val title: String,
    val source: String,
    val isContentUri: Boolean = false
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
    CLOUD_SIGN_IN,
    VIDEO_DOWNLOADER,
    VIDEO_PLAYER,
    PDF_TOOLS,
    SPEED_TEST,
    SECRET_MEDIA_VAULT,
    SECRET_VAULT_SETUP,
    IMAGE_VIEWER
}
