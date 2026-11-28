package com.example.media

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.example.browser.ImageDownloadFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class DownloadedVideoResult(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val localPath: String,
    val mimeType: String,
    val formatLabel: String,
    val qualityLabel: String,
    val byteSize: Long,
    val savedToPublicDownloads: Boolean = false
)

/**
 * Universal Streaming HTTP/HTTPS File & Video Downloader with:
 * - WebView Cookie (URL + Referer fallback), Origin, User-Agent, and Referer forwarding
 * - Full HLS (.m3u8) master highest-bandwidth variant selection, fMP4 (#EXT-X-MAP), and AES-128 key decryption
 * - Direct streaming for MP4, WebM, MKV, MOV, PDF, Images, Audio, ZIP, RAR, 7Z, APK, Office Docs & eBooks
 * - Inline Data URI & Blob Base64 download support
 * - Reliable mirroring to Android's public Downloads/Velo folder:
 *   * API 29+ via MediaStore.Downloads with automatic MIME/path fallback (zero permissions required)
 *   * API <= 28 via public Downloads/Velo + MediaScannerConnection when WRITE_EXTERNAL_STORAGE is granted
 * - Strict app-private path confinement
 */
class VideoDownloadEngine(private val context: Context) {

    private val downloadsDir = File(context.filesDir, "velo_downloads").apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val activeCallRef = AtomicReference<Call?>(null)

    fun cancelActiveDownload() {
        activeCallRef.getAndSet(null)?.cancel()
    }

