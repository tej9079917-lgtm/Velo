package com.example.viewmodel

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.WebView
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.ActiveDownloadProgress
import com.example.browser.ActiveOverlay
import com.example.browser.AdBlockerEngine
import com.example.browser.BrowserSettings
import com.example.browser.BrowserTab
import com.example.browser.DetectedImageMedia
import com.example.browser.DetectedVideoMedia
import com.example.browser.DownloadedMediaItem
import com.example.browser.ExternalOpenRequest
import com.example.browser.FindInPageState
import com.example.browser.FocusSessionState
import com.example.browser.ImageDownloadFormat
import com.example.browser.ImageViewerRequest
import com.example.browser.NetworkRequestLog
import com.example.browser.OfflinePageArchiver
import com.example.browser.PdfDocumentItem
import com.example.browser.ReaderArticle
import com.example.browser.ReadingListItem
import com.example.browser.ScratchpadClip
import com.example.browser.SearchEngine
import com.example.browser.SecretMediaItem
import com.example.browser.SecretMediaType
import com.example.browser.ShortcutItem
import com.example.browser.SpeedTestLiveState
import com.example.browser.SpeedTestPhase
import com.example.browser.SpeedTestRecord
import com.example.browser.SplitPane
import com.example.browser.ThemeMode
import com.example.browser.VaultCredential
import com.example.browser.VideoPlayerRequest
import com.example.browser.WebAppIntegrationMode
import com.example.browser.WebAppService
import com.example.crypto.E2EECryptoEngine
import com.example.crypto.SecretMediaVaultManager
import com.example.data.local.CachedPageDao
import com.example.data.local.CachedPageEntity
import com.example.data.local.DownloadedMediaDao
import com.example.data.local.DownloadedMediaEntity
import com.example.data.local.LocalPreferencesStore
import com.example.data.local.LocalShortcutDao
import com.example.data.local.LocalShortcutEntity
import com.example.data.local.ScratchpadClipDao
import com.example.data.local.ScratchpadClipEntity
import com.example.data.local.SecretMediaDao
import com.example.data.local.SecretMediaEntity
import com.example.data.local.SecretVaultConfig
import com.example.data.local.SpeedTestHistoryDao
import com.example.data.local.SpeedTestHistoryEntity
import com.example.data.remote.BrowserPreferencesDoc
import com.example.data.remote.ReadingPageDoc
import com.example.data.remote.ShortcutDoc
import com.example.data.remote.VaultConfigDoc
import com.example.data.remote.VaultItemDoc
import com.example.data.remote.VeloSyncRepository
import com.example.media.PdfEngine
import com.example.media.SpeedTestEngine
import com.example.media.VeloDownloadManagerWrapper
import com.example.media.VideoDownloadEngine
import com.example.system.MediaPlaybackForegroundService
import com.example.system.NetworkConnectivityObserver
import com.example.system.VeloFileSharing
import com.example.system.VeloNotificationManager
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID
import javax.crypto.SecretKey

sealed interface VaultGateState {
    data object SignedOut : VaultGateState
    data object Loading : VaultGateState
    data object NeedsSetup : VaultGateState
    data class Locked(val config: VaultConfigDoc) : VaultGateState
    data class Unlocked(val keyFingerprint: String) : VaultGateState
    data class Error(val message: String) : VaultGateState
}

data class DecryptedOfflineReaderState(
    val item: ReadingListItem,
    val htmlContent: String,
    val readableText: String
)

