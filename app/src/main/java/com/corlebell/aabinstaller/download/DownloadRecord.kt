package com.corlebell.aabinstaller.download

enum class DownloadStatus {
    DOWNLOADING,
    COMPLETED,
    FAILED
}

data class DownloadRecord(
    val id: String,
    val url: String,
    val fileName: String,
    val localPath: String,
    val size: Long,
    val status: DownloadStatus,
    val errorMessage: String = "",
    val createdAt: Long = System.currentTimeMillis()
)
