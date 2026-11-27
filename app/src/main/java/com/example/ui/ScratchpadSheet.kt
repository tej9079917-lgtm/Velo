package com.example.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.ScratchpadClip

@Composable
fun ScratchpadSheet(
    clips: List<ScratchpadClip>,
    activePageTitle: String,
    activePageUrl: String,
    onRequestClipCurrentPage: (onCaptured: (snippet: String) -> Unit) -> Unit,
    onSaveClip: (id: String?, title: String, sourceUrl: String, content: String, tag: String) -> Unit,
    onDeleteClip: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopiedNotice: (String) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    var searchQuery by remember { mutableStateOf("") }
    var editingClip by remember { mutableStateOf<ScratchpadClip?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    var composerInitialTitle by remember { mutableStateOf("") }
    var composerInitialUrl by remember { mutableStateOf("") }
    var composerInitialContent by remember { mutableStateOf("") }

    val filtered = remember(clips, searchQuery) {
        if (searchQuery.isBlank()) clips
        else {
            val q = searchQuery.trim().lowercase()
            clips.filter {
                it.title.lowercase().contains(q) ||
                    it.content.lowercase().contains(q) ||
                    it.tag.lowercase().contains(q) ||
                    it.sourceUrl.lowercase().contains(q)
            }
        }
    }

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
                    text = "Scratchpad & Clipper",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${clips.size} saved clips & page research notes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_scratchpad_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close scratchpad")
            }
        }

        // Quick Action Strip: 1-Tap Clip Page + New Blank Note
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    onRequestClipCurrentPage { snippet ->
                        editingClip = null
                        composerInitialTitle = activePageTitle.ifBlank { "Web Clip" }
                        composerInitialUrl = activePageUrl
                        composerInitialContent = snippet.ifBlank { activePageUrl }
                        showComposer = true
                    }
                },
                enabled = activePageUrl.startsWith("http"),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp).testTag("clip_current_page_button")
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Clip Page")
            }

            FilledTonalButton(
                onClick = {
                    editingClip = null
                    composerInitialTitle = ""
                    composerInitialUrl = if (activePageUrl.startsWith("http")) activePageUrl else ""
                    composerInitialContent = ""
                    showComposer = true
                },
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f).height(48.dp).testTag("new_scratchpad_note_button")
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("New Note")
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search clips, tags, or URLs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("scratchpad_search_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Text(
                        text = if (clips.isEmpty()) "No clips saved yet" else "No matching clips",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Highlight text on any page and tap Clip Page, or jot down research notes right inside Velo.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filtered, key = { it.id }) { clip ->
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = clip.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = clip.tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Text(
                                text = clip.content,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis
                            )

                            if (clip.sourceUrl.isNotBlank()) {
                                Text(
                                    text = clip.sourceUrl.removePrefix("https://").removePrefix("http://"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (clip.sourceUrl.startsWith("http")) {
                                    IconButton(
                                        onClick = { onOpenUrl(clip.sourceUrl) },
                                        modifier = Modifier.size(44.dp)
                                    ) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = "Open clipped source",
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText(clip.title, clip.content))
                                        onCopiedNotice("Copied clip to clipboard.")
                                    },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy clip text",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteClip(clip.id) },
                                    modifier = Modifier.size(44.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete clip",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showComposer) {
        ClipComposerDialog(
            initialTitle = editingClip?.title ?: composerInitialTitle,
            initialUrl = editingClip?.sourceUrl ?: composerInitialUrl,
            initialContent = editingClip?.content ?: composerInitialContent,
            initialTag = editingClip?.tag ?: "Research",
            onDismiss = { showComposer = false },
            onSave = { title, url, content, tag ->
                onSaveClip(editingClip?.id, title, url, content, tag)
                showComposer = false
            }
        )
    }
}

@Composable
private fun ClipComposerDialog(
    initialTitle: String,
    initialUrl: String,
    initialContent: String,
    initialTag: String,
    onDismiss: () -> Unit,
    onSave: (title: String, url: String, content: String, tag: String) -> Unit
) {
    var title by remember { mutableStateOf(initialTitle) }
    var url by remember { mutableStateOf(initialUrl) }
    var content by remember { mutableStateOf(initialContent) }
    var tag by remember { mutableStateOf(initialTag) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save Web Clip") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("clip_title_input")
                )
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("Tag (e.g., Research, Quote, Dev)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("clip_tag_input")
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Clipped text or note") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("clip_content_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title, url, content, tag) },
                enabled = content.isNotBlank(),
                modifier = Modifier.testTag("save_clip_confirm")
            ) {
                Text("Save Clip")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
