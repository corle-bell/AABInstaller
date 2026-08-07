package com.corlebell.aabinstaller.signing

import android.content.Context
import com.android.tools.build.bundletool.model.SigningConfiguration
import com.corlebell.bundletool.DebugSigning
import com.corlebell.bundletool.KeystoreSigning
import java.io.File

data class ResolvedSigning(
    val configuration: SigningConfiguration,
    val profileName: String,
    val certificateSha256: String,
    val subjectDn: String,
    val builtIn: Boolean
)

object SigningConfigLoader {

    fun resolve(context: Context, profile: SigningProfile): ResolvedSigning {
        if (profile.builtIn) {
            val config = DebugSigning.getOrCreate(context)
            val (sha256, subject) = KeystoreSigning.leafCertificateInfo(config)
            return ResolvedSigning(
                configuration = config,
                profileName = profile.name,
                certificateSha256 = sha256,
                subjectDn = subject,
                builtIn = true
            )
        }

        val repo = SigningRepository(context)
        var file = repo.keystoreFile(profile)
            ?: throw IllegalStateException("签名配置「${profile.name}」缺少密钥库文件")
        if (!file.isFile) {
            throw IllegalStateException("签名配置「${profile.name}」密钥库不存在: ${file.name}")
        }

        val keyPass = profile.keyPassword.ifBlank { profile.storePassword }

        // 历史导入的 JKS：转换为 PKCS12 并回写配置，后续走统一路径
        if (profile.storeType.equals("JKS", ignoreCase = true) ||
            KeystoreSigning.guessStoreType(file).equals("JKS", ignoreCase = true)
        ) {
            val p12Name = profile.keystoreFileName
                .substringBeforeLast('.')
                .ifBlank { profile.id } + ".p12"
            val p12File = File(repo.keystoresDir, p12Name)
            val migrated = KeystoreSigning.materializeAsPkcs12(
                sourceFile = file,
                destPkcs12 = p12File,
                storePassword = profile.storePassword,
                alias = profile.alias,
                keyPassword = keyPass
            )
            if (file.absolutePath != p12File.absolutePath && file.exists()) {
                // 保留原文件亦可；优先使用 p12
            }
            repo.save(
                profile.copy(
                    keystoreFileName = p12Name,
                    storeType = "PKCS12"
                )
            )
            file = p12File
            return ResolvedSigning(
                configuration = migrated.configuration,
                profileName = profile.name,
                certificateSha256 = migrated.certificateSha256,
                subjectDn = migrated.subjectDn,
                builtIn = false
            )
        }

        val loaded = KeystoreSigning.detectAndLoad(
            keystoreFile = file,
            storePassword = profile.storePassword,
            alias = profile.alias,
            keyPassword = keyPass,
            preferredType = profile.storeType
        )
        return ResolvedSigning(
            configuration = loaded.configuration,
            profileName = profile.name,
            certificateSha256 = loaded.certificateSha256,
            subjectDn = loaded.subjectDn,
            builtIn = false
        )
    }
}
