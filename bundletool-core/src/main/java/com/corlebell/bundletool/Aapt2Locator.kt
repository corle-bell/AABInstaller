package com.corlebell.bundletool

import android.content.Context
import java.io.File

/**
 * bundletool 的 build-apks 需要调用 aapt2 可执行文件把 proto 资源转成二进制格式。
 * jar 内自带的是桌面平台（win/mac/linux）二进制，在手机上无法执行，
 * 因此我们把 Android 原生编译的 aapt2 伪装成 native lib（libaapt2.so）打进 APK。
 * Android 10+ 禁止执行 data 目录下的文件，但 nativeLibraryDir 是允许 exec 的。
 */
object Aapt2Locator {

    fun locate(context: Context): File {
        val aapt2 = File(context.applicationInfo.nativeLibraryDir, "libaapt2.so")
        check(aapt2.exists()) {
            "未找到 aapt2 可执行文件（libaapt2.so）。" +
                "请确认 bundletool-core/src/main/jniLibs/<abi>/libaapt2.so 已随 APK 打包，" +
                "且当前设备 ABI（arm64-v8a / armeabi-v7a）受支持。"
        }
        if (!aapt2.canExecute()) {
            aapt2.setExecutable(true)
        }
        return aapt2
    }
}
