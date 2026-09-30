package com.corlebell.aabinstaller.download

import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class DownloadResult(
    val file: File,
    val contentType: String?,
    val contentDisposition: String?
)

class AabDownloader {

    /**
     * @param onProgress downloadedBytes, totalBytes（未知时 total=-1）
     */
    fun download(
        url: String,
        dest: File,
        settings: DownloadSettings = DownloadSettings(),
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): DownloadResult {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".part")
        partial.delete()

        val probe = open(url).apply {
            setRequestProperty("Range", "bytes=0-0")
        }
        probe.connect()
        try {
            val code = probe.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            val contentType = probe.contentType
            val contentDisposition = probe.getHeaderField("Content-Disposition")
            val finalUrl = probe.url.toString()
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                val total = totalFromContentRange(probe.getHeaderField("Content-Range"))
                probe.disconnect()
                val segments = if (
                    settings.parallelEnabled &&
                    total != null &&
                    total >= MIN_PARALLEL_BYTES
                ) {
                    segmentCount(total, settings.segmentCount)
                } else {
                    1
                }
                if (segments >= 2 && total != null) {
                    downloadParallel(finalUrl, partial, total, segments, onProgress)
                } else {
                    downloadSingle(finalUrl, partial, settings.singleBufferSize, onProgress)
                }
            } else {
                val total = probe.contentLengthLong
                readToFile(
                    probe.inputStream,
                    partial,
                    settings.singleBufferSize,
                    total,
                    onProgress
                )
            }
            return finish(partial, dest, contentType, contentDisposition)
        } finally {
            probe.disconnect()
        }
    }

    private fun downloadSingle(
        url: String,
        dest: File,
        bufferSize: Int,
        onProgress: (Long, Long) -> Unit
    ) {
        val connection = open(url)
        connection.connect()
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            readToFile(
                connection.inputStream,
                dest,
                bufferSize,
                connection.contentLengthLong,
                onProgress
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadParallel(
        url: String,
        dest: File,
        total: Long,
        segments: Int,
        onProgress: (Long, Long) -> Unit
    ) {
        RandomAccessFile(dest, "rw").use { it.setLength(total) }
        val ranges = splitRanges(total, segments)
        val downloaded = AtomicLong(0)
        val failed = AtomicBoolean(false)
        val error = AtomicReference<Throwable>(null)
        val ticker = ProgressTicker(onProgress)
        val pool = Executors.newFixedThreadPool(segments)
        val connections = mutableListOf<HttpURLConnection>()
        try {
            val futures = ranges.map { (start, end) ->
                pool.submit {
                    if (failed.get()) return@submit
                    try {
                        downloadRange(url, dest, start, end, connections, failed) { n ->
                            val soFar = downloaded.addAndGet(n.toLong())
                            ticker.emit(soFar, total)
                        }
                    } catch (t: Throwable) {
                        failed.set(true)
                        error.compareAndSet(null, t)
                        synchronized(connections) {
                            connections.forEach { runCatching { it.disconnect() } }
                        }
                    }
                }
            }
            futures.forEach { it.get() }
            error.get()?.let { throw it }
            ticker.emit(total, total, force = true)
        } finally {
            pool.shutdownNow()
            if (failed.get()) dest.delete()
        }
    }

    private fun downloadRange(
        url: String,
        dest: File,
        start: Long,
        endInclusive: Long,
        connections: MutableList<HttpURLConnection>,
        failed: AtomicBoolean,
        onChunk: (Int) -> Unit
    ) {
        val connection = open(url).apply {
            setRequestProperty("Range", "bytes=$start-$endInclusive")
        }
        synchronized(connections) { connections.add(connection) }
        connection.connect()
        try {
            if (failed.get()) return
            if (connection.responseCode != HttpURLConnection.HTTP_PARTIAL) {
                throw IllegalStateException("分段请求未被接受 HTTP ${connection.responseCode}")
            }
            RandomAccessFile(dest, "rw").use { raf ->
                raf.seek(start)
                val buffer = ByteArray(PARALLEL_BUFFER)
                connection.inputStream.use { input ->
                    var remaining = endInclusive - start + 1
                    while (remaining > 0 && !failed.get()) {
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        raf.write(buffer, 0, n)
                        remaining -= n
                        onChunk(n)
                    }
                    if (remaining > 0 && !failed.get()) {
                        throw IllegalStateException("分段下载不完整")
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readToFile(
        input: InputStream,
        dest: File,
        bufferSize: Int,
        total: Long,
        onProgress: (Long, Long) -> Unit
    ) {
        val ticker = ProgressTicker(onProgress)
        input.use { source ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(bufferSize)
                var downloaded = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    ticker.emit(downloaded, total)
                }
                ticker.emit(downloaded, total, force = true)
            }
        }
    }

    private fun finish(
        partial: File,
        dest: File,
        contentType: String?,
        contentDisposition: String?
    ): DownloadResult {
        if (dest.exists()) dest.delete()
        if (!partial.renameTo(dest)) {
            partial.copyTo(dest, overwrite = true)
            partial.delete()
        }
        return DownloadResult(dest, contentType, contentDisposition)
    }

    private fun open(url: String): HttpURLConnection {
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AABInstaller/1.0")
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    private fun totalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val total = header.substringAfter('/', "").trim()
        if (total.isEmpty() || total == "*") return null
        return total.toLongOrNull()?.takeIf { it >= 0 }
    }

    private fun segmentCount(total: Long, requested: Int): Int {
        var count = requested.coerceIn(2, 8)
        while (count > 1 && total / count < MIN_PART_BYTES) {
            count--
        }
        return count
    }

    private fun splitRanges(total: Long, segments: Int): List<Pair<Long, Long>> {
        val size = total / segments
        return (0 until segments).map { index ->
            val start = index * size
            val end = if (index == segments - 1) total - 1 else (start + size - 1)
            start to end
        }
    }

    private class ProgressTicker(
        private val onProgress: (Long, Long) -> Unit
    ) {
        private val lastNs = AtomicLong(0)

        fun emit(downloaded: Long, total: Long, force: Boolean = false) {
            val now = System.nanoTime()
            val reportedTotal = if (total > 0) total else -1L
            if (force) {
                lastNs.set(now)
                onProgress(downloaded, reportedTotal)
                return
            }
            val prev = lastNs.get()
            if (now - prev >= PROGRESS_INTERVAL_NS && lastNs.compareAndSet(prev, now)) {
                onProgress(downloaded, reportedTotal)
            }
        }
    }

    companion object {
        private const val MIN_PARALLEL_BYTES = 4L * 1024 * 1024
        private const val MIN_PART_BYTES = 1L * 1024 * 1024
        private const val PARALLEL_BUFFER = 256 * 1024
        private const val PROGRESS_INTERVAL_NS = 200_000_000L
    }
}
