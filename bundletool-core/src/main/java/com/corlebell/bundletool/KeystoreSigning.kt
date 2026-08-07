package com.corlebell.bundletool

import com.android.tools.build.bundletool.model.SigningConfiguration
import com.google.common.collect.ImmutableList
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * 从本地 keystore 加载 [SigningConfiguration]。
 *
 * Android 上 bundletool 的 [SigningConfiguration.extractFromKeystore] 不可用
 *（内部写死 `KeyStore.getInstance("JKS")`，系统无 JKS 实现）。
 * 因此：
 * - JKS：用 [JksPrivateKeyStore] 读出后，可再落盘为 PKCS12
 * - PKCS12：经 BouncyCastle 打开
 * - 组装配置时使用**完整证书链**，并校验私钥与证书匹配
 */
object KeystoreSigning {

    data class LoadResult(
        val configuration: SigningConfiguration,
        val storeType: String,
        val certificateSha256: String,
        val subjectDn: String
    )

    fun load(
        keystoreFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
        storeType: String? = null
    ): SigningConfiguration =
        detectAndLoad(keystoreFile, storePassword, alias, keyPassword, storeType).configuration

    fun detectAndLoad(
        keystoreFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
        preferredType: String? = null
    ): LoadResult {
        require(keystoreFile.isFile) { "密钥库不存在: ${keystoreFile.absolutePath}" }
        ensureBcProvider()

        val candidates = buildCandidateTypes(keystoreFile, preferredType)
        val errors = mutableListOf<String>()

        for (type in candidates) {
            try {
                return loadWithType(keystoreFile, storePassword, alias, keyPassword, type)
            } catch (e: Exception) {
                val cause = e.cause?.message?.let { " ($it)" }.orEmpty()
                errors += "$type: ${e.message ?: e.javaClass.simpleName}$cause"
            }
        }

        throw IllegalArgumentException(
            "无法打开密钥库，已尝试 ${candidates.joinToString()}。\n" +
                errors.joinToString("\n")
        )
    }

    /**
     * 将任意 JKS/PKCS12 规范为 PKCS12 文件（供 App 持久化）。
     * @return 写入后的 LoadResult（storeType 恒为 PKCS12）
     */
    fun materializeAsPkcs12(
        sourceFile: File,
        destPkcs12: File,
        storePassword: String,
        alias: String,
        keyPassword: String
    ): LoadResult {
        val loaded = detectAndLoad(sourceFile, storePassword, alias, keyPassword)
        val (privateKey, chain) = readKeyAndChain(
            sourceFile, storePassword, alias, keyPassword, loaded.storeType
        )
        ensureBcProvider()
        val out = KeyStore.getInstance("PKCS12", BC_PROVIDER_NAME)
        out.load(null, null)
        out.setKeyEntry(
            alias,
            privateKey,
            keyPassword.toCharArray(),
            chain.toTypedArray()
        )
        destPkcs12.parentFile?.mkdirs()
        destPkcs12.outputStream().use { out.store(it, storePassword.toCharArray()) }

        // 再从刚写入的 PKCS12 读回，确保落盘内容可用
        return loadWithType(destPkcs12, storePassword, alias, keyPassword, "PKCS12")
    }

    fun certificateSha256(certificate: X509Certificate): String {
        val md = MessageDigest.getInstance("SHA-256")
        val dig = md.digest(certificate.encoded)
        return dig.joinToString(":") { b -> "%02X".format(b) }
    }

    /** 从已构建的 SigningConfiguration 取出叶子证书指纹（避免 app 模块直接依赖 Guava 集合类型） */
    fun leafCertificateInfo(configuration: SigningConfiguration): Pair<String, String> {
        val cert = configuration.signerConfig.certificates[0] as X509Certificate
        return certificateSha256(cert) to cert.subjectX500Principal.name
    }

    fun guessStoreType(file: File): String {
        detectByMagic(file)?.let { return it }
        val name = file.name.lowercase()
        return when {
            name.endsWith(".p12") || name.endsWith(".pfx") -> "PKCS12"
            name.endsWith(".jks") || name.endsWith(".keystore") -> "JKS"
            else -> "PKCS12"
        }
    }

