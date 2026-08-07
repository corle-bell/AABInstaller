package com.corlebell.aabinstaller.signing

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.corlebell.aabinstaller.R
import com.corlebell.aabinstaller.databinding.ActivitySigningBinding
import com.corlebell.aabinstaller.databinding.DialogSigningProfileBinding
import com.corlebell.bundletool.KeystoreSigning
import java.io.File

class SigningActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySigningBinding
    private lateinit var repo: SigningRepository
    private lateinit var adapter: SigningProfileAdapter

    private var pendingImportUri: Uri? = null
    private var exportProfile: SigningProfile? = null

    private val pickKeystore = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showProfileDialog(existing = null, importUri = uri)
        }
    }

    private val createExport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val profile = exportProfile ?: return@registerForActivityResult
        if (uri == null) return@registerForActivityResult
        val src = repo.keystoreFile(profile)
        if (src == null || !src.exists()) {
            toast("没有可导出的密钥库文件")
            return@registerForActivityResult
        }
        contentResolver.openOutputStream(uri)?.use { out ->
            src.inputStream().use { it.copyTo(out) }
        }
        toast("已导出 ${profile.name}")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySigningBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repo = SigningRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = SigningProfileAdapter(
            onSelect = {
                repo.setSelectedId(it.id)
                refresh()
                toast("已选用：${it.name}")
            },
            onEdit = { showProfileDialog(existing = it, importUri = null) },
            onDelete = { confirmDelete(it) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnImport.setOnClickListener {
            pickKeystore.launch(
                arrayOf(
                    "application/x-java-keystore",
                    "application/pkcs12",
                    "application/octet-stream",
                    "*/*"
                )
            )
        }
        binding.btnExport.setOnClickListener { exportSelected() }

        refresh()
    }

    private fun refresh() {
        adapter.submit(repo.getAll(), repo.getSelectedId())
    }

    private fun exportSelected() {
        val profile = repo.getSelected()
        if (profile.builtIn) {
            toast("内置调试签名无需导出；请先选用导入的签名")
            return
        }
        exportProfile = profile
        val name = profile.keystoreFileName.ifBlank { "${profile.name}.p12" }
        createExport.launch(name)
    }

    private fun confirmDelete(profile: SigningProfile) {
        AlertDialog.Builder(this)
            .setTitle(R.string.signing_delete)
            .setMessage("确定删除签名「${profile.name}」？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                repo.delete(profile.id)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showProfileDialog(existing: SigningProfile?, importUri: Uri?) {
        val dialogBinding = DialogSigningProfileBinding.inflate(LayoutInflater.from(this))
        if (existing != null) {
            dialogBinding.etName.setText(existing.name)
            dialogBinding.etAlias.setText(existing.alias)
            dialogBinding.etStorePassword.setText(existing.storePassword)
            dialogBinding.etKeyPassword.setText(existing.keyPassword)
            dialogBinding.tvKeystoreHint.text = "密钥库: ${existing.keystoreFileName}"
        } else if (importUri != null) {
            val display = queryDisplayName(importUri) ?: "keystore"
            dialogBinding.etName.setText(display.substringBeforeLast('.'))
            dialogBinding.tvKeystoreHint.text = "将导入: $display"
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.signing_import else R.string.signing_edit)
            .setView(dialogBinding.root)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
                        val alias = dialogBinding.etAlias.text?.toString()?.trim().orEmpty()
                        val storePass = dialogBinding.etStorePassword.text?.toString().orEmpty()
                        val keyPass = dialogBinding.etKeyPassword.text?.toString()
                            ?.ifBlank { storePass }.orEmpty()
                        if (name.isBlank() || alias.isBlank() || storePass.isBlank()) {
                            toast("名称、别名、密钥库密码不能为空")
                            return@setOnClickListener
                        }
                        try {
                            if (existing == null) {
                                val uri = importUri ?: return@setOnClickListener
                                importAndSave(uri, name, alias, storePass, keyPass)
                            } else {
                                updateProfile(existing, name, alias, storePass, keyPass)
                            }
                            dialog.dismiss()
                            refresh()
                        } catch (t: Throwable) {
                            toast("失败: ${t.message}")
                        }
                    }
                }
            }
            .show()
    }

    private fun importAndSave(
        uri: Uri,
        name: String,
        alias: String,
        storePass: String,
        keyPass: String
    ) {
        val display = queryDisplayName(uri) ?: "imported.keystore"
        val id = repo.newId()
        val rawName = "${id}_${sanitizeFileName(display)}"
        val rawFile = File(repo.keystoresDir, rawName)
        contentResolver.openInputStream(uri)?.use { input ->
            rawFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取密钥库文件")

        // 统一落盘为 PKCS12，避免 Android 上 JKS/签名不一致
        val p12Name = "${id}.p12"
        val p12File = File(repo.keystoresDir, p12Name)
        val loaded = KeystoreSigning.materializeAsPkcs12(
            sourceFile = rawFile,
            destPkcs12 = p12File,
            storePassword = storePass,
            alias = alias,
            keyPassword = keyPass
        )
        if (rawFile.absolutePath != p12File.absolutePath) {
            rawFile.delete()
        }

        val profile = SigningProfile(
            id = id,
            name = name,
            alias = alias,
            storePassword = storePass,
            keyPassword = keyPass,
            keystoreFileName = p12Name,
            storeType = "PKCS12"
        )
        repo.save(profile)
        repo.setSelectedId(profile.id)
        toast("已导入并选用：${profile.name}\nSHA-256: ${loaded.certificateSha256}")
    }

    private fun updateProfile(
        existing: SigningProfile,
        name: String,
        alias: String,
        storePass: String,
        keyPass: String
    ) {
        val file = repo.keystoreFile(existing)
            ?: throw IllegalStateException("密钥库文件丢失")
        val loaded = KeystoreSigning.detectAndLoad(
            file, storePass, alias, keyPass, existing.storeType
        )
        // 若仍是 JKS，转成 PKCS12
        var storeType = loaded.storeType
        var fileName = existing.keystoreFileName
        if (storeType.equals("JKS", ignoreCase = true)) {
            val p12Name = "${existing.id}.p12"
            val p12File = File(repo.keystoresDir, p12Name)
            KeystoreSigning.materializeAsPkcs12(file, p12File, storePass, alias, keyPass)
            if (file.absolutePath != p12File.absolutePath) file.delete()
            storeType = "PKCS12"
            fileName = p12Name
        }
        repo.save(
            existing.copy(
                name = name,
                alias = alias,
                storePassword = storePass,
                keyPassword = keyPass,
                keystoreFileName = fileName,
                storeType = storeType
            )
        )
        toast("已更新：${name}\nSHA-256: ${loaded.certificateSha256}")
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_")

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
