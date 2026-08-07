package com.corlebell.bundletool

import android.content.Context
import android.os.Build
import android.util.Log
import com.android.tools.build.bundletool.androidtools.Aapt2Command
import com.android.tools.build.bundletool.commands.BuildApksCommand
import com.android.tools.build.bundletool.commands.BuildApksCommand.ApkBuildMode
import com.android.tools.build.bundletool.commands.ExtractApksCommand
import com.android.tools.build.bundletool.model.SigningConfiguration
import com.android.bundle.Devices.DeviceSpec
import java.io.File
import java.security.MessageDigest

/**
 * AAB -> 当前设备所需 APK 集转换核心。
 *
 * 与 bundletool build-apks --device-spec + extract-apks 等价。输出包含 base、
 * 当前 ABI/密度/语言配置以及 install-time feature splits。
 */
class UniversalApkBuilder(private val context: Context) {

    enum class Step { PREPARING, BUILDING, EXTRACTING }

    /**
     * @param aab               本地 AAB 文件
     * @param outputDir         APK Set 与设备 APK 输出目录
     * @param signingConfiguration 签名配置；为 null 时使用内置调试签名
     */
    fun build(
        aab: File,
        outputDir: File,
        signingConfiguration: SigningConfiguration? = null,
        onStep: (Step) -> Unit = {}
    ): List<File> {
        require(aab.isFile) { "AAB 文件不存在: $aab" }
        outputDir.mkdirs()

        onStep(Step.PREPARING)
        val aapt2 = Aapt2Locator.locate(context)
        val signing = signingConfiguration ?: DebugSigning.getOrCreate(context)
        val deviceSpec = createDeviceSpec()

        val apksFile = File(outputDir, "output.apks")
        apksFile.delete()

        onStep(Step.BUILDING)
        BuildApksCommand.builder()
            .setBundlePath(aab.toPath())
            .setOutputFile(apksFile.toPath())
            .setOverwriteOutput(true)
            .setApkBuildMode(ApkBuildMode.DEFAULT)
            .setDeviceSpec(deviceSpec)
            .setAapt2Command(Aapt2Command.createFromExecutablePath(aapt2.toPath()))
            .setSigningConfiguration(signing)
            .build()
            .execute()

        onStep(Step.EXTRACTING)
        val apksDir = File(outputDir, "device-apks").apply {
            deleteRecursively()
            mkdirs()
        }
        val apks = ExtractApksCommand.builder()
            .setApksArchivePath(apksFile.toPath())
            .setDeviceSpec(deviceSpec)
            .setOutputDirectory(apksDir.toPath())
            .build()
            .execute()
            .map { it.toFile() }
            .sortedBy { it.name }

        apksFile.delete()

        check(apks.isNotEmpty()) { "未生成适用于当前设备的 APK" }
        apks.forEach { apk ->
            check(apk.isFile && apk.length() > 0) { "生成的 APK 无效: ${apk.name}" }
            Log.i(TAG, "APK MD5 ${md5(apk)}  ${apk.name}  ${apk.length()} bytes")
        }
        return apks
    }

    private fun createDeviceSpec(): DeviceSpec {
        val locales = context.resources.configuration.locales
        val localeTags = buildList {
            for (index in 0 until locales.size()) {
                locales[index].toLanguageTag().takeIf { it.isNotBlank() }?.let(::add)
            }
        }.distinct()
        val features = context.packageManager.systemAvailableFeatures
            .mapNotNull { it.name }
            .distinct()

        return DeviceSpec.newBuilder()
            .addAllSupportedAbis(Build.SUPPORTED_ABIS.toList())
            .addAllSupportedLocales(localeTags)
            .addAllDeviceFeatures(features)
            .setScreenDensity(context.resources.displayMetrics.densityDpi)
            .setSdkVersion(Build.VERSION.SDK_INT)
            .setCodename(Build.VERSION.CODENAME)
            .build()
    }

    companion object {
        private const val TAG = "AABInstaller"

        fun md5(file: File): String {
            val digest = MessageDigest.getInstance("MD5")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
