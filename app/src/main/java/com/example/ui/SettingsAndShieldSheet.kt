package com.example.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Https
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.browser.AdBlockerEngine
import com.example.browser.BrowserSettings
import com.example.browser.SearchEngine
import com.example.browser.ThemeMode
import com.example.system.VeloNotificationManager
import com.example.ui.theme.ShieldEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsAndShieldSheet(
    settings: BrowserSettings,
    isSignedIn: Boolean,
    isVaultUnlocked: Boolean,
    userEmail: String?,
    keyFingerprint: String,
    activePageHost: String,
    activeTabBlockedCount: Int,
    isDesktopMode: Boolean,
    allowlistedVersion: Int,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetAdBlockEnabled: (Boolean) -> Unit,
    onSetHttpsOnlyEnabled: (Boolean) -> Unit,
    onSetDarkWebPagesEnabled: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetSearchEngine: (SearchEngine) -> Unit,
    onToggleSiteAllowlist: (String) -> Unit,
    onToggleDesktopMode: () -> Unit,
    onSendTestNotification: () -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenVaultUnlock: () -> Unit,
    onLockVault: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    val context = LocalContext.current
    val notifHelper = remember(context) { VeloNotificationManager(context) }
    var hasNotificationPermission by remember { mutableStateOf(notifHelper.hasNotificationPermission()) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted
        if (granted) {
            onSetNotificationsEnabled(true)
            onSendTestNotification()
        }
    }

    val isCurrentSiteAllowlisted = remember(activePageHost, allowlistedVersion) {
        activePageHost.isNotBlank() && AdBlockerEngine.isSiteAllowlisted(activePageHost)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Shield & Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = if (isSignedIn) "Synced across your signed-in devices" else "Private local mode • Optional cloud sync below",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_settings_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close settings")
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Privacy & Ad-Block Shield Section
            SettingsCard(title = "PRIVACY & AD SHIELD") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "${settings.trackersBlockedTotal}",
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Total ads & trackers blocked",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = "This tab: $activeTabBlockedCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ToggleRow(
                    icon = Icons.Default.Shield,
                    title = stringResource(R.string.ad_blocker_label),
                    subtitle = "Blocks ad networks, analytics beacons & cosmetic ad slots",
                    checked = settings.adBlockEnabled,
                    onCheckedChange = onSetAdBlockEnabled,
                    tag = "toggle_ad_blocker"
                )

                if (activePageHost.isNotBlank()) {
                    ToggleRow(
                        icon = Icons.Default.Shield,
                        title = "Shield on $activePageHost",
                        subtitle = if (isCurrentSiteAllowlisted) "Paused for this site" else "Active on this site",
                        checked = !isCurrentSiteAllowlisted,
                        onCheckedChange = { onToggleSiteAllowlist(activePageHost) },
                        tag = "toggle_site_shield"
                    )
                }

                ToggleRow(
                    icon = Icons.Default.Https,
                    title = stringResource(R.string.https_only_label),
                    subtitle = "Auto-upgrades links to HTTPS and warns before unencrypted HTTP",
                    checked = settings.httpsOnlyEnabled,
                    onCheckedChange = onSetHttpsOnlyEnabled,
                    tag = "toggle_https_only"
                )

                if (activePageHost.isNotBlank()) {
                    ToggleRow(
                        icon = Icons.Default.DesktopWindows,
                        title = "Desktop Site",
                        subtitle = "Request desktop viewport for current tab",
                        checked = isDesktopMode,
                        onCheckedChange = { onToggleDesktopMode() },
                        tag = "toggle_desktop_mode"
                    )
                }
            }

            // 2. Appearance & Late-Night Dark Mode
            SettingsCard(title = "DARK MODE & ACCESSIBILITY") {
                Text(
                    text = "Browser Theme",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.themeMode == mode,
                            onClick = { onSetThemeMode(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("theme_chip_${mode.name.lowercase()}")
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                ToggleRow(
                    icon = Icons.Default.DarkMode,
                    title = stringResource(R.string.dark_web_pages_label),
                    subtitle = "Applies an OLED-friendly dark reading filter to bright websites",
                    checked = settings.darkWebPagesEnabled,
                    onCheckedChange = onSetDarkWebPagesEnabled,
                    tag = "toggle_dark_web_pages"
                )
            }

            // 3. Search Engine
            SettingsCard(title = "PRIVACY SEARCH ENGINE") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Used for Omnibox & Voice Search",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchEngine.entries.forEach { engine ->
                        FilterChip(
                            selected = settings.searchEngine == engine,
                            onClick = { onSetSearchEngine(engine) },
                            label = { Text(engine.label) },
                            modifier = Modifier.testTag("search_engine_${engine.name.lowercase()}")
                        )
                    }
                }
            }

            // 4. Real-Time Push & Security Notifications
            SettingsCard(title = "REAL-TIME ALERTS & NOTIFICATIONS") {
                ToggleRow(
                    icon = Icons.Default.NotificationsActive,
                    title = stringResource(R.string.notifications_label),
                    subtitle = "Notify on E2EE sync updates, cached offline pages, and HTTPS warnings",
                    checked = settings.notificationsEnabled && hasNotificationPermission,
                    onCheckedChange = { enable ->
                        if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onSetNotificationsEnabled(enable)
                        }
                    },
                    tag = "toggle_notifications"
                )

                FilledTonalButton(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            onSendTestNotification()
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("test_notification_button")
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            "Enable & Test Security Notification"
                        } else {
                            "Send Test Security Alert"
                        }
                    )
                }
            }

            // 5. Account & Optional Zero-Knowledge E2EE Cloud Sync
            SettingsCard(title = "E2EE VAULT & CLOUD SYNC") {
                if (!isSignedIn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudSync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "Local Mode Active (No Login Required)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Sign in optionally to sync encrypted passwords, shortcuts, and offline archives across devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Button(
                        onClick = onOpenSignIn,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("settings_sign_in_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.sign_in_google))
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.EnhancedEncryption,
                            contentDescription = null,
                            tint = ShieldEmerald,
                            modifier = Modifier.size(20.dp)
                        )
                        Column {
                            Text(
                                text = userEmail ?: "Google Sync Active",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isVaultUnlocked) "AES-256-GCM KEY: $keyFingerprint"
                                else "E2EE Vault Locked • Tap Unlock to access passwords",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isVaultUnlocked) ShieldEmerald else MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isVaultUnlocked) {
                            OutlinedButton(
                                onClick = onLockVault,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp).testTag("lock_vault_button")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Lock Vault")
                            }
                        } else {
                            FilledTonalButton(
                                onClick = onOpenVaultUnlock,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(48.dp).testTag("unlock_vault_from_settings_button")
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Unlock Vault")
                            }
                        }
                        Button(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f).height(48.dp).testTag("settings_sign_out_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sign_out_action))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(tag)
        )
    }
}
