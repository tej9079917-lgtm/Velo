package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.ActiveDownloadProgress
import com.example.browser.DetectedVideoMedia
import com.example.browser.DownloadedMediaItem
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun VideoDownloaderSheet(
    detectedVideos: List<DetectedVideoMedia>,
    downloadedVideos: List<DownloadedMediaItem>,
    activeDownload: ActiveDownloadProgress?,
    onRescanPage: () -> Unit,
    onDownloadVideo: (url: String, title: String, quality: String) -> Unit,
    onCancelDownload: () -> Unit,
    onPlayStream: (DetectedVideoMedia) -> Unit,
    onPlayDownloaded: (DownloadedMediaItem) -> Unit,
    onHideDownloadedInVault: (DownloadedMediaItem) -> Unit,
    onDeleteDownloaded: (DownloadedMediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var directUrlInput by remember { mutableStateOf("") }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Video Downloader",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Save playing web videos & manage offline media",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRescanPage,
                modifier = Modifier.size(48.dp).testTag("rescan_videos_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan page for videos")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_video_downloader_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close video downloader")
            }
        }

        // Active Download Progress Card
        if (activeDownload != null) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Downloading: ${activeDownload.title}",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val dlMb = activeDownload.downloadedBytes / (1024.0 * 1024.0)
                            val sizeText = if (activeDownload.totalBytes > 0L) {
                                val totMb = activeDownload.totalBytes / (1024.0 * 1024.0)
                                "${"%.2f".format(dlMb)} MB / ${"%.2f".format(totMb)} MB"
                            } else {
                                "${"%.2f".format(dlMb)} MB downloaded"
                            }
                            Text(
                                text = sizeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = JetBrainsMonoFontFamily,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        TextButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.testTag("cancel_video_download_button")
                        ) {
                            Text("Cancel", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (activeDownload.fraction >= 0f) {
                        LinearProgressIndicator(
                            progress = { activeDownload.fraction },
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp)
                        )
                    }
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Direct Video URL Download Input
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Direct Video Stream URL",
                            style = MaterialTheme.typography.titleSmall
                        )
                        OutlinedTextField(
                            value = directUrlInput,
                            onValueChange = { directUrlInput = it },
                            placeholder = { Text("Paste .mp4, .webm, .mkv, or .m3u8 URL") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("direct_video_url_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    if (directUrlInput.isNotBlank()) {
                                        onDownloadVideo(directUrlInput.trim(), "", "Auto")
                                        directUrlInput = ""
                                    }
                                },
                                enabled = directUrlInput.isNotBlank() && activeDownload == null,
                                modifier = Modifier.weight(1f).testTag("start_direct_download_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Download")
                            }
                            OutlinedButton(
                                onClick = {
                                    if (directUrlInput.isNotBlank()) {
                                        onPlayStream(
                                            DetectedVideoMedia(
                                                url = directUrlInput.trim(),
                                                title = "Direct Stream",
                                                formatLabel = "STREAM",
                                                qualityLabel = "Auto",
                                                isPlaying = true
                                            )
                                        )
                                    }
                                },
                                enabled = directUrlInput.isNotBlank(),
                                modifier = Modifier.testTag("stream_direct_url_button")
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Play")
                            }
                        }
                    }
                }
            }

            // Detected Streams on Current Tab
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Detected on Current Page (${detectedVideos.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    if (detectedVideos.isNotEmpty()) {
                        Surface(
                            color = ShieldEmerald.copy(alpha = 0.16f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "READY",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = ShieldEmerald,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            }

            if (detectedVideos.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "No playing videos detected on this tab yet",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Play a video on any webpage and tap Refresh, or paste a direct stream link above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(detectedVideos, key = { it.url }) { media ->
                    DetectedVideoCard(
                        media = media,
                        isBusy = activeDownload != null,
                        onDownload = { onDownloadVideo(media.url, media.title, media.qualityLabel) },
                        onPlay = { onPlayStream(media) }
                    )
                }
            }

            // Downloaded Offline Library
            item {
                Text(
                    text = "Saved Videos (${downloadedVideos.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (downloadedVideos.isEmpty()) {
                item {
                    Text(
                        text = "Downloaded videos appear here for offline playback or 1-tap hiding in your Secret Vault.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items(downloadedVideos, key = { it.id }) { item ->
                    DownloadedVideoCard(
                        item = item,
                        onPlay = { onPlayDownloaded(item) },
                        onHideInVault = { onHideDownloadedInVault(item) },
                        onDelete = { onDeleteDownloaded(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DetectedVideoCard(
    media: DetectedVideoMedia,
    isBusy: Boolean,
    onDownload: () -> Unit,
    onPlay: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Movie, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = media.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = media.url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = WarningAmber.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${media.formatLabel} • ${media.qualityLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = WarningAmber,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDownload,
                    enabled = !isBusy,
                    modifier = Modifier.weight(1f).testTag("download_detected_video_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Download Video")
                }
                OutlinedButton(
                    onClick = onPlay,
                    modifier = Modifier.testTag("play_detected_video_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Velo Player")
                }
            }
        }
    }
}

@Composable
private fun DownloadedVideoCard(
    item: DownloadedMediaItem,
    onPlay: () -> Unit,
    onHideInVault: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(ShieldEmerald.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.VideoFile, contentDescription = null, tint = ShieldEmerald)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val mb = item.byteSize / (1024.0 * 1024.0)
                    Text(
                        text = "${item.formatLabel} • ${item.qualityLabel} • ${"%.1f".format(mb)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(42.dp).testTag("delete_downloaded_video_${item.id}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete video", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f).testTag("play_downloaded_video_${item.id}")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Play")
                }
                OutlinedButton(
                    onClick = onHideInVault,
                    modifier = Modifier.weight(1f).testTag("hide_downloaded_video_${item.id}")
                ) {
                    Icon(Icons.Default.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Hide in Vault")
                }
            }
        }
    }
}
