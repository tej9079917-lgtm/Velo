package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.FocusSessionState
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun PowerToolsSheet(
    hasActiveWebPage: Boolean,
    isSplitActive: Boolean,
    focusState: FocusSessionState,
    networkTotalCount: Int,
    networkBlockedCount: Int,
    clipsCount: Int,
    offlineCount: Int,
    detectedVideosCount: Int,
    downloadedVideosCount: Int,
    savedPdfsCount: Int,
    isSecretVaultConfigured: Boolean,
    onToggleSplitView: () -> Unit,
    onOpenReaderMode: () -> Unit,
    onOpenScratchpad: () -> Unit,
    onOpenNetworkInspector: () -> Unit,
    onOpenFocusTimer: () -> Unit,
    onEnterPipMode: () -> Unit,
    onOpenFindInPage: () -> Unit,
    onOpenOfflineReading: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenNewPrivateTab: () -> Unit,
    onOpenVideoDownloader: () -> Unit,
    onOpenVideoPlayer: () -> Unit,
    onOpenPdfTools: () -> Unit,
    onOpenSpeedTest: () -> Unit,
    onOpenSecretVaultSetup: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Velo Power Tools",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Unique workspace utilities for speed & deep focus",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_power_tools_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close power tools")
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            item {
                PowerToolCard(
                    icon = Icons.Default.Download,
                    title = "Video Downloader",
                    subtitle = if (detectedVideosCount > 0) "$detectedVideosCount playing stream(s) ready" else "Save playing web videos offline",
                    badge = when {
                        detectedVideosCount > 0 -> "$detectedVideosCount LIVE"
                        downloadedVideosCount > 0 -> "$downloadedVideosCount"
                        else -> null
                    },
                    accent = ShieldEmerald,
                    enabled = true,
                    onClick = onOpenVideoDownloader,
                    tag = "tool_video_downloader"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.PlayCircle,
                    title = "Video Player",
                    subtitle = "All formats, speed, quality & audio",
                    badge = "PRO",
                    accent = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onOpenVideoPlayer,
                    tag = "tool_video_player"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.PictureAsPdf,
                    title = "PDF Viewer & Conv.",
                    subtitle = "Read PDF, Webpage/Img/Text to PDF",
                    badge = if (savedPdfsCount > 0) "$savedPdfsCount" else "PDF",
                    accent = MaterialTheme.colorScheme.error,
                    enabled = true,
                    onClick = onOpenPdfTools,
                    tag = "tool_pdf_tools"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.Speed,
                    title = "Speed Test Meter",
                    subtitle = "Live ping, jitter, down & up Mbps",
                    badge = "LIVE",
                    accent = WarningAmber,
                    enabled = true,
                    onClick = onOpenSpeedTest,
                    tag = "tool_speed_test"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.EnhancedEncryption,
                    title = "Secret Media Vault",
                    subtitle = if (isSecretVaultConfigured) "Type your secret code in search bar" else "Setup code to hide media files",
                    badge = if (isSecretVaultConfigured) "CODE SET" else "SETUP",
                    accent = MaterialTheme.colorScheme.tertiary,
                    enabled = true,
                    onClick = onOpenSecretVaultSetup,
                    tag = "tool_secret_vault_setup"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.Splitscreen,
                    title = if (isSplitActive) "Exit Split-Screen" else "Split-Screen Tabs",
                    subtitle = if (isSplitActive) "Dual live panes active" else "Browse 2 pages side-by-side",
                    badge = if (isSplitActive) "ACTIVE" else null,
                    accent = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onToggleSplitView,
                    tag = "tool_split_screen"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = "Reader + TTS",
                    subtitle = if (hasActiveWebPage) "Clean article view & voice audio" else "Open a webpage first",
                    badge = "AUDIO",
                    accent = ShieldEmerald,
                    enabled = hasActiveWebPage,
                    onClick = onOpenReaderMode,
                    tag = "tool_reader_mode"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.ContentPaste,
                    title = "Web Clipper",
                    subtitle = "Page scratchpad & instant quotes",
                    badge = if (clipsCount > 0) "$clipsCount" else null,
                    accent = MaterialTheme.colorScheme.secondary,
                    enabled = true,
                    onClick = onOpenScratchpad,
                    tag = "tool_scratchpad"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.Radar,
                    title = "Network Inspector",
                    subtitle = "Live request feed & custom rules",
                    badge = if (networkTotalCount > 0) "$networkBlockedCount/$networkTotalCount" else null,
                    accent = WarningAmber,
                    enabled = true,
                    onClick = onOpenNetworkInspector,
                    tag = "tool_network_inspector"
                )
            }
            item {
                val mins = focusState.remainingSeconds / 60
                val secs = focusState.remainingSeconds % 60
                PowerToolCard(
                    icon = Icons.Default.Timer,
                    title = "Focus Shield",
                    subtitle = if (focusState.isActive) "Distraction blocker running" else "Pomodoro + site blocker",
                    badge = if (focusState.isActive) "%02d:%02d".format(mins, secs) else null,
                    accent = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onOpenFocusTimer,
                    tag = "tool_focus_timer"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.PictureInPictureAlt,
                    title = "Floating PiP",
                    subtitle = "Shrink browser into floating window",
                    badge = null,
                    accent = ShieldEmerald,
                    enabled = true,
                    onClick = {
                        onDismiss()
                        onEnterPipMode()
                    },
                    tag = "tool_pip_mode"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.FindInPage,
                    title = "Find in Page",
                    subtitle = if (hasActiveWebPage) "Search text inside current tab" else "Open a webpage first",
                    badge = null,
                    accent = MaterialTheme.colorScheme.secondary,
                    enabled = hasActiveWebPage,
                    onClick = onOpenFindInPage,
                    tag = "tool_find_in_page"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.OfflinePin,
                    title = "Offline Library",
                    subtitle = "Read cached pages with no signal",
                    badge = if (offlineCount > 0) "$offlineCount" else null,
                    accent = ShieldEmerald,
                    enabled = true,
                    onClick = onOpenOfflineReading,
                    tag = "tool_offline_reading"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.Key,
                    title = "Password Vault",
                    subtitle = "AES-256-GCM cloud autofill",
                    badge = "E2EE",
                    accent = MaterialTheme.colorScheme.primary,
                    enabled = true,
                    onClick = onOpenVault,
                    tag = "tool_vault"
                )
            }
            item {
                PowerToolCard(
                    icon = Icons.Default.VisibilityOff,
                    title = "New Private Tab",
                    subtitle = "Zero history & ephemeral storage",
                    badge = "STEALTH",
                    accent = MaterialTheme.colorScheme.tertiary,
                    enabled = true,
                    onClick = onOpenNewPrivateTab,
                    tag = "tool_private_tab"
                )
            }
        }
    }
}

@Composable
private fun PowerToolCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String?,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String
) {
    val contentAlpha = if (enabled) 1f else 0.45f
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(126.dp)
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 0.5f else 0.25f),
                RoundedCornerShape(18.dp)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(accent.copy(alpha = 0.16f * contentAlpha), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accent.copy(alpha = contentAlpha),
                        modifier = Modifier.size(20.dp)
                    )
                }
                if (badge != null) {
                    Surface(
                        color = accent.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
