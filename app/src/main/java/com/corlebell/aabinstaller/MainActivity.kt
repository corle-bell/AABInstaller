package com.corlebell.aabinstaller

import android.content.Intent
import android.os.Bundle
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
import kotlinx.coroutines.launch
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

        observeViewModel()
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
                            binding.btnConvert.isEnabled = true
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
        binding.btnPick.isEnabled = !working
        binding.btnConvert.isEnabled = !working && viewModel.selected.value != null
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
                .setMessage(R.string.perm_dialog_message)
                .setPositiveButton(R.string.perm_dialog_go) { _, _ ->
                    installer.requestInstallPermission(this)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
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
