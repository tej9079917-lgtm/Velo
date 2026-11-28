package com.example.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.webkit.WebView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withScale
import androidx.core.graphics.withTranslation
import com.example.browser.PdfDocumentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Native Android PDF Viewer & Converter Engine powered by PdfRenderer & PdfDocument.
 * Supports:
 * - High-DPI PDF page rendering + Night/Invert mode
 * - Webpage to PDF capture
 * - Multi-Image to PDF conversion (zero-permission Photo Picker URIs)
 * - Plain Text / Notes to paginated A4 PDF conversion
 * - PDF pages to PNG image extraction
 * - Zero-permission SAF import & export
 */
class PdfEngine(private val context: Context) {

    private val pdfDir = File(context.filesDir, "velo_pdfs").apply { mkdirs() }
    private val exportedImagesDir = File(context.filesDir, "velo_pdf_images").apply { mkdirs() }

    suspend fun listSavedPdfs(): List<PdfDocumentItem> = withContext(Dispatchers.IO) {
        val files = pdfDir.listFiles { f -> f.isFile && f.name.endsWith(".pdf", ignoreCase = true) }
            ?: return@withContext emptyList()
        files.sortedByDescending { it.lastModified() }.mapNotNull { file ->
            val pages = getPageCountInternal(file)
            if (pages <= 0) return@mapNotNull null
            PdfDocumentItem(
                id = file.nameWithoutExtension,
                title = extractFriendlyTitle(file.name),
                filePath = file.canonicalPath,
                pageCount = pages,
                byteSize = file.length(),
                createdAtMillis = file.lastModified()
            )
        }
    }

