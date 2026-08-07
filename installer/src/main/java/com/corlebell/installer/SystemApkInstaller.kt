package com.corlebell.installer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.net.Uri
import java.io.File
import java.util.UUID

/**
 * 使用 PackageInstaller Session 原子安装 base + split APK，并接收真实安装结果。
 */
class SystemApkInstaller(private val context: Context) : ApkInstaller {

    override fun canRequestInstalls(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    override fun install(apks: List<File>, onResult: (ApkInstaller.Result) -> Unit) {
        require(apks.isNotEmpty()) { "APK 列表不能为空" }
        apks.forEach { require(it.isFile && it.length() > 0) { "APK 文件无效: ${it.name}" } }

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
                        val confirmIntent = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        }
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
                        finish(ApkInstaller.Result.Failure(message))
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

    /** 跳转到"允许安装未知应用"设置页 */
    fun requestInstallPermission(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
