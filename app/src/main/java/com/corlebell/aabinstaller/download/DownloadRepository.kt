package com.corlebell.aabinstaller.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class DownloadRepository(private val context: Context) {

    private val indexFile = File(context.filesDir, "download_records.json")
    val downloadsDir: File = File(context.filesDir, "downloads").also { it.mkdirs() }

    fun getAll(): List<DownloadRecord> {
        if (!indexFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i -> fromJson(arr.getJSONObject(i)) }
                .sortedByDescending { it.createdAt }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getById(id: String): DownloadRecord? = getAll().firstOrNull { it.id == id }

    fun upsert(record: DownloadRecord) {
        val list = getAll().toMutableList()
        val idx = list.indexOfFirst { it.id == record.id }
        if (idx >= 0) list[idx] = record else list.add(0, record)
        persist(list)
    }

    fun delete(ids: Collection<String>) {
        val idSet = ids.toSet()
        val list = getAll().toMutableList()
        list.filter { it.id in idSet }.forEach { rec ->
            File(rec.localPath).delete()
        }
        persist(list.filterNot { it.id in idSet })
    }

    fun newId(): String = UUID.randomUUID().toString()

    fun suggestFileName(url: String): String {
        val last = url.substringAfterLast('/').substringBefore('?')
        return if (last.endsWith(".aab", ignoreCase = true)) last
        else "download-${System.currentTimeMillis()}.aab"
    }

    private fun persist(list: List<DownloadRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        indexFile.writeText(arr.toString(2))
    }

    private fun toJson(r: DownloadRecord) = JSONObject()
        .put("id", r.id)
        .put("url", r.url)
        .put("fileName", r.fileName)
        .put("localPath", r.localPath)
        .put("size", r.size)
        .put("status", r.status.name)
        .put("errorMessage", r.errorMessage)
        .put("createdAt", r.createdAt)

    private fun fromJson(o: JSONObject) = DownloadRecord(
        id = o.getString("id"),
        url = o.getString("url"),
        fileName = o.getString("fileName"),
        localPath = o.getString("localPath"),
        size = o.optLong("size", 0),
        status = runCatching { DownloadStatus.valueOf(o.getString("status")) }
            .getOrDefault(DownloadStatus.FAILED),
        errorMessage = o.optString("errorMessage", ""),
        createdAt = o.optLong("createdAt", 0)
    )
}
