package com.corlebell.aabinstaller.download

import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

enum class PackageKind {
    APK,
    AAB,
    UNKNOWN
}

data class ResolvedPackage(
    val fileName: String,
    val kind: PackageKind
)

object PackageKindResolver {

    fun fromFileName(name: String): PackageKind? = when {
        name.endsWith(".apk", ignoreCase = true) -> PackageKind.APK
        name.endsWith(".aab", ignoreCase = true) -> PackageKind.AAB
        else -> null
    }

    /**
     * 类型判断：Content-Disposition 文件名后缀，其次 URL 路径后缀，最后看 ZIP 根条目。
     */
    fun resolve(url: String, contentDisposition: String?, file: File): ResolvedPackage {
        val headerName = fileNameFromContentDisposition(contentDisposition)?.let(::sanitize)
        val urlName = fileNameFromUrl(url)?.let(::sanitize)
        val kind = fromFileName(headerName.orEmpty())
            ?: fromFileName(urlName.orEmpty())
            ?: sniff(file)
        val rawName = when {
            headerName != null && fromFileName(headerName) != null -> headerName
            urlName != null && fromFileName(urlName) != null -> urlName
            headerName != null -> headerName
            urlName != null -> urlName
            else -> "download-${System.currentTimeMillis()}"
        }
        return ResolvedPackage(withKindExtension(rawName, kind), kind)
    }

    fun fileNameFromUrl(url: String): String? {
        val last = url.substringAfterLast('/').substringBefore('?').trim()
        if (last.isBlank()) return null
        return runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
    }

    fun fileNameFromContentDisposition(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val encoded = Regex("filename\\*=(?:UTF-8|utf-8)''([^;]+)", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            return runCatching { URLDecoder.decode(encoded.trim(), "UTF-8") }.getOrNull()
        }
        val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
            .find(header)
            ?.groupValues
            ?.getOrNull(1)
        return plain?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun sanitize(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        return cleaned.take(180).ifBlank { "download" }
    }

    fun sniff(file: File): PackageKind {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                var apk = false
                while (entries.hasMoreElements()) {
                    val name = entries.nextElement().name
                    if (name == "BundleConfig.pb") return PackageKind.AAB
                    if (name == "AndroidManifest.xml") apk = true
                }
                if (apk) PackageKind.APK else PackageKind.UNKNOWN
            }
        } catch (_: Exception) {
            PackageKind.UNKNOWN
        }
    }

    private fun withKindExtension(name: String, kind: PackageKind): String {
        if (fromFileName(name) != null) return name
        val suffix = when (kind) {
            PackageKind.APK -> ".apk"
            PackageKind.AAB -> ".aab"
            PackageKind.UNKNOWN -> ""
        }
        return name + suffix
    }
}
