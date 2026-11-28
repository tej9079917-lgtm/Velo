package com.example.media

import android.Manifest
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class SystemDownloadEnqueued(
    val downloadId: Long,
    val fileName: String,
    val mimeType: String,
    val savedToPublicDownloads: Boolean
)

data class SystemDownloadStatus(
    val downloadId: Long,
    val status: Int,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val localUri: String?
)

data class SharedStorageSaveResult(
    val savedToDownloads: Boolean,
    val savedToGallery: Boolean,
    val downloadsUri: Uri? = null,
    val galleryUri: Uri? = null
)

/**
 * Shared-External-Storage & Android DownloadManager manager for Velo Browser:
 * - API 29+ (Android 10+): Uses scoped MediaStore APIs with ZERO broad storage permissions:
 *     * MediaStore.Downloads -> Downloads/Velo/ (every downloaded file)
 *     * MediaStore.Images.Media -> Pictures/Velo/ (downloaded images appear immediately in Gallery)
 *     * MediaStore.Video.Media -> Movies/Velo/ (downloaded videos appear immediately in Gallery)
 * - API <= 28 (Android 9 and below): Checks WRITE_EXTERNAL_STORAGE, saves into shared external
 *   Downloads/Velo, and invokes MediaScannerConnection / allowScanningByMediaScanner() so files
 *   appear immediately in both the system Downloads folder and the Gallery.
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
        val safeFileName = sanitizeFileName(rawName)
        val canWritePublic = hasSharedStorageWriteAccess(context)

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

                if (canWritePublic) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        @Suppress("DEPRECATION")
                        allowScanningByMediaScanner()
                    }
                    val subDir = when {
                        resolvedMime.startsWith("image/", ignoreCase = true) -> Environment.DIRECTORY_PICTURES
                        resolvedMime.startsWith("video/", ignoreCase = true) -> Environment.DIRECTORY_MOVIES
                        else -> Environment.DIRECTORY_DOWNLOADS
                    }
                    // Always place direct system downloads in Downloads/Velo (or Pictures/Movies for Gallery on API 29+)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && subDir != Environment.DIRECTORY_DOWNLOADS) {
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Velo/$safeFileName")
                    } else {
                        setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Velo/$safeFileName")
                    }
                } else {
                    setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeFileName)
                }
            }

            val id = dm.enqueue(request)
            Result.success(
                SystemDownloadEnqueued(
                    downloadId = id,
                    fileName = safeFileName,
                    mimeType = resolvedMime,
                    savedToPublicDownloads = canWritePublic
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Publishes a completed local file into Android's shared external storage:
     * - API 29+: Inserts into MediaStore.Downloads (Downloads/Velo/) for all files, AND for images/videos
     *   also inserts into MediaStore.Images.Media (Pictures/Velo/) or MediaStore.Video.Media (Movies/Velo/)
     *   so they immediately show up in both the system Downloads folder and the Gallery app.
     * - API <= 28: Writes directly into public Downloads/Velo (and notifies MediaScannerConnection so
     *   images & videos appear in the Gallery immediately) when WRITE_EXTERNAL_STORAGE is granted.
     */
    fun publishToSharedStorage(
        sourceFile: File,
        publicFileName: String,
        mimeType: String
    ): SharedStorageSaveResult {
        if (!sourceFile.exists() || !sourceFile.isFile) {
            return SharedStorageSaveResult(savedToDownloads = false, savedToGallery = false)
        }
        val safeName = sanitizeFileName(publicFileName)
        val resolvedMime = mimeType.ifBlank { "application/octet-stream" }
        val lowerMime = resolvedMime.lowercase()
        val isImage = lowerMime.startsWith("image/") && !lowerMime.contains("svg")
        val isVideo = lowerMime.startsWith("video/")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 1. Save to system Downloads/Velo via MediaStore.Downloads
            val downloadsUri = insertIntoMediaStore(
                sourceFile = sourceFile,
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                displayName = safeName,
                mimeType = resolvedMime,
                relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Velo/"
            ) ?: insertIntoMediaStore(
                sourceFile = sourceFile,
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                displayName = safeName,
                mimeType = "application/octet-stream",
                relativePath = "${Environment.DIRECTORY_DOWNLOADS}/Velo/"
            ) ?: insertIntoMediaStore(
                sourceFile = sourceFile,
                collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                displayName = safeName,
                mimeType = "application/octet-stream",
                relativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
            )

            // 2. For images & videos, also publish to MediaStore.Images / MediaStore.Video so Gallery indexes immediately
            val galleryUri = when {
                isImage -> insertIntoMediaStore(
                    sourceFile = sourceFile,
                    collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    displayName = safeName,
                    mimeType = resolvedMime,
                    relativePath = "${Environment.DIRECTORY_PICTURES}/Velo/"
                )
                isVideo -> insertIntoMediaStore(
                    sourceFile = sourceFile,
                    collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    displayName = safeName,
                    mimeType = resolvedMime,
                    relativePath = "${Environment.DIRECTORY_MOVIES}/Velo/"
                )
                else -> null
            }

            SharedStorageSaveResult(
                savedToDownloads = downloadsUri != null,
                savedToGallery = galleryUri != null,
                downloadsUri = downloadsUri,
                galleryUri = galleryUri
            )
        } else {
            if (!hasSharedStorageWriteAccess(context)) {
                return SharedStorageSaveResult(savedToDownloads = false, savedToGallery = false)
            }
            try {
                @Suppress("DEPRECATION")
                val publicDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val veloDir = File(publicDownloads, "Velo").apply { mkdirs() }
                val destDir = if (veloDir.exists() && veloDir.canWrite()) veloDir else publicDownloads.apply { mkdirs() }
                val destFile = resolveUniqueFile(destDir, safeName)
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { out ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                        out.flush()
                    }
                }
                val savedOk = destFile.exists() && destFile.length() > 0L
                if (savedOk) {
                    // Scanning the saved file registers it in both Downloads and the Android Gallery on API <= 28
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(destFile.absolutePath),
                        arrayOf(resolvedMime),
                        null
                    )
                }
                SharedStorageSaveResult(
                    savedToDownloads = savedOk,
                    savedToGallery = savedOk && (isImage || isVideo),
                    downloadsUri = if (savedOk) Uri.fromFile(destFile) else null,
                    galleryUri = if (savedOk && (isImage || isVideo)) Uri.fromFile(destFile) else null
                )
            } catch (_: Exception) {
                SharedStorageSaveResult(savedToDownloads = false, savedToGallery = false)
            }
        }
    }

    /**
     * Deletes Velo's mirrored copies from shared Downloads/Velo, Pictures/Velo, and Movies/Velo
     * when a user hides a downloaded item inside the Secret Media Vault so it no longer appears
     * in either the system Downloads folder or the Gallery.
     */
    fun deleteSharedStorageCopies(displayTitle: String) {
        val safePrefix = displayTitle.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60)
        if (safePrefix.isBlank()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val targets = listOf(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "%Downloads/Velo%",
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "%Pictures/Velo%",
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "%Movies/Velo%"
            )
            targets.forEach { (collection, relLike) ->
                try {
                    resolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
                        arrayOf("$safePrefix%", relLike)
                    )
                } catch (_: Exception) {
                }
            }
        } else if (hasSharedStorageWriteAccess(context)) {
            @Suppress("DEPRECATION")
            val dirs = listOf(
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Velo"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Velo"),
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Velo")
            )
            dirs.forEach { dir ->
                try {
                    dir.listFiles()?.forEach { f ->
                        if (f.isFile && f.name.startsWith(safePrefix, ignoreCase = true)) {
                            f.delete()
                            MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), null, null)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun insertIntoMediaStore(
        sourceFile: File,
        collection: Uri,
        displayName: String,
        mimeType: String,
        relativePath: String
    ): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: return null
            try {
                val outStream = resolver.openOutputStream(uri, "w") ?: resolver.openOutputStream(uri)
                if (outStream == null) {
                    resolver.delete(uri, null, null)
                    return null
                }
                outStream.use { out ->
                    FileInputStream(sourceFile).use { input ->
                        input.copyTo(out, bufferSize = 16 * 1024)
                        out.flush()
                    }
                }
                val doneValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
                uri
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                null
            }
        } catch (_: Exception) {
            null
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

    private fun sanitizeFileName(raw: String): String {
        return raw
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .take(72)
            .ifBlank { "velo_download_${System.currentTimeMillis() % 100000}.bin" }
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
        fun requiresLegacyStoragePermission(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
            return !hasSharedStorageWriteAccess(context)
        }

        fun hasSharedStorageWriteAccess(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
            return ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

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
