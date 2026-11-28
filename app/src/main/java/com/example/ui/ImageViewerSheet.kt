package com.example.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EnhancedEncryption
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.example.browser.ImageViewerRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/**
 * Native Full-Screen Image Viewer for images opened via Android "Open With" chooser
 * or downloaded inside Velo Browser. Supports pinch-to-zoom, pan, double-tap zoom,
 * 90-degree rotation, 1-tap Convert to PDF, and 1-tap Hide in Secret Vault.
 */
@Composable
fun ImageViewerSheet(
    request: ImageViewerRequest,
    onConvertToPdf: (Uri, String) -> Unit,
    onHideInSecretVault: (ImageViewerRequest) -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current

    var bitmap by remember(request.source) { mutableStateOf<Bitmap?>(null) }
    var isLoading by remember(request.source) { mutableStateOf(true) }
    var errorMessage by remember(request.source) { mutableStateOf<String?>(null) }

    var scale by remember(request.source) { mutableFloatStateOf(1f) }
    var offset by remember(request.source) { mutableStateOf(Offset.Zero) }
    var rotationDegrees by remember(request.source) { mutableFloatStateOf(0f) }

    LaunchedEffect(request.source, request.isContentUri) {
        isLoading = true
        errorMessage = null
        val decoded = withContext(Dispatchers.IO) {
            decodeSafeImageBitmap(context, request)
        }
        bitmap = decoded
        isLoading = false
        if (decoded == null) {
            errorMessage = "Unable to decode this image file."
        }
    }

    DisposableEffect(bitmap) {
        val current = bitmap
        onDispose {
            if (current != null && !current.isRecycled) {
                current.recycle()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Image Canvas with Pinch-Zoom & Pan
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(request.source) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        scale = newScale
                        offset = if (newScale > 1f) {
                            Offset(offset.x + pan.x, offset.y + pan.y)
                        } else {
                            Offset.Zero
                        }
                    }
                }
                .pointerInput(request.source) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1.2f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2.5f
                            }
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val img = bitmap
            when {
                isLoading -> {
                    CircularProgressIndicator(color = Color.White)
                }
                img != null && !img.isRecycled -> {
                    Image(
                        bitmap = img.asImageBitmap(),
                        contentDescription = request.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                                rotationZ = rotationDegrees
                            }
                            .testTag("image_viewer_canvas")
                    )
                }
                else -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Text(
                            text = errorMessage ?: "Image could not be loaded.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                        Button(onClick = onDismiss) {
                            Text("Close")
                        }
                    }
                }
            }
        }

        // Top Bar
        Surface(
            color = Color.Black.copy(alpha = 0.72f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = request.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Velo Image Viewer • Pinch or double-tap to zoom",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
                IconButton(
                    onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f },
                    modifier = Modifier.size(48.dp).testTag("image_viewer_rotate_button")
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Rotate image", tint = Color.White)
                }
                IconButton(
                    onClick = {
                        scale = if (scale > 1.2f) 1f else 2.2f
                        if (scale == 1f) offset = Offset.Zero
                    },
                    modifier = Modifier.size(48.dp).testTag("image_viewer_zoom_button")
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Toggle zoom", tint = Color.White)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp).testTag("close_image_viewer_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close image viewer", tint = Color.White)
                }
            }
        }

        // Bottom Quick Actions Bar
        Surface(
            color = Color.Black.copy(alpha = 0.78f),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pdfUri = remember(request.source, request.isContentUri) {
                    if (request.isContentUri || request.source.startsWith("file://")) {
                        request.source.toUri()
                    } else {
                        Uri.fromFile(File(request.source))
                    }
                }
                Button(
                    onClick = { onConvertToPdf(pdfUri, request.title) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("image_viewer_to_pdf_button")
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save as PDF")
                }
                OutlinedButton(
                    onClick = { onHideInSecretVault(request) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(48.dp).testTag("image_viewer_hide_vault_button")
                ) {
                    Icon(
                        Icons.Default.EnhancedEncryption,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Hide in Vault", color = Color.White)
                }
            }
        }
    }
}

private fun decodeSafeImageBitmap(
    context: android.content.Context,
    request: ImageViewerRequest,
    maxDimension: Int = 2400
): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openImageStream(context, request)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        openImageStream(context, request)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Exception) {
        null
    }
}

private fun openImageStream(
    context: android.content.Context,
    request: ImageViewerRequest
) = if (request.isContentUri || request.source.startsWith("content://") || request.source.startsWith("file://")) {
    context.contentResolver.openInputStream(request.source.toUri())
} else {
    val f = File(request.source)
    if (f.exists()) FileInputStream(f) else null
}