    suspend fun downloadVideo(
        rawUrl: String,
        title: String,
        qualityLabel: String,
        referer: String? = null,
        userAgent: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedVideoResult> = downloadAnyFile(
        rawUrl = rawUrl,
        suggestedTitle = title,
        qualityLabel = qualityLabel,
        contentDisposition = "",
        hintMimeType = "",
        referer = referer,
        userAgent = userAgent,
        onProgress = onProgress
    )

    suspend fun downloadAnyFile(
        rawUrl: String,
        suggestedTitle: String = "",
        qualityLabel: String = "File",
        contentDisposition: String = "",
        hintMimeType: String = "",
        referer: String? = null,
        userAgent: String? = null,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedVideoResult> = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val uri = try {
            cleanUrl.toUri()
        } catch (_: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid download URL."))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return@withContext Result.failure(IllegalArgumentException("Only HTTP/HTTPS downloads are supported."))
        }
        if (isRestrictedVideoHost(cleanUrl)) {
            return@withContext Result.failure(
                IllegalStateException(
                    "YouTube restricts third-party video downloads. Use Velo Downloader for direct files, documents, images, and open web streams."
                )
            )
        }

        val id = "dl_${UUID.randomUUID().toString().take(10)}"
        val isHls = cleanUrl.substringBefore('?').substringBefore('#').lowercase().endsWith(".m3u8") ||
            hintMimeType.lowercase().contains("mpegurl")

        try {
            if (isHls) {
                downloadHlsStream(
                    id = id,
                    playlistUrl = cleanUrl,
                    title = suggestedTitle,
                    qualityLabel = qualityLabel,
                    referer = referer,
                    userAgent = userAgent,
                    onProgress = onProgress
                )
            } else {
                downloadDirectStream(
                    id = id,
                    url = cleanUrl,
                    suggestedTitle = suggestedTitle,
                    qualityLabel = qualityLabel,
                    contentDisposition = contentDisposition,
                    hintMimeType = hintMimeType,
                    referer = referer,
                    userAgent = userAgent,
                    onProgress = onProgress
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCallRef.set(null)
        }
    }

    suspend fun saveInlineBase64File(
        suggestedName: String,
        mimeType: String,
        base64Payload: String
    ): Result<DownloadedVideoResult> = withContext(Dispatchers.IO) {
        try {
            val cleanBase64 = base64Payload.substringAfter(",", base64Payload).trim()
            if (cleanBase64.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Empty inline file payload."))
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (bytes.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Decoded file is empty."))
            }
            if (bytes.size > MAX_INLINE_BYTES) {
                return@withContext Result.failure(IllegalStateException("Inline file exceeds 30 MB limit."))
            }
            val id = "dl_${UUID.randomUUID().toString().take(10)}"
            val (formatLabel, resolvedMime, ext) = inferFormatAndMime(suggestedName, mimeType)
            val safeFile = createConfinedFile("${id}.$ext")
            FileOutputStream(safeFile).use { out ->
                out.write(bytes)
                out.flush()
            }
            val displayTitle = suggestedName
                .substringBeforeLast('.')
                .trim()
                .ifBlank { "Velo_Download_${id.takeLast(4)}" }
            val publicFileName = sanitizePublicFileName("$displayTitle.$ext")
            val mirrored = saveFileToPublicDownloads(safeFile, publicFileName, resolvedMime)

            Result.success(
                DownloadedVideoResult(
                    id = id,
                    title = displayTitle,
                    sourceUrl = "inline://download",
                    localPath = safeFile.canonicalPath,
                    mimeType = resolvedMime,
                    formatLabel = formatLabel,
                    qualityLabel = "Saved",
                    byteSize = bytes.size.toLong(),
                    savedToPublicDownloads = mirrored
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Downloads any webpage image (HTTP/HTTPS or inline data:image/) in FULL SIZE
     * and converts/exports it to the user's selected format (Original, PNG, JPEG, WEBP, or PDF).
     */
    suspend fun downloadImageInFormat(
        rawUrl: String,
        title: String,
        targetFormat: ImageDownloadFormat,
        referer: String? = null,
        userAgent: String? = null
    ): Result<DownloadedVideoResult> = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val id = "img_${UUID.randomUUID().toString().take(10)}"
        val friendlyTitle = title.trim().ifBlank { deriveTitleFromUrl(cleanUrl) }

        val rawBytes: ByteArray
        val sourceMime: String
        try {
            if (cleanUrl.startsWith("data:", ignoreCase = true)) {
                val header = cleanUrl.substringAfter("data:").substringBefore(",", "")
                sourceMime = header.substringBefore(";").ifBlank { "image/png" }
                val payload = cleanUrl.substringAfter(",", "")
                rawBytes = Base64.decode(payload, Base64.DEFAULT)
            } else {
                val req = buildAuthenticatedRequest(cleanUrl, referer, userAgent)
                val call = httpClient.newCall(req)
                activeCallRef.set(call)
                val fetched = call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("Image download failed (HTTP ${resp.code})."))
                    }
                    val ct = resp.header("Content-Type").orEmpty().substringBefore(';').trim()
                    val bytes = resp.body?.bytes()
                        ?: return@withContext Result.failure(IllegalStateException("Empty image response."))
                    if (bytes.size > MAX_INLINE_BYTES * 2) {
                        return@withContext Result.failure(IllegalStateException("Image exceeds 60 MB limit."))
                    }
                    bytes to ct
                }
                rawBytes = fetched.first
                sourceMime = fetched.second
            }
            if (rawBytes.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("Downloaded image was empty."))
            }

            if (targetFormat == ImageDownloadFormat.ORIGINAL) {
                val (fmtLabel, resolvedMime, ext) = inferFormatAndMime(cleanUrl, sourceMime)
                val safeFile = createConfinedFile("$id.$ext")
                FileOutputStream(safeFile).use { out ->
                    out.write(rawBytes)
                    out.flush()
                }
                val pubName = sanitizePublicFileName("$friendlyTitle.$ext")
                val mirrored = saveFileToPublicDownloads(safeFile, pubName, resolvedMime)
                return@withContext Result.success(
                    DownloadedVideoResult(
                        id = id,
                        title = friendlyTitle,
                        sourceUrl = cleanUrl,
                        localPath = safeFile.canonicalPath,
                        mimeType = resolvedMime,
                        formatLabel = fmtLabel,
                        qualityLabel = "Full Size",
                        byteSize = rawBytes.size.toLong(),
                        savedToPublicDownloads = mirrored
                    )
                )
            }

            // Decode full-size image (inScaled = false; sample only if > 4096px to prevent OOM)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(IllegalStateException("Unsupported image format for conversion."))
            }
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 4096 || bounds.outHeight / sampleSize > 4096) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inScaled = false
            }
            val bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return@withContext Result.failure(IllegalStateException("Could not decode image."))

            val resolutionTag = "${bitmap.width}×${bitmap.height}"
            val safeFile = createConfinedFile("$id.${targetFormat.ext}")
            try {
                if (targetFormat == ImageDownloadFormat.PDF) {
                    val pdfDoc = PdfDocument()
                    try {
                        val pageW = bitmap.width.coerceIn(595, 2400)
                        val pageH = (bitmap.height.toFloat() * (pageW.toFloat() / bitmap.width.toFloat()))
                            .toInt()
                            .coerceIn(400, 3200)
                        val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, 1).create()
                        val page = pdfDoc.startPage(pageInfo)
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.drawBitmap(
                            bitmap,
                            null,
                            RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()),
                            Paint(Paint.FILTER_BITMAP_FLAG)
                        )
                        pdfDoc.finishPage(page)
                        FileOutputStream(safeFile).use { out ->
                            pdfDoc.writeTo(out)
                            out.flush()
                        }
                    } finally {
                        pdfDoc.close()
                    }
                } else {
                    val compressFormat = when (targetFormat) {
                        ImageDownloadFormat.PNG -> Bitmap.CompressFormat.PNG
                        ImageDownloadFormat.JPEG -> Bitmap.CompressFormat.JPEG
                        ImageDownloadFormat.WEBP -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                Bitmap.CompressFormat.WEBP_LOSSLESS
                            } else {
                                @Suppress("DEPRECATION")
                                Bitmap.CompressFormat.WEBP
                            }
                        }
                        else -> Bitmap.CompressFormat.PNG
                    }
                    FileOutputStream(safeFile).use { out ->
                        bitmap.compress(compressFormat, 100, out)
                        out.flush()
                    }
                }
            } finally {
                bitmap.recycle()
            }

            val pubName = sanitizePublicFileName("$friendlyTitle.${targetFormat.ext}")
            val mirrored = saveFileToPublicDownloads(safeFile, pubName, targetFormat.mimeType)
            Result.success(
                DownloadedVideoResult(
                    id = id,
                    title = friendlyTitle,
                    sourceUrl = cleanUrl,
                    localPath = safeFile.canonicalPath,
                    mimeType = targetFormat.mimeType,
                    formatLabel = targetFormat.label,
                    qualityLabel = "Full $resolutionTag",
                    byteSize = safeFile.length(),
                    savedToPublicDownloads = mirrored
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCallRef.set(null)
        }
    }

    private suspend fun downloadDirectStream(
        id: String,
        url: String,
        suggestedTitle: String,
        qualityLabel: String,
        contentDisposition: String,
        hintMimeType: String,
        referer: String?,
        userAgent: String?,
        onProgress: (Long, Long) -> Unit
    ): Result<DownloadedVideoResult> {
        val request = buildAuthenticatedRequest(url, referer, userAgent)
        val call = httpClient.newCall(request)
        activeCallRef.set(call)

        var targetFile: File? = null
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Download failed (HTTP ${response.code})."))
                }
                val body = response.body ?: return Result.failure(IllegalStateException("Empty download response."))
                val respContentType = response.header("Content-Type").orEmpty().substringBefore(';').trim()
                val respDisposition = response.header("Content-Disposition").orEmpty().ifBlank { contentDisposition }
                val effectiveMime = respContentType.ifBlank { hintMimeType }

                // If server returned an HLS playlist even without .m3u8 in URL, redirect to HLS assembler
                if (effectiveMime.lowercase().contains("mpegurl")) {
                    val playlistBody = body.string()
                    return assembleHlsFromText(
                        id = id,
                        playlistUrl = response.request.url.toString(),
                        initialText = playlistBody,
                        title = suggestedTitle,
                        qualityLabel = qualityLabel,
                        referer = referer,
                        userAgent = userAgent,
                        onProgress = onProgress
                    )
                }

                val dispName = extractFileNameFromDisposition(respDisposition)
                val guessedName = dispName?.takeIf { it.isNotBlank() } ?: try {
                    URLUtil.guessFileName(response.request.url.toString(), respDisposition, effectiveMime)
                } catch (_: Exception) {
                    ""
                }
                val (formatLabel, mimeType, ext) = inferFormatAndMime(
                    urlOrName = if (guessedName.contains(".")) guessedName else response.request.url.toString(),
                    contentType = effectiveMime
                )
                val totalBytes = body.contentLength()

                val safeFile = createConfinedFile("${id}.$ext")
                targetFile = safeFile

                var downloaded = 0L
                var lastEmit = 0L
                val buffer = ByteArray(16 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(safeFile).use { output ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded > MAX_DOWNLOAD_BYTES) {
                                throw IllegalStateException("File exceeds 500 MB safety limit.")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 120L) {
                                lastEmit = now
                                onProgress(downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(downloaded, if (totalBytes > 0L) totalBytes else downloaded)

                if (downloaded == 0L) {
                    safeFile.delete()
                    return Result.failure(IllegalStateException("Downloaded file was empty."))
                }

                val friendlyTitle = suggestedTitle.trim().ifBlank {
                    guessedName.substringBeforeLast('.').trim().ifBlank { deriveTitleFromUrl(url) }
                }
                val publicFileName = sanitizePublicFileName("$friendlyTitle.$ext")
                val mirrored = saveFileToPublicDownloads(safeFile, publicFileName, mimeType)

                Result.success(
                    DownloadedVideoResult(
                        id = id,
                        title = friendlyTitle,
                        sourceUrl = url,
                        localPath = safeFile.canonicalPath,
                        mimeType = mimeType,
                        formatLabel = formatLabel,
                        qualityLabel = qualityLabel.ifBlank { formatLabel },
                        byteSize = downloaded,
                        savedToPublicDownloads = mirrored
                    )
                )
            }
        } catch (e: Exception) {
            targetFile?.delete()
            Result.failure(e)
        }
    }

    private suspend fun downloadHlsStream(
        id: String,
        playlistUrl: String,
        title: String,
        qualityLabel: String,
        referer: String?,
        userAgent: String?,
        onProgress: (Long, Long) -> Unit
    ): Result<DownloadedVideoResult> {
        val text = fetchText(playlistUrl, referer, userAgent)
            ?: return Result.failure(IllegalStateException("Could not load HLS playlist."))
        return assembleHlsFromText(id, playlistUrl, text, title, qualityLabel, referer, userAgent, onProgress)
    }

    private suspend fun assembleHlsFromText(
        id: String,
        playlistUrl: String,
        initialText: String,
        title: String,
        qualityLabel: String,
        referer: String?,
        userAgent: String?,
        onProgress: (Long, Long) -> Unit
    ): Result<DownloadedVideoResult> {
        val lines = initialText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Master playlist -> choose highest-bandwidth variant
        val mediaPlaylistUrl = if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val bestVariant = selectHighestBandwidthVariant(lines)
                ?: return Result.failure(IllegalStateException("No playable stream found in HLS master playlist."))
            resolveRelativeUrl(playlistUrl, bestVariant)
        } else {
            playlistUrl
        }

        val mediaText = if (mediaPlaylistUrl == playlistUrl) initialText else (
            fetchText(mediaPlaylistUrl, referer ?: playlistUrl, userAgent)
                ?: return Result.failure(IllegalStateException("Could not load HLS media playlist."))
            )

        val mediaLines = mediaText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var mediaSequence = 0L
        var mapInitUrl: String? = null
        var keyMethod = "NONE"
        var keyUri: String? = null
        var keyIvHex: String? = null

        val segmentUrls = mutableListOf<String>()
        for (line in mediaLines) {
            when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull() ?: 0L
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val uriAttr = extractQuotedAttr(line, "URI")
                    if (!uriAttr.isNullOrBlank()) {
                        mapInitUrl = resolveRelativeUrl(mediaPlaylistUrl, uriAttr)
                    }
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val attrs = line.substringAfter(':')
                    keyMethod = Regex("METHOD=([^,\\s]+)").find(attrs)?.groupValues?.getOrNull(1)?.uppercase() ?: "NONE"
                    val uriAttr = extractQuotedAttr(attrs, "URI")
                    if (!uriAttr.isNullOrBlank()) {
                        keyUri = resolveRelativeUrl(mediaPlaylistUrl, uriAttr)
                    }
                    keyIvHex = Regex("IV=0[xX]([0-9a-fA-F]+)").find(attrs)?.groupValues?.getOrNull(1)
                }
                !line.startsWith("#") -> {
                    segmentUrls.add(resolveRelativeUrl(mediaPlaylistUrl, line))
                    if (segmentUrls.size >= MAX_HLS_SEGMENTS) break
                }
            }
        }

        if (segmentUrls.isEmpty()) {
            return Result.failure(IllegalStateException("HLS playlist has no downloadable media segments."))
        }
        if (keyMethod != "NONE" && keyMethod != "AES-128") {
            return Result.failure(IllegalStateException("Stream uses DRM ($keyMethod) and cannot be downloaded."))
        }

        val aesKeyBytes: ByteArray? = if (keyMethod == "AES-128" && !keyUri.isNullOrBlank()) {
            fetchBytes(keyUri, referer ?: mediaPlaylistUrl, userAgent)
                ?.takeIf { it.size == 16 }
                ?: return Result.failure(IllegalStateException("Could not fetch HLS decryption key."))
        } else {
            null
        }

        val isFmp4 = mapInitUrl != null || segmentUrls.first().substringBefore('?').lowercase().endsWith(".m4s")
        val ext = if (isFmp4) "mp4" else "ts"
        val mimeType = if (isFmp4) "video/mp4" else "video/mp2t"
        val formatLabel = if (isFmp4) "HLS/MP4" else "HLS/TS"

        val safeFile = createConfinedFile("${id}.$ext")
        var totalDownloaded = 0L
        return try {
            FileOutputStream(safeFile).use { output ->
                // Write fMP4 init segment first when present
                if (mapInitUrl != null) {
                    val initBytes = fetchBytes(mapInitUrl, referer ?: mediaPlaylistUrl, userAgent)
                    if (initBytes != null && initBytes.isNotEmpty()) {
                        output.write(initBytes)
                        totalDownloaded += initBytes.size
                    }
                }

                segmentUrls.forEachIndexed { index, segUrl ->
                    currentCoroutineContext().ensureActive()
                    val req = buildAuthenticatedRequest(segUrl, referer ?: mediaPlaylistUrl, userAgent)
                    val call = httpClient.newCall(req)
                    activeCallRef.set(call)
                    call.execute().use { resp ->
                        if (resp.isSuccessful) {
                            val rawBytes = resp.body?.bytes()
                            if (rawBytes != null && rawBytes.isNotEmpty()) {
                                val data = if (aesKeyBytes != null) {
                                    decryptAes128Segment(rawBytes, aesKeyBytes, keyIvHex, mediaSequence + index)
                                } else {
                                    rawBytes
                                }
                                output.write(data)
                                totalDownloaded += data.size
                                if (totalDownloaded > MAX_DOWNLOAD_BYTES) {
                                    throw IllegalStateException("HLS download reached 500 MB limit.")
                                }
                            }
                        }
                    }
                    val estimatedTotal = if (index > 0) {
                        (totalDownloaded.toDouble() / (index + 1) * segmentUrls.size).toLong()
                    } else -1L
                    onProgress(totalDownloaded, estimatedTotal)
                }
                output.flush()
            }

            if (totalDownloaded == 0L) {
                safeFile.delete()
                return Result.failure(IllegalStateException("HLS stream produced no playable bytes."))
            }

            val friendlyTitle = title.trim().ifBlank { deriveTitleFromUrl(playlistUrl) }
            val mirrored = saveFileToPublicDownloads(
                sourceFile = safeFile,
                publicFileName = sanitizePublicFileName("$friendlyTitle.$ext"),
                mimeType = mimeType
            )

            Result.success(
                DownloadedVideoResult(
                    id = id,
                    title = friendlyTitle,
                    sourceUrl = playlistUrl,
                    localPath = safeFile.canonicalPath,
                    mimeType = mimeType,
                    formatLabel = formatLabel,
                    qualityLabel = qualityLabel.ifBlank { "Stream" },
                    byteSize = totalDownloaded,
                    savedToPublicDownloads = mirrored
                )
            )
        } catch (e: Exception) {
            safeFile.delete()
            Result.failure(e)
        }
    }

    private fun selectHighestBandwidthVariant(lines: List<String>): String? {
        var bestUrl: String? = null
        var bestBandwidth = -1L
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                var nextIdx = i + 1
                while (nextIdx < lines.size && lines[nextIdx].startsWith("#")) {
                    nextIdx++
                }
                if (nextIdx < lines.size) {
                    val candidate = lines[nextIdx]
                    if (bw >= bestBandwidth) {
                        bestBandwidth = bw
                        bestUrl = candidate
                    }
                }
                i = nextIdx + 1
            } else {
                i++
            }
        }
        return bestUrl ?: lines.firstOrNull { !it.startsWith("#") }
    }

    private fun extractQuotedAttr(line: String, key: String): String? {
        val match = Regex("""$key="([^"]+)"""").find(line)
        return match?.groupValues?.getOrNull(1)
    }

    private fun decryptAes128Segment(
        encrypted: ByteArray,
        key: ByteArray,
        ivHex: String?,
        sequenceNumber: Long
    ): ByteArray {
        val ivBytes = if (!ivHex.isNullOrBlank()) {
            val padded = ivHex.padStart(32, '0').takeLast(32)
            ByteArray(16) { idx -> padded.substring(idx * 2, idx * 2 + 2).toInt(16).toByte() }
        } else {
            ByteBuffer.allocate(16).putLong(0L).putLong(sequenceNumber).array()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ivBytes))
        return cipher.doFinal(encrypted)
    }

    private fun buildAuthenticatedRequest(url: String, referer: String?, userAgent: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9")

        val cookies = try {
            val direct = CookieManager.getInstance().getCookie(url)
            if (!direct.isNullOrBlank()) {
                direct
            } else if (!referer.isNullOrBlank()) {
                CookieManager.getInstance().getCookie(referer)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
        if (!referer.isNullOrBlank() && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            builder.header("Referer", referer)
            try {
                val refUri = referer.toUri()
                val scheme = refUri.scheme
                val auth = refUri.authority
                if (!scheme.isNullOrBlank() && !auth.isNullOrBlank()) {
                    builder.header("Origin", "$scheme://$auth")
                }
            } catch (_: Exception) {
            }
        }
        return builder.build()
    }

    /**
     * Copies any downloaded or converted file into Android's public Downloads/Velo folder:
     * - API 29+: Uses MediaStore.Downloads with zero permissions, retrying with application/octet-stream
     *   and root Downloads fallback if an OEM MediaStore rejects a specific MIME type or subfolder.
     * - API <= 28: Writes directly to public Downloads/Velo when WRITE_EXTERNAL_STORAGE is granted
     *   and notifies MediaScannerConnection so Files & Gallery index it immediately.
     */
    fun saveFileToPublicDownloads(sourceFile: File, publicFileName: String, mimeType: String): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile) return false
        val safeName = sanitizePublicFileName(publicFileName)
        val resolvedMime = mimeType.ifBlank { "application/octet-stream" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val subPath = "${Environment.DIRECTORY_DOWNLOADS}/Velo/"
            insertAndCopyToMediaStore(sourceFile, safeName, resolvedMime, subPath) ||
                insertAndCopyToMediaStore(sourceFile, safeName, "application/octet-stream", subPath) ||
                insertAndCopyToMediaStore(sourceFile, safeName, "application/octet-stream", "${Environment.DIRECTORY_DOWNLOADS}/")
        } else {
            if (!hasPublicDownloadsAccess(context)) return false
            try {
                @Suppress("DEPRECATION")
                val publicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val veloDir = File(publicRoot, "Velo").apply { mkdirs() }
                val destDir = if (veloDir.exists() && veloDir.canWrite()) veloDir else publicRoot.apply { mkdirs() }
                val destFile = resolveUniqueFile(destDir, safeName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                        out.flush()
                    }
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(destFile.absolutePath),
                    arrayOf(resolvedMime),
                    null
                )
                destFile.exists() && destFile.length() > 0L
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun insertAndCopyToMediaStore(
        sourceFile: File,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return false
            try {
                val outStream = resolver.openOutputStream(uri, "w") ?: resolver.openOutputStream(uri)
                if (outStream == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                outStream.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                        out.flush()
                    }
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                true
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveUniqueFile(dir: File, fileName: String): File {
        var candidate = File(dir, fileName)
        if (!candidate.exists()) return candidate
        val base = fileName.substringBeforeLast('.', fileName)
        val ext = fileName.substringAfterLast('.', "")
        for (i in 1..999) {
            val name = if (ext.isNotBlank() && ext != fileName) "$base ($i).$ext" else "$base ($i)"
            candidate = File(dir, name)
            if (!candidate.exists()) return candidate
        }
        return File(dir, "${System.currentTimeMillis()}_$fileName")
    }

    private fun fetchText(url: String, referer: String?, userAgent: String?): String? {
        val req = buildAuthenticatedRequest(url, referer, userAgent)
        val call = httpClient.newCall(req)
        activeCallRef.set(call)
        return try {
            call.execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchBytes(url: String, referer: String?, userAgent: String?): ByteArray? {
        val req = buildAuthenticatedRequest(url, referer, userAgent)
        val call = httpClient.newCall(req)
        activeCallRef.set(call)
        return try {
            call.execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relative: String): String {
        if (relative.startsWith("http://", ignoreCase = true) || relative.startsWith("https://", ignoreCase = true)) {
            return relative
        }
        val baseUri = baseUrl.toUri()
        return if (relative.startsWith("/")) {
            "${baseUri.scheme}://${baseUri.authority}$relative"
        } else {
            val basePrefix = baseUrl.substringBefore('?').substringBeforeLast('/')
            "$basePrefix/$relative"
        }
    }

    fun deleteLocalFile(path: String): Boolean {
        return try {
            val file = File(path)
            val baseCanonical = downloadsDir.canonicalPath + File.separator
            if (file.canonicalPath.startsWith(baseCanonical) && file.exists()) {
                file.delete()
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Removes Velo's own mirrored copy from public Downloads/Velo when a user hides
     * a downloaded item inside the Secret Vault, so it no longer appears in Files or Gallery.
     */
    fun deletePublicDownloadsCopy(displayTitle: String) {
        val safePrefix = displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60)
        if (safePrefix.isBlank()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                context.contentResolver.delete(
                    collection,
                    "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
                    arrayOf("$safePrefix%", "%Downloads/Velo%")
                )
            } catch (_: Exception) {
            }
        } else if (hasPublicDownloadsAccess(context)) {
            try {
                @Suppress("DEPRECATION")
                val veloDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Velo")
                veloDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith(safePrefix, ignoreCase = true)) {
                        f.delete()
                        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun createConfinedFile(fileName: String): File {
        val safeName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val file = File(downloadsDir, safeName)
        val baseCanonical = downloadsDir.canonicalPath + File.separator
        require(file.canonicalPath.startsWith(baseCanonical)) { "Invalid download file path." }
        return file
    }

    private fun sanitizePublicFileName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(72)
        return cleaned.ifBlank { "velo_file_${System.currentTimeMillis() % 100000}.bin" }
    }

    private fun extractFileNameFromDisposition(disposition: String): String? {
        if (disposition.isBlank()) return null
        val utf8Match = Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;\r\n]+)""").find(disposition)
        if (utf8Match != null) {
            val encoded = utf8Match.groupValues[1].trim().trim('"')
            return try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (_: Exception) {
                encoded
            }
        }
        val quotedMatch = Regex("""filename\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE).find(disposition)
        if (quotedMatch != null) return quotedMatch.groupValues[1].trim()
        val bareMatch = Regex("""filename\s*=\s*([^;\r\n]+)""", RegexOption.IGNORE_CASE).find(disposition)
        return bareMatch?.groupValues?.getOrNull(1)?.trim()?.trim('"')
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        private const val MAX_INLINE_BYTES = 30 * 1024 * 1024
        private const val MAX_HLS_SEGMENTS = 600
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun hasPublicDownloadsAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

        fun isRestrictedVideoHost(url: String): Boolean {
            val host = try {
                url.toUri().host?.lowercase().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (host.isBlank()) return false
            return host == "youtube.com" ||
                host.endsWith(".youtube.com") ||
                host == "youtu.be" ||
                host.endsWith(".youtu.be") ||
                host == "googlevideo.com" ||
                host.endsWith(".googlevideo.com")
        }

        fun inferFormatAndMime(urlOrName: String, contentType: String = ""): Triple<String, String, String> {
            val path = urlOrName.substringBefore('?').substringBefore('#').lowercase()
            val ct = contentType.lowercase()
            return when {
                path.endsWith(".pdf") || ct.contains("application/pdf") -> Triple("PDF", "application/pdf", "pdf")
                path.endsWith(".png") || ct.contains("image/png") -> Triple("PNG", "image/png", "png")
                path.endsWith(".jpg") || path.endsWith(".jpeg") || ct.contains("image/jpeg") -> Triple("JPG", "image/jpeg", "jpg")
                path.endsWith(".webp") || ct.contains("image/webp") -> Triple("WEBP", "image/webp", "webp")
                path.endsWith(".gif") || ct.contains("image/gif") -> Triple("GIF", "image/gif", "gif")
                path.endsWith(".bmp") || ct.contains("image/bmp") -> Triple("BMP", "image/bmp", "bmp")
                path.endsWith(".svg") || ct.contains("image/svg") -> Triple("SVG", "image/svg+xml", "svg")
                path.endsWith(".avif") || ct.contains("image/avif") -> Triple("AVIF", "image/avif", "avif")
                path.endsWith(".webm") || ct.contains("video/webm") -> Triple("WEBM", "video/webm", "webm")
                path.endsWith(".mkv") || ct.contains("matroska") -> Triple("MKV", "video/x-matroska", "mkv")
                path.endsWith(".mov") || ct.contains("quicktime") -> Triple("MOV", "video/quicktime", "mov")
                path.endsWith(".m4v") -> Triple("M4V", "video/x-m4v", "m4v")
                path.endsWith(".3gp") || ct.contains("3gpp") -> Triple("3GP", "video/3gpp", "3gp")
                path.endsWith(".ts") || ct.contains("video/mp2t") -> Triple("TS", "video/mp2t", "ts")
                path.endsWith(".m3u8") || ct.contains("mpegurl") -> Triple("HLS", "application/vnd.apple.mpegurl", "m3u8")
                path.endsWith(".mp4") || ct.contains("video/mp4") -> Triple("MP4", "video/mp4", "mp4")
                path.endsWith(".mp3") || ct.contains("audio/mpeg") -> Triple("MP3", "audio/mpeg", "mp3")
                path.endsWith(".m4a") || ct.contains("audio/mp4") -> Triple("M4A", "audio/mp4", "m4a")
                path.endsWith(".aac") || ct.contains("audio/aac") -> Triple("AAC", "audio/aac", "aac")
                path.endsWith(".opus") || ct.contains("audio/opus") -> Triple("OPUS", "audio/opus", "opus")
                path.endsWith(".wav") || ct.contains("audio/wav") -> Triple("WAV", "audio/wav", "wav")
                path.endsWith(".ogg") || ct.contains("audio/ogg") -> Triple("OGG", "audio/ogg", "ogg")
                path.endsWith(".flac") || ct.contains("audio/flac") -> Triple("FLAC", "audio/flac", "flac")
                path.endsWith(".zip") || ct.contains("application/zip") -> Triple("ZIP", "application/zip", "zip")
                path.endsWith(".rar") || ct.contains("rar") -> Triple("RAR", "application/vnd.rar", "rar")
                path.endsWith(".7z") || ct.contains("7z") -> Triple("7Z", "application/x-7z-compressed", "7z")
                path.endsWith(".tar") -> Triple("TAR", "application/x-tar", "tar")
                path.endsWith(".gz") || ct.contains("gzip") -> Triple("GZ", "application/gzip", "gz")
                path.endsWith(".apk") || ct.contains("android.package-archive") -> Triple("APK", "application/vnd.android.package-archive", "apk")
                path.endsWith(".epub") || ct.contains("epub") -> Triple("EPUB", "application/epub+zip", "epub")
                path.endsWith(".txt") || ct.contains("text/plain") -> Triple("TXT", "text/plain", "txt")
                path.endsWith(".csv") || ct.contains("text/csv") -> Triple("CSV", "text/csv", "csv")
                path.endsWith(".json") || ct.contains("application/json") -> Triple("JSON", "application/json", "json")
                path.endsWith(".xml") || ct.contains("xml") -> Triple("XML", "application/xml", "xml")
                path.endsWith(".doc") -> Triple("DOC", "application/msword", "doc")
                path.endsWith(".docx") -> Triple("DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
                path.endsWith(".xls") -> Triple("XLS", "application/vnd.ms-excel", "xls")
                path.endsWith(".xlsx") -> Triple("XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
                path.endsWith(".ppt") -> Triple("PPT", "application/vnd.ms-powerpoint", "ppt")
                path.endsWith(".pptx") -> Triple("PPTX", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx")
                ct.startsWith("video/") -> Triple("VIDEO", ct, "mp4")
                ct.startsWith("image/") -> Triple("IMAGE", ct, "jpg")
                ct.startsWith("audio/") -> Triple("AUDIO", ct, "mp3")
                else -> {
                    val lastSegment = path.substringAfterLast('/')
                    val rawExt = if (lastSegment.contains('.')) {
                        lastSegment.substringAfterLast('.').replace(Regex("[^a-z0-9]"), "").take(6)
                    } else {
                        ""
                    }
                    val safeExt = rawExt.ifBlank { "bin" }
                    val label = if (safeExt == "bin") "FILE" else safeExt.uppercase()
                    Triple(label, ct.ifBlank { "application/octet-stream" }, safeExt)
                }
            }
        }

        fun deriveTitleFromUrl(url: String): String {
            val uri: Uri = try {
                url.toUri()
            } catch (_: Exception) {
                return "Downloaded File"
            }
            val seg = uri.lastPathSegment
                ?.substringBeforeLast('.')
                ?.replace(Regex("[^a-zA-Z0-9 _-]"), " ")
                ?.trim()
                ?.take(48)
            return if (!seg.isNullOrBlank()) seg else (uri.host?.removePrefix("www.") ?: "Downloaded File")
        }
    }
}
