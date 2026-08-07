package com.corlebell.bundletool

import android.content.Context
import com.android.tools.build.bundletool.model.SigningConfiguration
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * V1 签名方案：首次运行生成一把自签名测试证书并持久化到应用私有目录，
 * 之后所有转换出的 APK 都用同一把 key 签名（保证同一台手机上可以覆盖升级）。
 */
object DebugSigning {

    private const val KEYSTORE_FILE = "aabinstaller-debug.p12"
    private const val ALIAS = "aabinstaller"
    private val PASSWORD = "android".toCharArray()

    @Synchronized
    fun getOrCreate(context: Context): SigningConfiguration {
        val ksFile = File(context.filesDir, KEYSTORE_FILE)
        val keyStore = KeyStore.getInstance("PKCS12")

        if (ksFile.exists()) {
            ksFile.inputStream().use { keyStore.load(it, PASSWORD) }
        } else {
            keyStore.load(null, null)
            val (privateKey, certificate) = generateSelfSigned()
            keyStore.setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf(certificate))
            ksFile.outputStream().use { keyStore.store(it, PASSWORD) }
        }

        val privateKey = keyStore.getKey(ALIAS, PASSWORD) as PrivateKey
        val certificate = keyStore.getCertificate(ALIAS) as X509Certificate
        return SigningConfiguration.builder()
            .setSignerConfig(privateKey, certificate)
            .build()
    }

    private fun generateSelfSigned(): Pair<PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - TimeUnit.DAYS.toMillis(1))
        val notAfter = Date(now + TimeUnit.DAYS.toMillis(365L * 30))
        val subject = X500Name("CN=AABInstaller Debug, O=AABInstaller")

        val certBuilder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )
        val provider = BouncyCastleProvider()
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(certBuilder.build(signer))

        return keyPair.private to certificate
    }
}
