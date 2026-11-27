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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkAdded
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.TextDecrease
import androidx.compose.material.icons.filled.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.browser.ReadingListItem
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber
import com.example.viewmodel.DecryptedOfflineReaderState

@Composable
fun OfflineReadingSheet(
    items: List<ReadingListItem>,
    isOnline: Boolean,
    isSavingOffline: Boolean,
    activePageUrl: String,
    activePageTitle: String,
    readerState: DecryptedOfflineReaderState?,
    onSaveCurrentPage: () -> Unit,
    onOpenOfflineReader: (ReadingListItem) -> Unit,
    onCloseOfflineReader: () -> Unit,
    onLoadOfflineHtmlInBrowser: (url: String, title: String, html: String) -> Unit,
    onCacheSyncedItemLocally: (ReadingListItem) -> Unit,
    onToggleArchive: (ReadingListItem) -> Unit,
    onDeleteItem: (ReadingListItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (readerState != null) {
        OfflineArticleReaderScreen(
            state = readerState,
            onBack = onCloseOfflineReader,
            onRenderFullHtml = {
                onLoadOfflineHtmlInBrowser(
                    readerState.item.url,
                    readerState.item.title,
                    readerState.htmlContent
                )
                onCloseOfflineReader()
                onDismiss()
            }
        )
        return
    }

    BackHandler(onBack = onDismiss)

    var filterMode by remember { mutableStateOf("ACTIVE") } // ACTIVE, CACHED, ARCHIVED
    val filtered = remember(items, filterMode) {
        when (filterMode) {
            "CACHED" -> items.filter { it.isCachedLocally && !it.archived }
            "ARCHIVED" -> items.filter { it.archived }
            else -> items.filter { !it.archived }
        }
    }
    val totalCachedKb = remember(items) {
        items.filter { it.isCachedLocally }.sumOf { it.cachedByteSize } / 1024L
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
                    text = "Offline Reading",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (isOnline) ShieldEmerald else WarningAmber,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "${if (isOnline) "Synced across devices" else "Offline mode"} • ${totalCachedKb} KB cached locally",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_offline_sheet")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close offline reading")
            }
        }

        // Quick Save Active Page Card
        if (activePageUrl.startsWith("http")) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Current Page",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = activePageTitle.ifBlank { activePageUrl },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = onSaveCurrentPage,
                        enabled = !isSavingOffline && isOnline,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag("save_current_page_offline_button")
                    ) {
                        if (isSavingOffline) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save")
                        }
                    }
                }
            }
        }

        // Filter Chips
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filterMode == "ACTIVE",
                onClick = { filterMode = "ACTIVE" },
                label = { Text("Reading List") }
            )
            FilterChip(
                selected = filterMode == "CACHED",
                onClick = { filterMode = "CACHED" },
                label = { Text("Cached Offline") }
            )
            FilterChip(
                selected = filterMode == "ARCHIVED",
                onClick = { filterMode = "ARCHIVED" },
                label = { Text("Archived") }
            )
        }

        // Articles List
        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.OfflinePin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "No Saved Offline Pages",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Tap the Save Offline icon while browsing any webpage to cache an encrypted local copy and sync your reading list across devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(filtered, key = { it.id }) { item ->
                    ReadingPageCard(
                        item = item,
                        isOnline = isOnline,
                        onOpen = {
                            if (item.isCachedLocally) onOpenOfflineReader(item)
                            else if (isOnline) onCacheSyncedItemLocally(item)
                        },
                        onDownloadCache = { onCacheSyncedItemLocally(item) },
                        onToggleArchive = { onToggleArchive(item) },
                        onDelete = { onDeleteItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingPageCard(
    item: ReadingListItem,
    isOnline: Boolean,
    onOpen: () -> Unit,
    onDownloadCache: () -> Unit,
    onToggleArchive: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .clickable(onClick = onOpen)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = item.url.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    IconButton(onClick = onToggleArchive, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (item.archived) Icons.Default.BookmarkAdded else Icons.Default.Archive,
                            contentDescription = if (item.archived) "Restore to list" else "Archive article",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete saved page",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (item.excerpt.isNotBlank()) {
                Text(
                    text = item.excerpt,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = if (item.isCachedLocally) Icons.Default.OfflinePin else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = if (item.isCachedLocally) ShieldEmerald else WarningAmber,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (item.isCachedLocally) {
                            "Ready offline (${(item.cachedByteSize / 1024L).coerceAtLeast(1)} KB)"
                        } else {
                            "Synced from cloud • Tap to cache"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!item.isCachedLocally && isOnline) {
                    FilledTonalButton(
                        onClick = onDownloadCache,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Cache Now", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineArticleReaderScreen(
    state: DecryptedOfflineReaderState,
    onBack: () -> Unit,
    onRenderFullHtml: () -> Unit
) {
    BackHandler(onBack = onBack)
    var fontSizeSp by remember { mutableIntStateOf(16) }

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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to reading list")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { fontSizeSp = (fontSizeSp - 2).coerceAtLeast(13) }) {
                    Icon(Icons.Default.TextDecrease, contentDescription = "Decrease text size")
                }
                IconButton(onClick = { fontSizeSp = (fontSizeSp + 2).coerceAtMost(24) }) {
                    Icon(Icons.Default.TextIncrease, contentDescription = "Increase text size")
                }
                FilledTonalButton(
                    onClick = onRenderFullHtml,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Full Web View")
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "OFFLINE ENCRYPTED CACHE • ZERO NETWORK",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Text(
                text = state.item.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = state.item.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.readableText.ifBlank { state.item.excerpt },
                fontSize = fontSizeSp.sp,
                lineHeight = (fontSizeSp * 1.6f).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
        }
    }
}
