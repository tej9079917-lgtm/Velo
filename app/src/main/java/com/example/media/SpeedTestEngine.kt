package com.example.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.browser.SpeedTestLiveState
import com.example.browser.SpeedTestPhase
import com.example.browser.SpeedTestRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real network Speed Test Engine powered by OkHttp.
 * Measures real HTTPS Ping & Jitter (ms), Download throughput (Mbps), and Upload throughput (Mbps).
 */
class SpeedTestEngine(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    fun detectConnectionType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Network"
            val network = cm.activeNetwork ?: return "Offline"
            val caps = cm.getNetworkCapabilities(network) ?: return "Network"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Online"
            }
        } catch (_: Exception) {
            "Network"
        }
    }

    suspend fun runSpeedTest(
        onProgress: (SpeedTestLiveState) -> Unit
    ): Result<SpeedTestRecord> = withContext(Dispatchers.IO) {
        val connectionType = detectConnectionType()
        if (connectionType == "Offline") {
            val err = "No active internet connection detected."
            onProgress(SpeedTestLiveState(phase = SpeedTestPhase.IDLE, errorMessage = err))
            return@withContext Result.failure(IllegalStateException(err))
        }

        val samples = mutableListOf<Float>()
        try {
            // --- PHASE 1: PING & JITTER (5 PROBES) ---
            onProgress(
                SpeedTestLiveState(
                    phase = SpeedTestPhase.PING,
                    progress = 0.05f
                )
            )
            val latencies = mutableListOf<Long>()
            repeat(5) { idx ->
                currentCoroutineContext().ensureActive()
                val elapsed = measureSinglePingMs()
                if (elapsed != null) latencies.add(elapsed)
                val avgSoFar = if (latencies.isNotEmpty()) latencies.average().roundToInt() else 0
                val jitterSoFar = computeJitterMs(latencies)
                onProgress(
                    SpeedTestLiveState(
                        phase = SpeedTestPhase.PING,
                        pingMs = avgSoFar,
                        jitterMs = jitterSoFar,
                        progress = 0.05f + (idx + 1) * 0.03f
                    )
                )
            }
            if (latencies.isEmpty()) {
                val err = "Unable to reach speed test servers. Check your connection."
                onProgress(SpeedTestLiveState(phase = SpeedTestPhase.IDLE, errorMessage = err))
                return@withContext Result.failure(IllegalStateException(err))
            }

            val finalPing = latencies.average().roundToInt().coerceAtLeast(1)
            val finalJitter = computeJitterMs(latencies)

            // --- PHASE 2: DOWNLOAD SPEED (STREAMING) ---
            onProgress(
                SpeedTestLiveState(
                    phase = SpeedTestPhase.DOWNLOAD,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    progress = 0.22f
                )
            )
            val downloadMbps = measureDownloadMbps { liveMbps, frac ->
                samples.add(liveMbps.toFloat())
                onProgress(
                    SpeedTestLiveState(
                        phase = SpeedTestPhase.DOWNLOAD,
                        currentMbps = liveMbps,
                        pingMs = finalPing,
                        jitterMs = finalJitter,
                        downloadMbps = liveMbps,
                        progress = 0.22f + frac * 0.42f,
                        samples = samples.takeLast(36)
                    )
                )
            }

            // --- PHASE 3: UPLOAD SPEED (STREAMING POST) ---
            onProgress(
                SpeedTestLiveState(
                    phase = SpeedTestPhase.UPLOAD,
                    currentMbps = 0.0,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = downloadMbps,
                    progress = 0.66f,
                    samples = samples.takeLast(36)
                )
            )
            val uploadMbps = measureUploadMbps { liveMbps, frac ->
                samples.add(liveMbps.toFloat())
                onProgress(
                    SpeedTestLiveState(
                        phase = SpeedTestPhase.UPLOAD,
                        currentMbps = liveMbps,
                        pingMs = finalPing,
                        jitterMs = finalJitter,
                        downloadMbps = downloadMbps,
                        uploadMbps = liveMbps,
                        progress = 0.66f + frac * 0.32f,
                        samples = samples.takeLast(36)
                    )
                )
            }

            val record = SpeedTestRecord(
                id = "spd_${UUID.randomUUID().toString().take(10)}",
                pingMs = finalPing,
                jitterMs = finalJitter,
                downloadMbps = downloadMbps,
                uploadMbps = uploadMbps,
                connectionType = connectionType,
                testedAtMillis = System.currentTimeMillis()
            )
            onProgress(
                SpeedTestLiveState(
                    phase = SpeedTestPhase.COMPLETE,
                    currentMbps = downloadMbps,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    downloadMbps = downloadMbps,
                    uploadMbps = uploadMbps,
                    progress = 1f,
                    samples = samples.takeLast(36)
                )
            )
            Result.success(record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun measureSinglePingMs(): Long? {
        val urls = listOf(
            "https://speed.cloudflare.com/__down?bytes=0",
            "https://www.google.com/generate_204"
        )
        for (url in urls) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .cacheControl(CacheControl.FORCE_NETWORK)
                    .get()
                    .build()
                val startNs = System.nanoTime()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful || resp.code == 204) {
                        return ((System.nanoTime() - startNs) / 1_000_000L).coerceAtLeast(1L)
                    }
                }
            } catch (_: Exception) {
                // try fallback
            }
        }
        return null
    }

    private suspend fun measureDownloadMbps(
        onTick: (liveMbps: Double, fraction: Float) -> Unit
    ): Double {
        val req = Request.Builder()
            .url("https://speed.cloudflare.com/__down?bytes=$DOWNLOAD_TARGET_BYTES")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()

        var totalBytes = 0L
        val startNs = System.nanoTime()
        var lastEmitNs = startNs
        val maxDurationNs = 5_000_000_000L // 5s cap

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Download probe failed (HTTP ${resp.code}).")
            }
            val stream = resp.body?.byteStream() ?: throw IllegalStateException("Empty download stream.")
            val buffer = ByteArray(32 * 1024)
            stream.use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    totalBytes += read
                    val nowNs = System.nanoTime()
                    val elapsedNs = (nowNs - startNs).coerceAtLeast(1L)
                    if (nowNs - lastEmitNs >= 160_000_000L) {
                        lastEmitNs = nowNs
                        val mbps = bytesToMbps(totalBytes, elapsedNs)
                        val frac = maxOf(
                            totalBytes.toFloat() / DOWNLOAD_TARGET_BYTES.toFloat(),
                            elapsedNs.toFloat() / maxDurationNs.toFloat()
                        ).coerceIn(0f, 1f)
                        onTick(mbps, frac)
                    }
                    if (elapsedNs >= maxDurationNs) break
                }
            }
        }
        val finalElapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
        return bytesToMbps(totalBytes, finalElapsedNs)
    }

    private suspend fun measureUploadMbps(
        onTick: (liveMbps: Double, fraction: Float) -> Unit
    ): Double {
        val totalToSend = UPLOAD_TARGET_BYTES
        val chunk = ByteArray(16 * 1024)
        var bytesWritten = 0L
        val startNs = System.nanoTime()
        var lastEmitNs = startNs
        val coroutineCtx = currentCoroutineContext()

        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = totalToSend.toLong()
            override fun writeTo(sink: BufferedSink) {
                var remaining = totalToSend
                while (remaining > 0) {
                    coroutineCtx.ensureActive()
                    val toWrite = minOf(chunk.size, remaining)
                    sink.write(chunk, 0, toWrite)
                    sink.flush()
                    bytesWritten += toWrite
                    remaining -= toWrite
                    val nowNs = System.nanoTime()
                    if (nowNs - lastEmitNs >= 160_000_000L) {
                        lastEmitNs = nowNs
                        val elapsedNs = (nowNs - startNs).coerceAtLeast(1L)
                        val mbps = bytesToMbps(bytesWritten, elapsedNs)
                        val frac = (bytesWritten.toFloat() / totalToSend.toFloat()).coerceIn(0f, 1f)
                        onTick(mbps, frac)
                    }
                }
            }
        }

        val req = Request.Builder()
            .url("https://speed.cloudflare.com/__up")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    // If Cloudflare upload returns non-2xx, report whatever bytes were transferred cleanly
                    val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
                    if (bytesWritten > 0L) bytesToMbps(bytesWritten, elapsedNs) else 0.0
                } else {
                    val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
                    bytesToMbps(bytesWritten.coerceAtLeast(totalToSend.toLong()), elapsedNs)
                }
            }
        } catch (_: Exception) {
            val elapsedNs = (System.nanoTime() - startNs).coerceAtLeast(1_000_000L)
            if (bytesWritten > 64 * 1024L) bytesToMbps(bytesWritten, elapsedNs) else 0.0
        }
    }

    private fun bytesToMbps(bytes: Long, elapsedNs: Long): Double {
        val seconds = elapsedNs.toDouble() / 1_000_000_000.0
        if (seconds <= 0.0) return 0.0
        return ((bytes * 8.0) / seconds) / 1_000_000.0
    }

    private fun computeJitterMs(latencies: List<Long>): Int {
        if (latencies.size < 2) return 0
        var diffSum = 0L
        for (i in 1 until latencies.size) {
            diffSum += abs(latencies[i] - latencies[i - 1])
        }
        return (diffSum.toDouble() / (latencies.size - 1)).roundToInt()
    }

    companion object {
        private const val DOWNLOAD_TARGET_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val UPLOAD_TARGET_BYTES = 2 * 1024 * 1024    // 2 MB
    }
}
