package com.example.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.PdfDocumentItem
import com.example.media.PdfEngine
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.ShieldEmerald
import com.example.ui.theme.WarningAmber

@Composable
fun PdfToolsSheet(
    pdfEngine: PdfEngine,
    savedPdfs: List<PdfDocumentItem>,
    activePdf: PdfDocumentItem?,
    isBusy: Boolean,
    hasActiveWebPage: Boolean,
    activePageTitle: String,
    onImportPdfUri: (Uri) -> Unit,
    onConvertImagesToPdf: (List<Uri>, String) -> Unit,
    onConvertTextToPdf: (String, String) -> Unit,
    onConvertWebPageToPdf: () -> Unit,
    onOpenPdf: (PdfDocumentItem) -> Unit,
    onCloseActivePdf: () -> Unit,
    onExtractPdfToImages: (PdfDocumentItem) -> Unit,
    onExportPdfToUri: (PdfDocumentItem, Uri) -> Unit,
    onHidePdfInVault: (PdfDocumentItem) -> Unit,
    onDeletePdf: (PdfDocumentItem) -> Unit,
    onDismiss: () -> Unit
) {
    if (activePdf != null) {
        PdfReaderView(
            pdfEngine = pdfEngine,
            document = activePdf,
            onExportToUri = { uri -> onExportPdfToUri(activePdf, uri) },
            onExtractImages = { onExtractPdfToImages(activePdf) },
            onHideInVault = { onHidePdfInVault(activePdf) },
            onClose = onCloseActivePdf
        )
        return
    }

    BackHandler(onBack = onDismiss)
    var showTextToPdfDialog by remember { mutableStateOf(false) }

    val openPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportPdfUri(uri)
    }

    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onConvertImagesToPdf(uris, "Images_${uris.size}_Pages")
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PDF Viewer & Converter",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "View, zoom, convert webpages, images & text to PDF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(48.dp).testTag("close_pdf_tools_button")
            ) {
                Icon(Icons.Default.Close, contentDescription = "Close PDF tools")
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Converter Action Grid
            item {
                Text(
                    text = "PDF Converters & Reader",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PdfActionCard(
                        icon = Icons.Default.FolderOpen,
                        title = "Open PDF",
                        subtitle = "Pick from device",
                        accent = MaterialTheme.colorScheme.primary,
                        enabled = !isBusy,
                        onClick = { openPdfLauncher.launch(arrayOf("application/pdf")) },
                        tag = "pdf_open_file_button",
                        modifier = Modifier.weight(1f)
                    )
                    PdfActionCard(
                        icon = Icons.Default.Language,
                        title = "Webpage -> PDF",
                        subtitle = if (hasActiveWebPage) activePageTitle.take(24) else "Open a page first",
                        accent = ShieldEmerald,
                        enabled = hasActiveWebPage && !isBusy,
                        onClick = onConvertWebPageToPdf,
                        tag = "pdf_webpage_convert_button",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PdfActionCard(
                        icon = Icons.Default.Image,
                        title = "Images -> PDF",
                        subtitle = "Combine photos",
                        accent = WarningAmber,
                        enabled = !isBusy,
                        onClick = {
                            pickImagesLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        tag = "pdf_images_convert_button",
                        modifier = Modifier.weight(1f)
                    )
                    PdfActionCard(
                        icon = Icons.Default.Description,
                        title = "Text -> PDF",
                        subtitle = "Notes to A4 PDF",
                        accent = MaterialTheme.colorScheme.secondary,
                        enabled = !isBusy,
                        onClick = { showTextToPdfDialog = true },
                        tag = "pdf_text_convert_button",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // Saved PDFs Library
            item {
                Text(
                    text = "Saved PDFs (${savedPdfs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (savedPdfs.isEmpty()) {
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No PDFs saved yet. Open any PDF file or convert a webpage, images, or text note above.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(savedPdfs, key = { it.id }) { doc ->
                    SavedPdfCard(
                        item = doc,
                        onOpen = { onOpenPdf(doc) },
                        onExtractImages = { onExtractPdfToImages(doc) },
                        onHideInVault = { onHidePdfInVault(doc) },
                        onDelete = { onDeletePdf(doc) }
                    )
                }
            }
        }
    }

    if (showTextToPdfDialog) {
        var titleInput by remember { mutableStateOf("") }
        var bodyInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showTextToPdfDialog = false },
            title = { Text("Text / Notes to PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it },
                        label = { Text("Document Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bodyInput,
                        onValueChange = { bodyInput = it },
                        label = { Text("Document Content") },
                        minLines = 5,
                        maxLines = 9,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (bodyInput.isNotBlank()) {
                            onConvertTextToPdf(titleInput.ifBlank { "Velo Document" }, bodyInput)
                            showTextToPdfDialog = false
                        }
                    },
                    enabled = bodyInput.isNotBlank()
                ) {
                    Text("Create PDF")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextToPdfDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PdfReaderView(
    pdfEngine: PdfEngine,
    document: PdfDocumentItem,
    onExportToUri: (Uri) -> Unit,
    onExtractImages: () -> Unit,
    onHideInVault: () -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    var currentPage by remember(document.filePath) { mutableIntStateOf(0) }
    var zoomScale by remember(document.filePath) { mutableFloatStateOf(1.4f) }
    var invertColors by remember { mutableStateOf(false) }
    var renderedBitmap by remember(document.filePath) { mutableStateOf<Bitmap?>(null) }
    var isRendering by remember { mutableStateOf(true) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        if (uri != null) onExportToUri(uri)
    }

    LaunchedEffect(document.filePath, currentPage, zoomScale, invertColors) {
        isRendering = true
        val bmp = pdfEngine.renderPdfPage(
            filePath = document.filePath,
            pageIndex = currentPage,
            scale = zoomScale,
            invertColors = invertColors
        )
        renderedBitmap = bmp
        isRendering = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(if (invertColors) Color(0xFF0B0F19) else MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars)
            .navigationBarsPadding()
    ) {
        // Top PDF Reader Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onClose,
                modifier = Modifier.size(48.dp).testTag("close_pdf_reader_button")
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back from PDF reader")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Page ${currentPage + 1} of ${document.pageCount} • ${(zoomScale / 1.4f * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = JetBrainsMonoFontFamily,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(
                onClick = { invertColors = !invertColors },
                modifier = Modifier.size(44.dp).testTag("pdf_invert_toggle_button")
            ) {
                Icon(
                    imageVector = if (invertColors) Icons.Default.LightMode else Icons.Default.DarkMode,
                    contentDescription = "Toggle PDF night mode"
                )
            }
            IconButton(
                onClick = { exportLauncher.launch("${document.title}.pdf") },
                modifier = Modifier.size(44.dp).testTag("pdf_export_button")
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = "Export PDF")
            }
            if (!document.deleteTempFileOnClose) {
                IconButton(
                    onClick = onHideInVault,
                    modifier = Modifier.size(44.dp).testTag("pdf_hide_vault_button")
                ) {
                    Icon(Icons.Default.EnhancedEncryption, contentDescription = "Hide PDF in Secret Vault")
                }
            }
        }

        // Scrollable Zoomable Page Surface
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(if (invertColors) Color(0xFF0B0F19) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val bmp = renderedBitmap
            if (bmp != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "PDF Page ${currentPage + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                    )
                }
            }
            if (isRendering) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        // Bottom PDF Controls
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (currentPage > 0) currentPage-- },
                        enabled = currentPage > 0,
                        modifier = Modifier.size(48.dp).testTag("pdf_prev_page_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous page")
                    }
                    Text(
                        text = "${currentPage + 1}/${document.pageCount}",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = JetBrainsMonoFontFamily
                    )
                    IconButton(
                        onClick = { if (currentPage < document.pageCount - 1) currentPage++ },
                        enabled = currentPage < document.pageCount - 1,
                        modifier = Modifier.size(48.dp).testTag("pdf_next_page_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next page")
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { zoomScale = (zoomScale - 0.35f).coerceAtLeast(0.8f) },
                        modifier = Modifier.size(44.dp).testTag("pdf_zoom_out_button")
                    ) {
                        Icon(Icons.Default.ZoomOut, contentDescription = "Zoom out")
                    }
                    IconButton(
                        onClick = { zoomScale = (zoomScale + 0.35f).coerceAtMost(2.8f) },
                        modifier = Modifier.size(44.dp).testTag("pdf_zoom_in_button")
                    ) {
                        Icon(Icons.Default.ZoomIn, contentDescription = "Zoom in")
                    }
                    if (!document.deleteTempFileOnClose) {
                        TextButton(onClick = onExtractImages) {
                            Text("To PNG")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier
) {
    val alpha = if (enabled) 1f else 0.45f
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .height(106.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .testTag(tag)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.16f * alpha), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent.copy(alpha = alpha), modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SavedPdfCard(
    item: PdfDocumentItem,
    onOpen: () -> Unit,
    onExtractImages: () -> Unit,
    onHideInVault: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(onClick = onOpen)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val kb = (item.byteSize / 1024L).coerceAtLeast(1L)
                    Text(
                        text = "${item.pageCount} page(s) • $kb KB",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = JetBrainsMonoFontFamily,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete PDF", tint = MaterialTheme.colorScheme.error)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("View")
                }
                OutlinedButton(onClick = onExtractImages) {
                    Text("To PNG")
                }
                OutlinedButton(onClick = onHideInVault) {
                    Icon(Icons.Default.EnhancedEncryption, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Vault")
                }
            }
        }
    }
}
