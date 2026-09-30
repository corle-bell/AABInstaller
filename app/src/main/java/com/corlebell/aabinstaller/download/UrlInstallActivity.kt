package com.corlebell.aabinstaller.download

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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
    private val downloader = AabDownloader()
    private val installer by lazy { SystemApkInstaller(this) }

    private var apksAwaitingPermission: List<File>? = null
    private var busy = false

    private val recordsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = result.data?.getStringExtra(DownloadRecordsActivity.EXTRA_INSTALL_ID) ?: return@registerForActivityResult
        val record = downloadRepo.getById(id) ?: return@registerForActivityResult
        routeInstall(File(record.localPath), record.fileName, record.kind, resetLog = true)
    }

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
        binding.toolbar.inflateMenu(R.menu.menu_url_install)
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_download_records -> {
                    recordsLauncher.launch(Intent(this, DownloadRecordsActivity::class.java))
                    true
                }
                R.id.action_download_settings -> {
                    startActivity(Intent(this, DownloadSettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.btnScan.setOnClickListener { startScan() }
        binding.btnDownload.setOnClickListener { startDownload(installAfter = false) }
        binding.btnDownloadInstall.setOnClickListener { startDownload(installAfter = true) }

        lifecycleScope.launch {
            ConversionLog.content.collect { log ->
                binding.tvLog.text = log
                binding.scrollLog.post {
                    binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
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
        val provisionalKind = PackageKindResolver.fromFileName(fileName) ?: PackageKind.UNKNOWN
        if (installAfter) {
            ConversionLog.reset()
            ConversionLog.append("开始下载并安装: $fileName")
        }
        val dest = File(downloadRepo.downloadsDir, "${id}_${PackageKindResolver.sanitize(fileName)}")
        var record = DownloadRecord(
            id = id,
            url = url,
            fileName = fileName,
            localPath = dest.absolutePath,
            size = 0,
            status = DownloadStatus.DOWNLOADING,
            kind = provisionalKind
        )
        downloadRepo.upsert(record)

        lifecycleScope.launch {
            try {
                val downloaded = withContext(Dispatchers.IO) {
                    downloader.download(url, dest, DownloadSettingsStore(this@UrlInstallActivity).get()) { downloaded, total ->
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
                val resolved = PackageKindResolver.resolve(
                    url,
                    downloaded.contentDisposition,
                    downloaded.file
                )
                val file = withContext(Dispatchers.IO) {
                    placeDownload(downloaded.file, id, resolved.fileName)
                }
                record = record.copy(
                    fileName = resolved.fileName,
                    localPath = file.absolutePath,
                    size = file.length(),
                    status = DownloadStatus.COMPLETED,
                    errorMessage = "",
                    kind = resolved.kind
                )
                downloadRepo.upsert(record)
                toast("下载完成: ${resolved.fileName}")
                if (installAfter) {
                    ConversionLog.append(
                        "下载完成: ${resolved.fileName} (${MainViewModel.formatSize(file.length())})"
                    )
                    routeInstall(file, resolved.fileName, resolved.kind, resetLog = false)
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
                busy = false
                setProgressVisible(false)
                if (installAfter) {
                    ConversionLog.append("下载失败: ${t.message ?: t.javaClass.simpleName}")
                }
                toast("下载失败: ${t.message}")
            }
        }
    }

    private fun routeInstall(
        file: File,
        displayName: String,
        kind: PackageKind,
        resetLog: Boolean
    ) {
        when (kind) {
            PackageKind.APK -> installApkDirect(file, displayName, resetLog)
            PackageKind.AAB -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    convertAndInstall(file, displayName, resetLog)
                } else {
                    if (resetLog) ConversionLog.reset()
                    ConversionLog.append(getString(R.string.aab_requires_o))
                    toast(getString(R.string.aab_requires_o))
                    busy = false
                    setProgressVisible(false)
                }
            }
            PackageKind.UNKNOWN -> {
                if (resetLog) ConversionLog.reset()
                ConversionLog.append(getString(R.string.unknown_package))
                toast(getString(R.string.unknown_package))
                busy = false
                setProgressVisible(false)
            }
        }
    }

    private fun installApkDirect(apk: File, displayName: String, resetLog: Boolean) {
        if (!apk.exists() || apk.length() <= 0L) {
            toast("文件不存在")
            busy = false
            setProgressVisible(false)
            return
        }
        if (resetLog) ConversionLog.reset()
        ConversionLog.append(
            "准备安装 APK: $displayName (${MainViewModel.formatSize(apk.length())})"
        )
        busy = false
        setProgressVisible(false)
        tryInstall(listOf(apk))
    }

    private fun placeDownload(downloaded: File, id: String, fileName: String): File {
        val dest = File(
            downloadRepo.downloadsDir,
            "${id}_${PackageKindResolver.sanitize(fileName)}"
        )
        if (downloaded.absolutePath == dest.absolutePath) return downloaded
        if (dest.exists()) dest.delete()
        if (!downloaded.renameTo(dest)) {
            downloaded.copyTo(dest, overwrite = true)
            downloaded.delete()
        }
        return dest
    }

    private fun convertAndInstall(
        aabFile: File,
        displayName: String,
        resetLog: Boolean = true
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (resetLog) ConversionLog.reset()
            ConversionLog.append(getString(R.string.aab_requires_o))
            toast(getString(R.string.aab_requires_o))
            busy = false
            setProgressVisible(false)
            return
        }
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
                .setMessage(installPermissionMessage())
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

    private fun installPermissionMessage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getString(R.string.perm_dialog_message)
        } else {
            getString(R.string.perm_dialog_message_legacy)
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "AABInstaller"
    }
}
