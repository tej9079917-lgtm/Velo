package com.example.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.net.toUri
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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * - WebView Cookie, User-Agent, and Referer forwarding (fixes 403/auth-protected downloads)
 * - HLS (.m3u8) segment assembly & direct streaming for MP4, WebM, MKV, PDF, Images, Audio, ZIP, APK
 * - Inline Data URI & Blob Base64 download support
 * - Automatic zero-permission mirroring to Android's public Downloads/Velo folder (MediaStore API 29+)
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

        val id = "dl_${UUID.randomUUID().toString().take(10)}"
        val isHls = cleanUrl.substringBefore('?').lowercase().endsWith(".m3u8") ||
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
            val mirrored = mirrorToPublicDownloads(safeFile, publicFileName, resolvedMime)

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
                val guessedName = try {
                    URLUtil.guessFileName(url, respDisposition, effectiveMime)
                } catch (_: Exception) {
                    ""
                }
                val (formatLabel, mimeType, ext) = inferFormatAndMime(
                    urlOrName = if (guessedName.contains(".")) guessedName else url,
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
                val mirrored = mirrorToPublicDownloads(safeFile, publicFileName, mimeType)

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
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        val mediaPlaylistUrl = if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variantLine = lines.firstOrNull { !it.startsWith("#") }
                ?: return Result.failure(IllegalStateException("No playable stream found in HLS master playlist."))
            resolveRelativeUrl(playlistUrl, variantLine)
        } else {
            playlistUrl
        }

        val mediaText = if (mediaPlaylistUrl == playlistUrl) text else (fetchText(mediaPlaylistUrl, referer, userAgent)
            ?: return Result.failure(IllegalStateException("Could not load HLS media playlist.")))

        val segmentUrls = mediaText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { resolveRelativeUrl(mediaPlaylistUrl, it) }
            .take(MAX_HLS_SEGMENTS)

        if (segmentUrls.isEmpty()) {
            return Result.failure(IllegalStateException("HLS playlist has no downloadable media segments."))
        }

        val safeFile = createConfinedFile("${id}.ts")
        var totalDownloaded = 0L
        return try {
            FileOutputStream(safeFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                segmentUrls.forEachIndexed { index, segUrl ->
                    currentCoroutineContext().ensureActive()
                    val req = buildAuthenticatedRequest(segUrl, referer ?: playlistUrl, userAgent)
                    val call = httpClient.newCall(req)
                    activeCallRef.set(call)
                    call.execute().use { resp ->
                        if (resp.isSuccessful) {
                            resp.body?.byteStream()?.use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val r = input.read(buffer)
                                    if (r == -1) break
                                    output.write(buffer, 0, r)
                                    totalDownloaded += r
                                    if (totalDownloaded > MAX_DOWNLOAD_BYTES) {
                                        throw IllegalStateException("HLS download reached 500 MB limit.")
                                    }
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
            val mirrored = mirrorToPublicDownloads(
                sourceFile = safeFile,
                publicFileName = sanitizePublicFileName("$friendlyTitle.ts"),
                mimeType = "video/mp2t"
            )

            Result.success(
                DownloadedVideoResult(
                    id = id,
                    title = friendlyTitle,
                    sourceUrl = playlistUrl,
                    localPath = safeFile.canonicalPath,
                    mimeType = "video/mp2t",
                    formatLabel = "HLS/TS",
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

    private fun buildAuthenticatedRequest(url: String, referer: String?, userAgent: String?): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent?.takeIf { it.isNotBlank() } ?: USER_AGENT)
            .header("Accept", "*/*")

        val cookies = try {
            CookieManager.getInstance().getCookie(url)
        } catch (_: Exception) {
            null
        }
        if (!cookies.isNullOrBlank()) {
            builder.header("Cookie", cookies)
        }
        if (!referer.isNullOrBlank() && (referer.startsWith("http://") || referer.startsWith("https://"))) {
            builder.header("Referer", referer)
        }
        return builder.build()
    }

    /**
     * Copies the downloaded file to Android's public Downloads/Velo folder via MediaStore (API 29+)
     * with ZERO storage permissions required, so files are visible in the phone's Files & Gallery apps.
     */
    private fun mirrorToPublicDownloads(sourceFile: File, publicFileName: String, mimeType: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, publicFileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType.ifBlank { "application/octet-stream" })
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Velo")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                        out.flush()
                    }
                } ?: return false
                val doneValues = ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                true
            } catch (_: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        } catch (_: Exception) {
            false
        }
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

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        private const val MAX_INLINE_BYTES = 30 * 1024 * 1024
        private const val MAX_HLS_SEGMENTS = 180
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun inferFormatAndMime(urlOrName: String, contentType: String = ""): Triple<String, String, String> {
            val path = urlOrName.substringBefore('?').lowercase()
            val ct = contentType.lowercase()
            return when {
                path.endsWith(".pdf") || ct.contains("application/pdf") -> Triple("PDF", "application/pdf", "pdf")
                path.endsWith(".png") || ct.contains("image/png") -> Triple("PNG", "image/png", "png")
                path.endsWith(".jpg") || path.endsWith(".jpeg") || ct.contains("image/jpeg") -> Triple("JPG", "image/jpeg", "jpg")
                path.endsWith(".webp") || ct.contains("image/webp") -> Triple("WEBP", "image/webp", "webp")
                path.endsWith(".gif") || ct.contains("image/gif") -> Triple("GIF", "image/gif", "gif")
                path.endsWith(".webm") || ct.contains("video/webm") -> Triple("WEBM", "video/webm", "webm")
                path.endsWith(".mkv") || ct.contains("matroska") -> Triple("MKV", "video/x-matroska", "mkv")
                path.endsWith(".mov") || ct.contains("quicktime") -> Triple("MOV", "video/quicktime", "mov")
                path.endsWith(".m4v") -> Triple("M4V", "video/x-m4v", "m4v")
                path.endsWith(".3gp") || ct.contains("3gpp") -> Triple("3GP", "video/3gpp", "3gp")
                path.endsWith(".m3u8") || ct.contains("mpegurl") -> Triple("HLS", "application/vnd.apple.mpegurl", "m3u8")
                path.endsWith(".mp4") || ct.contains("video/mp4") -> Triple("MP4", "video/mp4", "mp4")
                path.endsWith(".mp3") || ct.contains("audio/mpeg") -> Triple("MP3", "audio/mpeg", "mp3")
                path.endsWith(".m4a") || ct.contains("audio/mp4") -> Triple("M4A", "audio/mp4", "m4a")
                path.endsWith(".wav") || ct.contains("audio/wav") -> Triple("WAV", "audio/wav", "wav")
                path.endsWith(".ogg") || ct.contains("audio/ogg") -> Triple("OGG", "audio/ogg", "ogg")
                path.endsWith(".flac") || ct.contains("audio/flac") -> Triple("FLAC", "audio/flac", "flac")
                path.endsWith(".zip") || ct.contains("application/zip") -> Triple("ZIP", "application/zip", "zip")
                path.endsWith(".apk") || ct.contains("android.package-archive") -> Triple("APK", "application/vnd.android.package-archive", "apk")
                path.endsWith(".txt") || ct.contains("text/plain") -> Triple("TXT", "text/plain", "txt")
                path.endsWith(".csv") || ct.contains("text/csv") -> Triple("CSV", "text/csv", "csv")
                path.endsWith(".docx") -> Triple("DOCX", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx")
                path.endsWith(".xlsx") -> Triple("XLSX", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx")
                ct.startsWith("video/") -> Triple("VIDEO", ct, "mp4")
                ct.startsWith("image/") -> Triple("IMAGE", ct, "jpg")
                ct.startsWith("audio/") -> Triple("AUDIO", ct, "mp3")
                else -> Triple("FILE", ct.ifBlank { "application/octet-stream" }, path.substringAfterLast('.', "bin").take(6).ifBlank { "bin" })
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
