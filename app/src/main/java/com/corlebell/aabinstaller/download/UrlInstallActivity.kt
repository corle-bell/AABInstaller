package com.corlebell.aabinstaller.download

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.corlebell.aabinstaller.ConversionLog
import com.corlebell.aabinstaller.MainViewModel
import com.corlebell.aabinstaller.R
import com.corlebell.aabinstaller.databinding.ActivityUrlInstallBinding
import com.corlebell.aabinstaller.signing.SigningConfigLoader
import com.corlebell.aabinstaller.signing.SigningRepository
import com.corlebell.bundletool.UniversalApkBuilder
import com.corlebell.installer.ApkInstaller
import com.corlebell.installer.SystemApkInstaller
import com.google.zxing.integration.android.IntentIntegrator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class UrlInstallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityUrlInstallBinding
    private lateinit var downloadRepo: DownloadRepository
    private lateinit var adapter: DownloadListAdapter
    private val downloader = AabDownloader()
    private val installer by lazy { SystemApkInstaller(this) }

    private var apksAwaitingPermission: List<File>? = null
    private var busy = false

    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val intentResult = IntentIntegrator.parseActivityResult(result.resultCode, result.data)
        val contents = intentResult?.contents
        if (!contents.isNullOrBlank()) {
            binding.etUrl.setText(contents.trim())
            toast("已填入扫码结果")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUrlInstallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        downloadRepo = DownloadRepository(this)

        binding.toolbar.setNavigationOnClickListener { finish() }
        adapter = DownloadListAdapter(
            onSelectionChanged = {
                binding.btnDeleteSelected.isEnabled = adapter.selectedIds().isNotEmpty()
            },
            onInstall = { convertAndInstall(File(it.localPath), it.fileName) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnDownload.setOnClickListener { startDownload(installAfter = false) }
        binding.btnDownloadInstall.setOnClickListener { startDownload(installAfter = true) }
        binding.btnDeleteSelected.setOnClickListener { deleteSelected() }

        lifecycleScope.launch {
            ConversionLog.content.collect { log ->
                binding.tvLog.text = log
                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        apksAwaitingPermission?.let { apks ->
            if (installer.canRequestInstalls()) {
                apksAwaitingPermission = null
                installApks(apks)
            }
        }
    }

    private fun startScan() {
        val integrator = IntentIntegrator(this)
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
        integrator.setPrompt(getString(R.string.url_scan_prompt))
        integrator.setBeepEnabled(false)
        integrator.setOrientationLocked(true)
        scanLauncher.launch(integrator.createScanIntent())
    }

    private fun currentUrl(): String =
        binding.etUrl.text?.toString()?.trim().orEmpty()

    private fun startDownload(installAfter: Boolean) {
        if (busy) return
        val url = currentUrl()
        if (url.isBlank()) {
            toast("请输入或扫码填入链接")
            return
        }
        if (!url.startsWith("http://", ignoreCase = true) &&
            !url.startsWith("https://", ignoreCase = true)
        ) {
            toast("链接需以 http:// 或 https:// 开头")
            return
        }

        busy = true
        setProgressVisible(true, 0, "准备下载…")
        val id = downloadRepo.newId()
        val fileName = downloadRepo.suggestFileName(url)
        if (installAfter) {
            ConversionLog.reset()
            ConversionLog.append("开始下载并安装: $fileName")
        }
        val dest = File(downloadRepo.downloadsDir, "${id}_$fileName")
        var record = DownloadRecord(
            id = id,
            url = url,
            fileName = fileName,
            localPath = dest.absolutePath,
            size = 0,
            status = DownloadStatus.DOWNLOADING
        )
        downloadRepo.upsert(record)
        refreshList()

        lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    downloader.download(url, dest) { downloaded, total ->
                        runOnUiThread {
                            if (total > 0) {
                                val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                                setProgressVisible(
                                    true, pct,
                                    "下载中 ${MainViewModel.formatSize(downloaded)} / ${MainViewModel.formatSize(total)}"
                                )
                            } else {
                                setProgressVisible(
                                    true, -1,
                                    "下载中 ${MainViewModel.formatSize(downloaded)}"
                                )
                            }
                        }
                    }
                }
                record = record.copy(
                    size = file.length(),
                    status = DownloadStatus.COMPLETED,
                    errorMessage = ""
                )
                downloadRepo.upsert(record)
                refreshList()
                toast("下载完成: ${file.name}")
                if (installAfter) {
                    ConversionLog.append(
                        "下载完成: ${file.name} (${MainViewModel.formatSize(file.length())})"
                    )
                }
                if (installAfter) {
                    convertAndInstall(file, fileName, resetLog = false)
                } else {
                    busy = false
                    setProgressVisible(false)
                }
            } catch (t: Throwable) {
                record = record.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
                downloadRepo.upsert(record)
                refreshList()
                busy = false
                setProgressVisible(false)
                if (installAfter) {
                    ConversionLog.append("下载失败: ${t.message ?: t.javaClass.simpleName}")
                }
                toast("下载失败: ${t.message}")
            }
        }
    }

    private fun convertAndInstall(
        aabFile: File,
        displayName: String,
        resetLog: Boolean = true
    ) {
        if (!aabFile.exists()) {
            toast("文件不存在")
            busy = false
            setProgressVisible(false)
            return
        }
        if (resetLog) ConversionLog.reset()
        ConversionLog.append(
            "开始转换: $displayName (${MainViewModel.formatSize(aabFile.length())})"
        )
        busy = true
        setProgressVisible(true, -1, "正在转换 $displayName …")
        lifecycleScope.launch {
            try {
                val apks = withContext(Dispatchers.IO) {
                    val workDir = File(cacheDir, "convert").apply {
                        deleteRecursively()
                        mkdirs()
                    }
                    if (aabFile.length() > 0 && cacheDir.usableSpace < aabFile.length() * 3) {
                        throw IllegalStateException(
                            "存储空间不足，至少需要 ${MainViewModel.formatSize(aabFile.length() * 3)}"
                        )
                    }
                    // 若源文件不在 convert 目录，复制一份作为 input（避免边下边转冲突）
                    val input = File(workDir, "input.aab")
                    if (aabFile.absolutePath != input.absolutePath) {
                        aabFile.copyTo(input, overwrite = true)
                    }
                    ConversionLog.append("已复制 AAB 到转换目录")
                    val profile = SigningRepository(this@UrlInstallActivity).requireSelectedForConvert()
                    val resolved = SigningConfigLoader.resolve(this@UrlInstallActivity, profile)
                    ConversionLog.append("签名: ${resolved.profileName}")
                    ConversionLog.append("证书: ${resolved.subjectDn}")
                    ConversionLog.append("证书 SHA-256: ${resolved.certificateSha256}")
                    runOnUiThread {
                        setProgressVisible(
                            true, -1,
                            "签名 ${resolved.profileName}\nSHA-256: ${resolved.certificateSha256}"
                        )
                    }
                    val result = UniversalApkBuilder(this@UrlInstallActivity).build(
                        aab = input,
                        outputDir = workDir,
                        signingConfiguration = resolved.configuration
                    )
                    result.forEach { apk ->
                        val md5 = UniversalApkBuilder.md5(apk)
                        ConversionLog.append(
                            "APK: ${apk.name} | ${MainViewModel.formatSize(apk.length())} | MD5: $md5"
                        )
                        Log.i(
                            TAG,
                            "APK: ${apk.name} | ${MainViewModel.formatSize(apk.length())} | " +
                                "MD5: $md5"
                        )
                    }
                    result
                }
                setProgressVisible(false)
                busy = false
                ConversionLog.append("转换完成: ${apks.size} 个设备匹配 APK")
                toast("已生成 ${apks.size} 个 APK，MD5 已输出到 Logcat")
                tryInstall(apks)
            } catch (t: Throwable) {
                busy = false
                setProgressVisible(false)
                ConversionLog.append("转换失败: ${t.message ?: t.javaClass.simpleName}")
                toast("转换失败: ${t.message}")
            }
        }
    }

    private fun tryInstall(apks: List<File>) {
        if (installer.canRequestInstalls()) {
            installApks(apks)
        } else {
            apksAwaitingPermission = apks
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_dialog_title)
                .setMessage(R.string.perm_dialog_message)
                .setPositiveButton(R.string.perm_dialog_go) { _, _ ->
                    installer.requestInstallPermission(this)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun installApks(apks: List<File>) {
        installer.install(apks) { result ->
            runOnUiThread {
                when (result) {
                    ApkInstaller.Result.AwaitingUserConfirmation -> {
                        ConversionLog.append("等待用户确认安装…")
                        toast("请确认安装 ${apks.size} 个 APK")
                    }
                    ApkInstaller.Result.Success -> {
                        ConversionLog.append("安装成功")
                        toast("安装成功")
                    }
                    is ApkInstaller.Result.Failure -> {
                        ConversionLog.append("安装失败: ${result.message}")
                        toast("安装失败: ${result.message}")
                    }
                }
            }
        }
    }

    private fun deleteSelected() {
        val ids = adapter.selectedIds()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.url_delete_selected)
            .setMessage("确定删除选中的 ${ids.size} 条下载记录及本地文件？")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                downloadRepo.delete(ids)
                adapter.clearSelection()
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refreshList() {
        adapter.submit(downloadRepo.getAll())
    }

    private fun setProgressVisible(visible: Boolean, progress: Int = 0, text: String = "") {
        binding.progress.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvProgress.visibility = if (visible) View.VISIBLE else View.GONE
        binding.tvProgress.text = text
        if (progress < 0) {
            binding.progress.isIndeterminate = true
        } else {
            binding.progress.isIndeterminate = false
            binding.progress.progress = progress
        }
        binding.btnDownload.isEnabled = !visible
        binding.btnDownloadInstall.isEnabled = !visible
        binding.btnScan.isEnabled = !visible
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AABInstaller"
    }
}
