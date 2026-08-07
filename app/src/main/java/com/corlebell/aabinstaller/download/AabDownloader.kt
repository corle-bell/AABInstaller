package com.corlebell.aabinstaller.download

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class AabDownloader {

    /**
     * @param onProgress downloadedBytes, totalBytes（未知时 total=-1）
     */
    fun download(
        url: String,
        dest: File,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): File {
        dest.parentFile?.mkdirs()
        val partial = File(dest.parentFile, dest.name + ".part")
        partial.delete()

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AABInstaller/1.0")
        }

        try {
            connection.connect()
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("下载失败 HTTP $code")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
            return dest
        } finally {
            connection.disconnect()
        }
    }
}
