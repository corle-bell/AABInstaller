package com.corlebell.installer

import java.io.File

interface ApkInstaller {

    sealed interface Result {
        data object AwaitingUserConfirmation : Result
        data object Success : Result
        data class Failure(val message: String) : Result
    }

    /** 是否已允许安装未知来源。Android 8+ 为按应用授权，更低版本为系统全局开关。 */
    fun canRequestInstalls(): Boolean

    /** 通过一个 PackageInstaller Session 原子安装 base 与全部 split APK。 */
    fun install(apks: List<File>, onResult: (Result) -> Unit = {})

    fun install(apk: File, onResult: (Result) -> Unit = {}) =
        install(listOf(apk), onResult)
}
