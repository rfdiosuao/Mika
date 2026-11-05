package com.wangzan101.mika.core.lom

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 协议用到的密码学原语,一律走 BouncyCastle 的轻量接口(不注册 JCA 提供者,不依赖平台版本)。
 * 这里只做原语;字符串拼接、域分隔、信封流程在 [LomKeys]、[LomEnvelopes]。
 */
internal object LomCrypto {
    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** 恒定时间比较。 */
    fun equal(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    fun equal(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    fun x25519Public(private: ByteArray): ByteArray = X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded

    /** 共享密钥;对方给了小阶点(结果全零)时抛 `remote.invalid_key`。 */
    fun x25519Agree(private: ByteArray, peerPublic: ByteArray): ByteArray {
        if (peerPublic.size != 32) throw LomException("remote.invalid_key")
        val out = ByteArray(32)
        try {
            X25519PrivateKeyParameters(private, 0).generateSecret(X25519PublicKeyParameters(peerPublic, 0), out, 0)
        } catch (_: IllegalStateException) {
            throw LomException("remote.invalid_key")
        }
        if (out.all { it == 0.toByte() }) throw LomException("remote.invalid_key")
        return out
    }

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (_: RuntimeException) {
            false
        }
    }

    /** HKDF-SHA256,salt 为 32 个零字节,输出 32 字节。 */
    fun hkdf(ikm: ByteArray, info: ByteArray): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, ByteArray(32), info))
        return ByteArray(32).also { generator.generateBytes(it, 0, 32) }
    }

    /** 输出 密文 ‖ 16 字节标签。 */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val n = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    /** 认证失败返回 null。 */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        return try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
            val out = ByteArray(cipher.getOutputSize(sealed.size))
            val n = cipher.processBytes(sealed, 0, sealed.size, out, 0)
            val m = cipher.doFinal(out, n)
            out.copyOf(n + m)
        } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }
}
