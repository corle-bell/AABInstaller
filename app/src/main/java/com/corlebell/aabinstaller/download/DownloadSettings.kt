package com.corlebell.aabinstaller.download

import android.content.Context

data class DownloadSettings(
    val parallelEnabled: Boolean = true,
    val segmentCount: Int = 4,
    val enlargeSingleBuffer: Boolean = true
) {
    val singleBufferSize: Int
        get() = if (enlargeSingleBuffer) BUFFER_LARGE else BUFFER_SMALL

    companion object {
        const val BUFFER_SMALL = 64 * 1024
        const val BUFFER_LARGE = 1024 * 1024
        val SEGMENT_CHOICES = intArrayOf(2, 4, 8)
    }
}

class DownloadSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(): DownloadSettings {
        val segments = prefs.getInt(KEY_SEGMENTS, 4)
        return DownloadSettings(
            parallelEnabled = prefs.getBoolean(KEY_PARALLEL, true),
            segmentCount = segments.takeIf { it in DownloadSettings.SEGMENT_CHOICES } ?: 4,
            enlargeSingleBuffer = prefs.getBoolean(KEY_ENLARGE_BUFFER, true)
        )
    }

    fun save(settings: DownloadSettings) {
        prefs.edit()
            .putBoolean(KEY_PARALLEL, settings.parallelEnabled)
            .putInt(KEY_SEGMENTS, settings.segmentCount)
            .putBoolean(KEY_ENLARGE_BUFFER, settings.enlargeSingleBuffer)
            .commit()
    }

    companion object {
        private const val PREFS = "download_settings"
        private const val KEY_PARALLEL = "parallel"
        private const val KEY_SEGMENTS = "segments"
        private const val KEY_ENLARGE_BUFFER = "enlarge_buffer"
    }
}
