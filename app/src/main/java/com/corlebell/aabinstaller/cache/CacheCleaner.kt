package com.corlebell.aabinstaller.cache

import android.content.Context
import java.io.File

object CacheCleaner {

    /** 转换工作目录 */
    fun convertDir(context: Context): File = File(context.cacheDir, "convert")

    /**
     * 清理转换缓存（cache/convert）以及 cache 下其它临时产物。
     * 不删除 downloads 与 keystores。
     * @return 释放的大致字节数
     */
    fun clearConvertCache(context: Context): Long {
        var freed = 0L
        val convert = convertDir(context)
        if (convert.exists()) {
            freed += sizeOf(convert)
            convert.deleteRecursively()
        }
        // 清理 cache 根目录下残留的 .apk / .apks / .aab
        context.cacheDir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (file.isFile && (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".aab"))) {
                freed += file.length()
                file.delete()
            }
        }
        return freed
    }

    fun convertCacheSize(context: Context): Long {
        var size = sizeOf(convertDir(context))
        context.cacheDir.listFiles()?.forEach { file ->
            val name = file.name.lowercase()
            if (file.isFile && (name.endsWith(".apk") || name.endsWith(".apks") || name.endsWith(".aab"))) {
                size += file.length()
            }
        }
        return size
    }

    private fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { sizeOf(it) } ?: 0
    }
}
