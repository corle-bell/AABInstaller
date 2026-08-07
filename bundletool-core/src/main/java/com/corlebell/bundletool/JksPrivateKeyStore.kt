package com.corlebell.bundletool

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.EncryptedPrivateKeyInfo

/**
 * 解析含 PrivateKeyEntry 的 JKS（Android / BC 1.7x 官方 JKS SPI 已不支持私钥条目）。
 *
 * 格式与口令编码、KeyProtector 算法均为公开规范，独立实现，不依赖 BC 的只读 JKS SPI。
 */
internal object JksPrivateKeyStore {

    private const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION_1 = 0x01
    private const val VERSION_2 = 0x02
    private const val TAG_PRIVATE_KEY = 1
    private const val TAG_TRUSTED_CERT = 2
    private const val SALT_LEN = 20
    private const val DIGEST_LEN = 20

    data class Entry(
        val alias: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    fun loadPrivateKeyEntry(
        file: File,
        storePassword: String,
        alias: String,
        keyPassword: String
    ): Entry {
        val all = file.readBytes()
        verifyIntegrity(all, storePassword)
        val body = all.copyOfRange(0, all.size - DIGEST_LEN)
        val raw = parsePrivateKeyBlobs(body)
        val meta = raw.firstOrNull { it.alias.equals(alias, ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "别名 \"$alias\" 不存在。可用别名: ${raw.joinToString { it.alias }}"
            )
        if (meta.chain.isEmpty()) {
            throw IOException("别名 \"$alias\" 没有证书链")
        }
        val privateKey = try {
            recoverPrivateKey(meta.protectedKey, keyPassword)
        } catch (first: Exception) {
            if (keyPassword != storePassword) {
                try {
                    recoverPrivateKey(meta.protectedKey, storePassword)
                } catch (second: Exception) {
                    throw UnrecoverableKeyException(
                        "无法解密私钥，请检查别名密码"
                    ).initCause(first)
                }
            } else {
                throw UnrecoverableKeyException(
                    "无法解密私钥，请检查别名密码"
                ).initCause(first)
            }
        }
        return Entry(meta.alias, privateKey, meta.chain[0])
    }

    fun listAliases(file: File, storePassword: String): List<String> {
        val all = file.readBytes()
        verifyIntegrity(all, storePassword)
        val body = all.copyOfRange(0, all.size - DIGEST_LEN)
        return parsePrivateKeyBlobs(body).map { it.alias }
    }

    private data class RawKeyEntry(
        val alias: String,
        val protectedKey: ByteArray,
        val chain: List<X509Certificate>
    )

    private fun verifyIntegrity(all: ByteArray, storePassword: String) {
        if (all.size < DIGEST_LEN + 12) throw IOException("JKS 文件过短")
        val body = all.copyOfRange(0, all.size - DIGEST_LEN)
        val storedDigest = all.copyOfRange(all.size - DIGEST_LEN, all.size)
        val md = MessageDigest.getInstance("SHA-1")
        md.update(passwordBytes(storePassword))
        md.update("Mighty Aphrodite".toByteArray(Charsets.UTF_8))
        md.update(body)
        if (!md.digest().contentEquals(storedDigest)) {
            throw IOException("密钥库密码错误，或文件已损坏")
        }
    }

    private fun parsePrivateKeyBlobs(body: ByteArray): List<RawKeyEntry> {
        val data = DataInputStream(ByteArrayInputStream(body))
        val magic = data.readInt()
        if (magic != MAGIC) throw IOException("不是 JKS 文件")
        val version = data.readInt()
        if (version != VERSION_1 && version != VERSION_2) {
            throw IOException("不支持的 JKS 版本: $version")
        }
        val count = data.readInt()
        val certFactory = CertificateFactory.getInstance("X.509")
        val result = mutableListOf<RawKeyEntry>()
        repeat(count) {
            val tag = data.readInt()
            val alias = data.readUTF()
            data.readLong() // timestamp
            when (tag) {
                TAG_PRIVATE_KEY -> {
                    val protectedKey = readByteArray(data)
                    val chainLen = data.readInt()
                    val chain = ArrayList<X509Certificate>(chainLen)
                    repeat(chainLen) {
                        if (version == VERSION_2) data.readUTF()
                        chain.add(
                            certFactory.generateCertificate(
                                ByteArrayInputStream(readByteArray(data))
                            ) as X509Certificate
                        )
                    }
                    result.add(RawKeyEntry(alias, protectedKey, chain))
                }
                TAG_TRUSTED_CERT -> {
                    if (version == VERSION_2) data.readUTF()
                    readByteArray(data)
                }
                else -> throw IOException("未知 JKS 条目类型: $tag")
            }
        }
        return result
    }

    private fun recoverPrivateKey(encryptedPkcs8: ByteArray, password: String): PrivateKey {
        val epki = EncryptedPrivateKeyInfo(encryptedPkcs8)
        val protectedKey = epki.encryptedData
        if (protectedKey.size <= SALT_LEN + DIGEST_LEN) {
            throw UnrecoverableKeyException("受保护私钥数据过短")
        }

        val salt = protectedKey.copyOfRange(0, SALT_LEN)
        val encrKeyLen = protectedKey.size - SALT_LEN - DIGEST_LEN
        val encrKey = protectedKey.copyOfRange(SALT_LEN, SALT_LEN + encrKeyLen)
        val passwdBytes = passwordBytes(password)

        val md = MessageDigest.getInstance("SHA-1")
        var numRounds = encrKeyLen / DIGEST_LEN
        if (encrKeyLen % DIGEST_LEN != 0) numRounds++

        val xorKey = ByteArray(encrKeyLen)
        var digest = salt
        var xorOffset = 0
        for (i in 0 until numRounds) {
            md.update(passwdBytes)
            md.update(digest)
            digest = md.digest()
            md.reset()
            val len = if (i < numRounds - 1) DIGEST_LEN else xorKey.size - xorOffset
            System.arraycopy(digest, 0, xorKey, xorOffset, len)
            xorOffset += DIGEST_LEN
        }

        val plainKey = ByteArray(encrKeyLen)
        for (i in plainKey.indices) {
            plainKey[i] = (encrKey[i].toInt() xor xorKey[i].toInt()).toByte()
        }

        md.update(passwdBytes)
        md.update(plainKey)
        val check = md.digest()
        val expected = protectedKey.copyOfRange(SALT_LEN + encrKeyLen, protectedKey.size)
        if (!check.contentEquals(expected)) {
            throw UnrecoverableKeyException("无法恢复私钥（别名密码可能不正确）")
        }

        return parsePkcs8PrivateKey(plainKey)
    }

    private fun parsePkcs8PrivateKey(pkcs8: ByteArray): PrivateKey {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
        val algorithms = listOf("RSA", "EC", "DSA")
        var last: Exception? = null
        for (alg in algorithms) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                last = e
            }
        }
        for (alg in algorithms) {
            try {
                return KeyFactory.getInstance(alg, BouncyCastleProvider.PROVIDER_NAME)
                    .generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            } catch (e: Exception) {
                last = e
            }
        }
        throw UnrecoverableKeyException("无法解析 PKCS#8 私钥: ${last?.message}")
    }

    private fun passwordBytes(password: String): ByteArray {
        val chars = password.toCharArray()
        val bytes = ByteArray(chars.size * 2)
        var j = 0
        for (c in chars) {
            bytes[j++] = (c.code shr 8).toByte()
            bytes[j++] = c.code.toByte()
        }
        return bytes
    }

    private fun readByteArray(data: DataInputStream): ByteArray {
        val len = data.readInt()
        if (len < 0 || len > 50 * 1024 * 1024) {
            throw IOException("非法字节数组长度: $len")
        }
        val bytes = ByteArray(len)
        data.readFully(bytes)
        return bytes
    }
}
