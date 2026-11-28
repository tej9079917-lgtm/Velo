package com.example.system

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Secure FileProvider wrapper for sharing and opening downloaded content across Android apps
 * without FileUriExposedException or storage permission exceptions.
 * Strictly blocks sharing any file located inside Secret Media Vault directories.
 */
object VeloFileSharing {

    fun getAuthority(context: Context): String = "${context.packageName}.fileprovider"

    fun getShareableUri(context: Context, file: File): Uri? {
        if (!file.exists() || !file.isFile) return null
        val canonical = try {
            file.canonicalPath
        } catch (_: Exception) {
            return null
        }
        val secretDir = File(context.filesDir, "secret_vault").canonicalPath + File.separator
        val previewDir = File(context.cacheDir, "vault_preview").canonicalPath + File.separator
        if (canonical.startsWith(secretDir) || canonical.startsWith(previewDir)) {
            return null
        }
        return try {
            FileProvider.getUriForFile(context, getAuthority(context), file)
        } catch (_: Exception) {
            null
        }
    }

    fun shareFile(
        context: Context,
        file: File,
        mimeType: String,
        title: String
    ): Result<Unit> {
        val contentUri = getShareableUri(context, file)
            ?: return Result.failure(IllegalArgumentException("File cannot be shared."))
        val safeMime = mimeType.ifBlank { "*/*" }
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = safeMime
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                clipData = ClipData.newRawUri(title, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Share $title").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openWithExternalApp(
        context: Context,
        file: File,
        mimeType: String,
        title: String
    ): Result<Unit> {
        val contentUri = getShareableUri(context, file)
            ?: return Result.failure(IllegalArgumentException("File cannot be opened externally."))
        val safeMime = mimeType.ifBlank { "*/*" }
        return try {
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, safeMime)
                clipData = ClipData.newRawUri(title, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(viewIntent, "Open $title with").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
