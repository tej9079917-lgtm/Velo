package com.example.media

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.net.toUri

data class SystemDownloadEnqueued(
    val downloadId: Long,
    val fileName: String,
    val mimeType: String
)

data class SystemDownloadStatus(
    val downloadId: Long,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localUri: String?
)

/**
 * Clean Android DownloadManager wrapper for queuing background system downloads
 * with active WebView cookies, Origin, Referer, and User-Agent forwarding.
 * Saves to public Downloads/Velo on API 29+ (zero permissions) and on API <= 28
 * when WRITE_EXTERNAL_STORAGE is granted.
 */
class VeloDownloadManagerWrapper(private val context: Context) {

    private val downloadManager: DownloadManager? =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

    fun enqueueSystemDownload(
        url: String,
        suggestedFileName: String = "",
        contentDisposition: String = "",
        mimeType: String = "",
        referer: String? = null,
        userAgent: String? = null
    ): Result<SystemDownloadEnqueued> {
        val dm = downloadManager
            ?: return Result.failure(IllegalStateException("System DownloadManager unavailable on this device."))
        val cleanUrl = url.trim()
        val uri: Uri = try {
            cleanUrl.toUri()
        } catch (_: Exception) {
            return Result.failure(IllegalArgumentException("Invalid download URL."))
        }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result.failure(IllegalArgumentException("DownloadManager supports HTTP/HTTPS links only."))
        }
        if (VideoDownloadEngine.isRestrictedVideoHost(cleanUrl)) {
            return Result.failure(
                IllegalStateException(
                    "YouTube restricts third-party video downloads. Use Velo Downloader for direct files, documents, images, and open web streams."
                )
            )
        }

        val resolvedMime = mimeType.ifBlank {
            VideoDownloadEngine.inferFormatAndMime(cleanUrl, "").second
        }
        val rawName = suggestedFileName.trim().ifBlank {
            try {
                URLUtil.guessFileName(cleanUrl, contentDisposition, resolvedMime)
            } catch (_: Exception) {
                "velo_download.bin"
            }
        }
        val safeFileName = rawName
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(72)
            .ifBlank { "velo_download_${System.currentTimeMillis() % 100000}.bin" }

        return try {
            val request = DownloadManager.Request(uri).apply {
                setTitle(safeFileName)
                setDescription("Downloading via Velo Browser")
                setMimeType(resolvedMime)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)

                val cookies = try {
                    val direct = CookieManager.getInstance().getCookie(cleanUrl)
                    if (!direct.isNullOrBlank()) direct else referer?.let { CookieManager.getInstance().getCookie(it) }
                } catch (_: Exception) {
                    null
                }
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("Cookie", cookies)
                }
                val ua = userAgent?.takeIf { it.isNotBlank() }
                    ?: "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                addRequestHeader("User-Agent", ua)
                if (!referer.isNullOrBlank() && (referer.startsWith("http://") || referer.startsWith("https://"))) {
                    addRequestHeader("Referer", referer)
                    try {
                        val refUri = referer.toUri()
                        if (!refUri.scheme.isNullOrBlank() && !refUri.authority.isNullOrBlank()) {
                            addRequestHeader("Origin", "${refUri.scheme}://${refUri.authority}")
                        }
                    } catch (_: Exception) {
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || VideoDownloadEngine.hasPublicDownloadsAccess(context)) {
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Velo/$safeFileName")
                } else {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeFileName)
                }
            }

            val id = dm.enqueue(request)
            Result.success(
                SystemDownloadEnqueued(
                    downloadId = id,
                    fileName = safeFileName,
                    mimeType = resolvedMime
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun queryDownloadStatus(downloadId: Long): SystemDownloadStatus? {
        val dm = downloadManager ?: return null
        val query = DownloadManager.Query().setFilterById(downloadId)
        return try {
            dm.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val dlIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                SystemDownloadStatus(
                    downloadId = downloadId,
                    status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_FAILED,
                    bytesDownloaded = if (dlIdx >= 0) cursor.getLong(dlIdx) else 0L,
                    totalBytes = if (totalIdx >= 0) cursor.getLong(totalIdx) else -1L,
                    localUri = if (uriIdx >= 0) cursor.getString(uriIdx) else null
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        fun openSystemDownloadsFolder(context: Context): Result<Unit> {
            return try {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
