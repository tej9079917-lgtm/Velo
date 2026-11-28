package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.speech.RecognizerIntent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.browser.ActiveOverlay
import com.example.browser.BrowserTab
import com.example.browser.ExternalOpenRequest
import com.example.browser.FocusSessionState
import com.example.browser.ImageDownloadFormat
import com.example.browser.SecretMediaType
import com.example.browser.SplitPane
import com.example.browser.TabWebCallbacks
import com.example.browser.VideoPlayerRequest
import com.example.browser.WebAppIntegrationMode
import com.example.browser.WebAppService
import com.example.browser.WebViewTabManager
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.BrowserViewModel
import com.example.viewmodel.VaultGateState
import kotlinx.coroutines.delay

@Composable
fun BrowserMainScreen(
    viewModel: BrowserViewModel,
    userEmail: String?,
    isInPipMode: Boolean,
    initialExternalUrl: String?,
    initialExternalFile: ExternalOpenRequest?,
    onConsumeExternalUrl: () -> Unit,
    onConsumeExternalFile: () -> Unit,
    onEnterPipMode: () -> Unit,
    onSignOut: () -> Unit
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val urlFocusRequester = remember { FocusRequester() }

    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    val primaryTab by viewModel.primaryTab.collectAsStateWithLifecycle()
    val secondaryTab by viewModel.secondaryTab.collectAsStateWithLifecycle()
    val activePane by viewModel.activePane.collectAsStateWithLifecycle()
    val splitRatio by viewModel.splitRatio.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()
    val activeOverlay by viewModel.activeOverlay.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    val vaultCredentials by viewModel.vaultCredentials.collectAsStateWithLifecycle()
    val vaultGateState by viewModel.vaultGateState.collectAsStateWithLifecycle()
    val vaultBusy by viewModel.vaultBusy.collectAsStateWithLifecycle()
    val vaultError by viewModel.vaultErrorMessage.collectAsStateWithLifecycle()
    val readingList by viewModel.readingList.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
    val isSavingOffline by viewModel.isSavingOffline.collectAsStateWithLifecycle()
    val offlineReaderState by viewModel.offlineReaderState.collectAsStateWithLifecycle()
    val allowlistedVersion by viewModel.allowlistedVersion.collectAsStateWithLifecycle()
    val networkLogs by viewModel.networkLogs.collectAsStateWithLifecycle()
    val customBlockedHosts by viewModel.customBlockedHosts.collectAsStateWithLifecycle()
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val focusBlocklist by viewModel.focusBlocklist.collectAsStateWithLifecycle()
    val readerArticle by viewModel.readerArticle.collectAsStateWithLifecycle()
    val scratchpadClips by viewModel.scratchpadClips.collectAsStateWithLifecycle()
    val findState by viewModel.findState.collectAsStateWithLifecycle()

    val detectedVideos by viewModel.detectedVideosForActiveTab.collectAsStateWithLifecycle()
    val detectedImages by viewModel.detectedImagesForActiveTab.collectAsStateWithLifecycle()
    val downloadedMedia by viewModel.downloadedMedia.collectAsStateWithLifecycle()
    val activeDownload by viewModel.activeDownload.collectAsStateWithLifecycle()
    val downloaderInitialTab by viewModel.downloaderInitialTab.collectAsStateWithLifecycle()
    val videoPlayerRequest by viewModel.videoPlayerRequest.collectAsStateWithLifecycle()
    val imageViewerRequest by viewModel.imageViewerRequest.collectAsStateWithLifecycle()
    val savedPdfs by viewModel.savedPdfs.collectAsStateWithLifecycle()
    val activePdfDocument by viewModel.activePdfDocument.collectAsStateWithLifecycle()
    val isPdfBusy by viewModel.isPdfBusy.collectAsStateWithLifecycle()
    val speedTestState by viewModel.speedTestState.collectAsStateWithLifecycle()
    val speedTestHistory by viewModel.speedTestHistory.collectAsStateWithLifecycle()
    val secretVaultConfig by viewModel.secretVaultConfig.collectAsStateWithLifecycle()
    val isSecretVaultUnlocked by viewModel.isSecretVaultUnlocked.collectAsStateWithLifecycle()
    val secretVaultBusy by viewModel.secretVaultBusy.collectAsStateWithLifecycle()
    val secretVaultError by viewModel.secretVaultError.collectAsStateWithLifecycle()
    val secretMediaItems by viewModel.secretMediaItems.collectAsStateWithLifecycle()
    val secretImagePreview by viewModel.secretImagePreview.collectAsStateWithLifecycle()
    val pendingDeleteOriginalUris by viewModel.pendingDeleteOriginalUris.collectAsStateWithLifecycle()
    val backgroundAudioActive by viewModel.backgroundAudioActive.collectAsStateWithLifecycle()

    var hasPublicStorageAccess by remember { mutableStateOf(viewModel.hasPublicStorageAccess()) }
    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPublicStorageAccess = granted || viewModel.hasPublicStorageAccess()
        if (hasPublicStorageAccess) {
            viewModel.showTransientBanner("Storage access granted — downloads will save to Downloads/Velo.")
        } else {
            viewModel.showTransientBanner("Storage access denied — files will save inside Velo Downloads.")
        }
    }

    fun requestLegacyStorageIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && !viewModel.hasPublicStorageAccess()) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            hasPublicStorageAccess = true
        }
    }

    var longPressedImage by remember { mutableStateOf<Pair<String, String?>?>(null) }
    var longPressImageFormat by remember { mutableStateOf(ImageDownloadFormat.ORIGINAL) }

    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? Activity

    // Block OS screenshots, screen recordings & recents thumbnails while Vaults are open
    DisposableEffect(activeOverlay, activity) {
        val isSensitiveScreen = activeOverlay == ActiveOverlay.PASSWORD_VAULT ||
            activeOverlay == ActiveOverlay.SECRET_MEDIA_VAULT ||
            activeOverlay == ActiveOverlay.SECRET_VAULT_SETUP
        if (isSensitiveScreen) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Automatically lock Password Vault & Secret Media Vault whenever app leaves foreground
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                viewModel.lockVault()
                viewModel.lockSecretMediaVault()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isSignedIn = viewModel.currentUserId != null
    val unlockedFingerprint = (vaultGateState as? VaultGateState.Unlocked)?.keyFingerprint.orEmpty()
    val isVaultUnlocked = vaultGateState is VaultGateState.Unlocked

    val webViewManager = remember(context) {
        WebViewTabManager(
            context = context,
            callbacks = TabWebCallbacks(
                onPageStarted = { tabId, url -> viewModel.updateTabOnStart(tabId, url) },
                onPageFinished = { tabId, url, title, back, fwd ->
                    viewModel.updateTabOnFinish(tabId, url, title, back, fwd)
                },
                onProgressChanged = { tabId, progress -> viewModel.updateTabProgress(tabId, progress) },
                onTitleReceived = { tabId, title -> viewModel.updateTabTitle(tabId, title) },
                onTrackerBlocked = { tabId, _ -> viewModel.recordTrackerBlocked(tabId) },
                onInsecureHttpIntercepted = { tabId, httpUrl ->
                    viewModel.recordInsecureHttpIntercepted(tabId, httpUrl)
                },
                onNetworkRequestLogged = { log -> viewModel.recordNetworkLog(log) },
                onFocusDistractionBlocked = { tabId, host ->
                    viewModel.recordFocusDistractionBlocked(tabId, host)
                },
                onVideoDetected = { tabId, media ->
                    viewModel.recordDetectedVideo(tabId, media)
                },
                onImagesDetected = { tabId, images ->
                    viewModel.recordDetectedImages(tabId, images)
                },
                onDownloadRequested = { _, url, userAgent, contentDisposition, mimeType, referer ->
                    viewModel.startWebFileDownload(
                        url = url,
                        userAgent = userAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        referer = referer,
                        autoOpenIfPdf = true
                    )
                },
                onInlineDataDownload = { fileName, mimeType, base64Data ->
                    viewModel.saveInlineDataDownload(fileName, mimeType, base64Data)
                },
                onImageLongPressed = { imageUrl, referer ->
                    longPressedImage = imageUrl to referer
                }
            )
        )
    }

    LaunchedEffect(settings.adBlockEnabled, settings.httpsOnlyEnabled) {
        webViewManager.adBlockEnabled = settings.adBlockEnabled
        webViewManager.httpsOnlyEnabled = settings.httpsOnlyEnabled
    }

    LaunchedEffect(focusState.isActive, focusState.isPaused, focusBlocklist) {
        webViewManager.focusModeActive = focusState.isActive && !focusState.isPaused
        webViewManager.focusBlocklist = focusBlocklist
    }

    LaunchedEffect(settings.darkWebPagesEnabled) {
        webViewManager.applyDarkPagesToAll(settings.darkWebPagesEnabled)
    }

    DisposableEffect(webViewManager) {
        onDispose { webViewManager.destroyAll() }
    }

    LaunchedEffect(statusBanner) {
        if (statusBanner != null) {
            delay(3500)
            viewModel.dismissBanner()
        }
    }

    LaunchedEffect(initialExternalUrl) {
        val ext = initialExternalUrl
        if (!ext.isNullOrBlank()) {
            val resolved = viewModel.resolveInputToUrl(ext)
            val tab = viewModel.activeTab.value
            if (webViewManager.loadUrl(tab, resolved)) {
                viewModel.updateTabOnStart(tab.id, resolved)
            }
            onConsumeExternalUrl()
        }
    }

    LaunchedEffect(initialExternalFile) {
        val extFile = initialExternalFile
        if (extFile != null) {
            viewModel.openExternalFile(extFile)
            onConsumeExternalFile()
        }
    }

    var addressText by remember(activeTab.id, activeTab.url) { mutableStateOf(activeTab.url) }
    var isEditingAddress by remember { mutableStateOf(false) }

    fun navigateTab(tab: BrowserTab, rawInput: String, bypassHttps: Boolean = false) {
        val resolved = if (bypassHttps) rawInput else viewModel.resolveInputToUrl(rawInput)
        if (resolved.isBlank()) return
        if (webViewManager.loadUrl(tab, resolved, bypassHttpsCheck = bypassHttps)) {
            viewModel.updateTabOnStart(tab.id, resolved)
        }
        focusManager.clearFocus()
        isEditingAddress = false
    }

    fun navigateCurrentTab(rawInput: String, bypassHttps: Boolean = false) {
        navigateTab(viewModel.activeTab.value, rawInput, bypassHttps)
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                addressText = spoken
                navigateCurrentTab(spoken)
            }
        }
    }

    fun launchVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a search query or web address")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            viewModel.showTransientBanner("Voice recognition service not available on this device.")
        }
    }

    BackHandler(enabled = activeOverlay == ActiveOverlay.NONE && (findState.isOpen || activeTab.url.isNotBlank())) {
        if (findState.isOpen) {
            webViewManager.clearFindMatches(activeTab.id)
            viewModel.closeFindInPage()
        } else if (!webViewManager.goBack(activeTab.id)) {
            viewModel.navigateToHome(activeTab.id)
        }
    }

    val currentHost = remember(activeTab.url) {
        try {
            activeTab.url.toUri().host.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    val matchingCredentials = remember(activeTab.url, vaultCredentials) {
        if (activeTab.url.startsWith("http")) viewModel.matchingCredentialsForUrl(activeTab.url)
        else emptyList()
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // --- TOP MINIMALIST OMNIBOX BAR (HIDDEN IN PIP MODE) ---
            if (!isInPipMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Omnibox Pill
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .border(
                                        width = if (isEditingAddress) 1.5.dp else 1.dp,
                                        color = if (isEditingAddress) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                                        modifier = Modifier.size(34.dp).testTag("omnibox_shield_button")
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                activeTab.isShowingOfflineSnapshot -> Icons.Default.OfflinePin
                                                activeTab.isPrivate -> Icons.Default.VisibilityOff
                                                activeTab.url.isBlank() -> Icons.Default.Shield
                                                activeTab.isSecureHttps -> Icons.Default.Lock
                                                else -> Icons.Default.Warning
                                            },
                                            contentDescription = stringResource(R.string.shield_status_cd),
                                            tint = when {
                                                activeTab.isShowingOfflineSnapshot -> ShieldEmerald
                                                activeTab.isPrivate -> MaterialTheme.colorScheme.primary
                                                activeTab.url.isBlank() -> MaterialTheme.colorScheme.primary
                                                activeTab.isSecureHttps -> ShieldEmerald
                                                else -> WarningAmber
                                            },
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Box(
                                        modifier = Modifier.weight(1f),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (addressText.isEmpty() && !isEditingAddress) {
                                            Text(
                                                text = if (activeTab.isPrivate) "Private search or URL"
                                                else stringResource(R.string.search_or_url_hint),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        BasicTextField(
                                            value = if (isEditingAddress) addressText else formatDisplayUrl(addressText),
                                            onValueChange = { addressText = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                fontFamily = JetBrainsMonoFontFamily,
                                                fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                            keyboardOptions = KeyboardOptions(
                                                keyboardType = KeyboardType.Uri,
                                                imeAction = ImeAction.Go
                                            ),
                                            keyboardActions = KeyboardActions(
                                                onGo = {
                                                    val submitted = addressText
                                                    viewModel.handleOmniboxInput(
                                                        rawInput = submitted,
                                                        onSecretVaultTriggered = {
                                                            addressText = activeTab.url
                                                            focusManager.clearFocus()
                                                            isEditingAddress = false
                                                        },
                                                        onProceedToWebNavigation = {
                                                            navigateCurrentTab(submitted)
                                                        }
                                                    )
                                                }
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .focusRequester(urlFocusRequester)
                                                .onFocusChanged { focusStateField ->
                                                    isEditingAddress = focusStateField.isFocused
                                                    if (!focusStateField.isFocused && addressText.isBlank()) {
                                                        addressText = activeTab.url
                                                    }
                                                }
                                                .testTag("omnibox_input")
                                        )
                                    }

                                    if (isEditingAddress && addressText.isNotEmpty()) {
                                        IconButton(
                                            onClick = { addressText = "" },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Clear URL",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else {
                                        IconButton(
                                            onClick = { launchVoiceSearch() },
                                            modifier = Modifier.size(34.dp).testTag("omnibox_voice_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Mic,
                                                contentDescription = stringResource(R.string.voice_search_cd),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    if (activeTab.url.isNotBlank() && !isEditingAddress) {
                                        IconButton(
                                            onClick = {
                                                if (activeTab.isLoading) webViewManager.stopLoading(activeTab.id)
                                                else webViewManager.reload(activeTab.id)
                                            },
                                            modifier = Modifier.size(34.dp).testTag("omnibox_reload_button")
                                        ) {
                                            Icon(
                                                imageVector = if (activeTab.isLoading) Icons.Default.Close else Icons.Default.Refresh,
                                                contentDescription = if (activeTab.isLoading) stringResource(R.string.stop_button_cd)
                                                else stringResource(R.string.reload_button_cd),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Live Focus Countdown Pill
                            if (focusState.isActive) {
                                val fm = focusState.remainingSeconds / 60
                                val fs = focusState.remainingSeconds % 60
                                Surface(
                                    color = ShieldEmerald.copy(alpha = 0.16f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) }
                                        .testTag("focus_timer_pill")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Timer,
                                            contentDescription = "Focus timer active",
                                            tint = ShieldEmerald,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "%02d:%02d".format(fm, fs),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontFamily = JetBrainsMonoFontFamily,
                                            color = ShieldEmerald
                                        )
                                    }
                                }
                            }

                            // Compact Blocked Ads Pill -> opens Live Network Inspector
                            if (activeTab.url.isNotBlank() && settings.adBlockEnabled) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(36.dp)
                                        .clickable { viewModel.setOverlay(ActiveOverlay.NETWORK_INSPECTOR) }
                                        .testTag("blocked_count_pill")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Shield,
                                            contentDescription = "Open network inspector",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "${activeTab.blockedCount}",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }

                            // Direct 1-Tap Downloads Manager Button (shows live spinner when downloading or file count badge)
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                IconButton(
                                    onClick = {
                                        hasPublicStorageAccess = viewModel.hasPublicStorageAccess()
                                        if (activeTab.url.startsWith("http")) {
                                            webViewManager.scanPageForVideos(activeTab.id)
                                            webViewManager.scanPageForImages(activeTab.id)
                                        }
                                        viewModel.openDownloadsManager(initialTab = 0)
                                    },
                                    modifier = Modifier.size(48.dp).testTag("top_downloads_button")
                                ) {
                                    if (activeDownload != null) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Download,
                                            contentDescription = stringResource(R.string.downloads_button_cd),
                                            tint = if (downloadedMedia.isNotEmpty()) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                if (activeDownload == null && downloadedMedia.isNotEmpty()) {
                                    Surface(
                                        color = ShieldEmerald,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 6.dp, end = 4.dp)
                                    ) {
                                        Text(
                                            text = downloadedMedia.size.coerceAtMost(99).toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // Inline Find-in-Page Bar
                        androidx.compose.animation.AnimatedVisibility(visible = findState.isOpen) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                BasicTextField(
                                    value = findState.query,
                                    onValueChange = { q ->
                                        viewModel.updateFindQuery(q)
                                        webViewManager.findInPage(activeTab.id, q) { active, total ->
                                            viewModel.updateFindResults(active, total)
                                        }
                                    },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    decorationBox = { inner ->
                                        Box(modifier = Modifier.fillMaxWidth()) {
                                            if (findState.query.isEmpty()) {
                                                Text(
                                                    text = "Find on page...",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).testTag("find_in_page_input")
                                )
                                Text(
                                    text = "${findState.activeMatch}/${findState.totalMatches}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = JetBrainsMonoFontFamily,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(
                                    onClick = { webViewManager.findNext(activeTab.id, forward = false) },
                                    modifier = Modifier.size(36.dp).testTag("find_prev_button")
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = { webViewManager.findNext(activeTab.id, forward = true) },
                                    modifier = Modifier.size(36.dp).testTag("find_next_button")
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match", modifier = Modifier.size(20.dp))
                                }
                                IconButton(
                                    onClick = {
                                        webViewManager.clearFindMatches(activeTab.id)
                                        viewModel.closeFindInPage()
                                    },
                                    modifier = Modifier.size(36.dp).testTag("find_close_button")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Close find bar", modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        if (activeTab.isLoading && activeTab.progress in 1..99) {
                            LinearProgressIndicator(
                                progress = { activeTab.progress / 100f },
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(2.5.dp)
                            )
                        }

                        // --- NATIVE-LOOKING ALL-IN-ONE APPS HUB BAR (Instagram, WhatsApp, Spotify, Netflix, Prime) ---
                        val activeWebApp = remember(activeTab.url) { WebAppService.matchForUrl(activeTab.url) }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WebAppService.entries.forEach { appService ->
                                val isSelected = activeWebApp == appService
                                val dotColor = remember(appService.accentHex) {
                                    Color(appService.accentHex.toColorInt())
                                }
                                Surface(
                                    color = if (isSelected) dotColor.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .border(
                                            width = if (isSelected) 1.2.dp else 0.75.dp,
                                            color = if (isSelected) dotColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        .clickable {
                                            viewModel.launchWebApp(
                                                service = appService,
                                                preferNativeForHybrid = true,
                                                onOpenUrlInTab = { url -> navigateCurrentTab(url) }
                                            )
                                        }
                                        .testTag("webapp_tab_${appService.id}")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .background(dotColor, CircleShape)
                                        )
                                        Text(
                                            text = appService.shortLabel,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) dotColor else MaterialTheme.colorScheme.onSurface
                                        )
                                        if (appService.mode == WebAppIntegrationMode.HYBRID_NATIVE) {
                                            Icon(
                                                imageVector = Icons.Default.OpenInNew,
                                                contentDescription = "Hybrid HD app",
                                                tint = dotColor.copy(alpha = 0.85f),
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Background Audio Foreground Service Toggle Pill (ideal for Spotify Web)
                            if (activeWebApp == WebAppService.SPOTIFY || backgroundAudioActive) {
                                Surface(
                                    color = if (backgroundAudioActive) ShieldEmerald.copy(alpha = 0.22f)
                                    else MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .clickable {
                                            viewModel.toggleBackgroundAudio(
                                                title = activeTab.title.ifBlank { "Spotify Web Audio" },
                                                subtitle = "Velo Background Playback"
                                            )
                                        }
                                        .testTag("bg_audio_toggle_pill")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Headphones,
                                            contentDescription = "Toggle background audio",
                                            tint = if (backgroundAudioActive) ShieldEmerald else MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (backgroundAudioActive) "BG Audio ON" else "BG Audio",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (backgroundAudioActive) ShieldEmerald else MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // --- OFFLINE CONNECTIVITY BANNER ---
                androidx.compose.animation.AnimatedVisibility(visible = !isOnline) {
                    Surface(
                        color = WarningAmber.copy(alpha = 0.16f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CloudOff,
                                    contentDescription = null,
                                    tint = WarningAmber,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.offline_mode_banner),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TextButton(onClick = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) }) {
                                Text("Open Cache")
                            }
                        }
                    }
                }

                // --- HTTPS-ONLY INTERCEPT WARNING BANNER ---
                androidx.compose.animation.AnimatedVisibility(visible = activeTab.insecureUpgradeBlockedUrl != null) {
                    val blockedUrl = activeTab.insecureUpgradeBlockedUrl.orEmpty()
                    Surface(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Unencrypted HTTP Blocked",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = blockedUrl,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row {
                                TextButton(onClick = {
                                    val upgraded = blockedUrl.replaceFirst("http://", "https://", ignoreCase = true)
                                    viewModel.clearInsecureWarning(activeTab.id)
                                    navigateCurrentTab(upgraded)
                                }) {
                                    Text("HTTPS")
                                }
                                TextButton(onClick = {
                                    viewModel.clearInsecureWarning(activeTab.id)
                                    navigateCurrentTab(blockedUrl, bypassHttps = true)
                                }) {
                                    Text("Allow Once")
                                }
                            }
                        }
                    }
                }

                // --- MATCHING VAULT AUTOFILL BAR ---
                androidx.compose.animation.AnimatedVisibility(visible = matchingCredentials.isNotEmpty() && !isEditingAddress) {
                    val match = matchingCredentials.firstOrNull()
                    if (match != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        Icons.Default.Key,
                                        contentDescription = stringResource(R.string.vault_button_cd),
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "Vault match: ${match.username}",
                                        style = MaterialTheme.typography.labelMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                TextButton(
                                    onClick = {
                                        webViewManager.autofillCredentials(activeTab.id, match.username, match.password)
                                        viewModel.showTransientBanner("Autofilled ${match.siteName} credentials.")
                                    },
                                    modifier = Modifier.testTag("quick_autofill_button")
                                ) {
                                    Text("Fill Login")
                                }
                            }
                        }
                    }
                }
            }

            // --- MAIN CONTENT VIEWPORT (SINGLE OR SPLIT-SCREEN DUAL TAB VIEW) ---
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val secTab = secondaryTab
                val isWide = maxWidth >= 600.dp
                val containerHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
                val containerWidthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }

                if (secTab == null || isInPipMode) {
                    BrowserTabPane(
                        tab = activeTab,
                        isSplitMode = false,
                        isFocusedPane = true,
                        paneLabel = "",
                        isOnline = isOnline,
                        isCloudSynced = isVaultUnlocked,
                        settings = settings,
                        shortcuts = shortcuts,
                        savedOfflineCount = readingList.count { it.isCachedLocally },
                        vaultCount = vaultCredentials.size,
                        focusState = focusState,
                        webViewManager = webViewManager,
                        onPaneFocus = {},
                        onFocusSearch = {
                            isEditingAddress = true
                            urlFocusRequester.requestFocus()
                        },
                        onVoiceSearch = { launchVoiceSearch() },
                        onOpenUrl = { url -> navigateTab(activeTab, url) },
                        onAddShortcut = { t, u, a -> viewModel.addShortcut(t, u, a) },
                        onDeleteShortcut = { viewModel.deleteShortcut(it) },
                        onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                        onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                        onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                        onOpenPowerTools = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                        onOpenSettings = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                        onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                        onNavigateHome = { viewModel.navigateToHome(activeTab.id) },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (isWide) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        BrowserTabPane(
                            tab = primaryTab,
                            isSplitMode = true,
                            isFocusedPane = activePane == SplitPane.PRIMARY,
                            paneLabel = "PANE 1",
                            isOnline = isOnline,
                            isCloudSynced = isVaultUnlocked,
                            settings = settings,
                            shortcuts = shortcuts,
                            savedOfflineCount = readingList.count { it.isCachedLocally },
                            vaultCount = vaultCredentials.size,
                            focusState = focusState,
                            webViewManager = webViewManager,
                            onPaneFocus = { viewModel.setActivePane(SplitPane.PRIMARY) },
                            onFocusSearch = {
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                isEditingAddress = true
                                urlFocusRequester.requestFocus()
                            },
                            onVoiceSearch = {
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                launchVoiceSearch()
                            },
                            onOpenUrl = { url ->
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                navigateTab(primaryTab, url)
                            },
                            onAddShortcut = { t, u, a -> viewModel.addShortcut(t, u, a) },
                            onDeleteShortcut = { viewModel.deleteShortcut(it) },
                            onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                            onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                            onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                            onOpenPowerTools = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                            onOpenSettings = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                            onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                            onNavigateHome = { viewModel.navigateToHome(primaryTab.id) },
                            modifier = Modifier.weight(splitRatio).fillMaxHeight()
                        )

                        // Vertical Draggable Divider (Landscape / Tablet)
                        Column(
                            modifier = Modifier
                                .width(22.dp)
                                .fillMaxHeight()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        viewModel.updateSplitRatio(dragAmount.x / containerWidthPx)
                                    }
                                },
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            IconButton(onClick = { viewModel.swapSplitPanes() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Swap panes", modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            IconButton(onClick = { viewModel.toggleSplitView() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Exit split screen", modifier = Modifier.size(16.dp))
                            }
                        }

                        BrowserTabPane(
                            tab = secTab,
                            isSplitMode = true,
                            isFocusedPane = activePane == SplitPane.SECONDARY,
                            paneLabel = "PANE 2",
                            isOnline = isOnline,
                            isCloudSynced = isVaultUnlocked,
                            settings = settings,
                            shortcuts = shortcuts,
                            savedOfflineCount = readingList.count { it.isCachedLocally },
                            vaultCount = vaultCredentials.size,
                            focusState = focusState,
                            webViewManager = webViewManager,
                            onPaneFocus = { viewModel.setActivePane(SplitPane.SECONDARY) },
                            onFocusSearch = {
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                isEditingAddress = true
                                urlFocusRequester.requestFocus()
                            },
                            onVoiceSearch = {
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                launchVoiceSearch()
                            },
                            onOpenUrl = { url ->
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                navigateTab(secTab, url)
                            },
                            onAddShortcut = { t, u, a -> viewModel.addShortcut(t, u, a) },
                            onDeleteShortcut = { viewModel.deleteShortcut(it) },
                            onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                            onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                            onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                            onOpenPowerTools = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                            onOpenSettings = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                            onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                            onNavigateHome = { viewModel.navigateToHome(secTab.id) },
                            modifier = Modifier.weight(1f - splitRatio).fillMaxHeight()
                        )
                    }
                } else {
                    // Portrait Vertical Split-Screen
                    Column(modifier = Modifier.fillMaxSize()) {
                        BrowserTabPane(
                            tab = primaryTab,
                            isSplitMode = true,
                            isFocusedPane = activePane == SplitPane.PRIMARY,
                            paneLabel = "TOP PANE",
                            isOnline = isOnline,
                            isCloudSynced = isVaultUnlocked,
                            settings = settings,
                            shortcuts = shortcuts,
                            savedOfflineCount = readingList.count { it.isCachedLocally },
                            vaultCount = vaultCredentials.size,
                            focusState = focusState,
                            webViewManager = webViewManager,
                            onPaneFocus = { viewModel.setActivePane(SplitPane.PRIMARY) },
                            onFocusSearch = {
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                isEditingAddress = true
                                urlFocusRequester.requestFocus()
                            },
                            onVoiceSearch = {
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                launchVoiceSearch()
                            },
                            onOpenUrl = { url ->
                                viewModel.setActivePane(SplitPane.PRIMARY)
                                navigateTab(primaryTab, url)
                            },
                            onAddShortcut = { t, u, a -> viewModel.addShortcut(t, u, a) },
                            onDeleteShortcut = { viewModel.deleteShortcut(it) },
                            onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                            onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                            onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                            onOpenPowerTools = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                            onOpenSettings = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                            onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                            onNavigateHome = { viewModel.navigateToHome(primaryTab.id) },
                            modifier = Modifier.weight(splitRatio).fillMaxWidth()
                        )

                        // Horizontal Draggable Split Divider Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        viewModel.updateSplitRatio(dragAmount.y / containerHeightPx)
                                    }
                                }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "SPLIT VIEW • DRAG TO RESIZE",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IconButton(
                                    onClick = { viewModel.swapSplitPanes() },
                                    modifier = Modifier.size(24.dp).testTag("split_swap_button")
                                ) {
                                    Icon(Icons.Default.SwapVert, contentDescription = "Swap panes", modifier = Modifier.size(16.dp))
                                }
                                IconButton(
                                    onClick = { viewModel.toggleSplitView() },
                                    modifier = Modifier.size(24.dp).testTag("split_close_button")
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Exit split screen", modifier = Modifier.size(16.dp))
                                }
                            }
                        }

                        BrowserTabPane(
                            tab = secTab,
                            isSplitMode = true,
                            isFocusedPane = activePane == SplitPane.SECONDARY,
                            paneLabel = "BOTTOM PANE",
                            isOnline = isOnline,
                            isCloudSynced = isVaultUnlocked,
                            settings = settings,
                            shortcuts = shortcuts,
                            savedOfflineCount = readingList.count { it.isCachedLocally },
                            vaultCount = vaultCredentials.size,
                            focusState = focusState,
                            webViewManager = webViewManager,
                            onPaneFocus = { viewModel.setActivePane(SplitPane.SECONDARY) },
                            onFocusSearch = {
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                isEditingAddress = true
                                urlFocusRequester.requestFocus()
                            },
                            onVoiceSearch = {
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                launchVoiceSearch()
                            },
                            onOpenUrl = { url ->
                                viewModel.setActivePane(SplitPane.SECONDARY)
                                navigateTab(secTab, url)
                            },
                            onAddShortcut = { t, u, a -> viewModel.addShortcut(t, u, a) },
                            onDeleteShortcut = { viewModel.deleteShortcut(it) },
                            onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                            onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                            onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                            onOpenPowerTools = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                            onOpenSettings = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                            onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                            onNavigateHome = { viewModel.navigateToHome(secTab.id) },
                            modifier = Modifier.weight(1f - splitRatio).fillMaxWidth()
                        )
                    }
                }

                // Floating "Download Media / Video" Pill Button available on ANY active webpage (no download link required!)
                if (!isInPipMode) {
                    val hasWebPage = activeTab.url.startsWith("http://", ignoreCase = true) ||
                        activeTab.url.startsWith("https://", ignoreCase = true)
                    androidx.compose.animation.AnimatedVisibility(
                        visible = hasWebPage || detectedVideos.isNotEmpty() || activeDownload != null,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = if (statusBanner != null) 62.dp else 16.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(24.dp),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .height(48.dp)
                                .clickable {
                                    hasPublicStorageAccess = viewModel.hasPublicStorageAccess()
                                    if (activeTab.url.startsWith("http")) {
                                        webViewManager.scanPageForVideos(activeTab.id)
                                        webViewManager.scanPageForImages(activeTab.id)
                                    }
                                    val targetTab = when {
                                        activeDownload != null -> 0
                                        detectedVideos.isNotEmpty() -> 1
                                        detectedImages.isNotEmpty() -> 2
                                        else -> 0
                                    }
                                    viewModel.openDownloadsManager(initialTab = targetTab)
                                }
                                .testTag("floating_download_video_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (activeDownload != null) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    val pct = ((activeDownload?.fraction ?: -1f) * 100f).toInt()
                                    Text(
                                        text = if (pct >= 0) "Downloading $pct%" else "Downloading...",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download videos and images from page",
                                        modifier = Modifier.size(18.dp)
                                    )
                                    val totalMedia = detectedVideos.size + detectedImages.size
                                    Text(
                                        text = when {
                                            detectedVideos.isNotEmpty() -> "Download Video (${detectedVideos.size})"
                                            totalMedia > 0 -> "Save Media ($totalMedia)"
                                            else -> "Save Media"
                                        },
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                // Transient Toast / Status Pill
                if (!isInPipMode) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = statusBanner != null,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(14.dp),
                            tonalElevation = 6.dp,
                            modifier = Modifier.border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                RoundedCornerShape(14.dp)
                            )
                        ) {
                            Text(
                                text = statusBanner.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                            )
                        }
                    }
                }
            }

            // --- MINIMALIST BOTTOM POWER-USER TOOLBAR (HIDDEN IN PIP MODE) ---
            if (!isInPipMode) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(
                            onClick = {
                                if (!webViewManager.goBack(activeTab.id) && activeTab.url.isNotBlank()) {
                                    viewModel.navigateToHome(activeTab.id)
                                }
                            },
                            enabled = activeTab.canGoBack || activeTab.url.isNotBlank(),
                            modifier = Modifier.size(48.dp).testTag("nav_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back_button_cd)
                            )
                        }

                        IconButton(
                            onClick = { webViewManager.goForward(activeTab.id) },
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.size(48.dp).testTag("nav_forward_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = stringResource(R.string.forward_button_cd)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.navigateToHome(activeTab.id) },
                            modifier = Modifier.size(48.dp).testTag("nav_home_button")
                        ) {
                            Icon(
                                Icons.Default.Home,
                                contentDescription = stringResource(R.string.home_button_cd)
                            )
                        }

                        // Save Offline / Open Offline Reading
                        IconButton(
                            onClick = {
                                if (activeTab.url.startsWith("http") && !activeTab.isShowingOfflineSnapshot) {
                                    viewModel.saveCurrentPageForOffline(activeTab.url, activeTab.title)
                                } else {
                                    viewModel.setOverlay(ActiveOverlay.OFFLINE_READING)
                                }
                            },
                            enabled = !isSavingOffline,
                            modifier = Modifier.size(48.dp).testTag("nav_offline_button")
                        ) {
                            if (isSavingOffline) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    imageVector = if (activeTab.url.startsWith("http")) Icons.Default.CloudDownload
                                    else Icons.Default.OfflinePin,
                                    contentDescription = stringResource(R.string.save_offline_cd),
                                    tint = if (activeTab.url.startsWith("http")) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Tab Count Switcher Pill
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clickable { viewModel.setOverlay(ActiveOverlay.TAB_SWITCHER) }
                                .testTag("nav_tabs_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .border(
                                        width = 1.8.dp,
                                        color = if (activeTab.isPrivate) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface,
                                        shape = RoundedCornerShape(7.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = tabs.size.coerceAtMost(99).toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (activeTab.isPrivate) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Power Tools Hub (Split-Screen, Reader+TTS, Clipper, Inspector, Focus, PiP, Find)
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = { viewModel.setOverlay(ActiveOverlay.POWER_TOOLS) },
                                modifier = Modifier.size(48.dp).testTag("nav_power_tools_button")
                            ) {
                                Icon(
                                    Icons.Default.GridView,
                                    contentDescription = stringResource(R.string.power_tools_cd),
                                    tint = if (secondaryTab != null || focusState.isActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (secondaryTab != null || focusState.isActive) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 10.dp, end = 10.dp)
                                        .size(7.dp)
                                        .background(ShieldEmerald, CircleShape)
                                )
                            }
                        }

                        // Shield & Settings
                        IconButton(
                            onClick = { viewModel.setOverlay(ActiveOverlay.SETTINGS_SHIELD) },
                            modifier = Modifier.size(48.dp).testTag("nav_settings_button")
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = stringResource(R.string.menu_button_cd)
                            )
                        }
                    }
                }
            }
        }

        // --- FULL-SCREEN POWER-USER OVERLAYS ---
        if (!isInPipMode) {
            when (activeOverlay) {
                ActiveOverlay.NONE -> Unit

                ActiveOverlay.TAB_SWITCHER -> TabSwitcherOverlay(
                    tabs = tabs,
                    activeTabId = activeTab.id,
                    onSelectTab = { viewModel.selectTab(it) },
                    onCloseTab = { tabId ->
                        viewModel.closeTab(tabId) { id, wasPrivate ->
                            webViewManager.destroyTab(id, wasPrivate)
                        }
                    },
                    onNewTab = { isPrivate -> viewModel.createNewTab(isPrivate = isPrivate) },
                    onWipeAllPrivateTabs = {
                        viewModel.wipeAllPrivateTabs { ids -> webViewManager.wipePrivateSessionData(ids) }
                    },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.POWER_TOOLS -> PowerToolsSheet(
                    hasActiveWebPage = activeTab.url.startsWith("http"),
                    isSplitActive = secondaryTab != null,
                    focusState = focusState,
                    networkTotalCount = networkLogs.size,
                    networkBlockedCount = networkLogs.count { it.wasBlocked },
                    clipsCount = scratchpadClips.size,
                    offlineCount = readingList.count { it.isCachedLocally },
                    detectedVideosCount = detectedVideos.size,
                    downloadedVideosCount = downloadedMedia.size,
                    savedPdfsCount = savedPdfs.size,
                    isSecretVaultConfigured = secretVaultConfig != null,
                    onToggleSplitView = { viewModel.toggleSplitView() },
                    onOpenReaderMode = {
                        webViewManager.extractReadableArticle(
                            tabId = activeTab.id,
                            fallbackUrl = activeTab.url,
                            fallbackTitle = activeTab.title
                        ) { article ->
                            viewModel.setReaderArticle(article)
                        }
                    },
                    onOpenScratchpad = { viewModel.setOverlay(ActiveOverlay.SCRATCHPAD) },
                    onOpenNetworkInspector = { viewModel.setOverlay(ActiveOverlay.NETWORK_INSPECTOR) },
                    onOpenFocusTimer = { viewModel.setOverlay(ActiveOverlay.FOCUS_TIMER) },
                    onEnterPipMode = onEnterPipMode,
                    onOpenFindInPage = { viewModel.openFindInPage() },
                    onOpenOfflineReading = { viewModel.setOverlay(ActiveOverlay.OFFLINE_READING) },
                    onOpenVault = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                    onOpenNewPrivateTab = { viewModel.createNewTab(isPrivate = true) },
                    onOpenVideoDownloader = {
                        hasPublicStorageAccess = viewModel.hasPublicStorageAccess()
                        if (activeTab.url.startsWith("http")) {
                            webViewManager.scanPageForVideos(activeTab.id)
                            webViewManager.scanPageForImages(activeTab.id)
                        }
                        viewModel.openDownloadsManager(initialTab = 0)
                    },
                    onOpenVideoPlayer = {
                        val firstDetected = detectedVideos.firstOrNull()
                        if (firstDetected != null) {
                            viewModel.openVideoPlayer(
                                VideoPlayerRequest(
                                    title = firstDetected.title,
                                    primarySource = firstDetected.url,
                                    isLocalFile = false,
                                    qualityVariants = detectedVideos.map { "${it.formatLabel} • ${it.qualityLabel}" to it.url }
                                )
                            )
                        } else {
                            viewModel.setOverlay(ActiveOverlay.VIDEO_PLAYER)
                        }
                    },
                    onOpenPdfTools = {
                        viewModel.refreshSavedPdfs()
                        viewModel.setOverlay(ActiveOverlay.PDF_TOOLS)
                    },
                    onOpenSpeedTest = { viewModel.setOverlay(ActiveOverlay.SPEED_TEST) },
                    onOpenSecretVaultSetup = {
                        viewModel.clearSecretVaultError()
                        viewModel.setOverlay(ActiveOverlay.SECRET_VAULT_SETUP)
                    },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.READER_MODE -> {
                    val article = readerArticle
                    if (article != null) {
                        ReaderModeSheet(
                            article = article,
                            onDismiss = { viewModel.clearReaderArticle() }
                        )
                    } else {
                        viewModel.setOverlay(ActiveOverlay.NONE)
                    }
                }

                ActiveOverlay.SCRATCHPAD -> ScratchpadSheet(
                    clips = scratchpadClips,
                    activePageTitle = activeTab.title,
                    activePageUrl = activeTab.url,
                    onRequestClipCurrentPage = { onCaptured ->
                        webViewManager.extractClipSnippet(activeTab.id, onCaptured)
                    },
                    onSaveClip = { id, title, url, content, tag ->
                        viewModel.saveScratchpadClip(id, title, url, content, tag)
                    },
                    onDeleteClip = { viewModel.deleteScratchpadClip(it) },
                    onOpenUrl = { url ->
                        viewModel.setOverlay(ActiveOverlay.NONE)
                        navigateCurrentTab(url)
                    },
                    onCopiedNotice = { viewModel.showTransientBanner(it) },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.NETWORK_INSPECTOR -> NetworkInspectorSheet(
                    logs = networkLogs,
                    customBlockedHosts = customBlockedHosts,
                    onToggleCustomBlockHost = { host ->
                        viewModel.toggleCustomBlockedHost(host)
                    },
                    onClearLogs = { viewModel.clearNetworkLogs() },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.FOCUS_TIMER -> FocusTimerSheet(
                    focusState = focusState,
                    blocklist = focusBlocklist,
                    onStartSession = { viewModel.startFocusSession(it) },
                    onTogglePause = { viewModel.togglePauseFocus() },
                    onStopSession = { viewModel.stopFocusSession() },
                    onAddDomain = { viewModel.addFocusDomain(it) },
                    onRemoveDomain = { viewModel.removeFocusDomain(it) },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.CLOUD_SIGN_IN -> AuthScreen(
                    onAuthSuccess = {
                        viewModel.setOverlay(ActiveOverlay.NONE)
                        viewModel.showTransientBanner("Signed in! Cloud sync enabled.")
                    },
                    onClose = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.PASSWORD_VAULT -> {
                    when (val gate = vaultGateState) {
                        is VaultGateState.SignedOut -> AuthScreen(
                            onAuthSuccess = {
                                // Auth state update recreates VM as signed-in; close sign-in sheet smoothly
                            },
                            onClose = { viewModel.setOverlay(ActiveOverlay.NONE) }
                        )

                        is VaultGateState.Loading -> {
                            BackHandler { viewModel.setOverlay(ActiveOverlay.NONE) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Text("Syncing encrypted vault...", style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { viewModel.setOverlay(ActiveOverlay.NONE) }) {
                                        Text("Back to Browser")
                                    }
                                }
                            }
                        }

                        is VaultGateState.NeedsSetup -> VaultUnlockScreen(
                            isSetupMode = true,
                            vaultConfig = null,
                            userEmail = userEmail,
                            isBusy = vaultBusy,
                            errorMessage = vaultError,
                            onCreateVault = { p1, p2 -> viewModel.setupNewVault(p1, p2) },
                            onUnlockVault = { _, _ -> },
                            onClearError = { viewModel.clearVaultError() },
                            onSignOut = {
                                viewModel.setOverlay(ActiveOverlay.NONE)
                                onSignOut()
                            },
                            onClose = { viewModel.setOverlay(ActiveOverlay.NONE) }
                        )

                        is VaultGateState.Locked -> VaultUnlockScreen(
                            isSetupMode = false,
                            vaultConfig = gate.config,
                            userEmail = userEmail,
                            isBusy = vaultBusy,
                            errorMessage = vaultError,
                            onCreateVault = { _, _ -> },
                            onUnlockVault = { passphrase, cfg -> viewModel.unlockExistingVault(passphrase, cfg) },
                            onClearError = { viewModel.clearVaultError() },
                            onSignOut = {
                                viewModel.setOverlay(ActiveOverlay.NONE)
                                onSignOut()
                            },
                            onClose = { viewModel.setOverlay(ActiveOverlay.NONE) }
                        )

                        is VaultGateState.Error -> {
                            BackHandler { viewModel.setOverlay(ActiveOverlay.NONE) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Vault Sync Issue", style = MaterialTheme.typography.headlineSmall)
                                    Text(gate.message, style = MaterialTheme.typography.bodyMedium)
                                    Button(onClick = { viewModel.setOverlay(ActiveOverlay.NONE) }) {
                                        Text("Return to Browser")
                                    }
                                }
                            }
                        }

                        is VaultGateState.Unlocked -> PasswordVaultSheet(
                            credentials = vaultCredentials,
                            keyFingerprint = gate.keyFingerprint,
                            activePageUrl = activeTab.url,
                            onSaveCredential = { id, siteName, siteUrl, username, password, notes ->
                                viewModel.saveVaultCredential(id, siteName, siteUrl, username, password, notes)
                            },
                            onDeleteCredential = { viewModel.deleteVaultCredential(it) },
                            onAutofillCredential = { cred ->
                                webViewManager.autofillCredentials(activeTab.id, cred.username, cred.password)
                                viewModel.setOverlay(ActiveOverlay.NONE)
                                viewModel.showTransientBanner("Autofilled ${cred.siteName} into current page.")
                            },
                            onCopiedNotice = { viewModel.showTransientBanner(it) },
                            onLockVault = { viewModel.lockVault() },
                            onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                        )
                    }
                }

                ActiveOverlay.OFFLINE_READING -> OfflineReadingSheet(
                    items = readingList,
                    isOnline = isOnline,
                    isSavingOffline = isSavingOffline,
                    activePageUrl = activeTab.url,
                    activePageTitle = activeTab.title,
                    readerState = offlineReaderState,
                    onSaveCurrentPage = { viewModel.saveCurrentPageForOffline(activeTab.url, activeTab.title) },
                    onOpenOfflineReader = { viewModel.openOfflineReader(it) },
                    onCloseOfflineReader = { viewModel.closeOfflineReader() },
                    onLoadOfflineHtmlInBrowser = { url, title, html ->
                        val tab = viewModel.activeTab.value
                        viewModel.markTabOfflineSnapshot(tab.id, url, title)
                        webViewManager.loadOfflineHtml(tab, url, html)
                    },
                    onCacheSyncedItemLocally = { viewModel.cacheSyncedPageLocally(it) },
                    onToggleArchive = { viewModel.toggleReadingArchived(it) },
                    onDeleteItem = { viewModel.deleteReadingPage(it) },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.SETTINGS_SHIELD -> SettingsAndShieldSheet(
                    settings = settings,
                    isSignedIn = isSignedIn,
                    isVaultUnlocked = isVaultUnlocked,
                    userEmail = userEmail,
                    keyFingerprint = unlockedFingerprint,
                    activePageHost = currentHost,
                    activeTabBlockedCount = activeTab.blockedCount,
                    isDesktopMode = activeTab.isDesktopMode,
                    allowlistedVersion = allowlistedVersion,
                    onSetThemeMode = { viewModel.setThemeMode(it) },
                    onSetAdBlockEnabled = { viewModel.setAdBlockEnabled(it) },
                    onSetHttpsOnlyEnabled = { viewModel.setHttpsOnlyEnabled(it) },
                    onSetDarkWebPagesEnabled = { viewModel.setDarkWebPagesEnabled(it) },
                    onSetNotificationsEnabled = { viewModel.setNotificationsEnabled(it) },
                    onSetSearchEngine = { viewModel.setSearchEngine(it) },
                    onToggleSiteAllowlist = { host ->
                        viewModel.toggleAllowlistForHost(host)
                        webViewManager.reload(activeTab.id)
                    },
                    onToggleDesktopMode = {
                        val nowDesktop = viewModel.toggleDesktopMode(activeTab.id)
                        webViewManager.applyDesktopMode(activeTab.id, nowDesktop)
                    },
                    onSendTestNotification = { viewModel.sendTestSecurityNotification() },
                    onOpenSignIn = { viewModel.setOverlay(ActiveOverlay.CLOUD_SIGN_IN) },
                    onOpenVaultUnlock = { viewModel.setOverlay(ActiveOverlay.PASSWORD_VAULT) },
                    onLockVault = { viewModel.lockVault() },
                    onSignOut = {
                        viewModel.lockVault()
                        viewModel.setOverlay(ActiveOverlay.NONE)
                        onSignOut()
                    },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.VIDEO_DOWNLOADER -> VideoDownloaderSheet(
                    initialTab = downloaderInitialTab,
                    hasStorageAccess = hasPublicStorageAccess,
                    detectedVideos = detectedVideos,
                    detectedImages = detectedImages,
                    downloadedVideos = downloadedMedia,
                    activeDownload = activeDownload,
                    onRequestStoragePermission = { requestLegacyStorageIfNeeded() },
                    onOpenDownloadsFolder = { viewModel.openSystemDownloadsFolder() },
                    onRescanPage = {
                        webViewManager.scanPageForVideos(activeTab.id)
                        webViewManager.scanPageForImages(activeTab.id)
                        viewModel.showTransientBanner("Scanned webpage for videos & full-size images.")
                    },
                    onDownloadVideo = { url, title, quality ->
                        viewModel.startVideoDownload(url, title, quality)
                    },
                    onSystemDownload = { url, title ->
                        viewModel.enqueueSystemDownload(url, title)
                    },
                    onDownloadImageInFormat = { url, title, fmt ->
                        viewModel.downloadImageInFormat(url, title, fmt)
                    },
                    onCancelDownload = { viewModel.cancelVideoDownload() },
                    onPlayStream = { media ->
                        viewModel.openVideoPlayer(
                            request = VideoPlayerRequest(
                                title = media.title,
                                primarySource = media.url,
                                isLocalFile = false,
                                qualityVariants = detectedVideos.map { "${it.formatLabel} • ${it.qualityLabel}" to it.url }
                            ),
                            returnTo = ActiveOverlay.VIDEO_DOWNLOADER
                        )
                    },
                    onPlayDownloaded = { item ->
                        viewModel.openDownloadedItem(item)
                    },
                    onShareDownloaded = { item ->
                        viewModel.shareDownloadedItem(item)
                    },
                    onHideDownloadedInVault = { item ->
                        val lowerMime = item.mimeType.lowercase()
                        val fmt = item.formatLabel.uppercase()
                        val mediaType = when {
                            lowerMime == "application/pdf" || fmt == "PDF" -> SecretMediaType.PDF
                            lowerMime.startsWith("image/") || fmt in setOf("PNG", "JPG", "JPEG", "WEBP", "GIF", "IMAGE") -> SecretMediaType.IMAGE
                            lowerMime.startsWith("audio/") || fmt in setOf("MP3", "M4A", "WAV", "OGG", "FLAC", "AUDIO") -> SecretMediaType.AUDIO
                            else -> SecretMediaType.VIDEO
                        }
                        viewModel.hideLocalFileInSecretVault(
                            filePath = item.localPath,
                            displayName = item.title,
                            type = mediaType,
                            mimeType = item.mimeType,
                            onOriginalHidden = { viewModel.deleteDownloadedMedia(item) }
                        )
                    },
                    onDeleteDownloaded = { viewModel.deleteDownloadedMedia(it) },
                    onDismiss = { viewModel.setOverlay(ActiveOverlay.NONE) }
                )

                ActiveOverlay.VIDEO_PLAYER -> VideoPlayerSheet(
                    request = videoPlayerRequest,
                    onSelectLocalUri = { uri, isAudio ->
                        viewModel.openVideoPlayer(
                            VideoPlayerRequest(
                                title = if (isAudio) "Local Audio" else "Local Video",
                                primarySource = uri.toString(),
                                isLocalFile = true,
                                isAudioOnly = isAudio
                            )
                        )
                    },
                    onOpenStreamUrl = { url ->
                        viewModel.openVideoPlayer(
                            VideoPlayerRequest(
                                title = "Network Video Stream",
                                primarySource = url,
                                isLocalFile = false
                            )
                        )
                    },
                    onEnterPipMode = onEnterPipMode,
                    onDismiss = { viewModel.closeVideoPlayer() }
                )

                ActiveOverlay.PDF_TOOLS -> PdfToolsSheet(
                    pdfEngine = viewModel.pdfEngine,
                    savedPdfs = savedPdfs,
                    activePdf = activePdfDocument,
                    isBusy = isPdfBusy,
                    hasActiveWebPage = activeTab.url.startsWith("http"),
                    activePageTitle = activeTab.title,
                    onImportPdfUri = { viewModel.importPdfFromUri(it) },
                    onConvertImagesToPdf = { uris, title -> viewModel.convertImagesToPdf(uris, title) },
                    onConvertTextToPdf = { title, body -> viewModel.convertTextToPdf(title, body) },
                    onConvertWebPageToPdf = {
                        viewModel.convertActiveWebViewToPdf(
                            webView = webViewManager.getWebView(activeTab.id),
                            title = activeTab.title
                        )
                    },
                    onOpenPdf = { viewModel.openPdfDocument(it) },
                    onCloseActivePdf = { viewModel.closeActivePdfDocument() },
                    onExtractPdfToImages = { viewModel.extractPdfToPngImages(it) },
                    onExportPdfToUri = { doc, uri -> viewModel.exportPdfToUri(doc, uri) },
                    onHidePdfInVault = { doc ->
                        viewModel.hideLocalFileInSecretVault(
                            filePath = doc.filePath,
                            displayName = "${doc.title}.pdf",
                            type = SecretMediaType.PDF,
                            mimeType = "application/pdf",
                            onOriginalHidden = { viewModel.deletePdfDocument(doc) }
                        )
                    },
                    onDeletePdf = { viewModel.deletePdfDocument(it) },
                    onDismiss = {
                        viewModel.closeActivePdfDocument()
                        viewModel.setOverlay(ActiveOverlay.NONE)
                    }
                )

                ActiveOverlay.SPEED_TEST -> SpeedTestSheet(
                    liveState = speedTestState,
                    history = speedTestHistory,
                    onStartTest = { viewModel.startSpeedTest() },
                    onStopTest = { viewModel.stopSpeedTest() },
                    onClearHistory = { viewModel.clearSpeedTestHistory() },
                    onDismiss = {
                        viewModel.stopSpeedTest()
                        viewModel.setOverlay(ActiveOverlay.NONE)
                    }
                )

                ActiveOverlay.SECRET_VAULT_SETUP -> SecretVaultSetupSheet(
                    isAlreadyConfigured = secretVaultConfig != null,
                    isCurrentlyUnlocked = isSecretVaultUnlocked,
                    isBusy = secretVaultBusy,
                    errorMessage = secretVaultError,
                    onSaveCode = { newCode, confirm, current ->
                        viewModel.setupOrChangeSecretCode(newCode, confirm, current)
                    },
                    onClearError = { viewModel.clearSecretVaultError() },
                    onDismiss = {
                        viewModel.clearSecretVaultError()
                        viewModel.setOverlay(
                            if (isSecretVaultUnlocked) ActiveOverlay.SECRET_MEDIA_VAULT else ActiveOverlay.NONE
                        )
                    }
                )

                ActiveOverlay.SECRET_MEDIA_VAULT -> {
                    if (isSecretVaultUnlocked) {
                        SecretMediaVaultSheet(
                            items = secretMediaItems,
                            imagePreview = secretImagePreview,
                            isBusy = secretVaultBusy,
                            pendingDeleteUris = pendingDeleteOriginalUris,
                            onHideUris = { uris, type -> viewModel.hideUrisInSecretVault(uris, type) },
                            onOriginalDeleteFinished = { count -> viewModel.onOriginalDeletePromptFinished(count) },
                            onClearPendingDeleteUris = { viewModel.clearPendingDeleteOriginalUris() },
                            onOpenItem = { viewModel.openSecretMediaItem(it) },
                            onCloseImagePreview = { viewModel.closeSecretImagePreview() },
                            onExportItem = { item, uri -> viewModel.exportSecretMediaItem(item, uri) },
                            onDeleteItem = { viewModel.deleteSecretMediaItem(it) },
                            onOpenChangeCode = {
                                viewModel.clearSecretVaultError()
                                viewModel.setOverlay(ActiveOverlay.SECRET_VAULT_SETUP)
                            },
                            onLockAndClose = { viewModel.lockSecretMediaVault() }
                        )
                    } else {
                        viewModel.setOverlay(ActiveOverlay.NONE)
                    }
                }

                ActiveOverlay.IMAGE_VIEWER -> {
                    val imgReq = imageViewerRequest
                    if (imgReq != null) {
                        ImageViewerSheet(
                            request = imgReq,
                            onConvertToPdf = { uri, title ->
                                viewModel.setOverlay(ActiveOverlay.PDF_TOOLS)
                                viewModel.convertImagesToPdf(listOf(uri), title)
                            },
                            onHideInSecretVault = { req ->
                                viewModel.hideImageViewerInSecretVault(req)
                            },
                            onDismiss = { viewModel.closeImageViewer() }
                        )
                    } else {
                        viewModel.setOverlay(ActiveOverlay.NONE)
                    }
                }
            }
        }

        // Long-press webpage image download dialog with Full-Size Format Picker
        val pendingImg = longPressedImage
        if (pendingImg != null) {
            AlertDialog(
                onDismissRequest = { longPressedImage = null },
                title = { Text("Download Full-Size Image") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Choose output format to save full resolution to Downloads/Velo:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ImageDownloadFormat.entries.forEach { fmt ->
                                FilterChip(
                                    selected = longPressImageFormat == fmt,
                                    onClick = { longPressImageFormat = fmt },
                                    label = { Text(fmt.label) }
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val (imgUrl, referer) = pendingImg
                            val chosenFmt = longPressImageFormat
                            longPressedImage = null
                            viewModel.downloadImageInFormat(
                                imageUrl = imgUrl,
                                title = "",
                                format = chosenFmt,
                                referer = referer
                            )
                        },
                        modifier = Modifier.testTag("confirm_download_image_button")
                    ) {
                        Text("Save (${longPressImageFormat.label})")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { longPressedImage = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun BrowserTabPane(
    tab: BrowserTab,
    isSplitMode: Boolean,
    isFocusedPane: Boolean,
    paneLabel: String,
    isOnline: Boolean,
    isCloudSynced: Boolean,
    settings: com.example.browser.BrowserSettings,
    shortcuts: List<com.example.browser.ShortcutItem>,
    savedOfflineCount: Int,
    vaultCount: Int,
    focusState: FocusSessionState,
    webViewManager: WebViewTabManager,
    onPaneFocus: () -> Unit,
    onFocusSearch: () -> Unit,
    onVoiceSearch: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onAddShortcut: (String, String, String) -> Unit,
    onDeleteShortcut: (String) -> Unit,
    onOpenVault: () -> Unit,
    onOpenOfflineReading: () -> Unit,
    onOpenNewPrivateTab: () -> Unit,
    onOpenPowerTools: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenFocusTimer: () -> Unit,
    onNavigateHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderMod = if (isSplitMode) {
        modifier.border(
            width = if (isFocusedPane) 2.dp else 1.dp,
            color = if (isFocusedPane) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        )
    } else {
        modifier
    }

    Column(modifier = borderMod) {
        if (isSplitMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isFocusedPane) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable(onClick = onPaneFocus)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$paneLabel • ${tab.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocusedPane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isFocusedPane) {
                    Text(
                        text = "ACTIVE",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val blockedHost = tab.focusBlockedHost
            when {
                blockedHost != null -> {
                    val mins = focusState.remainingSeconds / 60
                    val secs = focusState.remainingSeconds % 60
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Timer,
                                contentDescription = null,
                                tint = ShieldEmerald,
                                modifier = Modifier.size(44.dp)
                            )
                            Text(
                                text = "Focus Shield Active",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "$blockedHost is paused during your deep-work sprint (%02d:%02d left).".format(mins, secs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = onNavigateHome, shape = RoundedCornerShape(12.dp)) {
                                    Text("Back to Work")
                                }
                                OutlinedButton(onClick = onOpenFocusTimer, shape = RoundedCornerShape(12.dp)) {
                                    Text("Focus Settings")
                                }
                            }
                        }
                    }
                }

                tab.url.isBlank() -> {
                    SpeedDialHomeView(
                        isPrivateMode = tab.isPrivate,
                        isOnline = isOnline,
                        isCloudSynced = isCloudSynced,
                        settings = settings,
                        shortcuts = shortcuts,
                        savedOfflineCount = savedOfflineCount,
                        vaultCount = vaultCount,
                        onFocusSearch = onFocusSearch,
                        onVoiceSearch = onVoiceSearch,
                        onOpenUrl = onOpenUrl,
                        onAddShortcut = onAddShortcut,
                        onDeleteShortcut = onDeleteShortcut,
                        onOpenVault = onOpenVault,
                        onOpenOfflineReading = onOpenOfflineReading,
                        onOpenNewPrivateTab = onOpenNewPrivateTab,
                        onOpenPowerTools = onOpenPowerTools,
                        onOpenSettings = onOpenSettings
                    )
                }

                else -> {
                    AndroidView(
                        factory = { ctx ->
                            FrameLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = { container ->
                            val targetWebView = webViewManager.getOrCreateWebView(tab)
                            if (container.childCount != 1 || container.getChildAt(0) !== targetWebView) {
                                container.removeAllViews()
                                (targetWebView.parent as? ViewGroup)?.removeView(targetWebView)
                                container.addView(targetWebView)
                            }
                        },
                        modifier = Modifier.fillMaxSize().testTag("active_webview_container_${tab.id}")
                    )
                }
            }
        }
    }
}

private fun formatDisplayUrl(url: String): String {
    if (url.isBlank()) return ""
    return url
        .removePrefix("https://")
        .removePrefix("http://")
        .removePrefix("www.")
        .trimEnd('/')
}
