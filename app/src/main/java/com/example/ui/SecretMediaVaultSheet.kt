package com.example.ui

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.SecretMediaItem
import com.example.browser.SecretMediaType
import com.example.crypto.SecretMediaVaultManager
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber

/**
 * First-Time Secret Code Setup or Change Secret Code screen.
 * Explains how typing the user's secret code directly in the top browser search bar
 * opens the encrypted Secret Media Vault silently without leaving browser history.
 */
@Composable
fun SecretVaultSetupSheet(
    isAlreadyConfigured: Boolean,
    isCurrentlyUnlocked: Boolean,
    isBusy: Boolean,
    errorMessage: String?,
    onSaveCode: (newCode: String, confirmCode: String, currentCode: String) -> Unit,
    onClearError: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)

    var currentCode by remember { mutableStateOf("") }
    var newCode by remember { mutableStateOf("") }
    var confirmCode by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isAlreadyConfigured) "Change Secret Code" else "Setup Secret Vault",
                style = MaterialTheme.typography.headlineSmall
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_secret_setup_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close secret vault setup")
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Stealth Search-Bar Access",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Hide private Videos, Images, PDFs & Audios with AES-256-GCM encryption. " +
                        "Once set, simply type your secret code into Velo's top search bar and press Go to unlock instantly — it is never sent to any search engine.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!isAlreadyConfigured) {
                    Text(
                        text = "Tip: First-time setup code is ${SecretMediaVaultManager.DEFAULT_SETUP_CODE}. Choose any 4–24 character code without spaces (e.g. #4829 or *7788).",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = ShieldEmerald
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (isAlreadyConfigured && !isCurrentlyUnlocked) {
            OutlinedTextField(
                value = currentCode,
                onValueChange = {
                    currentCode = it
                    onClearError()
                },
                label = { Text("Current Secret Code") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("secret_current_code_input")
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = newCode,
            onValueChange = {
                newCode = it
                onClearError()
            },
            label = { Text(if (isAlreadyConfigured) "New Secret Code" else "Create Secret Code") },
            placeholder = { Text("e.g. #4829 or *9012") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("secret_new_code_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmCode,
            onValueChange = {
                confirmCode = it
                onClearError()
            },
            label = { Text("Confirm Secret Code") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("secret_confirm_code_input")
        )

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { onSaveCode(newCode, confirmCode, currentCode) },
            enabled = !isBusy && newCode.isNotBlank() && confirmCode.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("save_secret_code_button")
        ) {
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Securing Vault...")
            } else {
                Icon(Icons.Default.EnhancedEncryption, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (isAlreadyConfigured) "Update Code & Re-Encrypt" else "Activate Secret Vault")
            }
        }
    }
}

/**
 * Unlocked Encrypted Secret Media Vault for hiding & privately viewing Videos, Images, PDFs & Audios.
 */
@Composable
fun SecretMediaVaultSheet(
    items: List<SecretMediaItem>,
    imagePreview: Pair<SecretMediaItem, ByteArray>?,
    isBusy: Boolean,
    pendingDeleteUris: List<Uri> = emptyList(),
    onHideUris: (List<Uri>, SecretMediaType?) -> Unit,
    onOriginalDeleteFinished: (Int) -> Unit = {},
    onClearPendingDeleteUris: () -> Unit = {},
    onOpenItem: (SecretMediaItem) -> Unit,
    onCloseImagePreview: () -> Unit,
    onExportItem: (SecretMediaItem, Uri) -> Unit,
    onDeleteItem: (SecretMediaItem) -> Unit,
    onOpenChangeCode: () -> Unit,
    onLockAndClose: () -> Unit
) {
    if (imagePreview != null) {
        SecretInMemoryImageViewer(
            item = imagePreview.first,
            imageBytes = imagePreview.second,
            onClose = onCloseImagePreview
        )
        return
    }

    BackHandler(onBack = onLockAndClose)
    val context = LocalContext.current

    var selectedFilter by remember { mutableStateOf<SecretMediaType?>(null) }
    var pendingExportItem by remember { mutableStateOf<SecretMediaItem?>(null) }

    val filteredItems = remember(items, selectedFilter) {
        if (selectedFilter == null) items else items.filter { it.type == selectedFilter }
    }

    // Android 11+ MediaStore delete confirmation dialog (removes unencrypted originals from Gallery & Files)
    val deleteOriginalLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            onOriginalDeleteFinished(pendingDeleteUris.size)
        } else {
            onClearPendingDeleteUris()
        }
    }

    LaunchedEffect(pendingDeleteUris) {
        if (pendingDeleteUris.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, pendingDeleteUris)
                deleteOriginalLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build()
                )
            } catch (_: Exception) {
                onClearPendingDeleteUris()
            }
        }
    }

    // Use SAF OpenMultipleDocuments for all media types so Velo can encrypt AND remove the original from Gallery & Files
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onHideUris(uris, SecretMediaType.IMAGE)
    }

    val pickVideosLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onHideUris(uris, SecretMediaType.VIDEO)
    }

    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onHideUris(uris, SecretMediaType.PDF)
    }

    val pickAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) onHideUris(uris, SecretMediaType.AUDIO)
    }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val item = pendingExportItem
        pendingExportItem = null
        if (uri != null && item != null) {
            onExportItem(item, uri)
        }
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
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.EnhancedEncryption, contentDescription = null, tint = ShieldEmerald)
                    Text(
                        text = "Secret Vault",
                        style = MaterialTheme.typography.headlineSmall
                    )
                }
                Text(
                    text = "AES-256-GCM Encrypted • Moves files out of Gallery & Files",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShieldEmerald
                )
            }
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(modifier = Modifier.width(6.dp))
            }
            IconButton(
                onClick = onOpenChangeCode,
                modifier = Modifier.size(44.dp).testTag("secret_vault_change_code_button")
            ) {
                Icon(Icons.Default.Key, contentDescription = "Change secret code")
            }
            IconButton(
                onClick = onLockAndClose,
                modifier = Modifier.size(48.dp).testTag("secret_vault_lock_button")
            ) {
                Icon(Icons.Default.Lock, contentDescription = "Lock and close secret vault", tint = MaterialTheme.colorScheme.error)
            }
        }

        // Quick Hide-and-Move Buttons Row (Videos, Images, PDFs, Audios)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { pickVideosLauncher.launch(arrayOf("video/*")) },
                enabled = !isBusy,
                modifier = Modifier.testTag("vault_hide_videos_button")
            ) {
                Icon(Icons.Default.VideoFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Videos")
            }
            Button(
                onClick = { pickImagesLauncher.launch(arrayOf("image/*")) },
                enabled = !isBusy,
                modifier = Modifier.testTag("vault_hide_images_button")
            ) {
                Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Images")
            }
            OutlinedButton(
                onClick = { pickPdfLauncher.launch(arrayOf("application/pdf")) },
                enabled = !isBusy,
                modifier = Modifier.testTag("vault_hide_pdfs_button")
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ PDFs")
            }
            OutlinedButton(
                onClick = { pickAudioLauncher.launch(arrayOf("audio/*")) },
                enabled = !isBusy,
                modifier = Modifier.testTag("vault_hide_audios_button")
            ) {
                Icon(Icons.Default.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("+ Audios")
            }
        }

        // Filter Chips (All / Videos / Images / PDFs / Audios)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = { selectedFilter = null },
                label = { Text("All (${items.size})") }
            )
            SecretMediaType.entries.forEach { type ->
                val count = items.count { it.type == type }
                FilterChip(
                    selected = selectedFilter == type,
                    onClick = { selectedFilter = type },
                    label = { Text("${type.label} ($count)") }
                )
            }
        }

        // Hidden Items List
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filteredItems.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Your Secret Vault is empty",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Use the buttons above to encrypt & hide Videos, Images, PDFs, or Audio files. They are stored strictly encrypted inside Velo and hidden from gallery apps.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredItems, key = { it.id }) { item ->
                    SecretMediaRowCard(
                        item = item,
                        onOpen = { onOpenItem(item) },
                        onExport = {
                            pendingExportItem = item
                            exportDocLauncher.launch(item.displayName)
                        },
                        onDelete = { onDeleteItem(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun SecretMediaRowCard(
    item: SecretMediaItem,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    val (icon, tint) = when (item.type) {
        SecretMediaType.VIDEO -> Icons.Default.VideoFile to MaterialTheme.colorScheme.primary
        SecretMediaType.IMAGE -> Icons.Default.Image to ShieldEmerald
        SecretMediaType.PDF -> Icons.Default.PictureAsPdf to MaterialTheme.colorScheme.error
        SecretMediaType.AUDIO -> Icons.Default.AudioFile to WarningAmber
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(tint.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sizeLabel = if (item.byteSize >= 1024L * 1024L) {
                    "${"%.1f".format(item.byteSize / (1024.0 * 1024.0))} MB"
                } else {
                    "${(item.byteSize / 1024L).coerceAtLeast(1L)} KB"
                }
                Text(
                    text = "${item.type.label} • $sizeLabel • AES-256",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onOpen, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Open hidden item", tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onExport, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export item")
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete hidden item", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun SecretInMemoryImageViewer(
    item: SecretMediaItem,
    imageBytes: ByteArray,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val bitmap = remember(imageBytes) {
        BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )
            } else {
                Text("Unable to decode encrypted image.", color = Color.White)
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(Color.Black.copy(alpha = 0.65f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) {
                Text("Reset", color = Color.White)
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag("close_secret_image_viewer")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close encrypted image", tint = Color.White)
            }
        }
    }
}
