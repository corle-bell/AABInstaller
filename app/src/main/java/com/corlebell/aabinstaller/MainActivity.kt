package com.corlebell.aabinstaller

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.corlebell.aabinstaller.cache.CacheCleaner
import com.corlebell.aabinstaller.databinding.ActivityMainBinding
import com.corlebell.aabinstaller.download.UrlInstallActivity
import com.corlebell.aabinstaller.signing.SigningActivity
import com.corlebell.installer.ApkInstaller
import com.corlebell.installer.SystemApkInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private val installer by lazy { SystemApkInstaller(this) }

    /** 转换完成但缺少"安装未知应用"权限时暂存，授权返回后继续安装 */
    private var apksAwaitingPermission: List<File>? = null

    private val pickAab = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            if (!uri.toString().endsWith(".aab", ignoreCase = true)) {
                viewModel.appendLog("提示: 所选文件扩展名不是 .aab，将尝试按 AAB 解析")
            }
            viewModel.onFilePicked(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.btnPick.setOnClickListener {
            if (!aabConversionSupported) {
                toast(getString(R.string.aab_requires_o))
                return@setOnClickListener
            }
            pickAab.launch(arrayOf("*/*"))
        }
        binding.btnConvert.setOnClickListener {
            viewModel.startConvert()
        }
        binding.btnUrlInstall.setOnClickListener {
            startActivity(Intent(this, UrlInstallActivity::class.java))
        }
        binding.btnSigning.setOnClickListener {
            startActivity(Intent(this, SigningActivity::class.java))
        }

        if (!aabConversionSupported) {
            binding.tvFileName.text = getString(R.string.aab_requires_o)
            binding.btnPick.isEnabled = false
            binding.btnConvert.isEnabled = false
        }

        observeViewModel()
        handleIncomingIntent(intent)
    }

    private val aabConversionSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshSigningLabel()
        apksAwaitingPermission?.let { apks ->
            if (installer.canRequestInstalls()) {
                apksAwaitingPermission = null
                launchInstall(apks)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_signing -> {
                startActivity(Intent(this, SigningActivity::class.java))
                true
            }
            R.id.action_url_install -> {
                startActivity(Intent(this, UrlInstallActivity::class.java))
                true
            }
            R.id.action_clear_cache -> {
                confirmClearCache()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val uri = resolveIncomingUri(intent) ?: return

        if (intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
            uri.scheme == ContentResolver.SCHEME_CONTENT
        ) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // 部分来源不支持持久化 URI 权限
            }
        }

        val name = displayName(uri)
        when {
            name.endsWith(".apk", ignoreCase = true) -> installIncomingApk(uri, name)
            aabConversionSupported -> {
                if (!name.endsWith(".aab", ignoreCase = true)) {
                    viewModel.appendLog("提示: 所选文件扩展名不是 .aab，将尝试按 AAB 解析")
                }
                viewModel.onFilePicked(uri)
            }
            else -> {
                viewModel.appendLog(getString(R.string.aab_requires_o))
                toast(getString(R.string.aab_requires_o))
            }
        }
    }

    private fun installIncomingApk(uri: Uri, displayName: String) {
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    val dir = File(cacheDir, "incoming").apply { mkdirs() }
                    val safeName = displayName
                        .substringAfterLast('/')
                        .substringAfterLast('\\')
                        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                        .ifBlank { "incoming.apk" }
                    val dest = File(dir, safeName)
                    contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取 APK")
                    dest
                }
                viewModel.appendLog(
                    "准备安装 APK: ${apk.name} (${MainViewModel.formatSize(apk.length())})"
                )
                tryInstall(listOf(apk))
            } catch (t: Throwable) {
                viewModel.appendLog("无法读取 APK: ${t.message ?: t.javaClass.simpleName}")
                toast("无法读取 APK: ${t.message}")
            }
        }
    }

    private fun resolveIncomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> readStreamUri(intent)
        else -> null
    }

    private fun readStreamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            readStreamUriTiramisu(intent)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @android.annotation.SuppressLint("NewApi")
    private fun readStreamUriTiramisu(intent: Intent): Uri? =
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)

    private fun displayName(uri: Uri): String =
        queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    private fun confirmClearCache() {
        val size = CacheCleaner.convertCacheSize(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_clear_cache)
            .setMessage("将清理转换临时文件，约 ${MainViewModel.formatSize(size)}。下载记录与签名配置不会删除。")
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val freed = viewModel.clearConvertCache()
                Toast.makeText(
                    this,
                    "已清理，约释放 ${MainViewModel.formatSize(freed)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { render(it) }
                }
                launch {
                    viewModel.selected.collect { selected ->
                        if (selected != null) {
                            binding.tvFileName.text = selected.name
                            binding.tvFileSize.text = MainViewModel.formatSize(selected.size)
                            binding.btnConvert.isEnabled = aabConversionSupported
                        }
                    }
                }
                launch {
                    viewModel.signingName.collect { name ->
                        binding.tvSigning.text = getString(R.string.current_signing, name)
                    }
                }
                launch {
                    viewModel.log.collect { log ->
                        binding.tvLog.text = log
                        binding.scrollLog.post {
                            binding.scrollLog.fullScroll(View.FOCUS_DOWN)
                        }
                    }
                }
                launch {
                    viewModel.installRequest.collect { apks -> tryInstall(apks) }
                }
            }
        }
    }

    private fun render(state: ConvertState) {
        val working = state in listOf(
            ConvertState.COPYING, ConvertState.PARSING,
            ConvertState.BUILDING, ConvertState.SIGNING
        )
        binding.progress.visibility = if (working) View.VISIBLE else View.INVISIBLE
        binding.btnPick.isEnabled = aabConversionSupported && !working
        binding.btnConvert.isEnabled =
            aabConversionSupported && !working && viewModel.selected.value != null
        binding.btnUrlInstall.isEnabled = !working
        binding.btnSigning.isEnabled = !working

        binding.tvState.text = when (state) {
            ConvertState.IDLE -> getString(R.string.state_idle)
            ConvertState.COPYING -> getString(R.string.state_copying)
            ConvertState.PARSING -> getString(R.string.state_parsing)
            ConvertState.BUILDING -> getString(R.string.state_building)
            ConvertState.SIGNING -> getString(R.string.state_signing)
            ConvertState.INSTALLING -> getString(R.string.state_installing)
            ConvertState.SUCCESS -> getString(R.string.state_success)
            ConvertState.ERROR -> getString(R.string.state_error)
        }

        if (state == ConvertState.SUCCESS || state == ConvertState.INSTALLING) {
            viewModel.pendingApks?.let { apks ->
                binding.btnConvert.isEnabled = false
                binding.btnInstallAgain.visibility = View.VISIBLE
                binding.btnInstallAgain.setOnClickListener { tryInstall(apks) }
            }
        }
    }

    private fun tryInstall(apks: List<File>) {
        if (installer.canRequestInstalls()) {
            launchInstall(apks)
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

    private fun installPermissionMessage(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getString(R.string.perm_dialog_message)
        } else {
            getString(R.string.perm_dialog_message_legacy)
        }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun launchInstall(apks: List<File>) {
        installer.install(apks) { result ->
            runOnUiThread {
                when (result) {
                    ApkInstaller.Result.AwaitingUserConfirmation ->
                        viewModel.onInstallAwaitingConfirmation()
                    ApkInstaller.Result.Success ->
                        viewModel.onInstallSuccess()
                    is ApkInstaller.Result.Failure ->
                        viewModel.onInstallFailure(result.message)
                }
            }
        }
    }
}
