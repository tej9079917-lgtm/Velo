package com.example

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.browser.OfflinePageArchiver
import com.example.crypto.SecretMediaVaultManager
import com.example.data.local.LocalPreferencesStore
import com.example.data.local.VeloDatabase
import com.example.data.remote.VeloSyncRepository
import com.example.data.remote.authStateFlow
import com.example.data.remote.signOutGoogle
import com.example.media.PdfEngine
import com.example.media.SpeedTestEngine
import com.example.media.VideoDownloadEngine
import com.example.system.NetworkConnectivityObserver
import com.example.system.VeloNotificationManager
import com.example.ui.BrowserMainScreen
import com.example.ui.theme.VeloBrowserTheme
import com.example.viewmodel.BrowserViewModel
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {

    private var pendingExternalUrl by mutableStateOf<String?>(null)
    private var isInPipMode by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        captureExternalUrl(intent)
        setContent {
            VeloBrowserApp(
                pendingExternalUrl = pendingExternalUrl,
                isInPipMode = isInPipMode,
                onConsumeExternalUrl = { pendingExternalUrl = null },
                onEnterPipMode = { enterBrowserPipMode() }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureExternalUrl(intent)
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipMode = isInPictureInPictureMode
    }

    private fun enterBrowserPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sourceRect = Rect()
                window.decorView.getGlobalVisibleRect(sourceRect)
                builder
                    .setSourceRectHint(sourceRect)
                    .setAutoEnterEnabled(true)
            }
            enterPictureInPictureMode(builder.build())
        }
    }

    private fun captureExternalUrl(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val dataUrl = intent.dataString
            if (!dataUrl.isNullOrBlank() && (dataUrl.startsWith("http://") || dataUrl.startsWith("https://"))) {
                pendingExternalUrl = dataUrl
            }
        }
    }
}

@Composable
private fun VeloBrowserApp(
    pendingExternalUrl: String?,
    isInPipMode: Boolean,
    onConsumeExternalUrl: () -> Unit,
    onEnterPipMode: () -> Unit
) {
    val context = LocalContext.current
    val hasFirebase = remember(context) { FirebaseApp.getApps(context).isNotEmpty() }
    val authFlow = remember(hasFirebase) {
        if (hasFirebase) Firebase.auth.authStateFlow() else flowOf<FirebaseUser?>(null)
    }
    val initialUser = remember(hasFirebase) {
        if (hasFirebase) Firebase.auth.currentUser else null
    }
    val currentUser by authFlow.collectAsStateWithLifecycle(initialValue = initialUser)
    val user = currentUser
    val sessionKey = user?.uid ?: "local_guest"

    // Launches directly into Velo Browser with ZERO startup login wall.
    // Cloud Sync & E2EE Password Vault remain optionally accessible from Settings or Vault.
    key(sessionKey) {
        BrowserWorkspaceRoot(
            sessionKey = sessionKey,
            currentUserId = user?.uid,
            userEmail = user?.email,
            isInPipMode = isInPipMode,
            pendingExternalUrl = pendingExternalUrl,
            onConsumeExternalUrl = onConsumeExternalUrl,
            onEnterPipMode = onEnterPipMode
        )
    }
}

@Composable
private fun BrowserWorkspaceRoot(
    sessionKey: String,
    currentUserId: String?,
    userEmail: String?,
    isInPipMode: Boolean,
    pendingExternalUrl: String?,
    onConsumeExternalUrl: () -> Unit,
    onEnterPipMode: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val viewModel: BrowserViewModel = viewModel(
        key = sessionKey,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val syncRepository = if (currentUserId != null && FirebaseApp.getApps(app).isNotEmpty()) {
                    val databaseId = app.getString(R.string.firestore_database_id)
                    VeloSyncRepository(FirebaseFirestore.getInstance(databaseId))
                } else {
                    null
                }
                val localDb = VeloDatabase.getInstance(app)
                BrowserViewModel(
                    appContext = app.applicationContext,
                    syncRepository = syncRepository,
                    cachedPageDao = localDb.cachedPageDao(),
                    localShortcutDao = localDb.localShortcutDao(),
                    scratchpadClipDao = localDb.scratchpadClipDao(),
                    downloadedMediaDao = localDb.downloadedMediaDao(),
                    secretMediaDao = localDb.secretMediaDao(),
                    speedTestHistoryDao = localDb.speedTestHistoryDao(),
                    localPrefs = LocalPreferencesStore(app),
                    offlineArchiver = OfflinePageArchiver(),
                    notificationManager = VeloNotificationManager(app),
                    connectivityObserver = NetworkConnectivityObserver(app),
                    videoDownloadEngine = VideoDownloadEngine(app),
                    pdfEngine = PdfEngine(app),
                    speedTestEngine = SpeedTestEngine(app),
                    secretVaultManager = SecretMediaVaultManager(app),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeTab by viewModel.activeTab.collectAsStateWithLifecycle()

    VeloBrowserTheme(
        themeMode = settings.themeMode,
        isPrivateMode = activeTab.isPrivate
    ) {
        BrowserMainScreen(
            viewModel = viewModel,
            userEmail = userEmail,
            isInPipMode = isInPipMode,
            initialExternalUrl = pendingExternalUrl,
            onConsumeExternalUrl = onConsumeExternalUrl,
            onEnterPipMode = onEnterPipMode,
            onSignOut = { signOutGoogle(context, credentialManager, scope) }
        )
    }
}