class BrowserViewModel(
    private val appContext: Context,
    private val syncRepository: VeloSyncRepository?,
    private val cachedPageDao: CachedPageDao,
    private val localShortcutDao: LocalShortcutDao,
    private val scratchpadClipDao: ScratchpadClipDao,
    private val downloadedMediaDao: DownloadedMediaDao,
    private val secretMediaDao: SecretMediaDao,
    private val speedTestHistoryDao: SpeedTestHistoryDao,
    private val localPrefs: LocalPreferencesStore,
    private val offlineArchiver: OfflinePageArchiver,
    private val notificationManager: VeloNotificationManager,
    private val connectivityObserver: NetworkConnectivityObserver,
    private val videoDownloadEngine: VideoDownloadEngine,
    val pdfEngine: PdfEngine,
    private val speedTestEngine: SpeedTestEngine,
    private val secretVaultManager: SecretMediaVaultManager,
    val currentUserId: String?
) : ViewModel() {

    private val localOwnerId: String = currentUserId ?: LOCAL_GUEST_ID

    private val _vaultKey = MutableStateFlow<SecretKey?>(null)
    private val _vaultErrorMessage = MutableStateFlow<String?>(null)
    val vaultErrorMessage: StateFlow<String?> = _vaultErrorMessage.asStateFlow()

    private val _vaultBusy = MutableStateFlow(false)
    val vaultBusy: StateFlow<Boolean> = _vaultBusy.asStateFlow()

    // --- TABS & SPLIT-SCREEN STATE ---
    private val initialTab = BrowserTab(id = "tab_home_${UUID.randomUUID().toString().take(8)}")
    private val _tabs = MutableStateFlow(listOf(initialTab))
    val tabs: StateFlow<List<BrowserTab>> = _tabs.asStateFlow()

    private val _primaryTabId = MutableStateFlow(initialTab.id)
    private val _secondaryTabId = MutableStateFlow<String?>(null)
    val secondaryTabId: StateFlow<String?> = _secondaryTabId.asStateFlow()

    private val _activePane = MutableStateFlow(SplitPane.PRIMARY)
    val activePane: StateFlow<SplitPane> = _activePane.asStateFlow()

    private val _splitRatio = MutableStateFlow(0.5f)
    val splitRatio: StateFlow<Float> = _splitRatio.asStateFlow()

    val primaryTab: StateFlow<BrowserTab> = combine(_tabs, _primaryTabId) { list, id ->
        list.firstOrNull { it.id == id } ?: list.first()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialTab)

    val secondaryTab: StateFlow<BrowserTab?> = combine(_tabs, _secondaryTabId) { list, secId ->
        if (secId == null) null else list.firstOrNull { it.id == secId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val activeTab: StateFlow<BrowserTab> = combine(primaryTab, secondaryTab, _activePane) { primary, secondary, pane ->
        if (pane == SplitPane.SECONDARY && secondary != null) secondary else primary
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialTab)

    private val _activeOverlay = MutableStateFlow(ActiveOverlay.NONE)
    val activeOverlay: StateFlow<ActiveOverlay> = _activeOverlay.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private val _isSavingOffline = MutableStateFlow(false)
    val isSavingOffline: StateFlow<Boolean> = _isSavingOffline.asStateFlow()

    private val _offlineReaderState = MutableStateFlow<DecryptedOfflineReaderState?>(null)
    val offlineReaderState: StateFlow<DecryptedOfflineReaderState?> = _offlineReaderState.asStateFlow()

    private val _allowlistedVersion = MutableStateFlow(0)
    val allowlistedVersion: StateFlow<Int> = _allowlistedVersion.asStateFlow()

    // --- POWER-USER FEATURES STATE ---
    private val _networkLogs = MutableStateFlow<List<NetworkRequestLog>>(emptyList())
    val networkLogs: StateFlow<List<NetworkRequestLog>> = _networkLogs.asStateFlow()

    val customBlockedHosts: StateFlow<Set<String>> = localPrefs.customBlockedHostsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val focusBlocklist: StateFlow<Set<String>> = localPrefs.focusBlocklistFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LocalPreferencesStore.DEFAULT_FOCUS_DOMAINS)

    private val _focusState = MutableStateFlow(FocusSessionState())
    val focusState: StateFlow<FocusSessionState> = _focusState.asStateFlow()
    private var focusTimerJob: Job? = null

    private val _readerArticle = MutableStateFlow<ReaderArticle?>(null)
    val readerArticle: StateFlow<ReaderArticle?> = _readerArticle.asStateFlow()

    private val _findState = MutableStateFlow(FindInPageState())
    val findState: StateFlow<FindInPageState> = _findState.asStateFlow()

    val scratchpadClips: StateFlow<List<ScratchpadClip>> = scratchpadClipDao.observeClips()
        .map { entities ->
            entities.map { e ->
                ScratchpadClip(
                    id = e.id,
                    title = e.title,
                    sourceUrl = e.sourceUrl,
                    content = e.content,
                    tag = e.tag,
                    updatedAtMillis = e.updatedAtMillis
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- VIDEO & IMAGE DETECTOR, DOWNLOADER & PLAYER STATE ---
    private val systemDownloadManager = VeloDownloadManagerWrapper(appContext)

    private val _detectedVideosByTab = MutableStateFlow<Map<String, List<DetectedVideoMedia>>>(emptyMap())
    val detectedVideosForActiveTab: StateFlow<List<DetectedVideoMedia>> =
        combine(activeTab, _detectedVideosByTab) { tab, map ->
            map[tab.id].orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _detectedImagesByTab = MutableStateFlow<Map<String, List<DetectedImageMedia>>>(emptyMap())
    val detectedImagesForActiveTab: StateFlow<List<DetectedImageMedia>> =
        combine(activeTab, _detectedImagesByTab) { tab, map ->
            map[tab.id].orEmpty()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedMedia: StateFlow<List<DownloadedMediaItem>> = downloadedMediaDao.observeDownloads()
        .map { list ->
            list.map { e ->
                DownloadedMediaItem(
                    id = e.id,
                    title = e.title,
                    sourceUrl = e.sourceUrl,
                    localPath = e.localPath,
                    mimeType = e.mimeType,
                    formatLabel = e.formatLabel,
                    qualityLabel = e.qualityLabel,
                    byteSize = e.byteSize,
                    createdAtMillis = e.createdAtMillis
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeDownload = MutableStateFlow<ActiveDownloadProgress?>(null)
    val activeDownload: StateFlow<ActiveDownloadProgress?> = _activeDownload.asStateFlow()
    private var downloadJob: Job? = null

    private val _videoPlayerRequest = MutableStateFlow<VideoPlayerRequest?>(null)
    val videoPlayerRequest: StateFlow<VideoPlayerRequest?> = _videoPlayerRequest.asStateFlow()
    private val _imageViewerRequest = MutableStateFlow<ImageViewerRequest?>(null)
    val imageViewerRequest: StateFlow<ImageViewerRequest?> = _imageViewerRequest.asStateFlow()
    private var returnOverlayAfterMedia: ActiveOverlay = ActiveOverlay.NONE

    // --- PDF VIEWER & CONVERTER STATE ---
    private val _savedPdfs = MutableStateFlow<List<PdfDocumentItem>>(emptyList())
    val savedPdfs: StateFlow<List<PdfDocumentItem>> = _savedPdfs.asStateFlow()

    private val _activePdfDocument = MutableStateFlow<PdfDocumentItem?>(null)
    val activePdfDocument: StateFlow<PdfDocumentItem?> = _activePdfDocument.asStateFlow()

    private val _isPdfBusy = MutableStateFlow(false)
    val isPdfBusy: StateFlow<Boolean> = _isPdfBusy.asStateFlow()

    // --- SPEED TEST METER STATE ---
    private val _speedTestState = MutableStateFlow(SpeedTestLiveState())
    val speedTestState: StateFlow<SpeedTestLiveState> = _speedTestState.asStateFlow()
    private var speedTestJob: Job? = null

    val speedTestHistory: StateFlow<List<SpeedTestRecord>> = speedTestHistoryDao.observeHistory()
        .map { list ->
            list.map { e ->
                SpeedTestRecord(
                    id = e.id,
                    pingMs = e.pingMs,
                    jitterMs = e.jitterMs,
                    downloadMbps = e.downloadMbps,
                    uploadMbps = e.uploadMbps,
                    connectionType = e.connectionType,
                    testedAtMillis = e.testedAtMillis
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // --- SECRET MEDIA VAULT STATE (UNLOCKED VIA OMNIBOX SECRET CODE) ---
    val secretVaultConfig: StateFlow<SecretVaultConfig?> = localPrefs.secretVaultConfigFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _secretVaultKey = MutableStateFlow<SecretKey?>(null)
    val isSecretVaultUnlocked: StateFlow<Boolean> = _secretVaultKey
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _secretVaultBusy = MutableStateFlow(false)
    val secretVaultBusy: StateFlow<Boolean> = _secretVaultBusy.asStateFlow()

    private val _secretVaultError = MutableStateFlow<String?>(null)
    val secretVaultError: StateFlow<String?> = _secretVaultError.asStateFlow()

    val secretMediaItems: StateFlow<List<SecretMediaItem>> = secretMediaDao.observeSecretMedia()
        .map { list ->
            list.map { e ->
                SecretMediaItem(
                    id = e.id,
                    displayName = e.displayName,
                    type = SecretMediaType.fromWire(e.mediaType),
                    mimeType = e.mimeType,
                    encryptedFileName = e.encryptedFileName,
                    ivBase64 = e.ivBase64,
                    byteSize = e.byteSize,
                    addedAtMillis = e.addedAtMillis
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _secretImagePreview = MutableStateFlow<Pair<SecretMediaItem, ByteArray>?>(null)
    val secretImagePreview: StateFlow<Pair<SecretMediaItem, ByteArray>?> = _secretImagePreview.asStateFlow()

    private val _pendingDeleteOriginalUris = MutableStateFlow<List<Uri>>(emptyList())
    val pendingDeleteOriginalUris: StateFlow<List<Uri>> = _pendingDeleteOriginalUris.asStateFlow()

    // --- ALL-IN-ONE WEB APPS HUB & BACKGROUND AUDIO FOREGROUND SERVICE ---
    private val _backgroundAudioActive = MutableStateFlow(false)
    val backgroundAudioActive: StateFlow<Boolean> = _backgroundAudioActive.asStateFlow()

    // --- CONNECTIVITY ---
    val isOnline: StateFlow<Boolean> = connectivityObserver.observeOnlineStatus()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = connectivityObserver.isCurrentlyOnline()
        )

    // --- OPTIONAL FIRESTORE STREAMS (NEVER STARTED WHEN SIGNED OUT) ---
    private val vaultConfigStream: StateFlow<Result<VaultConfigDoc?>?> =
        if (currentUserId != null && syncRepository != null) {
            syncRepository.observeVaultConfig(currentUserId)
                .map<VaultConfigDoc?, Result<VaultConfigDoc?>?> { Result.success(it) }
                .catch { error ->
                    val fsError = unwrapFirestoreError(error)
                    if (fsError == null && error is CancellationException) throw error
                    emit(Result.failure(fsError ?: error))
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        } else {
            MutableStateFlow<Result<VaultConfigDoc?>?>(null).asStateFlow()
        }

    val vaultGateState: StateFlow<VaultGateState> = combine(vaultConfigStream, _vaultKey) { configResult, key ->
        if (currentUserId == null || syncRepository == null) {
            VaultGateState.SignedOut
        } else when {
            configResult == null -> VaultGateState.Loading
            configResult.isFailure -> VaultGateState.Error(
                configResult.exceptionOrNull()?.localizedMessage ?: "Failed to sync vault configuration."
            )
            else -> {
                val cfg = configResult.getOrNull()
                when {
                    cfg == null -> VaultGateState.NeedsSetup
                    key == null -> VaultGateState.Locked(cfg)
                    else -> VaultGateState.Unlocked(cfg.keyFingerprint)
                }
            }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        if (currentUserId == null) VaultGateState.SignedOut else VaultGateState.Loading
    )

    private val rawRemotePreferences: StateFlow<BrowserPreferencesDoc?> =
        if (currentUserId != null && syncRepository != null) {
            syncRepository.observePreferences(currentUserId)
                .catch { error ->
                    val fsError = unwrapFirestoreError(error)
                    if (fsError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing preferences", fsError ?: error)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        } else {
            flowOf<BrowserPreferencesDoc?>(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
        }

    val settings: StateFlow<BrowserSettings> = combine(localPrefs.settingsFlow, rawRemotePreferences) { local, remote ->
        if (remote == null) {
            local
        } else {
            BrowserSettings(
                themeMode = ThemeMode.fromWire(remote.themeMode),
                adBlockEnabled = remote.adBlockEnabled,
                httpsOnlyEnabled = remote.httpsOnlyEnabled,
                darkWebPagesEnabled = remote.darkWebPagesEnabled,
                notificationsEnabled = remote.notificationsEnabled,
                searchEngine = SearchEngine.fromWire(remote.searchEngine),
                trackersBlockedTotal = maxOf(local.trackersBlockedTotal, remote.trackersBlockedTotal)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BrowserSettings())

    private val localShortcuts: StateFlow<List<ShortcutItem>> = localShortcutDao.observeShortcuts()
        .map { entities ->
            entities.map { e ->
                ShortcutItem(
                    id = e.id,
                    title = e.title,
                    url = e.url,
                    accentHex = e.accentHex,
                    orderIndex = e.orderIndex
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawRemoteShortcuts: StateFlow<List<ShortcutDoc>> =
        if (currentUserId != null && syncRepository != null) {
            syncRepository.observeShortcuts(currentUserId)
                .catch { error ->
                    val fsError = unwrapFirestoreError(error)
                    if (fsError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing shortcuts", fsError ?: error)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            flowOf(emptyList<ShortcutDoc>()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        }

    val shortcuts: StateFlow<List<ShortcutItem>> = combine(localShortcuts, rawRemoteShortcuts, _vaultKey) { local, remoteDocs, key ->
        if (currentUserId != null && key != null) {
            val decrypted = remoteDocs.mapNotNull { doc -> decryptShortcut(doc, key) }
                .sortedBy { it.orderIndex }
            if (decrypted.isNotEmpty()) decrypted else local
        } else {
            local
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawVaultItems: StateFlow<List<VaultItemDoc>> =
        if (currentUserId != null && syncRepository != null) {
            syncRepository.observeVaultItems(currentUserId)
                .catch { error ->
                    val fsError = unwrapFirestoreError(error)
                    if (fsError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing vault items", fsError ?: error)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            flowOf(emptyList<VaultItemDoc>()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        }

    val vaultCredentials: StateFlow<List<VaultCredential>> = combine(rawVaultItems, _vaultKey) { docs, key ->
        if (key == null) emptyList()
        else docs.mapNotNull { doc -> decryptVaultItem(doc, key) }
            .sortedBy { it.siteName.lowercase() }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val rawReadingPages: StateFlow<List<ReadingPageDoc>> =
        if (currentUserId != null && syncRepository != null) {
            syncRepository.observeReadingList(currentUserId)
                .catch { error ->
                    val fsError = unwrapFirestoreError(error)
                    if (fsError == null && error is CancellationException) throw error
                    Log.w(TAG, "Error observing reading list", fsError ?: error)
                }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        } else {
            flowOf(emptyList<ReadingPageDoc>()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        }

    private val localCachedPages: StateFlow<List<CachedPageEntity>> =
        cachedPageDao.observeCachedPages(localOwnerId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val readingList: StateFlow<List<ReadingListItem>> =
        combine(rawReadingPages, localCachedPages, _vaultKey) { docs, localCache, key ->
            val cachedMap = localCache.associateBy { it.urlHash }
            val result = mutableListOf<ReadingListItem>()
            val seenHashes = mutableSetOf<String>()

            if (currentUserId != null && key != null) {
                docs.forEach { doc ->
                    decryptReadingPage(doc, key, cachedMap[doc.urlHash])?.let { item ->
                        result.add(item)
                        seenHashes.add(item.urlHash)
                    }
                }
            }
            // Include local unencrypted offline pages (available immediately without login or vault unlock)
            localCache.forEach { entity ->
                if (!entity.isEncrypted && entity.urlHash !in seenHashes) {
                    result.add(
                        ReadingListItem(
                            id = entity.urlHash,
                            urlHash = entity.urlHash,
                            title = entity.title,
                            url = entity.url,
                            excerpt = entity.excerpt,
                            cachedByteSize = entity.byteSize,
                            archived = entity.archived,
                            isCachedLocally = true,
                            savedAtMillis = entity.cachedAtMillis
                        )
                    )
                }
            }
            result.sortedByDescending { it.savedAtMillis }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var pendingTrackerSyncCount = 0L

    init {
        viewModelScope.launch {
            seedLocalDefaultShortcutsIfEmpty()
            refreshSavedPdfs()
            if (currentUserId != null && syncRepository != null) {
                syncRepository.ensurePreferencesExist(currentUserId)
            }
        }
        viewModelScope.launch {
            localPrefs.customBlockedHostsFlow.collect { hosts ->
                AdBlockerEngine.setCustomBlockedHosts(hosts)
            }
        }
    }

    private suspend fun seedLocalDefaultShortcutsIfEmpty() {
        if (localShortcutDao.count() > 0) return
        val defaults = DEFAULT_SHORTCUTS.mapIndexed { index, (title, url, accent) ->
            LocalShortcutEntity(
                id = "local_default_$index",
                title = title,
                url = url,
                accentHex = accent,
                orderIndex = index
            )
        }
        localShortcutDao.upsertAll(defaults)
    }

    // --- E2EE VAULT SETUP / UNLOCK / LOCK ---

    fun clearVaultError() {
        _vaultErrorMessage.value = null
    }

    fun setupNewVault(passphrase: String, confirmPassphrase: String) {
        val uid = currentUserId ?: return
        val repo = syncRepository ?: return
        if (passphrase.length < 8) {
            _vaultErrorMessage.value = "Use at least 8 characters for strong AES-256 encryption."
            return
        }
        if (passphrase != confirmPassphrase) {
            _vaultErrorMessage.value = "Passphrases do not match."
            return
        }
        viewModelScope.launch {
            _vaultBusy.value = true
            _vaultErrorMessage.value = null
            try {
                val bundle = withContext(Dispatchers.Default) {
                    E2EECryptoEngine.createNewVault(passphrase)
                }
                val result = repo.createVaultConfig(
                    userId = uid,
                    saltBase64 = bundle.saltBase64,
                    verifierCiphertext = bundle.verifierCiphertext,
                    verifierIv = bundle.verifierIv,
                    kdfIterations = bundle.kdfIterations,
                    keyFingerprint = bundle.keyFingerprint
                )
                if (result.isSuccess) {
                    _vaultKey.value = bundle.derivedKey
                    seedRemoteDefaultShortcutsIfEmpty(uid, repo, bundle.derivedKey)
                    showTransientBanner("E2EE Cloud Vault initialized (${bundle.keyFingerprint}).")
                    if (settings.value.notificationsEnabled) {
                        notificationManager.notifySecurityAlert(
                            title = "E2EE Vault Initialized",
                            body = "AES-256-GCM key active (${bundle.keyFingerprint}). Synced across devices."
                        )
                    }
                } else {
                    _vaultErrorMessage.value = result.exceptionOrNull()?.localizedMessage
                        ?: "Could not save vault configuration."
                }
            } finally {
                _vaultBusy.value = false
            }
        }
    }

    fun unlockExistingVault(passphrase: String, config: VaultConfigDoc) {
        if (passphrase.isBlank()) {
            _vaultErrorMessage.value = "Enter your master passphrase to unlock."
            return
        }
        viewModelScope.launch {
            _vaultBusy.value = true
            _vaultErrorMessage.value = null
            try {
                val key = withContext(Dispatchers.Default) {
                    E2EECryptoEngine.unlockVault(
                        passphrase = passphrase,
                        saltBase64 = config.saltBase64,
                        verifierCiphertext = config.verifierCiphertext,
                        verifierIv = config.verifierIv,
                        iterations = config.kdfIterations.toInt()
                    )
                }
                if (key != null) {
                    _vaultKey.value = key
                    showTransientBanner("Vault unlocked & synced (${config.keyFingerprint}).")
                } else {
                    _vaultErrorMessage.value = "Incorrect master passphrase. Unable to verify decryption key."
                }
            } finally {
                _vaultBusy.value = false
            }
        }
    }

    fun lockVault() {
        _vaultKey.value = null
        _offlineReaderState.value = null
        if (_activeOverlay.value == ActiveOverlay.PASSWORD_VAULT) {
            _activeOverlay.value = ActiveOverlay.NONE
        }
    }

    private suspend fun seedRemoteDefaultShortcutsIfEmpty(uid: String, repo: VeloSyncRepository, key: SecretKey) {
        if (rawRemoteShortcuts.value.isNotEmpty()) return
        DEFAULT_SHORTCUTS.forEachIndexed { index, (title, url, accent) ->
            val json = JSONObject().apply {
                put("title", title)
                put("url", url)
                put("accentHex", accent)
            }.toString()
            val env = E2EECryptoEngine.encrypt(json, key)
            repo.addShortcut(uid, env.ciphertextBase64, env.ivBase64, index)
        }
    }

    // --- NAVIGATION, TABS & SPLIT-SCREEN ---

    fun setOverlay(overlay: ActiveOverlay) {
        _activeOverlay.value = overlay
    }

    fun dismissBanner() {
        _statusBanner.value = null
    }

    fun showTransientBanner(message: String) {
        _statusBanner.value = message
    }

    fun createNewTab(isPrivate: Boolean = false, initialUrl: String = ""): BrowserTab {
        val newTab = BrowserTab(
            id = "tab_${UUID.randomUUID().toString().take(10)}",
            title = if (isPrivate) "Private Tab" else "New Tab",
            url = initialUrl,
            isPrivate = isPrivate
        )
        _tabs.update { it + newTab }
        if (_secondaryTabId.value != null && _activePane.value == SplitPane.SECONDARY) {
            _secondaryTabId.value = newTab.id
        } else {
            _primaryTabId.value = newTab.id
        }
        _activeOverlay.value = ActiveOverlay.NONE
        return newTab
    }

    fun selectTab(tabId: String) {
        if (_tabs.value.none { it.id == tabId }) return
        if (_secondaryTabId.value != null && _activePane.value == SplitPane.SECONDARY) {
            if (_primaryTabId.value == tabId) {
                _activePane.value = SplitPane.PRIMARY
            } else {
                _secondaryTabId.value = tabId
            }
        } else {
            if (_secondaryTabId.value == tabId) {
                _activePane.value = SplitPane.SECONDARY
            } else {
                _primaryTabId.value = tabId
            }
        }
        _activeOverlay.value = ActiveOverlay.NONE
    }

    fun toggleSplitView() {
        if (_secondaryTabId.value != null) {
            _secondaryTabId.value = null
            _activePane.value = SplitPane.PRIMARY
            showTransientBanner("Split-Screen closed.")
            return
        }
        val currentPrimary = _primaryTabId.value
        val existingOther = _tabs.value.firstOrNull { it.id != currentPrimary }
        val secondId = if (existingOther != null) {
            existingOther.id
        } else {
            val newSecond = BrowserTab(
                id = "tab_split_${UUID.randomUUID().toString().take(8)}",
                title = "Split Tab"
            )
            _tabs.update { it + newSecond }
            newSecond.id
        }
        _secondaryTabId.value = secondId
        _activePane.value = SplitPane.SECONDARY
        _activeOverlay.value = ActiveOverlay.NONE
        showTransientBanner("Split-Screen active — tap either pane to focus.")
    }

    fun setActivePane(pane: SplitPane) {
        if (pane == SplitPane.SECONDARY && _secondaryTabId.value == null) return
        _activePane.value = pane
    }

    fun swapSplitPanes() {
        val secId = _secondaryTabId.value ?: return
        val primId = _primaryTabId.value
        _primaryTabId.value = secId
        _secondaryTabId.value = primId
    }

    fun updateSplitRatio(deltaFraction: Float) {
        _splitRatio.update { (it + deltaFraction).coerceIn(0.25f, 0.75f) }
    }

    fun closeTab(tabId: String, onWebViewDestroy: (String, Boolean) -> Unit) {
        val target = _tabs.value.firstOrNull { it.id == tabId } ?: return
        onWebViewDestroy(target.id, target.isPrivate)
        val remaining = _tabs.value.filterNot { it.id == tabId }
        if (remaining.isEmpty()) {
            val replacement = BrowserTab(id = "tab_home_${UUID.randomUUID().toString().take(8)}")
            _tabs.value = listOf(replacement)
            _primaryTabId.value = replacement.id
            _secondaryTabId.value = null
            _activePane.value = SplitPane.PRIMARY
        } else {
            _tabs.value = remaining
            if (_secondaryTabId.value == tabId) {
                _secondaryTabId.value = null
                _activePane.value = SplitPane.PRIMARY
            }
            if (_primaryTabId.value == tabId) {
                val fallback = remaining.firstOrNull { it.id != _secondaryTabId.value } ?: remaining.last()
                _primaryTabId.value = fallback.id
                if (_secondaryTabId.value == fallback.id) {
                    _secondaryTabId.value = null
                    _activePane.value = SplitPane.PRIMARY
                }
            }
        }
        _detectedVideosByTab.update { it - tabId }
    }

    fun wipeAllPrivateTabs(onWipePrivateWebViews: (List<String>) -> Unit) {
        val privateIds = _tabs.value.filter { it.isPrivate }.map { it.id }
        if (privateIds.isEmpty()) return
        onWipePrivateWebViews(privateIds)
        val remaining = _tabs.value.filterNot { it.isPrivate }
        if (remaining.isEmpty()) {
            val fresh = BrowserTab(id = "tab_home_${UUID.randomUUID().toString().take(8)}")
            _tabs.value = listOf(fresh)
            _primaryTabId.value = fresh.id
            _secondaryTabId.value = null
            _activePane.value = SplitPane.PRIMARY
        } else {
            _tabs.value = remaining
            if (_secondaryTabId.value in privateIds) {
                _secondaryTabId.value = null
                _activePane.value = SplitPane.PRIMARY
            }
            if (_primaryTabId.value in privateIds) {
                _primaryTabId.value = remaining.last().id
            }
        }
        showTransientBanner("All private tabs, cookies & session storage wiped.")
    }

    fun navigateToHome(tabId: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(
                    url = "",
                    title = if (it.isPrivate) "Private Tab" else "New Tab",
                    isLoading = false,
                    progress = 0,
                    isShowingOfflineSnapshot = false,
                    insecureUpgradeBlockedUrl = null,
                    focusBlockedHost = null
                ) else it
            }
        }
        _detectedVideosByTab.update { it - tabId }
    }

    fun resolveInputToUrl(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""
        val lower = trimmed.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            return if (settings.value.httpsOnlyEnabled && lower.startsWith("http://")) {
                "https://" + trimmed.substring(7)
            } else {
                trimmed
            }
        }
        val looksLikeDomain = !trimmed.contains(" ") && trimmed.contains(".") && trimmed.length >= 4
        return if (looksLikeDomain) {
            "https://$trimmed"
        } else {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            settings.value.searchEngine.searchUrlPrefix + encoded
        }
    }

    fun updateTabOnStart(tabId: String, url: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(
                    url = url,
                    isLoading = true,
                    progress = 15,
                    isSecureHttps = url.startsWith("https://", ignoreCase = true),
                    insecureUpgradeBlockedUrl = null,
                    focusBlockedHost = null,
                    isShowingOfflineSnapshot = false
                ) else it
            }
        }
        _detectedVideosByTab.update { it - tabId }
    }

    fun updateTabProgress(tabId: String, progress: Int) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(
                    progress = progress,
                    isLoading = progress in 1..99
                ) else it
            }
        }
    }

    fun updateTabTitle(tabId: String, title: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(title = title) else it
            }
        }
    }

    fun updateTabOnFinish(
        tabId: String,
        url: String,
        title: String,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(
                    url = if (url == "about:blank") it.url else url,
                    title = title.ifBlank { it.title },
                    isLoading = false,
                    progress = 100,
                    canGoBack = canGoBack,
                    canGoForward = canGoForward,
                    isSecureHttps = url.startsWith("https://", ignoreCase = true) || it.isShowingOfflineSnapshot
                ) else it
            }
        }
        flushPendingTrackerCounts()
    }

    fun recordTrackerBlocked(tabId: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(blockedCount = it.blockedCount + 1) else it
            }
        }
        pendingTrackerSyncCount += 1L
        if (pendingTrackerSyncCount >= 5L) {
            flushPendingTrackerCounts()
        }
    }

    fun recordNetworkLog(log: NetworkRequestLog) {
        _networkLogs.update { current ->
            val updated = ArrayList<NetworkRequestLog>(minOf(current.size + 1, MAX_NETWORK_LOGS))
            updated.add(log)
            val takeCount = (MAX_NETWORK_LOGS - 1).coerceAtMost(current.size)
            for (i in 0 until takeCount) {
                updated.add(current[i])
            }
            updated
        }
    }

    fun clearNetworkLogs() {
        _networkLogs.value = emptyList()
    }

    fun toggleCustomBlockedHost(host: String) {
        val clean = host.trim().lowercase().removePrefix("www.")
        if (clean.isBlank()) return
        val currentlyBlocked = customBlockedHosts.value.contains(clean)
        viewModelScope.launch {
            localPrefs.setCustomBlockedHost(clean, blocked = !currentlyBlocked)
            showTransientBanner(
                if (!currentlyBlocked) "Added custom block rule for $clean"
                else "Removed custom block rule for $clean"
            )
        }
    }

    fun recordInsecureHttpIntercepted(tabId: String, originalHttpUrl: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(insecureUpgradeBlockedUrl = originalHttpUrl) else it
            }
        }
        if (settings.value.notificationsEnabled) {
            notificationManager.notifySecurityAlert(
                title = "HTTPS-Only Shield Active",
                body = "Blocked unencrypted HTTP request to ${originalHttpUrl.toUri().host ?: originalHttpUrl}"
            )
        }
    }

    fun clearInsecureWarning(tabId: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(insecureUpgradeBlockedUrl = null) else it
            }
        }
    }

    fun toggleDesktopMode(tabId: String): Boolean {
        var newMode = false
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) {
                    newMode = !it.isDesktopMode
                    it.copy(isDesktopMode = newMode)
                } else it
            }
        }
        return newMode
    }

    fun markTabOfflineSnapshot(tabId: String, url: String, title: String) {
        _tabs.update { list ->
            list.map {
                if (it.id == tabId) it.copy(
                    url = url,
                    title = title,
                    isLoading = false,
                    isSecureHttps = true,
                    isShowingOfflineSnapshot = true,
                    focusBlockedHost = null
                ) else it
            }
        }
    }

    fun toggleAllowlistForHost(host: String) {
        val nowAllowlisted = AdBlockerEngine.toggleSiteAllowlist(host)
        _allowlistedVersion.update { it + 1 }
        showTransientBanner(
            if (nowAllowlisted) "Shield paused for $host" else "Ad & Tracker Shield re-enabled for $host"
        )
    }

    private fun flushPendingTrackerCounts() {
        val delta = pendingTrackerSyncCount
        if (delta <= 0L) return
        pendingTrackerSyncCount = 0L
        viewModelScope.launch {
            localPrefs.incrementTrackersBlocked(delta)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.incrementTrackersBlocked(currentUserId, delta)
            }
        }
    }

    // --- FOCUS TIMER & DISTRACTION BLOCKER ---

    fun startFocusSession(durationMinutes: Int) {
        val totalSec = durationMinutes.coerceIn(1, 120) * 60
        focusTimerJob?.cancel()
        _focusState.value = FocusSessionState(
            isActive = true,
            isPaused = false,
            remainingSeconds = totalSec,
            totalSeconds = totalSec,
            blockedAttempts = 0
        )
        focusTimerJob = viewModelScope.launch {
            while (isActive) {
                delay(1000L)
                val state = _focusState.value
                if (!state.isActive) break
                if (state.isPaused) continue
                val next = state.remainingSeconds - 1
                if (next <= 0) {
                    _focusState.value = state.copy(isActive = false, isPaused = false, remainingSeconds = 0)
                    clearAllFocusBlockedTabs()
                    showTransientBanner("Focus session complete! Blocked ${state.blockedAttempts} distractions.")
                    if (settings.value.notificationsEnabled) {
                        notificationManager.notifySecurityAlert(
                            title = "Velo Focus Session Complete",
                            body = "Deep-work sprint finished. ${state.blockedAttempts} distraction visits blocked."
                        )
                    }
                    break
                } else {
                    _focusState.value = state.copy(remainingSeconds = next)
                }
            }
        }
        showTransientBanner("Focus Shield started for ${durationMinutes}m.")
    }

    fun togglePauseFocus() {
        _focusState.update { state ->
            if (!state.isActive) state else state.copy(isPaused = !state.isPaused)
        }
    }

    fun stopFocusSession() {
        focusTimerJob?.cancel()
        focusTimerJob = null
        _focusState.value = FocusSessionState(isActive = false, isPaused = false, remainingSeconds = 25 * 60, totalSeconds = 25 * 60)
        clearAllFocusBlockedTabs()
        showTransientBanner("Focus Shield ended.")
    }

    fun addFocusDomain(domain: String) {
        viewModelScope.launch {
            localPrefs.addFocusDomain(domain)
        }
    }

    fun removeFocusDomain(domain: String) {
        viewModelScope.launch {
            localPrefs.removeFocusDomain(domain)
        }
    }

    fun recordFocusDistractionBlocked(tabId: String, host: String) {
        _focusState.update { it.copy(blockedAttempts = it.blockedAttempts + 1) }
        _tabs.update { list ->
            list.map { tab ->
                if (tab.id == tabId) tab.copy(
                    isLoading = false,
                    progress = 0,
                    focusBlockedHost = host
                ) else tab
            }
        }
    }

    fun clearFocusBlocked(tabId: String) {
        _tabs.update { list ->
            list.map { if (it.id == tabId) it.copy(focusBlockedHost = null) else it }
        }
    }

    private fun clearAllFocusBlockedTabs() {
        _tabs.update { list ->
            list.map { if (it.focusBlockedHost != null) it.copy(focusBlockedHost = null) else it }
        }
    }

    // --- READER MODE ---

    fun setReaderArticle(article: ReaderArticle) {
        _readerArticle.value = article
        _activeOverlay.value = ActiveOverlay.READER_MODE
    }

    fun clearReaderArticle() {
        _readerArticle.value = null
        if (_activeOverlay.value == ActiveOverlay.READER_MODE) {
            _activeOverlay.value = ActiveOverlay.NONE
        }
    }

    // --- FIND IN PAGE ---

    fun openFindInPage() {
        _activeOverlay.value = ActiveOverlay.NONE
        _findState.update { it.copy(isOpen = true) }
    }

    fun updateFindQuery(query: String) {
        _findState.update { it.copy(query = query) }
    }

    fun updateFindResults(activeMatch: Int, totalMatches: Int) {
        _findState.update { it.copy(activeMatch = activeMatch, totalMatches = totalMatches) }
    }

    fun closeFindInPage() {
        _findState.value = FindInPageState()
    }

    // --- PAGE SCRATCHPAD & WEB CLIPPER ---

    fun saveScratchpadClip(
        existingId: String?,
        title: String,
        sourceUrl: String,
        content: String,
        tag: String
    ) {
        val cleanContent = content.trim()
        if (cleanContent.isBlank()) {
            showTransientBanner("Add note content before saving.")
            return
        }
        val cleanTitle = title.trim().ifBlank {
            sourceUrl.toUri().host?.removePrefix("www.") ?: "Quick Note"
        }.take(100)
        val cleanTag = tag.trim().ifBlank { "Research" }.take(28)
        viewModelScope.launch {
            scratchpadClipDao.upsert(
                ScratchpadClipEntity(
                    id = existingId ?: "clip_${UUID.randomUUID().toString().take(10)}",
                    title = cleanTitle,
                    sourceUrl = sourceUrl.trim().take(400),
                    content = cleanContent.take(3000),
                    tag = cleanTag,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
            showTransientBanner("Saved clip \"$cleanTitle\" to Scratchpad.")
        }
    }

    fun deleteScratchpadClip(clipId: String) {
        viewModelScope.launch {
            scratchpadClipDao.deleteById(clipId)
            showTransientBanner("Clip deleted from Scratchpad.")
        }
    }

    // --- CUSTOMIZABLE HOMEPAGE SHORTCUTS (LOCAL-FIRST + OPTIONAL E2EE CLOUD SYNC) ---

    fun addShortcut(title: String, rawUrl: String, accentHex: String = "#2DD4BF") {
        val cleanTitle = title.trim().take(48)
        val normalizedUrl = resolveInputToUrl(rawUrl)
        if (cleanTitle.isBlank() || normalizedUrl.isBlank()) {
            showTransientBanner("Enter a valid shortcut title and web address.")
            return
        }
        viewModelScope.launch {
            val nextIndex = (shortcuts.value.maxOfOrNull { it.orderIndex } ?: -1) + 1
            val uid = currentUserId
            val repo = syncRepository
            val key = _vaultKey.value

            if (uid != null && repo != null && key != null) {
                val payloadJson = JSONObject().apply {
                    put("title", cleanTitle)
                    put("url", normalizedUrl)
                    put("accentHex", accentHex)
                }.toString()
                val env = withContext(Dispatchers.Default) {
                    E2EECryptoEngine.encrypt(payloadJson, key)
                }
                val res = repo.addShortcut(uid, env.ciphertextBase64, env.ivBase64, nextIndex)
                if (res.isSuccess) {
                    showTransientBanner("Shortcut \"$cleanTitle\" encrypted & synced.")
                } else {
                    showTransientBanner("Failed to sync shortcut.")
                }
            } else {
                localShortcutDao.upsert(
                    LocalShortcutEntity(
                        id = "local_${UUID.randomUUID().toString().take(10)}",
                        title = cleanTitle,
                        url = normalizedUrl,
                        accentHex = accentHex,
                        orderIndex = nextIndex
                    )
                )
                showTransientBanner("Shortcut \"$cleanTitle\" saved on device.")
            }
        }
    }

    fun deleteShortcut(shortcutId: String) {
        viewModelScope.launch {
            if (currentUserId != null && syncRepository != null && _vaultKey.value != null && !shortcutId.startsWith("local_")) {
                syncRepository.deleteShortcut(currentUserId, shortcutId)
            } else {
                localShortcutDao.deleteById(shortcutId)
            }
        }
    }

    // --- E2EE PASSWORD VAULT (REQUIRES SIGNED-IN & UNLOCKED VAULT) ---

    fun saveVaultCredential(
        existingId: String?,
        siteName: String,
        siteUrl: String,
        username: String,
        password: String,
        notes: String
    ) {
        val uid = currentUserId ?: return
        val repo = syncRepository ?: return
        val key = _vaultKey.value ?: return
        if (siteName.isBlank() || username.isBlank() || password.isBlank()) {
            showTransientBanner("Site name, username, and password are required.")
            return
        }
        viewModelScope.launch {
            val payloadJson = JSONObject().apply {
                put("siteName", siteName.trim().take(120))
                put("siteUrl", siteUrl.trim().take(300))
                put("username", username.trim().take(160))
                put("password", password.take(256))
                put("notes", notes.trim().take(500))
            }.toString()
            val env = withContext(Dispatchers.Default) {
                E2EECryptoEngine.encryptMultiLayer(payloadJson, key)
            }
            val result = if (existingId.isNullOrBlank()) {
                repo.addVaultItem(uid, env.ciphertextBase64, env.ivBase64)
            } else {
                repo.updateVaultItem(uid, existingId, env.ciphertextBase64, env.ivBase64)
            }
            if (result.isSuccess) {
                showTransientBanner("Password sealed with 3-Layer E2EE (AES-GCM + HKDF + HMAC).")
                if (settings.value.notificationsEnabled) {
                    notificationManager.notifySyncUpdate("3-Layer encrypted vault synced for ${siteName.trim()}")
                }
            } else {
                showTransientBanner("Could not sync vault entry.")
            }
        }
    }

    fun deleteVaultCredential(itemId: String) {
        val uid = currentUserId ?: return
        val repo = syncRepository ?: return
        viewModelScope.launch {
            val res = repo.deleteVaultItem(uid, itemId)
            if (res.isSuccess) showTransientBanner("Credential removed from vault.")
        }
    }

    fun matchingCredentialsForUrl(url: String): List<VaultCredential> {
        val currentHost = try {
            url.toUri().host?.lowercase()?.removePrefix("www.").orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (currentHost.isBlank()) return emptyList()
        return vaultCredentials.value.filter { cred ->
            val credHost = try {
                val target = if (cred.siteUrl.startsWith("http")) cred.siteUrl else "https://${cred.siteUrl}"
                target.toUri().host?.lowercase()?.removePrefix("www.").orEmpty()
            } catch (_: Exception) {
                cred.siteUrl.lowercase()
            }
            credHost.isNotBlank() && (currentHost == credHost || currentHost.endsWith(".$credHost") || credHost.endsWith(".$currentHost"))
        }
    }

    // --- OFFLINE READING & CACHED PAGES (WORKS LOCALLY + OPTIONAL E2EE CLOUD SYNC) ---

    fun saveCurrentPageForOffline(url: String, fallbackTitle: String) {
        if (url.isBlank() || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            showTransientBanner("Open a webpage first to save it for offline reading.")
            return
        }
        viewModelScope.launch {
            _isSavingOffline.value = true
            try {
                val archiveResult = offlineArchiver.archiveUrl(url, fallbackTitle)
                val archived = archiveResult.getOrElse { err ->
                    showTransientBanner("Offline save failed: ${err.localizedMessage ?: "Network error"}")
                    return@launch
                }

                val urlHash = E2EECryptoEngine.sha256Hex(archived.normalizedUrl.lowercase())
                val kb = (archived.byteSize / 1024L).coerceAtLeast(1L)
                val uid = currentUserId
                val repo = syncRepository
                val key = _vaultKey.value

                if (uid != null && repo != null && key != null) {
                    val (htmlEnv, metaEnv) = withContext(Dispatchers.Default) {
                        val hEnv = E2EECryptoEngine.encrypt(archived.htmlContent, key)
                        val metaJson = JSONObject().apply {
                            put("title", archived.title)
                            put("url", archived.normalizedUrl)
                            put("excerpt", archived.excerpt)
                            put("cachedByteSize", archived.byteSize)
                        }.toString()
                        val mEnv = E2EECryptoEngine.encrypt(metaJson, key)
                        hEnv to mEnv
                    }
                    cachedPageDao.upsert(
                        CachedPageEntity(
                            urlHash = urlHash,
                            userId = uid,
                            title = archived.title,
                            url = archived.normalizedUrl,
                            excerpt = archived.excerpt,
                            encryptedHtml = htmlEnv.ciphertextBase64,
                            iv = htmlEnv.ivBase64,
                            isEncrypted = true,
                            byteSize = archived.byteSize
                        )
                    )
                    val syncRes = repo.upsertReadingPage(
                        userId = uid,
                        urlHash = urlHash,
                        encryptedPayload = metaEnv.ciphertextBase64,
                        iv = metaEnv.ivBase64
                    )
                    if (syncRes.isSuccess) {
                        showTransientBanner("Cached offline (${kb} KB) & synced across devices.")
                        if (settings.value.notificationsEnabled) {
                            notificationManager.notifyOfflinePageCached(archived.title, kb)
                        }
                    } else {
                        showTransientBanner("Cached locally (${kb} KB). Cloud sync will retry online.")
                    }
                } else {
                    cachedPageDao.upsert(
                        CachedPageEntity(
                            urlHash = urlHash,
                            userId = localOwnerId,
                            title = archived.title,
                            url = archived.normalizedUrl,
                            excerpt = archived.excerpt,
                            encryptedHtml = archived.htmlContent,
                            iv = "",
                            isEncrypted = false,
                            byteSize = archived.byteSize
                        )
                    )
                    showTransientBanner("Saved offline on device (${kb} KB).")
                    if (settings.value.notificationsEnabled) {
                        notificationManager.notifyOfflinePageCached(archived.title, kb)
                    }
                }
            } finally {
                _isSavingOffline.value = false
            }
        }
    }

    fun cacheSyncedPageLocally(item: ReadingListItem) {
        saveCurrentPageForOffline(item.url, item.title)
    }

    fun openOfflineReader(item: ReadingListItem) {
        viewModelScope.launch {
            val cached = cachedPageDao.getByUrlHash(localOwnerId, item.urlHash)
            if (cached == null) {
                showTransientBanner("Not cached on this device yet. Tap Download while online.")
                return@launch
            }
            val html = if (cached.isEncrypted) {
                val key = _vaultKey.value
                if (key == null) {
                    showTransientBanner("Unlock your E2EE Vault in Settings to read encrypted snapshots.")
                    return@launch
                }
                withContext(Dispatchers.Default) {
                    try {
                        E2EECryptoEngine.decrypt(cached.encryptedHtml, cached.iv, key)
                    } catch (_: Exception) {
                        null
                    }
                }
            } else {
                cached.encryptedHtml
            }

            if (html == null) {
                showTransientBanner("Failed to open local cached page.")
                return@launch
            }
            val readableText = withContext(Dispatchers.Default) {
                offlineArchiver.extractReadableExcerpt(html)
            }
            _offlineReaderState.value = DecryptedOfflineReaderState(
                item = item,
                htmlContent = html,
                readableText = readableText
            )
        }
    }

    fun closeOfflineReader() {
        _offlineReaderState.value = null
    }

    fun toggleReadingArchived(item: ReadingListItem) {
        viewModelScope.launch {
            cachedPageDao.updateArchived(localOwnerId, item.urlHash, !item.archived)
            if (currentUserId != null && syncRepository != null && item.id != item.urlHash) {
                syncRepository.toggleReadingArchive(currentUserId, item.id, !item.archived)
            }
        }
    }

    fun deleteReadingPage(item: ReadingListItem) {
        viewModelScope.launch {
            cachedPageDao.deleteByUrlHash(localOwnerId, item.urlHash)
            if (currentUserId != null && syncRepository != null && item.id != item.urlHash) {
                syncRepository.deleteReadingPage(currentUserId, item.id)
            }
            showTransientBanner("Removed \"${item.title}\" from offline reading list.")
        }
    }

    // --- PREFERENCES (LOCAL DATASTORE + OPTIONAL FIRESTORE SYNC) ---

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            localPrefs.setThemeMode(mode)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "themeMode", mode.name)
            }
        }
    }

    fun setAdBlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPrefs.setAdBlockEnabled(enabled)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "adBlockEnabled", enabled)
            }
        }
    }

    fun setHttpsOnlyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPrefs.setHttpsOnlyEnabled(enabled)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "httpsOnlyEnabled", enabled)
            }
        }
    }

    fun setDarkWebPagesEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPrefs.setDarkWebPagesEnabled(enabled)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "darkWebPagesEnabled", enabled)
            }
        }
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            localPrefs.setNotificationsEnabled(enabled)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "notificationsEnabled", enabled)
            }
        }
    }

    fun setSearchEngine(engine: SearchEngine) {
        viewModelScope.launch {
            localPrefs.setSearchEngine(engine)
            if (currentUserId != null && syncRepository != null) {
                syncRepository.updatePreferenceField(currentUserId, "searchEngine", engine.name)
            }
        }
    }

    fun sendTestSecurityNotification() {
        notificationManager.notifySecurityAlert(
            title = "Velo Privacy Shield Active",
            body = "Privacy shield verified • ${settings.value.trackersBlockedTotal} trackers blocked."
        )
        showTransientBanner("Real-time security alert dispatched.")
    }

    // --- DECRYPTION HELPERS ---

    private fun decryptShortcut(doc: ShortcutDoc, key: SecretKey): ShortcutItem? {
        return try {
            val json = JSONObject(E2EECryptoEngine.decrypt(doc.encryptedPayload, doc.iv, key))
            ShortcutItem(
                id = doc.id,
                title = json.optString("title", "Shortcut"),
                url = json.optString("url", "https://duckduckgo.com"),
                accentHex = json.optString("accentHex", "#2DD4BF"),
                orderIndex = doc.orderIndex.toInt()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptVaultItem(doc: VaultItemDoc, key: SecretKey): VaultCredential? {
        return try {
            val json = JSONObject(E2EECryptoEngine.decryptAuto(doc.encryptedPayload, doc.iv, key))
            val ts = (doc.updatedAt ?: doc.createdAt ?: Timestamp.now()).toDate().time
            VaultCredential(
                id = doc.id,
                siteName = json.optString("siteName", "Account"),
                siteUrl = json.optString("siteUrl", ""),
                username = json.optString("username", ""),
                password = json.optString("password", ""),
                notes = json.optString("notes", ""),
                updatedAtMillis = ts
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptReadingPage(
        doc: ReadingPageDoc,
        key: SecretKey,
        localCache: CachedPageEntity?
    ): ReadingListItem? {
        return try {
            val json = JSONObject(E2EECryptoEngine.decrypt(doc.encryptedPayload, doc.iv, key))
            val ts = (doc.updatedAt ?: doc.createdAt ?: Timestamp.now()).toDate().time
            ReadingListItem(
                id = doc.id,
                urlHash = doc.urlHash,
                title = json.optString("title", "Saved Page"),
                url = json.optString("url", ""),
                excerpt = json.optString("excerpt", ""),
                cachedByteSize = localCache?.byteSize ?: json.optLong("cachedByteSize", 0L),
                archived = doc.archived,
                isCachedLocally = localCache != null,
                savedAtMillis = ts
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun unwrapFirestoreError(error: Throwable): FirebaseFirestoreException? {
        return generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
    }

    // --- VIDEO DETECTOR, DOWNLOADER & PLAYER ---

    fun recordDetectedVideo(tabId: String, media: DetectedVideoMedia) {
        _detectedVideosByTab.update { currentMap ->
            val list = currentMap[tabId].orEmpty().toMutableList()
            val existingIdx = list.indexOfFirst { it.url == media.url }
            if (existingIdx >= 0) {
                val old = list[existingIdx]
                list[existingIdx] = old.copy(
                    title = media.title.ifBlank { old.title },
                    qualityLabel = if (media.qualityLabel != "Auto") media.qualityLabel else old.qualityLabel,
                    isPlaying = media.isPlaying || old.isPlaying
                )
            } else {
                list.add(0, media)
                if (list.size > 15) {
                    list.removeAt(list.lastIndex)
                }
            }
            currentMap + (tabId to list)
        }
    }

    fun recordDetectedImages(tabId: String, images: List<DetectedImageMedia>) {
        _detectedImagesByTab.update { currentMap ->
            currentMap + (tabId to images)
        }
    }

    fun startVideoDownload(url: String, title: String, qualityLabel: String = "HD") {
        startWebFileDownload(
            url = url,
            suggestedTitle = title,
            qualityLabel = qualityLabel,
            userAgent = "",
            contentDisposition = "",
            mimeType = "",
            referer = activeTab.value.url.takeIf { it.startsWith("http") },
            autoOpenIfPdf = false
        )
    }

    fun enqueueSystemDownload(url: String, title: String = "", mimeType: String = "") {
        val result = systemDownloadManager.enqueueSystemDownload(
            url = url,
            suggestedFileName = title,
            mimeType = mimeType,
            referer = activeTab.value.url.takeIf { it.startsWith("http") }
        )
        result.onSuccess { enq ->
            showTransientBanner("Queued \"${enq.fileName}\" in Android DownloadManager (Downloads/Velo).")
        }.onFailure { err ->
            showTransientBanner(err.localizedMessage ?: "Unable to queue system download.")
        }
    }

    fun downloadImageInFormat(
        imageUrl: String,
        title: String,
        format: ImageDownloadFormat,
        referer: String? = null
    ) {
        val cleanUrl = imageUrl.trim()
        if (cleanUrl.isBlank()) return
        val displayTitle = title.trim().ifBlank { VideoDownloadEngine.deriveTitleFromUrl(cleanUrl) }
        _activeDownload.value = ActiveDownloadProgress(
            url = cleanUrl,
            title = "$displayTitle (${format.label})",
            downloadedBytes = 0L,
            totalBytes = -1L
        )
        showTransientBanner("Downloading full-size image as ${format.label}...")
        viewModelScope.launch {
            val result = videoDownloadEngine.downloadImageInFormat(
                rawUrl = cleanUrl,
                title = displayTitle,
                targetFormat = format,
                referer = referer ?: activeTab.value.url.takeIf { it.startsWith("http") }
            )
            _activeDownload.value = null
            result.onSuccess { done ->
                handleCompletedDownload(done, autoOpenIfPdf = false)
            }.onFailure { err ->
                showTransientBanner(err.localizedMessage ?: "Image download failed.")
            }
        }
    }

    fun shareDownloadedItem(item: DownloadedMediaItem) {
        val file = java.io.File(item.localPath)
        VeloFileSharing.shareFile(
            context = appContext,
            file = file,
            mimeType = item.mimeType,
            title = item.title
        ).onFailure { err ->
            showTransientBanner(err.localizedMessage ?: "Unable to share \"${item.title}\".")
        }
    }

    fun startWebFileDownload(
        url: String,
        suggestedTitle: String = "",
        qualityLabel: String = "File",
        userAgent: String = "",
        contentDisposition: String = "",
        mimeType: String = "",
        referer: String? = null,
        autoOpenIfPdf: Boolean = true
    ) {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://", ignoreCase = true) && !cleanUrl.startsWith("https://", ignoreCase = true)) {
            showTransientBanner("Enter a valid HTTP or HTTPS download URL.")
            return
        }
        downloadJob?.cancel()
        videoDownloadEngine.cancelActiveDownload()
        val displayTitle = suggestedTitle.trim().ifBlank { VideoDownloadEngine.deriveTitleFromUrl(cleanUrl) }
        _activeDownload.value = ActiveDownloadProgress(
            url = cleanUrl,
            title = displayTitle,
            downloadedBytes = 0L,
            totalBytes = -1L
        )
        showTransientBanner("Downloading \"$displayTitle\"...")
        downloadJob = viewModelScope.launch {
            val result = videoDownloadEngine.downloadAnyFile(
                rawUrl = cleanUrl,
                suggestedTitle = suggestedTitle,
                qualityLabel = qualityLabel,
                contentDisposition = contentDisposition,
                hintMimeType = mimeType,
                referer = referer,
                userAgent = userAgent,
                onProgress = { downloaded, total ->
                    _activeDownload.value = ActiveDownloadProgress(
                        url = cleanUrl,
                        title = displayTitle,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
            )
            _activeDownload.value = null
            result.onSuccess { done ->
                handleCompletedDownload(done, autoOpenIfPdf = autoOpenIfPdf)
            }.onFailure { err ->
                if (err !is CancellationException) {
                    showTransientBanner(err.localizedMessage ?: "Download failed.")
                }
            }
        }
    }

    fun saveInlineDataDownload(fileName: String, mimeType: String, base64Data: String) {
        viewModelScope.launch {
            showTransientBanner("Saving \"$fileName\"...")
            val result = videoDownloadEngine.saveInlineBase64File(fileName, mimeType, base64Data)
            result.onSuccess { done ->
                handleCompletedDownload(done, autoOpenIfPdf = true)
            }.onFailure { err ->
                showTransientBanner(err.localizedMessage ?: "Failed to save inline download.")
            }
        }
    }

    private suspend fun handleCompletedDownload(
        done: com.example.media.DownloadedVideoResult,
        autoOpenIfPdf: Boolean
    ) {
        downloadedMediaDao.upsert(
            DownloadedMediaEntity(
                id = done.id,
                title = done.title,
                sourceUrl = done.sourceUrl,
                localPath = done.localPath,
                mimeType = done.mimeType,
                formatLabel = done.formatLabel,
                qualityLabel = done.qualityLabel,
                byteSize = done.byteSize
            )
        )
        val sizeStr = if (done.byteSize >= 1024L * 1024L) {
            "${"%.1f".format(done.byteSize / (1024.0 * 1024.0))} MB"
        } else {
            "${(done.byteSize / 1024L).coerceAtLeast(1L)} KB"
        }
        val publicNote = if (done.savedToPublicDownloads) " • Saved to Downloads/Velo" else ""

        if (done.mimeType.equals("application/pdf", ignoreCase = true) ||
            done.formatLabel.equals("PDF", ignoreCase = true)
        ) {
            pdfEngine.importPdfFromFile(java.io.File(done.localPath), done.title)
                .onSuccess { pdfDoc ->
                    refreshSavedPdfs()
                    if (autoOpenIfPdf) {
                        _activePdfDocument.value = pdfDoc
                        _activeOverlay.value = ActiveOverlay.PDF_TOOLS
                    }
                    showTransientBanner("Downloaded PDF \"${pdfDoc.title}\" ($sizeStr)$publicNote")
                }
                .onFailure {
                    showTransientBanner("Downloaded \"${done.title}\" ($sizeStr)$publicNote")
                }
        } else {
            showTransientBanner("Downloaded \"${done.title}\" ($sizeStr)$publicNote")
        }
    }

    fun openDownloadedItem(item: DownloadedMediaItem) {
        val mime = item.mimeType.lowercase()
        val fmt = item.formatLabel.uppercase()
        when {
            mime == "application/pdf" || fmt == "PDF" || item.localPath.endsWith(".pdf", ignoreCase = true) -> {
                viewModelScope.launch {
                    val file = java.io.File(item.localPath)
                    pdfEngine.importPdfFromFile(file, item.title)
                        .onSuccess { pdfDoc ->
                            refreshSavedPdfs()
                            openPdfDocument(pdfDoc, returnTo = ActiveOverlay.VIDEO_DOWNLOADER)
                        }
                        .onFailure {
                            showTransientBanner("Unable to open PDF file.")
                        }
                }
            }
            mime.startsWith("image/") || fmt in setOf("PNG", "JPG", "WEBP", "GIF", "IMAGE") -> {
                openImageViewer(
                    request = ImageViewerRequest(
                        title = item.title,
                        source = item.localPath,
                        isContentUri = false
                    ),
                    returnTo = ActiveOverlay.VIDEO_DOWNLOADER
                )
            }
            mime.startsWith("audio/") || fmt in setOf("MP3", "M4A", "WAV", "OGG", "FLAC", "AUDIO") -> {
                openVideoPlayer(
                    request = VideoPlayerRequest(
                        title = item.title,
                        primarySource = item.localPath,
                        isLocalFile = true,
                        isAudioOnly = true
                    ),
                    returnTo = ActiveOverlay.VIDEO_DOWNLOADER
                )
            }
            mime.startsWith("video/") || fmt in setOf("MP4", "WEBM", "MKV", "MOV", "M4V", "3GP", "HLS", "HLS/TS", "VIDEO") -> {
                openVideoPlayer(
                    request = VideoPlayerRequest(
                        title = item.title,
                        primarySource = item.localPath,
                        isLocalFile = true,
                        isAudioOnly = false
                    ),
                    returnTo = ActiveOverlay.VIDEO_DOWNLOADER
                )
            }
            else -> {
                showTransientBanner("Saved in Downloads/Velo (${item.title}).")
            }
        }
    }

    fun openExternalFile(request: ExternalOpenRequest) {
        val uri = try {
            request.uriString.toUri()
        } catch (_: Exception) {
            showTransientBanner("Unable to open selected file.")
            return
        }
        val scheme = uri.scheme?.lowercase().orEmpty()
        val rawMime = request.mimeType?.lowercase().orEmpty()
        val displayName = resolveUriDisplayName(uri)
            ?: uri.lastPathSegment?.substringAfterLast('/')?.take(64)
            ?: "External File"
        val nameLower = displayName.lowercase()
        val pathLower = uri.path?.lowercase().orEmpty()

        val isPdf = rawMime == "application/pdf" || nameLower.endsWith(".pdf") || pathLower.endsWith(".pdf")
        val isVideo = rawMime.startsWith("video/") ||
            listOf(".mp4", ".mkv", ".webm", ".mov", ".m4v", ".3gp", ".ts", ".m3u8").any { nameLower.endsWith(it) || pathLower.endsWith(it) }
        val isImage = rawMime.startsWith("image/") ||
            listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".heic").any { nameLower.endsWith(it) || pathLower.endsWith(it) }
        val isAudio = rawMime.startsWith("audio/") ||
            listOf(".mp3", ".m4a", ".wav", ".ogg", ".flac", ".aac").any { nameLower.endsWith(it) || pathLower.endsWith(it) }

        when {
            isPdf -> {
                if (scheme == "http" || scheme == "https") {
                    startWebFileDownload(
                        url = uri.toString(),
                        suggestedTitle = displayName.removeSuffix(".pdf"),
                        mimeType = "application/pdf",
                        autoOpenIfPdf = true
                    )
                } else {
                    _activeOverlay.value = ActiveOverlay.PDF_TOOLS
                    importPdfFromUri(uri)
                }
            }
            isVideo -> {
                openVideoPlayer(
                    request = VideoPlayerRequest(
                        title = displayName,
                        primarySource = uri.toString(),
                        isLocalFile = scheme != "http" && scheme != "https",
                        isAudioOnly = false
                    ),
                    returnTo = ActiveOverlay.NONE
                )
            }
            isAudio -> {
                openVideoPlayer(
                    request = VideoPlayerRequest(
                        title = displayName,
                        primarySource = uri.toString(),
                        isLocalFile = scheme != "http" && scheme != "https",
                        isAudioOnly = true
                    ),
                    returnTo = ActiveOverlay.NONE
                )
            }
            isImage -> {
                openImageViewer(
                    request = ImageViewerRequest(
                        title = displayName,
                        source = uri.toString(),
                        isContentUri = scheme == "content"
                    ),
                    returnTo = ActiveOverlay.NONE
                )
            }
            else -> {
                showTransientBanner("Unsupported external file type ($displayName).")
            }
        }
    }

    fun openImageViewer(
        request: ImageViewerRequest,
        returnTo: ActiveOverlay = ActiveOverlay.NONE
    ) {
        returnOverlayAfterMedia = returnTo
        _imageViewerRequest.value = request
        _activeOverlay.value = ActiveOverlay.IMAGE_VIEWER
    }

    fun closeImageViewer() {
        _imageViewerRequest.value = null
        val target = returnOverlayAfterMedia
        returnOverlayAfterMedia = ActiveOverlay.NONE
        _activeOverlay.value = target
    }

    fun hideImageViewerInSecretVault(request: ImageViewerRequest) {
        if (request.isContentUri || request.source.startsWith("content://") || request.source.startsWith("file://")) {
            hideUrisInSecretVault(listOf(request.source.toUri()), SecretMediaType.IMAGE)
        } else {
            hideLocalFileInSecretVault(
                filePath = request.source,
                displayName = request.title,
                type = SecretMediaType.IMAGE,
                mimeType = "image/jpeg"
            )
        }
    }

    fun cancelVideoDownload() {
        videoDownloadEngine.cancelActiveDownload()
        downloadJob?.cancel()
        downloadJob = null
        _activeDownload.value = null
        showTransientBanner("Download canceled.")
    }

    fun deleteDownloadedMedia(item: DownloadedMediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                videoDownloadEngine.deleteLocalFile(item.localPath)
            }
            downloadedMediaDao.deleteById(item.id)
            showTransientBanner("Deleted \"${item.title}\".")
        }
    }

    fun openVideoPlayer(
        request: VideoPlayerRequest,
        returnTo: ActiveOverlay = ActiveOverlay.NONE
    ) {
        returnOverlayAfterMedia = returnTo
        _videoPlayerRequest.value = request
        _activeOverlay.value = ActiveOverlay.VIDEO_PLAYER
    }

    fun closeVideoPlayer() {
        val req = _videoPlayerRequest.value
        if (req?.deleteTempFileOnClose == true) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    java.io.File(req.primarySource).delete()
                } catch (_: Exception) {
                }
                secretVaultManager.wipeAllTempPreviews()
            }
        }
        _videoPlayerRequest.value = null
        val target = if (returnOverlayAfterMedia == ActiveOverlay.SECRET_MEDIA_VAULT && _secretVaultKey.value != null) {
            ActiveOverlay.SECRET_MEDIA_VAULT
        } else {
            returnOverlayAfterMedia
        }
        returnOverlayAfterMedia = ActiveOverlay.NONE
        _activeOverlay.value = target
    }

    // --- PDF VIEWER & CONVERTER ---

    fun refreshSavedPdfs() {
        viewModelScope.launch {
            _savedPdfs.value = pdfEngine.listSavedPdfs()
        }
    }

    fun openPdfDocument(
        item: PdfDocumentItem,
        returnTo: ActiveOverlay = ActiveOverlay.PDF_TOOLS
    ) {
        returnOverlayAfterMedia = returnTo
        _activePdfDocument.value = item
        _activeOverlay.value = ActiveOverlay.PDF_TOOLS
    }

    fun closeActivePdfDocument() {
        val active = _activePdfDocument.value
        if (active?.deleteTempFileOnClose == true) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    java.io.File(active.filePath).delete()
                } catch (_: Exception) {
                }
                secretVaultManager.wipeAllTempPreviews()
            }
        }
        _activePdfDocument.value = null
        if (returnOverlayAfterMedia == ActiveOverlay.SECRET_MEDIA_VAULT && _secretVaultKey.value != null) {
            returnOverlayAfterMedia = ActiveOverlay.NONE
            _activeOverlay.value = ActiveOverlay.SECRET_MEDIA_VAULT
        }
    }

    fun importPdfFromUri(uri: Uri) {
        viewModelScope.launch {
            _isPdfBusy.value = true
            try {
                pdfEngine.importPdfFromUri(uri)
                    .onSuccess { doc ->
                        refreshSavedPdfs()
                        _activePdfDocument.value = doc
                        showTransientBanner("Opened PDF \"${doc.title}\" (${doc.pageCount} pages).")
                    }
                    .onFailure { err ->
                        showTransientBanner(err.localizedMessage ?: "Unable to open PDF.")
                    }
            } finally {
                _isPdfBusy.value = false
            }
        }
    }

    fun convertImagesToPdf(uris: List<Uri>, title: String) {
        viewModelScope.launch {
            _isPdfBusy.value = true
            try {
                pdfEngine.convertImagesToPdf(uris, title)
                    .onSuccess { doc ->
                        refreshSavedPdfs()
                        _activePdfDocument.value = doc
                        showTransientBanner("Created PDF \"${doc.title}\" (${doc.pageCount} pages).")
                    }
                    .onFailure { err ->
                        showTransientBanner(err.localizedMessage ?: "Image-to-PDF conversion failed.")
                    }
            } finally {
                _isPdfBusy.value = false
            }
        }
    }

    fun convertTextToPdf(title: String, body: String) {
        viewModelScope.launch {
            _isPdfBusy.value = true
            try {
                pdfEngine.convertTextToPdf(title, body)
                    .onSuccess { doc ->
                        refreshSavedPdfs()
                        _activePdfDocument.value = doc
                        showTransientBanner("Converted text to PDF \"${doc.title}\".")
                    }
                    .onFailure { err ->
                        showTransientBanner(err.localizedMessage ?: "Text-to-PDF conversion failed.")
                    }
            } finally {
                _isPdfBusy.value = false
            }
        }
    }

    fun convertActiveWebViewToPdf(webView: WebView?, title: String) {
        if (webView == null) {
            showTransientBanner("Open a webpage first to save it as a PDF.")
            return
        }
        viewModelScope.launch {
            _isPdfBusy.value = true
            try {
                pdfEngine.convertWebViewToPdf(webView, title)
                    .onSuccess { doc ->
                        refreshSavedPdfs()
                        _activePdfDocument.value = doc
                        showTransientBanner("Saved webpage as PDF (${doc.pageCount} pages).")
                    }
                    .onFailure { err ->
                        showTransientBanner(err.localizedMessage ?: "Webpage-to-PDF failed.")
                    }
            } finally {
                _isPdfBusy.value = false
            }
        }
    }

    fun extractPdfToPngImages(item: PdfDocumentItem) {
        viewModelScope.launch {
            _isPdfBusy.value = true
            try {
                pdfEngine.convertPdfToPngImages(item.filePath)
                    .onSuccess { files ->
                        showTransientBanner("Extracted ${files.size} PNG page images from \"${item.title}\".")
                    }
                    .onFailure { err ->
                        showTransientBanner(err.localizedMessage ?: "PDF-to-Images extraction failed.")
                    }
            } finally {
                _isPdfBusy.value = false
            }
        }
    }

    fun exportPdfToUri(item: PdfDocumentItem, destUri: Uri) {
        viewModelScope.launch {
            pdfEngine.exportPdfToUri(item.filePath, destUri)
                .onSuccess { showTransientBanner("Exported \"${item.title}.pdf\" successfully.") }
                .onFailure { showTransientBanner(it.localizedMessage ?: "PDF export failed.") }
        }
    }

    fun deletePdfDocument(item: PdfDocumentItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { pdfEngine.deletePdf(item.filePath) }
            if (_activePdfDocument.value?.id == item.id) {
                _activePdfDocument.value = null
            }
            refreshSavedPdfs()
            showTransientBanner("Deleted PDF \"${item.title}\".")
        }
    }

    // --- INTERNET SPEED TEST METER ---

    fun startSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            val result = speedTestEngine.runSpeedTest { liveState ->
                _speedTestState.value = liveState
            }
            result.onSuccess { rec ->
                speedTestHistoryDao.insert(
                    SpeedTestHistoryEntity(
                        id = rec.id,
                        pingMs = rec.pingMs,
                        jitterMs = rec.jitterMs,
                        downloadMbps = rec.downloadMbps,
                        uploadMbps = rec.uploadMbps,
                        connectionType = rec.connectionType,
                        testedAtMillis = rec.testedAtMillis
                    )
                )
            }.onFailure { err ->
                if (err !is CancellationException) {
                    _speedTestState.value = SpeedTestLiveState(
                        phase = SpeedTestPhase.IDLE,
                        errorMessage = err.localizedMessage ?: "Speed test failed."
                    )
                }
            }
        }
    }

    fun stopSpeedTest() {
        speedTestJob?.cancel()
        speedTestJob = null
        _speedTestState.value = SpeedTestLiveState(phase = SpeedTestPhase.IDLE)
    }

    fun clearSpeedTestHistory() {
        viewModelScope.launch {
            speedTestHistoryDao.clearAll()
            showTransientBanner("Speed test history cleared.")
        }
    }

    // --- SECRET MEDIA VAULT (ACCESSED ONLY VIA SEARCH BAR SECRET CODE) ---

    fun clearSecretVaultError() {
        _secretVaultError.value = null
    }

    /**
     * Checks whether raw search-bar input matches the user's Secret Media Vault code.
     * NEVER sends a matched code to the web, search engine, or tab history.
     * If no custom code is configured yet, typing "#0000" opens first-time Secret Code setup.
     */
    fun handleOmniboxInput(
        rawInput: String,
        onSecretVaultTriggered: () -> Unit,
        onProceedToWebNavigation: () -> Unit
    ) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return

        val lower = trimmed.lowercase()
        val isExplicitUrl = lower.startsWith("http://") || lower.startsWith("https://")
        val isCandidateCode = !isExplicitUrl && !trimmed.contains(" ") && trimmed.length in 4..24

        if (!isCandidateCode) {
            onProceedToWebNavigation()
            return
        }

        val existingConfig = secretVaultConfig.value
        if (existingConfig == null) {
            if (trimmed == SecretMediaVaultManager.DEFAULT_SETUP_CODE) {
                onSecretVaultTriggered()
                _secretVaultError.value = null
                _activeOverlay.value = ActiveOverlay.SECRET_VAULT_SETUP
                return
            }
            onProceedToWebNavigation()
            return
        }

        // Verify candidate code against PBKDF2 verifier off the main thread
        viewModelScope.launch {
            val derivedKey = withContext(Dispatchers.Default) {
                secretVaultManager.verifyAndDeriveKey(trimmed, existingConfig)
            }
            if (derivedKey != null) {
                onSecretVaultTriggered()
                _secretVaultKey.value = derivedKey
                _secretVaultError.value = null
                _activeOverlay.value = ActiveOverlay.SECRET_MEDIA_VAULT
            } else {
                onProceedToWebNavigation()
            }
        }
    }

    fun setupOrChangeSecretCode(
        newCode: String,
        confirmCode: String,
        currentCode: String = ""
    ) {
        val cleanNew = newCode.trim()
        if (cleanNew.length !in 4..24 || cleanNew.contains(" ")) {
            _secretVaultError.value = "Secret code must be 4–24 characters with no spaces (e.g. #4829)."
            return
        }
        if (cleanNew != confirmCode.trim()) {
            _secretVaultError.value = "Secret codes do not match."
            return
        }

        viewModelScope.launch {
            _secretVaultBusy.value = true
            _secretVaultError.value = null
            try {
                val existingConfig = secretVaultConfig.value
                val oldKey: SecretKey? = if (existingConfig != null) {
                    _secretVaultKey.value ?: withContext(Dispatchers.Default) {
                        secretVaultManager.verifyAndDeriveKey(currentCode.trim(), existingConfig)
                    }
                } else null

                if (existingConfig != null && oldKey == null) {
                    _secretVaultError.value = "Current secret code is incorrect."
                    return@launch
                }

                val (newConfig, newKey) = withContext(Dispatchers.Default) {
                    secretVaultManager.createVaultConfigForCode(cleanNew)
                }

                // Re-encrypt existing hidden files if changing an existing code
                if (oldKey != null && secretMediaItems.value.isNotEmpty()) {
                    val rekeyRes = secretVaultManager.reEncryptAllFiles(
                        items = secretMediaItems.value,
                        oldKey = oldKey,
                        newKey = newKey
                    )
                    val updatedMap = rekeyRes.getOrElse { err ->
                        _secretVaultError.value = err.localizedMessage ?: "Failed to re-encrypt vault files."
                        return@launch
                    }
                    secretMediaItems.value.forEach { item ->
                        val updated = updatedMap[item.id] ?: return@forEach
                        secretMediaDao.upsert(
                            SecretMediaEntity(
                                id = item.id,
                                displayName = item.displayName,
                                mediaType = item.type.name,
                                mimeType = item.mimeType,
                                encryptedFileName = updated.first,
                                ivBase64 = updated.second,
                                byteSize = item.byteSize,
                                addedAtMillis = item.addedAtMillis
                            )
                        )
                    }
                }

                localPrefs.saveSecretVaultConfig(newConfig)
                _secretVaultKey.value = newKey
                _activeOverlay.value = ActiveOverlay.SECRET_MEDIA_VAULT
                showTransientBanner("Secret Vault active — type your code in the search bar anytime to open.")
            } finally {
                _secretVaultBusy.value = false
            }
        }
    }

    fun clearPendingDeleteOriginalUris() {
        _pendingDeleteOriginalUris.value = emptyList()
    }

    fun onOriginalDeletePromptFinished(removedCount: Int) {
        _pendingDeleteOriginalUris.value = emptyList()
        if (removedCount > 0) {
            showTransientBanner("Removed $removedCount original file(s) from Gallery & Files — now hidden in Velo Vault only.")
        }
    }

    fun hideUrisInSecretVault(
        uris: List<Uri>,
        forcedType: SecretMediaType? = null,
        removeOriginalsFromDevice: Boolean = true
    ) {
        val key = _secretVaultKey.value
        if (key == null) {
            showTransientBanner("Unlock Secret Vault first by typing your code in the search bar.")
            return
        }
        if (uris.isEmpty()) return

        viewModelScope.launch {
            _secretVaultBusy.value = true
            var hiddenCount = 0
            var deletedOriginals = 0
            val needsDeleteConsent = mutableListOf<Uri>()
            try {
                for (uri in uris) {
                    val mime = appContext.contentResolver.getType(uri).orEmpty()
                    val name = resolveUriDisplayName(uri) ?: "Hidden_File_${System.currentTimeMillis() % 10000}"
                    val mediaType = forcedType ?: inferSecretMediaType(mime, name)

                    val encResult = withContext(Dispatchers.IO) {
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            secretVaultManager.encryptStreamToVault(input, key)
                        } ?: Result.failure(IllegalStateException("Could not read selected file."))
                    }

                    encResult.onSuccess { enc ->
                        secretMediaDao.upsert(
                            SecretMediaEntity(
                                id = "sec_${UUID.randomUUID().toString().take(10)}",
                                displayName = name.take(80),
                                mediaType = mediaType.name,
                                mimeType = mime.ifBlank { defaultMimeFor(mediaType) },
                                encryptedFileName = enc.encryptedFileName,
                                ivBase64 = enc.ivBase64,
                                byteSize = enc.plaintextByteSize
                            )
                        )
                        hiddenCount++

                        // Permanently remove the unencrypted original from Gallery/Files ONLY after verified encryption
                        if (removeOriginalsFromDevice) {
                            val removed = withContext(Dispatchers.IO) {
                                tryDeleteSourceUri(uri)
                            }
                            if (removed) {
                                deletedOriginals++
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                uri.authority?.contains("media", ignoreCase = true) == true
                            ) {
                                needsDeleteConsent.add(uri)
                            }
                        }
                    }
                }
                if (needsDeleteConsent.isNotEmpty()) {
                    _pendingDeleteOriginalUris.value = needsDeleteConsent
                }
                when {
                    hiddenCount == 0 -> showTransientBanner("Could not import selected media into Secret Vault.")
                    deletedOriginals == hiddenCount -> showTransientBanner(
                        "Encrypted & moved $hiddenCount item(s) to Secret Vault — removed from Gallery & Files."
                    )
                    needsDeleteConsent.isNotEmpty() -> showTransientBanner(
                        "Encrypted $hiddenCount item(s). Tap Allow to remove original(s) from Gallery & Files."
                    )
                    deletedOriginals > 0 -> showTransientBanner(
                        "Encrypted $hiddenCount item(s) ($deletedOriginals removed from Files/Gallery)."
                    )
                    else -> showTransientBanner(
                        "Encrypted $hiddenCount item(s) in Secret Vault. Select via Files picker to auto-remove originals."
                    )
                }
            } finally {
                _secretVaultBusy.value = false
            }
        }
    }

    private fun tryDeleteSourceUri(uri: Uri): Boolean {
        return try {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                if (DocumentsContract.deleteDocument(appContext.contentResolver, uri)) {
                    return true
                }
            }
            val rows = appContext.contentResolver.delete(uri, null, null)
            rows > 0
        } catch (_: Exception) {
            false
        }
    }

    fun hideLocalFileInSecretVault(
        filePath: String,
        displayName: String,
        type: SecretMediaType,
        mimeType: String,
        onOriginalHidden: (() -> Unit)? = null
    ) {
        val key = _secretVaultKey.value
        if (key == null) {
            if (secretVaultConfig.value == null) {
                _activeOverlay.value = ActiveOverlay.SECRET_VAULT_SETUP
                showTransientBanner("Set a Secret Code first to hide files in the Secret Vault.")
            } else {
                showTransientBanner("Type your secret code in the search bar to unlock the Vault first.")
            }
            return
        }
        viewModelScope.launch {
            _secretVaultBusy.value = true
            try {
                val file = java.io.File(filePath)
                if (!file.exists()) {
                    showTransientBanner("File no longer exists on device.")
                    return@launch
                }
                val encResult = withContext(Dispatchers.IO) {
                    file.inputStream().buffered().use { input ->
                        secretVaultManager.encryptStreamToVault(input, key)
                    }
                }
                encResult.onSuccess { enc ->
                    secretMediaDao.upsert(
                        SecretMediaEntity(
                            id = "sec_${UUID.randomUUID().toString().take(10)}",
                            displayName = displayName.take(80),
                            mediaType = type.name,
                            mimeType = mimeType.ifBlank { defaultMimeFor(type) },
                            encryptedFileName = enc.encryptedFileName,
                            ivBase64 = enc.ivBase64,
                            byteSize = enc.plaintextByteSize
                        )
                    )
                    withContext(Dispatchers.IO) {
                        videoDownloadEngine.deletePublicDownloadsCopy(displayName)
                    }
                    onOriginalHidden?.invoke()
                    showTransientBanner("Encrypted & moved \"$displayName\" into Secret Vault (hidden from Gallery & Files).")
                }.onFailure { err ->
                    showTransientBanner(err.localizedMessage ?: "Failed to encrypt file.")
                }
            } finally {
                _secretVaultBusy.value = false
            }
        }
    }

    fun openSecretMediaItem(item: SecretMediaItem) {
        val key = _secretVaultKey.value ?: return
        viewModelScope.launch {
            _secretVaultBusy.value = true
            try {
                when (item.type) {
                    SecretMediaType.IMAGE -> {
                        secretVaultManager.decryptVaultFileToBytes(item.encryptedFileName, item.ivBase64, key)
                            .onSuccess { bytes -> _secretImagePreview.value = item to bytes }
                            .onFailure { showTransientBanner("Unable to decrypt image.") }
                    }
                    SecretMediaType.VIDEO, SecretMediaType.AUDIO -> {
                        val ext = extensionForMime(item.mimeType, item.type)
                        secretVaultManager.decryptVaultFileToTempPreview(item.encryptedFileName, item.ivBase64, ext, key)
                            .onSuccess { tempFile ->
                                openVideoPlayer(
                                    request = VideoPlayerRequest(
                                        title = item.displayName,
                                        primarySource = tempFile.canonicalPath,
                                        isLocalFile = true,
                                        isAudioOnly = item.type == SecretMediaType.AUDIO,
                                        deleteTempFileOnClose = true
                                    ),
                                    returnTo = ActiveOverlay.SECRET_MEDIA_VAULT
                                )
                            }
                            .onFailure { showTransientBanner("Unable to decrypt media for playback.") }
                    }
                    SecretMediaType.PDF -> {
                        secretVaultManager.decryptVaultFileToTempPreview(item.encryptedFileName, item.ivBase64, "pdf", key)
                            .onSuccess { tempFile ->
                                val pages = pdfEngine.getPageCount(tempFile.canonicalPath).coerceAtLeast(1)
                                openPdfDocument(
                                    item = PdfDocumentItem(
                                        id = item.id,
                                        title = item.displayName,
                                        filePath = tempFile.canonicalPath,
                                        pageCount = pages,
                                        byteSize = item.byteSize,
                                        createdAtMillis = item.addedAtMillis,
                                        deleteTempFileOnClose = true
                                    ),
                                    returnTo = ActiveOverlay.SECRET_MEDIA_VAULT
                                )
                            }
                            .onFailure { showTransientBanner("Unable to decrypt PDF document.") }
                    }
                }
            } finally {
                _secretVaultBusy.value = false
            }
        }
    }

    fun closeSecretImagePreview() {
        _secretImagePreview.value = null
    }

    fun exportSecretMediaItem(item: SecretMediaItem, destUri: Uri) {
        val key = _secretVaultKey.value ?: return
        viewModelScope.launch {
            _secretVaultBusy.value = true
            try {
                val res = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(destUri)?.use { out ->
                        secretVaultManager.decryptVaultFileToStream(item.encryptedFileName, item.ivBase64, key, out)
                    } ?: Result.failure(IllegalStateException("Could not open export destination."))
                }
                res.onSuccess { showTransientBanner("Exported \"${item.displayName}\" from Secret Vault.") }
                    .onFailure { showTransientBanner(it.localizedMessage ?: "Export failed.") }
            } finally {
                _secretVaultBusy.value = false
            }
        }
    }

    fun deleteSecretMediaItem(item: SecretMediaItem) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                secretVaultManager.deleteVaultFile(item.encryptedFileName)
            }
            secretMediaDao.deleteById(item.id)
            if (_secretImagePreview.value?.first?.id == item.id) {
                _secretImagePreview.value = null
            }
            showTransientBanner("Permanently erased \"${item.displayName}\" from Secret Vault.")
        }
    }

    fun lockSecretMediaVault() {
        _secretVaultKey.value = null
        _secretImagePreview.value = null
        secretVaultManager.wipeAllTempPreviews()
        if (_activeOverlay.value == ActiveOverlay.SECRET_MEDIA_VAULT ||
            _activeOverlay.value == ActiveOverlay.SECRET_VAULT_SETUP
        ) {
            _activeOverlay.value = ActiveOverlay.NONE
        }
    }

    private fun resolveUriDisplayName(uri: Uri): String? {
        return try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    private fun inferSecretMediaType(mime: String, name: String): SecretMediaType {
        val m = mime.lowercase()
        val n = name.lowercase()
        return when {
            m.startsWith("video/") || n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".webm") || n.endsWith(".mov") ->
                SecretMediaType.VIDEO
            m.startsWith("audio/") || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".wav") || n.endsWith(".ogg") || n.endsWith(".flac") ->
                SecretMediaType.AUDIO
            m == "application/pdf" || n.endsWith(".pdf") ->
                SecretMediaType.PDF
            else -> SecretMediaType.IMAGE
        }
    }

    private fun defaultMimeFor(type: SecretMediaType): String = when (type) {
        SecretMediaType.VIDEO -> "video/mp4"
        SecretMediaType.IMAGE -> "image/jpeg"
        SecretMediaType.PDF -> "application/pdf"
        SecretMediaType.AUDIO -> "audio/mpeg"
    }

    private fun extensionForMime(mime: String, type: SecretMediaType): String {
        val m = mime.lowercase()
        return when {
            m.contains("webm") -> "webm"
            m.contains("matroska") -> "mkv"
            m.contains("quicktime") -> "mov"
            m.contains("mp4") && type == SecretMediaType.AUDIO -> "m4a"
            m.contains("mpeg") && type == SecretMediaType.AUDIO -> "mp3"
            m.contains("wav") -> "wav"
            m.contains("ogg") -> "ogg"
            m.contains("flac") -> "flac"
            type == SecretMediaType.AUDIO -> "mp3"
            type == SecretMediaType.PDF -> "pdf"
            else -> "mp4"
        }
    }

    // --- ALL-IN-ONE WEB APPS HUB & BACKGROUND AUDIO SERVICE ---

    fun launchWebApp(
        service: WebAppService,
        preferNativeForHybrid: Boolean = true,
        onOpenUrlInTab: (String) -> Unit
    ) {
        if (preferNativeForHybrid && service.mode == WebAppIntegrationMode.HYBRID_NATIVE) {
            val launchedNative = WebAppService.launchNativeAppIfInstalled(appContext, service)
            if (launchedNative) {
                showTransientBanner("Opened official ${service.label} app for hardware Widevine L1 HD/4K.")
                return
            } else {
                showTransientBanner("${service.label} app not installed — opening desktop web view.")
            }
        }
        onOpenUrlInTab(service.url)
        when (service) {
            WebAppService.WHATSAPP -> showTransientBanner("WhatsApp Web active (Desktop UA + Mobile Single-Column UI).")
            WebAppService.SPOTIFY -> showTransientBanner("Spotify Web active — tap BG Audio to keep playing with screen off.")
            WebAppService.INSTAGRAM -> showTransientBanner("Instagram Web active (app banners hidden).")
            else -> Unit
        }
    }

    fun toggleBackgroundAudio(title: String, subtitle: String) {
        val next = !_backgroundAudioActive.value
        _backgroundAudioActive.value = next
        if (next) {
            MediaPlaybackForegroundService.start(
                context = appContext,
                title = title.ifBlank { "Velo Background Audio" },
                subtitle = subtitle.ifBlank { "Screen-off audio session active" }
            )
            showTransientBanner("Background Audio Foreground Service started.")
        } else {
            MediaPlaybackForegroundService.stop(appContext)
            showTransientBanner("Background Audio stopped.")
        }
    }

    fun stopBackgroundAudio() {
        if (_backgroundAudioActive.value) {
            _backgroundAudioActive.value = false
            MediaPlaybackForegroundService.stop(appContext)
        }
    }

    override fun onCleared() {
        super.onCleared()
        focusTimerJob?.cancel()
        downloadJob?.cancel()
        speedTestJob?.cancel()
        videoDownloadEngine.cancelActiveDownload()
        secretVaultManager.wipeAllTempPreviews()
        MediaPlaybackForegroundService.stop(appContext)
    }

    companion object {
        private const val TAG = "BrowserViewModel"
        private const val LOCAL_GUEST_ID = "local_guest"
        private const val MAX_NETWORK_LOGS = 300

        private val DEFAULT_SHORTCUTS = listOf(
            Triple("Wikipedia", "https://en.wikipedia.org", "#38BDF8"),
            Triple("DuckDuckGo", "https://duckduckgo.com", "#F97316"),
            Triple("GitHub", "https://github.com", "#A78BFA"),
            Triple("MDN Docs", "https://developer.mozilla.org", "#2DD4BF"),
            Triple("Hacker News", "https://news.ycombinator.com", "#FB923C"),
            Triple("Reuters", "https://www.reuters.com", "#10B981")
        )
    }
}
