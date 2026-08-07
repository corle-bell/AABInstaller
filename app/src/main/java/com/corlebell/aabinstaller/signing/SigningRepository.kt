package com.corlebell.aabinstaller.signing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class SigningRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val profilesFile = File(context.filesDir, "signing_profiles.json")
    val keystoresDir: File = File(context.filesDir, "keystores").also { it.mkdirs() }

    fun getSelectedId(): String =
        prefs.getString(KEY_SELECTED, BUILTIN_ID) ?: BUILTIN_ID

    fun setSelectedId(id: String) {
        // 必须 commit：立刻对后续转换可见，避免 apply 异步导致仍用内置签名
        prefs.edit().putString(KEY_SELECTED, id).commit()
    }

    /**
     * UI 展示用：若选中项丢失则回退并修复为内置。
     */
    fun getSelected(): SigningProfile {
        val id = getSelectedId()
        val found = getAll().firstOrNull { it.id == id }
        if (found != null) return found
        setSelectedId(BUILTIN_ID)
        return builtinProfile()
    }

    /**
     * 转换前调用：选中项必须真实存在，禁止静默落到内置签名。
     */
    fun requireSelectedForConvert(): SigningProfile {
        val id = getSelectedId()
        return getAll().firstOrNull { it.id == id }
            ?: throw IllegalStateException(
                "选中的签名配置不存在 (id=$id)。请到「签名配置」重新选择或导入。"
            )
    }

    fun getAll(): List<SigningProfile> {
        val custom = loadCustom()
        return listOf(builtinProfile()) + custom
    }

    fun getById(id: String): SigningProfile? =
        getAll().firstOrNull { it.id == id }

    fun save(profile: SigningProfile) {
        val list = loadCustom().toMutableList()
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) list[idx] = profile else list.add(profile)
        persistCustom(list)
    }

    fun delete(id: String) {
        if (id == BUILTIN_ID) return
        val list = loadCustom().toMutableList()
        val removed = list.firstOrNull { it.id == id }
        list.removeAll { it.id == id }
        persistCustom(list)
        removed?.keystoreFileName?.takeIf { it.isNotBlank() }?.let {
            File(keystoresDir, it).delete()
        }
        if (getSelectedId() == id) setSelectedId(BUILTIN_ID)
    }

    fun keystoreFile(profile: SigningProfile): File? {
        if (profile.builtIn || profile.keystoreFileName.isBlank()) return null
        return File(keystoresDir, profile.keystoreFileName)
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun builtinProfile() = SigningProfile(
        id = BUILTIN_ID,
        name = "内置调试签名",
        alias = "aabinstaller",
        storePassword = "android",
        keyPassword = "android",
        builtIn = true
    )

    private fun loadCustom(): List<SigningProfile> {
        if (!profilesFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(profilesFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SigningProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    alias = o.getString("alias"),
                    storePassword = o.optString("storePassword", ""),
                    keyPassword = o.optString("keyPassword", ""),
                    keystoreFileName = o.optString("keystoreFileName", ""),
                    storeType = o.optString("storeType", "PKCS12"),
                    builtIn = false
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistCustom(list: List<SigningProfile>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("alias", p.alias)
                    .put("storePassword", p.storePassword)
                    .put("keyPassword", p.keyPassword)
                    .put("keystoreFileName", p.keystoreFileName)
                    .put("storeType", p.storeType)
            )
        }
        profilesFile.writeText(arr.toString(2))
    }

    companion object {
        const val BUILTIN_ID = "builtin-debug"
        private const val PREFS = "signing"
        private const val KEY_SELECTED = "selected_id"
    }
}
