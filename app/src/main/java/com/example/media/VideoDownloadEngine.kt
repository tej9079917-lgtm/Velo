package com.example.media

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
    val byteSize: Long
)

/**
 * Streaming HTTP/HTTPS video downloader with progress tracking, cancellation,
 * HLS (.m3u8) segment assembly, and strict app-private path confinement.
 */
class VideoDownloadEngine(context: Context) {

    private val downloadsDir = File(context.filesDir, "velo_downloads").apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<DownloadedVideoResult> = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val uri = try {
            cleanUrl.toUri()
        } catch (_: Exception) {
            return@withContext Result.failure(IllegalArgumentException("Invalid video URL."))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return@withContext Result.failure(IllegalArgumentException("Only HTTP/HTTPS video streams are supported."))
        }

        val id = "vid_${UUID.randomUUID().toString().take(10)}"
        val isHls = cleanUrl.substringBefore('?').lowercase().endsWith(".m3u8")

        try {
            if (isHls) {
                downloadHlsStream(
                    id = id,
                    playlistUrl = cleanUrl,
                    title = title,
                    qualityLabel = qualityLabel,
                    onProgress = onProgress
                )
            } else {
                downloadDirectStream(
                    id = id,
                    url = cleanUrl,
                    title = title,
                    qualityLabel = qualityLabel,
                    onProgress = onProgress
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            activeCallRef.set(null)
        }
    }

    private suspend fun downloadDirectStream(
        id: String,
        url: String,
        title: String,
        qualityLabel: String,
        onProgress: (Long, Long) -> Unit
    ): Result<DownloadedVideoResult> {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        val call = httpClient.newCall(request)
        activeCallRef.set(call)

        var targetFile: File? = null
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(IllegalStateException("Download failed (HTTP ${response.code})."))
                }
                val body = response.body ?: return Result.failure(IllegalStateException("Empty video response."))
                val contentType = response.header("Content-Type").orEmpty()
                val (formatLabel, mimeType, ext) = inferFormatAndMime(url, contentType)
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
                                throw IllegalStateException("Video exceeds 500 MB safety limit.")
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= 140L) {
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
                    return Result.failure(IllegalStateException("Downloaded video stream was empty."))
                }

                Result.success(
                    DownloadedVideoResult(
                        id = id,
                        title = title.trim().ifBlank { deriveTitleFromUrl(url) },
                        sourceUrl = url,
                        localPath = safeFile.canonicalPath,
                        mimeType = mimeType,
                        formatLabel = formatLabel,
                        qualityLabel = qualityLabel.ifBlank { "Auto" },
                        byteSize = downloaded
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
        onProgress: (Long, Long) -> Unit
    ): Result<DownloadedVideoResult> {
        val text = fetchText(playlistUrl)
            ?: return Result.failure(IllegalStateException("Could not load HLS playlist."))
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // If master playlist, pick the first variant stream
        val mediaPlaylistUrl = if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
            val variantLine = lines.firstOrNull { !it.startsWith("#") }
                ?: return Result.failure(IllegalStateException("No playable stream found in HLS master playlist."))
            resolveRelativeUrl(playlistUrl, variantLine)
        } else {
            playlistUrl
        }

        val mediaText = if (mediaPlaylistUrl == playlistUrl) text else (fetchText(mediaPlaylistUrl)
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
                    val req = Request.Builder().url(segUrl).header("User-Agent", USER_AGENT).build()
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

            Result.success(
                DownloadedVideoResult(
                    id = id,
                    title = title.trim().ifBlank { deriveTitleFromUrl(playlistUrl) },
                    sourceUrl = playlistUrl,
                    localPath = safeFile.canonicalPath,
                    mimeType = "video/mp2t",
                    formatLabel = "HLS/TS",
                    qualityLabel = qualityLabel.ifBlank { "Stream" },
                    byteSize = totalDownloaded
                )
            )
        } catch (e: Exception) {
            safeFile.delete()
            Result.failure(e)
        }
    }

    private fun fetchText(url: String): String? {
        val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
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

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 500L * 1024L * 1024L
        private const val MAX_HLS_SEGMENTS = 180
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        fun inferFormatAndMime(url: String, contentType: String = ""): Triple<String, String, String> {
            val path = url.substringBefore('?').lowercase()
            val ct = contentType.lowercase()
            return when {
                path.endsWith(".webm") || ct.contains("webm") -> Triple("WEBM", "video/webm", "webm")
                path.endsWith(".mkv") || ct.contains("matroska") -> Triple("MKV", "video/x-matroska", "mkv")
                path.endsWith(".mov") || ct.contains("quicktime") -> Triple("MOV", "video/quicktime", "mov")
                path.endsWith(".m4v") -> Triple("M4V", "video/x-m4v", "m4v")
                path.endsWith(".3gp") || ct.contains("3gpp") -> Triple("3GP", "video/3gpp", "3gp")
                path.endsWith(".m3u8") || ct.contains("mpegurl") -> Triple("HLS", "application/vnd.apple.mpegurl", "m3u8")
                path.endsWith(".mp3") || ct.contains("audio/mpeg") -> Triple("MP3", "audio/mpeg", "mp3")
                path.endsWith(".m4a") || ct.contains("audio/mp4") -> Triple("M4A", "audio/mp4", "m4a")
                else -> Triple("MP4", "video/mp4", "mp4")
            }
        }

        fun deriveTitleFromUrl(url: String): String {
            val uri: Uri = try {
                url.toUri()
            } catch (_: Exception) {
                return "Downloaded Video"
            }
            val seg = uri.lastPathSegment
                ?.substringBeforeLast('.')
                ?.replace(Regex("[^a-zA-Z0-9 _-]"), " ")
                ?.trim()
                ?.take(48)
            return if (!seg.isNullOrBlank()) seg else (uri.host?.removePrefix("www.") ?: "Downloaded Video")
        }
    }
}
