package com.sg.linuxgo.bootstrap

import android.util.Log
import com.sg.linuxgo.Bootstrap
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.Executors

/**
 * HTTP download helpers used by [Bootstrap] install pipelines.
 */
internal class BootstrapDownloadOps(
    private val downloadOverallProgress: (filePercent: Int) -> Int,
    private val downloadBandStart: () -> Int,
    private val tag: String = "Bootstrap"
) {
    companion object {
        const val DL_CONNECT_TIMEOUT_MS = 20_000
        const val DL_READ_TIMEOUT_MS = 120_000
        const val DL_BUFFER_SIZE = 256 * 1024 // 256 KiB — was 16 KiB (huge speed win)
        const val DL_PROGRESS_MIN_INTERVAL_MS = 200L
        const val DL_USER_AGENT = "PocketLinux/1.0 (Android; multi-part download)"
        const val DL_MIN_SIZE_FOR_PARALLEL = 1L * 1024 * 1024 // 1 MiB
        /** Transient 5xx/429 from GitHub origin/CDN — retry before failing install. */
        const val DL_PROBE_MAX_ATTEMPTS = 4
        const val DL_PROBE_RETRY_BASE_MS = 600L
    }

    data class DownloadProbe(
        val url: String,
        val contentLength: Long,
        val rangesSupported: Boolean
    )

    fun fileSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { b -> "%02x".format(b) }
    }

    // ── Fast download pipeline ────────────────────────────────────────────────
    // GitHub Releases / CDNs often mishandle HEAD (no Accept-Ranges). We probe with
    // a 1-byte Range GET, then open parallel ranged streams with large buffers.

    fun openHttp(url: String): java.net.HttpURLConnection {
        val conn = URL(url).openConnection() as java.net.HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = DL_CONNECT_TIMEOUT_MS
        conn.readTimeout = DL_READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", DL_USER_AGENT)
        conn.setRequestProperty("Accept-Encoding", "identity") // avoid gzip on large binaries
        return conn
    }

    fun resolveRedirectLocation(baseUrl: String, location: String): String {
        return try {
            URL(URL(baseUrl), location).toString()
        } catch (_: Exception) {
            location
        }
    }

    /**
     * Host-only label for user-facing errors (never echo full download URLs in the UI).
     * Full URLs stay in logcat only.
     */
    fun publicHostLabel(url: String): String {
        return try {
            URL(url).host?.takeIf { it.isNotBlank() } ?: "download server"
        } catch (_: Exception) {
            "download server"
        }
    }

    fun isTransientHttpCode(code: Int): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599

    /**
     * Follow redirects and detect Content-Length + byte-range support.
     * Prefer Range GET probe (206 + Content-Range) over HEAD — reliable on GitHub CDNs.
     * Retries transient 5xx/429 from origin or CDN (common on large release assets).
     */
    fun probeDownload(urlString: String): DownloadProbe {
        var lastError: Exception? = null
        for (attempt in 0 until DL_PROBE_MAX_ATTEMPTS) {
            try {
                return probeDownloadOnce(urlString)
            } catch (e: TransientProbeException) {
                lastError = e
                Log.w(tag, "Download probe attempt ${attempt + 1}/${DL_PROBE_MAX_ATTEMPTS} failed: ${e.message}")
                if (attempt < DL_PROBE_MAX_ATTEMPTS - 1) {
                    try {
                        Thread.sleep(DL_PROBE_RETRY_BASE_MS * (attempt + 1))
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e.toUserException()
                    }
                }
            }
        }
        throw (lastError as? TransientProbeException)?.toUserException()
            ?: Exception("Could not reach container image download server — please retry.")
    }

    private class TransientProbeException(
        val code: Int,
        val host: String,
        causeUrl: String
    ) : Exception("HTTP $code while probing $causeUrl") {
        fun toUserException(): Exception =
            Exception(
                "Download server returned HTTP $code ($host). " +
                    "This is usually temporary — please retry."
            )
    }

    private fun probeDownloadOnce(urlString: String): DownloadProbe {
        var currentUrl = urlString
        var redirectCount = 0
        while (redirectCount < 8) {
            val conn = openHttp(currentUrl)
            try {
                conn.requestMethod = "GET"
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connect()
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: break
                    currentUrl = resolveRedirectLocation(currentUrl, loc)
                    redirectCount++
                    continue
                }
                if (code == 206) {
                    val contentRange = conn.getHeaderField("Content-Range") // bytes 0-0/TOTAL
                    val total = parseContentRangeTotal(contentRange)
                    val len = if (total > 0) total else conn.contentLengthLong
                    // Drain/close the 1-byte body
                    try { conn.inputStream.use { it.read() } } catch (_: Exception) {}
                    Log.d(tag, "Download probe 206: len=$len ranges=true url=$currentUrl")
                    return DownloadProbe(currentUrl, len, rangesSupported = len > 0)
                }
                if (code == 200) {
                    // Server ignored Range — single-stream only
                    val len = conn.contentLengthLong
                    val accept = conn.getHeaderField("Accept-Ranges")
                    val ranges = accept != null && accept.contains("bytes") && len > 0
                    try { conn.inputStream.close() } catch (_: Exception) {}
                    Log.d(tag, "Download probe 200: len=$len ranges=$ranges url=$currentUrl")
                    return DownloadProbe(currentUrl, len, rangesSupported = ranges)
                }
                val host = publicHostLabel(currentUrl)
                Log.e(tag, "Download probe HTTP $code host=$host url=$currentUrl")
                if (isTransientHttpCode(code)) {
                    throw TransientProbeException(code, host, currentUrl)
                }
                throw Exception(
                    "Download server returned HTTP $code ($host). " +
                        "Check your connection and try again."
                )
            } finally {
                try { conn.disconnect() } catch (_: Exception) {}
            }
        }
        // Fallback: unknown size, single stream on last URL
        return DownloadProbe(currentUrl, -1L, rangesSupported = false)
    }

    fun parseContentRangeTotal(contentRange: String?): Long {
        if (contentRange.isNullOrBlank()) return -1L
        // "bytes 0-0/123456" or "bytes */123456"
        val slash = contentRange.lastIndexOf('/')
        if (slash < 0 || slash == contentRange.lastIndex) return -1L
        return contentRange.substring(slash + 1).trim().toLongOrNull() ?: -1L
    }

    fun parallelThreadCount(contentLength: Long): Int {
        return when {
            contentLength >= 400L * 1024 * 1024 -> 8
            contentLength >= 100L * 1024 * 1024 -> 6
            contentLength >= 20L * 1024 * 1024 -> 4
            else -> 3
        }
    }

    fun downloadFileMultiThreaded(urlString: String, outputFile: File, callback: Bootstrap.BootstrapCallback) {
        val tempFile = File(outputFile.absolutePath + ".tmp")
        if (tempFile.exists()) tempFile.delete()

        val probe = probeDownload(urlString)
        val finalUrl = probe.url
        val contentLength = probe.contentLength
        val fileName = outputFile.name

        if (!probe.rangesSupported || contentLength < DL_MIN_SIZE_FOR_PARALLEL) {
            Log.i(tag, "Single-stream download (ranges=${probe.rangesSupported} len=$contentLength)")
            downloadFile(finalUrl, outputFile, callback)
            return
        }

        val numThreads = parallelThreadCount(contentLength)
        Log.i(tag, "Parallel download: ${numThreads}x streams, ${contentLength / (1024 * 1024)} MB → $fileName")
        val chunkSize = contentLength / numThreads
        val downloadExecutor = Executors.newFixedThreadPool(numThreads)

        // Pre-allocate sparse file so each thread can seek independently
        java.io.RandomAccessFile(tempFile, "rw").use { it.setLength(contentLength) }

        val progressMap = java.util.concurrent.ConcurrentHashMap<Int, Long>()
        val lastProgressEmitMs = java.util.concurrent.atomic.AtomicLong(0L)
        val futures = mutableListOf<java.util.concurrent.Future<*>>()

        fun emitProgress(force: Boolean = false) {
            val now = System.currentTimeMillis()
            val last = lastProgressEmitMs.get()
            if (!force && now - last < DL_PROGRESS_MIN_INTERVAL_MS) return
            if (!lastProgressEmitMs.compareAndSet(last, now) && !force) return
            val currentTotal = progressMap.values.sum().coerceAtMost(contentLength)
            val filePercent = ((currentTotal * 100) / contentLength).toInt().coerceIn(0, 100)
            val overall = downloadOverallProgress(filePercent)
            callback.onDownloadProgress(fileName, overall, currentTotal, contentLength)
        }

        for (i in 0 until numThreads) {
            val start = i * chunkSize
            val end = if (i == numThreads - 1) contentLength - 1 else (i + 1) * chunkSize - 1
            val expected = end - start + 1

            futures.add(downloadExecutor.submit {
                var attempts = 0
                var success = false
                while (!success && attempts < 4) {
                    try {
                        val conn = openHttp(finalUrl)
                        conn.setRequestProperty("Range", "bytes=$start-$end")
                        conn.connect()
                        val code = conn.responseCode
                        if (code != 206 && code != 200) {
                            conn.disconnect()
                            throw Exception("Server returned $code for range $start-$end")
                        }
                        java.io.BufferedInputStream(conn.inputStream, DL_BUFFER_SIZE).use { input ->
                            java.io.RandomAccessFile(tempFile, "rw").use { raf ->
                                raf.seek(start)
                                val channel = raf.channel
                                val buffer = ByteArray(DL_BUFFER_SIZE)
                                var totalReadPart = 0L
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    if (Thread.currentThread().isInterrupted) {
                                        throw InterruptedException("Download thread interrupted")
                                    }
                                    // FileChannel is faster than RandomAccessFile.write for big chunks
                                    channel.write(java.nio.ByteBuffer.wrap(buffer, 0, bytesRead))
                                    totalReadPart += bytesRead
                                    progressMap[i] = totalReadPart
                                    emitProgress(force = false)
                                }
                                if (totalReadPart < expected && code == 206) {
                                    // Partial chunk — allow retry
                                    throw Exception("Short range read: got $totalReadPart expected $expected")
                                }
                            }
                        }
                        conn.disconnect()
                        success = true
                        emitProgress(force = true)
                    } catch (e: Exception) {
                        attempts++
                        progressMap[i] = 0L
                        Log.w(tag, "Range $start-$end attempt $attempts failed: ${e.message}")
                        if (attempts >= 4) throw e
                        Thread.sleep(400L * attempts)
                    }
                }
            })
        }

        try {
            futures.forEach { it.get() }
            emitProgress(force = true)
        } catch (e: Exception) {
            downloadExecutor.shutdownNow()
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
        downloadExecutor.shutdown()

        if (tempFile.exists()) {
            if (!tempFile.renameTo(outputFile)) {
                throw Exception("Failed to rename temp download file to target")
            }
        }
    }

    fun downloadFile(urlString: String, outputFile: File, callback: Bootstrap.BootstrapCallback? = null) {
        val tempFile = File(outputFile.absolutePath + ".tmp")
        if (tempFile.exists()) tempFile.delete()

        var currentUrl = urlString
        var redirectCount = 0
        val fileName = outputFile.name
        var lastEmit = 0L
        try {
            while (redirectCount < 8) {
                val connection = openHttp(currentUrl)
                connection.requestMethod = "GET"
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val redirectUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (redirectUrl != null) {
                        currentUrl = resolveRedirectLocation(currentUrl, redirectUrl)
                        redirectCount++
                        continue
                    }
                }

                if (responseCode != 200 && responseCode != 206) {
                    val host = publicHostLabel(currentUrl)
                    Log.e(tag, "Download HTTP $responseCode host=$host url=$currentUrl")
                    connection.disconnect()
                    throw Exception(
                        "Download server returned HTTP $responseCode ($host). " +
                            "Please retry."
                    )
                }

                val totalSize = connection.contentLengthLong
                java.io.BufferedInputStream(connection.inputStream, DL_BUFFER_SIZE).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val bufferedOut = java.io.BufferedOutputStream(output, DL_BUFFER_SIZE)
                        val buffer = ByteArray(DL_BUFFER_SIZE)
                        var bytesRead: Int
                        var totalRead = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            if (Thread.currentThread().isInterrupted) {
                                throw InterruptedException("Download interrupted by abort")
                            }
                            bufferedOut.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            val now = System.currentTimeMillis()
                            if (now - lastEmit >= DL_PROGRESS_MIN_INTERVAL_MS) {
                                lastEmit = now
                                if (totalSize > 0) {
                                    val filePercent = ((totalRead * 100) / totalSize).toInt()
                                    val overall = downloadOverallProgress(filePercent)
                                    callback?.onDownloadProgress(fileName, overall, totalRead, totalSize)
                                } else {
                                    callback?.onDownloadProgress(fileName, downloadBandStart(), totalRead, -1)
                                }
                            }
                        }
                        bufferedOut.flush()
                        // Final progress tick
                        if (totalSize > 0) {
                            callback?.onDownloadProgress(
                                fileName,
                                downloadOverallProgress(100),
                                totalRead,
                                totalSize
                            )
                        } else {
                            callback?.onDownloadProgress(fileName, downloadBandStart(), totalRead, -1)
                        }
                    }
                }
                connection.disconnect()
                break
            }

            if (tempFile.exists()) {
                if (!tempFile.renameTo(outputFile)) {
                    throw Exception("Failed to rename temp download file to target")
                }
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }
}
