package com.example.viewmodel

import android.util.Log
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.browser.ActiveOverlay
import com.example.browser.AdBlockerEngine
import com.example.browser.BrowserSettings
import com.example.browser.BrowserTab
import com.example.browser.FindInPageState
import com.example.browser.FocusSessionState
import com.example.browser.NetworkRequestLog
import com.example.browser.OfflinePageArchiver
import com.example.browser.ReaderArticle
import com.example.browser.ReadingListItem
import com.example.browser.ScratchpadClip
import com.example.browser.SearchEngine
import com.example.browser.ShortcutItem
import com.example.browser.SplitPane
import com.example.browser.ThemeMode
import com.example.browser.VaultCredential
import com.example.crypto.E2EECryptoEngine
import com.example.data.local.CachedPageDao
import com.example.data.local.CachedPageEntity
import com.example.data.local.LocalPreferencesStore
import com.example.data.local.LocalShortcutDao
import com.example.data.local.LocalShortcutEntity
import com.example.data.local.ScratchpadClipDao
import com.example.data.local.ScratchpadClipEntity
import com.example.data.remote.BrowserPreferencesDoc
import com.example.data.remote.ReadingPageDoc
import com.example.data.remote.ShortcutDoc
import com.example.data.remote.VaultConfigDoc
import com.example.data.remote.VaultItemDoc
import com.example.data.remote.VeloSyncRepository
import com.example.system.NetworkConnectivityObserver
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
    private val syncRepository: VeloSyncRepository?,
    private val cachedPageDao: CachedPageDao,
    private val localShortcutDao: LocalShortcutDao,
    private val scratchpadClipDao: ScratchpadClipDao,
    private val localPrefs: LocalPreferencesStore,
    private val offlineArchiver: OfflinePageArchiver,
    private val notificationManager: VeloNotificationManager,
    private val connectivityObserver: NetworkConnectivityObserver,
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
                E2EECryptoEngine.encrypt(payloadJson, key)
            }
            val result = if (existingId.isNullOrBlank()) {
                repo.addVaultItem(uid, env.ciphertextBase64, env.ivBase64)
            } else {
                repo.updateVaultItem(uid, existingId, env.ciphertextBase64, env.ivBase64)
            }
            if (result.isSuccess) {
                showTransientBanner("Credential encrypted (AES-256-GCM) & synced.")
                if (settings.value.notificationsEnabled) {
                    notificationManager.notifySyncUpdate("Password vault synced for ${siteName.trim()}")
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
            val json = JSONObject(E2EECryptoEngine.decrypt(doc.encryptedPayload, doc.iv, key))
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

    override fun onCleared() {
        super.onCleared()
        focusTimerJob?.cancel()
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
