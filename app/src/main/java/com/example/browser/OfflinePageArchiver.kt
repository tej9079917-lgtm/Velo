package com.example.browser

import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class ArchivedWebPage(
    val normalizedUrl: String,
    val title: String,
    val excerpt: String,
    val htmlContent: String,
    val byteSize: Long
)

/**
 * Fetches and sanitizes web pages for local offline storage.
 * Validates URLs strictly to public http/https endpoints to protect against local/private network access.
 */
class OfflinePageArchiver {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val MAX_ARCHIVE_BYTES = 1_500_000
    }

    suspend fun archiveUrl(rawUrl: String, fallbackTitle: String): Result<ArchivedWebPage> =
        withContext(Dispatchers.IO) {
            try {
                val uri = rawUrl.trim().toUri()
                val scheme = uri.scheme?.lowercase()
                if (scheme != "https" && scheme != "http") {
                    return@withContext Result.failure(IllegalArgumentException("Only http/https web pages can be saved offline."))
                }
                val host = uri.host?.lowercase()
                    ?: return@withContext Result.failure(IllegalArgumentException("Invalid web address."))
                if (isBlockedHost(host)) {
                    return@withContext Result.failure(IllegalArgumentException("Local or private network addresses cannot be archived."))
                }

                val request = Request.Builder()
                    .url(rawUrl.trim())
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) VeloBrowser/1.0 Mobile Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(IllegalStateException("Server responded with HTTP ${response.code}"))
                    }
                    val bodyBytes = response.body?.byteStream()?.use { stream ->
                        val buffer = ByteArray(8192)
                        val out = java.io.ByteArrayOutputStream()
                        var total = 0
                        var read: Int
                        while (stream.read(buffer).also { read = it } != -1 && total < MAX_ARCHIVE_BYTES) {
                            val toWrite = minOf(read, MAX_ARCHIVE_BYTES - total)
                            out.write(buffer, 0, toWrite)
                            total += toWrite
                        }
                        out.toByteArray()
                    } ?: ByteArray(0)

                    val rawHtml = String(bodyBytes, Charsets.UTF_8)
                    val cleanedHtml = stripInlineScripts(rawHtml)
                    val extractedTitle = extractTitle(cleanedHtml).ifBlank { fallbackTitle.ifBlank { host } }
                    val excerpt = extractReadableExcerpt(cleanedHtml)

                    Result.success(
                        ArchivedWebPage(
                            normalizedUrl = rawUrl.trim(),
                            title = extractedTitle.take(140),
                            excerpt = excerpt.take(280),
                            htmlContent = cleanedHtml,
                            byteSize = cleanedHtml.toByteArray(Charsets.UTF_8).size.toLong()
                        )
                    )
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local") || host == "169.254.169.254") {
            return true
        }
        return try {
            val addresses = InetAddress.getAllByName(host)
            addresses.any { addr ->
                addr.isLoopbackAddress ||
                    addr.isSiteLocalAddress ||
                    addr.isLinkLocalAddress ||
                    addr.isAnyLocalAddress
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun stripInlineScripts(html: String): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), "")
    }

    private fun extractTitle(html: String): String {
        val match = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.getOrNull(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()
    }

    fun extractReadableExcerpt(html: String): String {
        val noStyles = html
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (noStyles.length > 260) noStyles.take(257) + "..." else noStyles
    }
}
