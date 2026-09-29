package com.corlebell.installer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File
import java.util.UUID

/**
 * 使用 PackageInstaller Session 原子安装 base + split APK，并接收真实安装结果。
 *
 * 部分 ColorOS 机型在覆盖安装成功后点「完成」会误报
 * INSTALL_FAILED_ABORTED，因此失败时会对比安装前后包状态做兜底。
 */
class SystemApkInstaller(private val context: Context) : ApkInstaller {

    override fun canRequestInstalls(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            canRequestPackageInstallsOreo()
        } else {
            unknownSourcesEnabledLegacy()
        }

    override fun install(apks: List<File>, onResult: (ApkInstaller.Result) -> Unit) {
        require(apks.isNotEmpty()) { "APK 列表不能为空" }
        apks.forEach { require(it.isFile && it.length() > 0) { "APK 文件无效: ${it.name}" } }

        val target = resolveTargetSnapshot(apks)
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        ).apply {
            setSize(apks.sumOf { it.length() })
        }
        val sessionId = packageInstaller.createSession(params)
        val action = "${context.packageName}.INSTALL_RESULT.$sessionId.${UUID.randomUUID()}"

        lateinit var receiver: BroadcastReceiver
        fun finish(result: ApkInstaller.Result) {
            runCatching { context.unregisterReceiver(receiver) }
            onResult(result)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                when (val status = intent.getIntExtra(
                    PackageInstaller.EXTRA_STATUS,
                    PackageInstaller.STATUS_FAILURE
                )) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        onResult(ApkInstaller.Result.AwaitingUserConfirmation)
                        val confirmIntent = readConfirmIntent(intent)
                        if (confirmIntent == null) {
                            finish(ApkInstaller.Result.Failure("系统安装确认页面不存在"))
                        } else {
                            confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            receiverContext.startActivity(confirmIntent)
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> finish(ApkInstaller.Result.Success)
                    else -> {
                        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            ?: "安装失败，状态码 $status"
                        if (target != null && looksLikeSuccessfulInstall(target)) {
                            finish(ApkInstaller.Result.Success)
                        } else {
                            finish(ApkInstaller.Result.Failure(message))
                        }
                    }
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(action),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        var session: PackageInstaller.Session? = null
        try {
            session = packageInstaller.openSession(sessionId)
            apks.forEachIndexed { index, apk ->
                val entryName = "${index}_${apk.name}"
                session.openWrite(entryName, 0, apk.length()).use { output ->
                    apk.inputStream().buffered().use { input -> input.copyTo(output) }
                    session.fsync(output)
                }
            }
            val callbackIntent = Intent(action).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callbackIntent,
                flags
            )
            session.commit(pendingIntent.intentSender)
            session.close()
        } catch (t: Throwable) {
            runCatching { session?.abandon() }
            runCatching { packageInstaller.abandonSession(sessionId) }
            finish(ApkInstaller.Result.Failure(t.message ?: t.javaClass.simpleName))
        }
    }

    /** 跳转到允许安装未知来源的设置页。Android 8+ 为按应用授权，更低版本为系统安全设置。 */
    fun requestInstallPermission(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            unknownAppSourcesIntent(context)
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    @SuppressLint("NewApi")
    private fun canRequestPackageInstallsOreo(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun unknownSourcesEnabledLegacy(): Boolean =
        runCatching {
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.INSTALL_NON_MARKET_APPS,
                0
            ) == 1
        }.getOrDefault(false)

    @SuppressLint("NewApi")
    private fun unknownAppSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    private fun readConfirmIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            readConfirmIntentTiramisu(intent)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    @SuppressLint("NewApi")
    private fun readConfirmIntentTiramisu(intent: Intent): Intent? =
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)

    private fun resolveTargetSnapshot(apks: List<File>): InstallSnapshot? {
        val archive = apks.firstNotNullOfOrNull { readArchiveInfo(it) } ?: return null
        val packageName = archive.packageName ?: return null
        val expectedVersion = archive.longVersionCodeCompat()
        val installed = readInstalledInfo(packageName)
        return InstallSnapshot(
            packageName = packageName,
            expectedVersionCode = expectedVersion,
            wasInstalled = installed != null,
            beforeVersionCode = installed?.longVersionCodeCompat(),
            beforeLastUpdateTime = installed?.lastUpdateTime
        )
    }

    private fun looksLikeSuccessfulInstall(before: InstallSnapshot): Boolean {
        val after = readInstalledInfo(before.packageName) ?: return false
        val afterVersion = after.longVersionCodeCompat()
        val afterUpdateTime = after.lastUpdateTime

        // 新装成功
        if (!before.wasInstalled) return true

        // 覆盖安装：lastUpdateTime 变新
        val beforeUpdateTime = before.beforeLastUpdateTime
        if (beforeUpdateTime != null && afterUpdateTime > beforeUpdateTime) return true

        // 版本号变为目标 APK 版本（升级/降级）
        if (before.beforeVersionCode != null &&
            afterVersion == before.expectedVersionCode &&
            afterVersion != before.beforeVersionCode
        ) {
            return true
        }

        return false
    }

    private fun readArchiveInfo(apk: File): PackageInfo? {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        }.getOrNull()
    }

    private fun readInstalledInfo(packageName: String): PackageInfo? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                readInstalledInfoTiramisu(packageName)
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
        }.getOrNull()
    }

    @SuppressLint("NewApi")
    private fun readInstalledInfoTiramisu(packageName: String): PackageInfo =
        context.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(0)
        )

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            longVersionCodeApi28()
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    @SuppressLint("NewApi")
    private fun PackageInfo.longVersionCodeApi28(): Long = longVersionCode

    private data class InstallSnapshot(
        val packageName: String,
        val expectedVersionCode: Long,
        val wasInstalled: Boolean,
        val beforeVersionCode: Long?,
        val beforeLastUpdateTime: Long?
    )
}