    suspend fun getPageCount(filePath: String): Int = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !isAllowedReadablePdf(file)) return@withContext 0
        getPageCountInternal(file)
    }

    suspend fun renderPdfPage(
        filePath: String,
        pageIndex: Int,
        scale: Float = 1.5f,
        invertColors: Boolean = false
    ): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(filePath)
        if (!file.exists() || !isAllowedReadablePdf(file)) return@withContext null
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    if (pageIndex !in 0 until renderer.pageCount) return@withContext null
                    renderer.openPage(pageIndex).use { page ->
                        val safeScale = scale.coerceIn(0.6f, 2.8f)
                        val targetW = (page.width * safeScale).toInt().coerceIn(360, 2200)
                        val targetH = (page.height * safeScale).toInt().coerceIn(480, 3200)
                        val bitmap = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                        if (invertColors) {
                            val inverted = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            val invCanvas = Canvas(inverted)
                            val paint = Paint().apply {
                                colorFilter = ColorMatrixColorFilter(
                                    ColorMatrix(
                                        floatArrayOf(
                                            -1f, 0f, 0f, 0f, 255f,
                                            0f, -1f, 0f, 0f, 255f,
                                            0f, 0f, -1f, 0f, 255f,
                                            0f, 0f, 0f, 1f, 0f
                                        )
                                    )
                                )
                            }
                            invCanvas.drawBitmap(bitmap, 0f, 0f, paint)
                            bitmap.recycle()
                            inverted
                        } else {
                            bitmap
                        }
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importPdfFromUri(uri: Uri): Result<PdfDocumentItem> = withContext(Dispatchers.IO) {
        try {
            val rawName = resolveDisplayName(uri) ?: "Imported Document"
            val cleanTitle = rawName.removeSuffix(".pdf").removeSuffix(".PDF").trim().ifBlank { "Document" }
            val outFile = createConfinedPdfFile(cleanTitle)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                    output.flush()
                }
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read selected PDF."))

            val pageCount = getPageCountInternal(outFile)
            if (pageCount <= 0) {
                outFile.delete()
                return@withContext Result.failure(IllegalArgumentException("Selected file is not a valid PDF document."))
            }

            Result.success(
                PdfDocumentItem(
                    id = outFile.nameWithoutExtension,
                    title = cleanTitle,
                    filePath = outFile.canonicalPath,
                    pageCount = pageCount,
                    byteSize = outFile.length(),
                    createdAtMillis = outFile.lastModified()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun convertImagesToPdf(imageUris: List<Uri>, title: String): Result<PdfDocumentItem> =
        withContext(Dispatchers.IO) {
            if (imageUris.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Select at least one image."))
            }
            val cleanTitle = title.trim().ifBlank { "Images Bundle" }
            val pdfDoc = PdfDocument()
            var pagesWritten = 0
            try {
                imageUris.take(30).forEach { uri ->
                    val bitmap = decodeScaledBitmap(uri, maxDimension = 1600) ?: return@forEach
                    try {
                        val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pagesWritten + 1).create()
                        val page = pdfDoc.startPage(pageInfo)
                        val canvas = page.canvas
                        canvas.drawColor(Color.WHITE)

                        val margin = 24f
                        val availW = A4_WIDTH - margin * 2f
                        val availH = A4_HEIGHT - margin * 2f
                        val ratio = min(availW / bitmap.width.toFloat(), availH / bitmap.height.toFloat())
                        val drawW = bitmap.width * ratio
                        val drawH = bitmap.height * ratio
                        val left = (A4_WIDTH - drawW) / 2f
                        val top = (A4_HEIGHT - drawH) / 2f
                        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), Paint(Paint.FILTER_BITMAP_FLAG))

                        pdfDoc.finishPage(page)
                        pagesWritten++
                    } finally {
                        bitmap.recycle()
                    }
                }
                if (pagesWritten == 0) {
                    return@withContext Result.failure(IllegalStateException("Could not decode any of the selected images."))
                }

                val outFile = createConfinedPdfFile(cleanTitle)
                FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
                Result.success(
                    PdfDocumentItem(
                        id = outFile.nameWithoutExtension,
                        title = cleanTitle,
                        filePath = outFile.canonicalPath,
                        pageCount = pagesWritten,
                        byteSize = outFile.length(),
                        createdAtMillis = outFile.lastModified()
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pdfDoc.close()
            }
        }

    suspend fun convertTextToPdf(title: String, bodyText: String): Result<PdfDocumentItem> =
        withContext(Dispatchers.IO) {
            val cleanBody = bodyText.trim()
            if (cleanBody.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Enter text content to convert to PDF."))
            }
            val cleanTitle = title.trim().ifBlank { "Velo Note" }
            val pdfDoc = PdfDocument()
            try {
                val margin = 42
                val contentWidth = A4_WIDTH - margin * 2
                val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(15, 23, 42)
                    textSize = 18f
                    isFakeBoldText = true
                }
                val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(30, 41, 59)
                    textSize = 11.5f
                }
                val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.rgb(100, 116, 139)
                    textSize = 9f
                }
                val dividerPaint = Paint().apply {
                    color = Color.rgb(203, 213, 225)
                    strokeWidth = 1f
                }

                val fullLayout = StaticLayout.Builder
                    .obtain(cleanBody, 0, cleanBody.length, bodyPaint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(4f, 1.15f)
                    .build()

                val totalLines = fullLayout.lineCount
                var currentLine = 0
                var pageNumber = 1

                while (currentLine < totalLines && pageNumber <= 40) {
                    val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, pageNumber).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)

                    var topY = margin.toFloat()
                    if (pageNumber == 1) {
                        canvas.drawText(cleanTitle.take(64), margin.toFloat(), topY + 18f, titlePaint)
                        topY += 28f
                        canvas.drawLine(margin.toFloat(), topY, (A4_WIDTH - margin).toFloat(), topY, dividerPaint)
                        topY += 16f
                    }

                    val bottomLimit = A4_HEIGHT - margin - 24f
                    val availHeight = bottomLimit - topY
                    val startLineTop = fullLayout.getLineTop(currentLine)

                    var endLine = currentLine
                    while (endLine < totalLines) {
                        val lineBottom = fullLayout.getLineBottom(endLine) - startLineTop
                        if (lineBottom > availHeight && endLine > currentLine) break
                        endLine++
                    }

                    val startOffset = fullLayout.getLineStart(currentLine)
                    val endOffset = fullLayout.getLineEnd(endLine - 1)
                    val pageText = cleanBody.substring(startOffset, endOffset)
                    val pageLayout = StaticLayout.Builder
                        .obtain(pageText, 0, pageText.length, bodyPaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(4f, 1.15f)
                        .build()

                    canvas.withTranslation(x = margin.toFloat(), y = topY) {
                        pageLayout.draw(this)
                    }

                    canvas.drawText(
                        "Velo Browser • Page $pageNumber",
                        margin.toFloat(),
                        (A4_HEIGHT - 22).toFloat(),
                        footerPaint
                    )
                    pdfDoc.finishPage(page)
                    currentLine = endLine
                    pageNumber++
                }

                val outFile = createConfinedPdfFile(cleanTitle)
                FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
                Result.success(
                    PdfDocumentItem(
                        id = outFile.nameWithoutExtension,
                        title = cleanTitle,
                        filePath = outFile.canonicalPath,
                        pageCount = max(1, pageNumber - 1),
                        byteSize = outFile.length(),
                        createdAtMillis = outFile.lastModified()
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                pdfDoc.close()
            }
        }

    /**
     * Captures the currently rendered WebView into a multi-page A4 PDF document.
     * Drawing happens safely on Main thread, file writing happens on IO thread.
     */
    suspend fun convertWebViewToPdf(webView: WebView, title: String): Result<PdfDocumentItem> {
        val cleanTitle = title.trim().ifBlank { "Webpage Snapshot" }
        val pdfDoc = PdfDocument()
        return try {
            val pagesCount = withContext(Dispatchers.Main) {
                val viewW = webView.width.coerceAtLeast(360)
                val contentH = (webView.contentHeight * webView.resources.displayMetrics.density)
                    .toInt()
                    .coerceAtLeast(webView.height.coerceAtLeast(640))
                val scaleToA4 = A4_WIDTH.toFloat() / viewW.toFloat()
                val sliceHeightPx = (A4_HEIGHT / scaleToA4).toInt().coerceAtLeast(400)
                val totalPages = ceil(contentH.toDouble() / sliceHeightPx.toDouble()).toInt().coerceIn(1, 10)
                val originalScrollY = webView.scrollY

                for (p in 0 until totalPages) {
                    val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH, A4_HEIGHT, p + 1).create()
                    val page = pdfDoc.startPage(pageInfo)
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    val scrollOffset = p * sliceHeightPx
                    canvas.withScale(x = scaleToA4, y = scaleToA4) {
                        translate(0f, -scrollOffset.toFloat())
                        webView.draw(this)
                    }
                    pdfDoc.finishPage(page)
                }
                webView.scrollTo(webView.scrollX, originalScrollY)
                totalPages
            }

            withContext(Dispatchers.IO) {
                val outFile = createConfinedPdfFile(cleanTitle)
                FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
                Result.success(
                    PdfDocumentItem(
                        id = outFile.nameWithoutExtension,
                        title = cleanTitle,
                        filePath = outFile.canonicalPath,
                        pageCount = pagesCount,
                        byteSize = outFile.length(),
                        createdAtMillis = outFile.lastModified()
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDoc.close()
        }
    }

    suspend fun convertPdfToPngImages(pdfFilePath: String, maxPages: Int = 15): Result<List<File>> =
        withContext(Dispatchers.IO) {
            val file = File(pdfFilePath)
            if (!file.exists() || !isAllowedReadablePdf(file)) {
                return@withContext Result.failure(IllegalArgumentException("PDF file not found."))
            }
            val outputFiles = mutableListOf<File>()
            try {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                    PdfRenderer(pfd).use { renderer ->
                        val count = min(renderer.pageCount, maxPages)
                        val prefix = "img_${UUID.randomUUID().toString().take(8)}"
                        for (i in 0 until count) {
                            renderer.openPage(i).use { page ->
                                val w = (page.width * 2f).toInt().coerceIn(595, 1800)
                                val h = (page.height * 2f).toInt().coerceIn(842, 2600)
                                val bmp = createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                try {
                                    Canvas(bmp).drawColor(Color.WHITE)
                                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    val pngFile = File(exportedImagesDir, "${prefix}_page_${i + 1}.png")
                                    FileOutputStream(pngFile).use { out ->
                                        bmp.compress(Bitmap.CompressFormat.PNG, 95, out)
                                        out.flush()
                                    }
                                    outputFiles.add(pngFile)
                                } finally {
                                    bmp.recycle()
                                }
                            }
                        }
                    }
                }
                Result.success(outputFiles)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun exportPdfToUri(filePath: String, destinationUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            val source = File(filePath)
            if (!source.exists() || !isAllowedReadablePdf(source)) {
                return@withContext Result.failure(IllegalArgumentException("PDF file missing."))
            }
            try {
                FileInputStream(source).use { input ->
                    context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                        input.copyTo(output, bufferSize = 16 * 1024)
                        output.flush()
                    } ?: return@withContext Result.failure(IllegalStateException("Could not open destination URI."))
                }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun deletePdf(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            val baseCanonical = pdfDir.canonicalPath + File.separator
            if (file.canonicalPath.startsWith(baseCanonical) && file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getPageCountInternal(file: File): Int {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { it.pageCount }
            }
        } catch (_: Exception) {
            0
        }
    }

    private fun decodeScaledBitmap(uri: Uri, maxDimension: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun createConfinedPdfFile(title: String): File {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9 _-]"), "").trim().replace(" ", "_").take(36)
            .ifBlank { "Document" }
        val suffix = UUID.randomUUID().toString().take(8)
        val file = File(pdfDir, "${safeTitle}__${suffix}.pdf")
        val baseCanonical = pdfDir.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(baseCanonical)) { "Invalid PDF output path." }
        return file
    }

    private fun extractFriendlyTitle(fileName: String): String {
        val base = fileName.removeSuffix(".pdf").removeSuffix(".PDF")
        val clean = if (base.contains("__")) base.substringBeforeLast("__") else base
        return clean.replace("_", " ").trim().ifBlank { "PDF Document" }
    }

    private fun isAllowedReadablePdf(file: File): Boolean {
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            return false
        }
        val pdfBase = pdfDir.canonicalPath + File.separator
        val previewBase = File(context.cacheDir, "vault_preview").canonicalPath + File.separator
        return canonical.startsWith(pdfBase) || canonical.startsWith(previewBase)
    }

    companion object {
        private const val A4_WIDTH = 595
        private const val A4_HEIGHT = 842
    }
}