    private fun loadWithType(
        keystoreFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
        storeType: String
    ): LoadResult {
        val (privateKey, chain) = readKeyAndChain(
            keystoreFile, storePassword, alias, keyPassword, storeType
        )
        val leaf = chain.first()
        assertKeyMatchesCertificate(privateKey, leaf)

        val configuration = SigningConfiguration.builder()
            .setSignerConfig(privateKey, ImmutableList.copyOf(chain))
            .build()

        return LoadResult(
            configuration = configuration,
            storeType = storeType.uppercase().let {
                if (it == "JKS") "JKS" else "PKCS12"
            },
            certificateSha256 = certificateSha256(leaf),
            subjectDn = leaf.subjectX500Principal.name
        )
    }

    private fun readKeyAndChain(
        keystoreFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
        storeType: String
    ): Pair<PrivateKey, List<X509Certificate>> {
        if (storeType.equals("JKS", ignoreCase = true)) {
            val entry = JksPrivateKeyStore.loadPrivateKeyEntry(
                keystoreFile, storePassword, alias, keyPassword
            )
            return entry.privateKey to listOf(entry.certificate)
        }

        val keyStore = KeyStore.getInstance("PKCS12", BC_PROVIDER_NAME)
        keystoreFile.inputStream().use { keyStore.load(it, storePassword.toCharArray()) }
        if (!keyStore.containsAlias(alias)) {
            val aliases = keyStore.aliases().toList().joinToString()
            throw IllegalArgumentException("别名 \"$alias\" 不存在。可用别名: $aliases")
        }
        val privateKey = keyStore.getKey(alias, keyPassword.toCharArray()) as? PrivateKey
            ?: throw IllegalArgumentException("无法读取别名 \"$alias\" 的私钥，请检查别名密码")
        val rawChain = keyStore.getCertificateChain(alias)
            ?: arrayOf(keyStore.getCertificate(alias))
        val chain = rawChain.mapNotNull { it as? X509Certificate }
        if (chain.isEmpty()) {
            throw IllegalArgumentException("别名 \"$alias\" 没有 X509 证书")
        }
        return privateKey to chain
    }

    private fun assertKeyMatchesCertificate(privateKey: PrivateKey, certificate: X509Certificate) {
        val payload = "aab-installer-key-check".toByteArray()
        val algorithms = when (privateKey.algorithm.uppercase()) {
            "RSA" -> listOf("SHA256withRSA", "SHA1withRSA")
            "EC" -> listOf("SHA256withECDSA", "SHA1withECDSA")
            "DSA" -> listOf("SHA256withDSA", "SHA1withDSA")
            else -> listOf("SHA256withRSA")
        }
        var last: Exception? = null
        for (alg in algorithms) {
            try {
                val sig = Signature.getInstance(alg)
                sig.initSign(privateKey)
                sig.update(payload)
                val signed = sig.sign()
                val verify = Signature.getInstance(alg)
                verify.initVerify(certificate.publicKey)
                verify.update(payload)
                if (verify.verify(signed)) return
            } catch (e: Exception) {
                last = e
            }
        }
        throw IllegalStateException(
            "私钥与证书不匹配，无法用于 APK 签名。" +
                (last?.message?.let { " ($it)" } ?: "")
        )
    }

    private fun detectByMagic(file: File): String? {
        return try {
            file.inputStream().use { input ->
                val b0 = input.read()
                val b1 = input.read()
                when {
                    b0 == 0xFE && b1 == 0xED -> "JKS"
                    b0 == 0x30 -> "PKCS12"
                    else -> null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCandidateTypes(file: File, preferredType: String?): List<String> {
        val preferred = preferredType?.takeIf { it.isNotBlank() } ?: guessStoreType(file)
        val fallback = if (preferred.equals("JKS", ignoreCase = true)) "PKCS12" else "JKS"
        return listOf(preferred, fallback).distinctBy { it.uppercase() }
    }

    @Synchronized
    private fun ensureBcProvider() {
        val existing = Security.getProvider(BC_PROVIDER_NAME)
        if (existing == null || existing.javaClass != BouncyCastleProvider::class.java) {
            if (existing != null) {
                Security.removeProvider(BC_PROVIDER_NAME)
            }
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private const val BC_PROVIDER_NAME = BouncyCastleProvider.PROVIDER_NAME
}
