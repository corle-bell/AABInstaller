package com.corlebell.aabinstaller

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.corlebell.aabinstaller.cache.CacheCleaner
import com.corlebell.aabinstaller.signing.SigningConfigLoader
import com.corlebell.aabinstaller.signing.SigningRepository
import com.corlebell.bundletool.UniversalApkBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SelectedAab(
    val uri: Uri,
    val name: String,
    val size: Long
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val signingRepo = SigningRepository(application)

    private val _state = MutableStateFlow(ConvertState.IDLE)
    val state: StateFlow<ConvertState> = _state.asStateFlow()

    val log: StateFlow<String> = ConversionLog.content

    private val _selected = MutableStateFlow<SelectedAab?>(null)
    val selected: StateFlow<SelectedAab?> = _selected.asStateFlow()

    private val _signingName = MutableStateFlow(signingRepo.getSelected().name)
    val signingName: StateFlow<String> = _signingName.asStateFlow()

    /** 转换成功后待安装的 base + split APK 集 */
    private val _installRequest = MutableSharedFlow<List<File>>(extraBufferCapacity = 1)
    val installRequest: SharedFlow<List<File>> = _installRequest.asSharedFlow()

    var pendingApks: List<File>? = null
        private set

    fun refreshSigningLabel() {
        _signingName.value = signingRepo.getSelected().name
    }

    fun onFilePicked(uri: Uri) {
        val app = getApplication<Application>()
        var name = "unknown.aab"
        var size = -1L
        app.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
            }
        }
        _selected.value = SelectedAab(uri, name, size)
        _state.value = ConvertState.IDLE
        appendLog("已选择: $name (${formatSize(size)})")
    }

    fun onLocalAabPicked(file: File) {
        _selected.value = SelectedAab(Uri.fromFile(file), file.name, file.length())
        _state.value = ConvertState.IDLE
        appendLog("已选择本地文件: ${file.name} (${formatSize(file.length())})")
        startConvertFromFile(file)
    }

    fun startConvert() {
        val selected = _selected.value ?: return
        if (isWorking()) return

        ConversionLog.reset()
        viewModelScope.launch {
            try {
                val apks = withContext(Dispatchers.IO) { doConvertFromUri(selected) }
                pendingApks = apks
                _state.value = ConvertState.INSTALLING
                appendLog("转换完成: ${apks.size} 个设备匹配 APK")
                _installRequest.tryEmit(apks)
            } catch (t: Throwable) {
                _state.value = ConvertState.ERROR
                appendLog("失败: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun startConvertFromFile(file: File) {
        if (isWorking()) return
        ConversionLog.reset()
        viewModelScope.launch {
            try {
                val apks = withContext(Dispatchers.IO) { doConvertFromFile(file) }
                pendingApks = apks
                _state.value = ConvertState.INSTALLING
                appendLog("转换完成: ${apks.size} 个设备匹配 APK")
                _installRequest.tryEmit(apks)
            } catch (t: Throwable) {
                _state.value = ConvertState.ERROR
                appendLog("失败: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    private fun isWorking(): Boolean =
        _state.value in listOf(
            ConvertState.COPYING, ConvertState.PARSING,
            ConvertState.BUILDING, ConvertState.SIGNING
        )

    private fun doConvertFromUri(selected: SelectedAab): List<File> {
        val app = getApplication<Application>()
        val workDir = CacheCleaner.convertDir(app).apply {
            deleteRecursively()
            mkdirs()
        }

        if (selected.size > 0 && app.cacheDir.usableSpace < selected.size * 3) {
            throw IllegalStateException(
                "存储空间不足，至少需要 ${formatSize(selected.size * 3)} 可用空间"
            )
        }

        _state.value = ConvertState.COPYING
        appendLog("正在复制 AAB 到缓存目录…")
        val aabFile = File(workDir, "input.aab")
        app.contentResolver.openInputStream(selected.uri)?.use { input ->
            aabFile.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("无法读取所选文件")

        return buildApk(aabFile, workDir)
    }

    private fun doConvertFromFile(source: File): List<File> {
        val app = getApplication<Application>()
        val workDir = CacheCleaner.convertDir(app).apply {
            deleteRecursively()
            mkdirs()
        }
        if (source.length() > 0 && app.cacheDir.usableSpace < source.length() * 3) {
            throw IllegalStateException(
                "存储空间不足，至少需要 ${formatSize(source.length() * 3)} 可用空间"
            )
        }
        _state.value = ConvertState.COPYING
        appendLog("正在准备 AAB…")
        val aabFile = File(workDir, "input.aab")
        source.copyTo(aabFile, overwrite = true)
        return buildApk(aabFile, workDir)
    }

    private fun buildApk(aabFile: File, workDir: File): List<File> {
        val app = getApplication<Application>()
        val profile = signingRepo.requireSelectedForConvert()
        val resolved = SigningConfigLoader.resolve(app, profile)
        appendLog("开始转换（签名: ${resolved.profileName}）")
        appendLog("证书: ${resolved.subjectDn}")
        appendLog("SHA-256: ${resolved.certificateSha256}")
        if (resolved.builtIn) {
            appendLog("警告: 当前为内置调试签名，与正式 carout 等密钥不兼容")
        }
        val builder = UniversalApkBuilder(app)
        val apks = builder.build(aabFile, workDir, resolved.configuration) { step ->
            when (step) {
                UniversalApkBuilder.Step.PREPARING -> {
                    _state.value = ConvertState.PARSING
                    appendLog("正在准备 aapt2 与签名配置…")
                }
                UniversalApkBuilder.Step.BUILDING -> {
                    _state.value = ConvertState.BUILDING
                    appendLog("正在按当前设备生成 APK Set…")
                }
                UniversalApkBuilder.Step.EXTRACTING -> {
                    _state.value = ConvertState.SIGNING
                    appendLog("正在提取设备匹配的 base 与 split APK…")
                }
            }
        }
        apks.forEach { apk ->
            appendLog(
                "APK: ${apk.name} | ${formatSize(apk.length())} | " +
                    "MD5: ${UniversalApkBuilder.md5(apk)}"
            )
        }
        return apks
    }

    fun clearConvertCache(): Long {
        val freed = CacheCleaner.clearConvertCache(getApplication())
        appendLog("已清理转换缓存，约释放 ${formatSize(freed)}")
        return freed
    }

    fun onInstallAwaitingConfirmation() {
        _state.value = ConvertState.INSTALLING
        appendLog("等待用户确认安装…")
    }

    fun onInstallSuccess() {
        _state.value = ConvertState.SUCCESS
        appendLog("安装成功")
    }

    fun onInstallFailure(message: String) {
        _state.value = ConvertState.ERROR
        appendLog("安装失败: $message")
    }

    fun appendLog(message: String) {
        ConversionLog.append(message)
    }

    companion object {
        fun formatSize(bytes: Long): String = when {
            bytes < 0 -> "未知大小"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
        }
    }
}
