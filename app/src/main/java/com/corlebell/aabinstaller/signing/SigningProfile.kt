package com.corlebell.aabinstaller.signing

/**
 * 签名配置档案。
 * @param builtIn 内置调试签名，不可删除，keystore 由 DebugSigning 管理
 */
data class SigningProfile(
    val id: String,
    val name: String,
    val alias: String,
    val storePassword: String,
    val keyPassword: String,
    /** 相对 filesDir/keystores/ 的文件名；内置签名为空 */
    val keystoreFileName: String = "",
    val storeType: String = "PKCS12",
    val builtIn: Boolean = false
)
