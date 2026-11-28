package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.browser.DetectedImageMedia
import com.example.browser.DetectedVideoMedia
import com.example.browser.DownloadedMediaItem
import com.example.browser.ImageDownloadFormat
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun VideoDownloaderSheet(
    detectedVideos: List<DetectedVideoMedia>,
    detectedImages: List<DetectedImageMedia>,
    downloadedVideos: List<DownloadedMediaItem>,
    activeDownload: ActiveDownloadProgress?,
    onRescanPage: () -> Unit,
    onDownloadVideo: (url: String, title: String, quality: String) -> Unit,
    onSystemDownload: (url: String, title: String) -> Unit,
    onDownloadImageInFormat: (url: String, title: String, format: ImageDownloadFormat) -> Unit,
    onCancelDownload: () -> Unit,
    onPlayStream: (DetectedVideoMedia) -> Unit,
    onPlayDownloaded: (DownloadedMediaItem) -> Unit,
    onShareDownloaded: (DownloadedMediaItem) -> Unit,
    onHideDownloadedInVault: (DownloadedMediaItem) -> Unit,
    onDeleteDownloaded: (DownloadedMediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    var directUrlInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(if (detectedVideos.isEmpty() && detectedImages.isNotEmpty()) 1 else 0) }
    var selectedImageFormat by remember { mutableStateOf(ImageDownloadFormat.ORIGINAL) }

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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Media & Image Saver",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Save videos, full-size images in any format & files to Downloads/Velo",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRescanPage,
                modifier = Modifier.size(48.dp).testTag("rescan_videos_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan page for videos and images")
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_video_downloader_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close media downloader")
            }
        }

        // Section Filter Tabs (Videos / Page Images / Saved)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                label = { Text("Videos (${detectedVideos.size})") },
                modifier = Modifier.testTag("downloader_tab_videos")
            )
            FilterChip(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                label = { Text("Page Images (${detectedImages.size})") },
                modifier = Modifier.testTag("downloader_tab_images")
            )
            FilterChip(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                label = { Text("Saved (${downloadedVideos.size})") },
                modifier = Modifier.testTag("downloader_tab_saved")
            )
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
            when (selectedTab) {
                // TAB 0: PAGE VIDEOS & DIRECT URL DOWNLOADER
                0 -> {
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
                                    text = "Direct Video or File URL",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                OutlinedTextField(
                                    value = directUrlInput,
                                    onValueChange = { directUrlInput = it },
                                    placeholder = { Text("Paste .mp4, .webm, .mkv, .m3u8, .pdf, or image URL") },
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
                                                onSystemDownload(directUrlInput.trim(), "")
                                                directUrlInput = ""
                                            }
                                        },
                                        enabled = directUrlInput.isNotBlank(),
                                        modifier = Modifier.testTag("system_dm_direct_button")
                                    ) {
                                        Text("System DM")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Detected Videos on Page (${detectedVideos.size})",
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
                                        text = "No videos detected on this tab yet",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Start playing any embedded video on the page and tap Refresh (top right) to capture its stream without needing a download link.",
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
                                onSystemDownload = { onSystemDownload(media.url, media.title) },
                                onPlay = { onPlayStream(media) }
                            )
                        }
                    }
                }

                // TAB 1: FULL-SIZE MULTI-FORMAT PAGE IMAGE DOWNLOADER
                1 -> {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Full-Size Image Output Format",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Downloads the highest-resolution source image and converts it losslessly into your chosen format:",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ImageDownloadFormat.entries.forEach { fmt ->
                                        FilterChip(
                                            selected = selectedImageFormat == fmt,
                                            onClick = { selectedImageFormat = fmt },
                                            label = { Text(fmt.label) },
                                            modifier = Modifier.testTag("img_format_chip_${fmt.name.lowercase()}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (detectedImages.isEmpty()) {
                        item {
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "No webpage images scanned yet",
                                        style = MaterialTheme.typography.titleSmall
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Open any webpage with photos and tap Refresh above, or long-press any image on the page to download in full size.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        items(detectedImages, key = { it.url }) { img ->
                            DetectedImageCard(
                                image = img,
                                selectedFormat = selectedImageFormat,
                                isBusy = activeDownload != null,
                                onDownload = { onDownloadImageInFormat(img.url, img.title, selectedImageFormat) }
                            )
                        }
                    }
                }

                // TAB 2: SAVED DOWNLOADS (OPEN / SHARE VIA FILEPROVIDER / HIDE IN VAULT / DELETE)
                else -> {
                    if (downloadedVideos.isEmpty()) {
                        item {
                            Text(
                                text = "Downloaded videos, full-size images, PDFs, audios, and files appear here and in your phone's Downloads/Velo folder.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        items(downloadedVideos, key = { it.id }) { item ->
                            DownloadedVideoCard(
                                item = item,
                                onPlay = { onPlayDownloaded(item) },
                                onShare = { onShareDownloaded(item) },
                                onHideInVault = { onHideDownloadedInVault(item) },
                                onDelete = { onDeleteDownloaded(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectedImageCard(
    image: DetectedImageMedia,
    selectedFormat: ImageDownloadFormat,
    isBusy: Boolean,
    onDownload: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = image.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (image.url.startsWith("data:")) "Inline Image Data" else image.url,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    color = ShieldEmerald.copy(alpha = 0.16f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "${image.formatLabel} • ${image.resolutionLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = ShieldEmerald,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Button(
                onClick = onDownload,
                enabled = !isBusy,
                modifier = Modifier.fillMaxWidth().testTag("download_detected_image_button")
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Download Full Size (${selectedFormat.label})")
            }
        }
    }
}

@Composable
private fun DetectedVideoCard(
    media: DetectedVideoMedia,
    isBusy: Boolean,
    onDownload: () -> Unit,
    onSystemDownload: () -> Unit,
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
                    Text("Download")
                }
                if (media.formatLabel != "HLS") {
                    OutlinedButton(
                        onClick = onSystemDownload,
                        modifier = Modifier.testTag("system_dm_detected_video_button")
                    ) {
                        Text("DM")
                    }
                }
                OutlinedButton(
                    onClick = onPlay,
                    modifier = Modifier.testTag("play_detected_video_button")
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Play")
                }
            }
        }
    }
}

@Composable
private fun DownloadedVideoCard(
    item: DownloadedMediaItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onHideInVault: () -> Unit,
    onDelete: () -> Unit
) {
    val lowerMime = item.mimeType.lowercase()
    val fmt = item.formatLabel.uppercase()
    val isPdf = lowerMime == "application/pdf" || fmt == "PDF"
    val isImage = lowerMime.startsWith("image/") || fmt in setOf("PNG", "JPG", "JPEG", "WEBP", "GIF", "IMAGE")
    val isAudio = lowerMime.startsWith("audio/") || fmt in setOf("MP3", "M4A", "WAV", "OGG", "FLAC", "AUDIO")
    val isVideo = lowerMime.startsWith("video/") || fmt in setOf("MP4", "WEBM", "MKV", "MOV", "M4V", "3GP", "HLS", "HLS/TS", "VIDEO")

    val itemIcon = when {
        isPdf -> Icons.Default.PictureAsPdf
        isImage -> Icons.Default.Image
        isAudio -> Icons.Default.MusicNote
        isVideo -> Icons.Default.VideoFile
        else -> Icons.Default.Description
    }
    val actionLabel = when {
        isPdf -> "Open PDF"
        isImage -> "View"
        isAudio -> "Play"
        isVideo -> "Play"
        else -> "Info"
    }
    val actionIcon = if (isVideo || isAudio) Icons.Default.PlayArrow else Icons.Default.OpenInNew

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
                    Icon(itemIcon, contentDescription = null, tint = ShieldEmerald)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val sizeText = if (item.byteSize >= 1024L * 1024L) {
                        "${"%.1f".format(item.byteSize / (1024.0 * 1024.0))} MB"
                    } else {
                        "${(item.byteSize / 1024L).coerceAtLeast(1L)} KB"
                    }
                    Text(
                        text = "${item.formatLabel} • ${item.qualityLabel} • $sizeText",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(48.dp).testTag("share_downloaded_video_${item.id}")
                ) {
                    Icon(Icons.Default.Share, contentDescription = "Share file", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(48.dp).testTag("delete_downloaded_video_${item.id}")
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete download", tint = MaterialTheme.colorScheme.error)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.weight(1f).testTag("play_downloaded_video_${item.id}")
                ) {
                    Icon(actionIcon, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(actionLabel)
                }
                if (isPdf || isImage || isAudio || isVideo) {
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
}
