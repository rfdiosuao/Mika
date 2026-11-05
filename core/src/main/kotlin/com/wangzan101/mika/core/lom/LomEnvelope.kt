package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.SecureRandom

/**
 * 信封:桌面 ↔ 手机之间每个请求/回复的加密签名消息。恰好六个字符串字段,不能多也不能少。
 * 算法见公开文档 06:X25519 临时密钥 → HKDF-SHA256 → ChaCha20-Poly1305,Ed25519 对 `AAD ‖ body` 签名。
 */
data class LomEnvelope(
    val id: String, val sender: String, val recipient: String,
    val ephemeral: String, val ciphertext: String, val signature: String,
) {
    fun toJson(): JsonObject = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(id), "sender" to JsonPrimitive(sender), "recipient" to JsonPrimitive(recipient),
            "ephemeral" to JsonPrimitive(ephemeral), "ciphertext" to JsonPrimitive(ciphertext), "signature" to JsonPrimitive(signature),
        )
    )

    companion object {
        private val KEYS = setOf("id", "sender", "recipient", "ephemeral", "ciphertext", "signature")

        /** 不是恰好六个字符串字段就返回 null。 */
        fun fromJson(element: kotlinx.serialization.json.JsonElement?): LomEnvelope? {
            val obj = element as? JsonObject ?: return null
            if (obj.keys != KEYS) return null
            fun s(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            return LomEnvelope(
                s("id") ?: return null, s("sender") ?: return null, s("recipient") ?: return null,
                s("ephemeral") ?: return null, s("ciphertext") ?: return null, s("signature") ?: return null,
            )
        }

        fun parse(text: String): LomEnvelope? = runCatching { fromJson(Json.parseToJsonElement(text)) }.getOrNull()
    }
}

/** 打开信封的结果。除 [Ok] 外都意味着「还不能证明来自被批准的桌面」:一律不回复。 */
sealed class LomOpen {
    class Ok(val id: String, val sender: String, val plaintext: ByteArray) : LomOpen()
    class Drop(val reason: String) : LomOpen()
}

object LomEnvelopes {
    const val MAX_BODY = 400_000
    const val MIN_BODY = 28
    const val MAX_PLAINTEXT = 350_000
    private val HEX_ID = Regex("[0-9a-fA-F]{32}")

    private fun aad(id: String, sender: String, recipient: String, ephemeral: String): ByteArray =
        "LOM-REMOTE-V1\n$id\n$sender\n$recipient\n$ephemeral".toByteArray(Charsets.UTF_8)

    /**
     * 收件人 → 发送方信任 → 长度 → 验签(在解密之前)→ 解密。[trusted] 是被批准的桌面公钥串集合。
     * 失败只给内部原因(用于测试和不含敏感内容的日志),调用方不得据此回复对方。
     */
    fun open(envelope: LomEnvelope, self: LomIdentity, trusted: Set<String>): LomOpen {
        if (!LomCrypto.equal(envelope.recipient, self.keys)) return LomOpen.Drop("recipient")
        if (trusted.none { LomCrypto.equal(it, envelope.sender) }) return LomOpen.Drop("sender_untrusted")
        if (!HEX_ID.matches(envelope.id)) return LomOpen.Drop("id")
        if (!LomKeys.isValid(envelope.sender)) return LomOpen.Drop("sender_format")
        val ephemeral = Base64Url.decodeExactly(envelope.ephemeral, 32) ?: return LomOpen.Drop("ephemeral")
        val body = Base64Url.decode(envelope.ciphertext) ?: return LomOpen.Drop("ciphertext")
        if (body.size < MIN_BODY || body.size > MAX_BODY) return LomOpen.Drop("ciphertext_size")
        val signature = Base64Url.decodeExactly(envelope.signature, 64) ?: return LomOpen.Drop("signature_format")
        val aad = aad(envelope.id, envelope.sender, envelope.recipient, envelope.ephemeral)
        if (!LomCrypto.ed25519Verify(LomKeys.ed25519Of(envelope.sender), aad + body, signature)) return LomOpen.Drop("signature")
        val key = try {
            LomCrypto.hkdf(self.agree(ephemeral), aad)
        } catch (_: LomException) {
            return LomOpen.Drop("invalid_key")
        }
        val plaintext = LomCrypto.chachaOpen(key, body.copyOfRange(0, 12), body.copyOfRange(12, body.size), aad)
            ?: return LomOpen.Drop("decrypt")
        return LomOpen.Ok(envelope.id, envelope.sender, plaintext)
    }

    /** 封装回复(或测试里模拟桌面发请求):`id` 沿用请求,`recipient` 是对方的 K,临时 X25519 密钥用完即弃。 */
    fun seal(
        plaintext: ByteArray, id: String, self: LomIdentity, recipientKeys: String, random: SecureRandom = SecureRandom(),
    ): LomEnvelope {
        if (plaintext.size > MAX_PLAINTEXT) throw LomException("remote.payload_invalid")
        val recipientX = LomKeys.x25519Of(recipientKeys)
        val ephemeralPrivate = ByteArray(32).also(random::nextBytes)
        val ephemeral = Base64Url.encode(LomCrypto.x25519Public(ephemeralPrivate))
        val aad = aad(id, self.keys, recipientKeys, ephemeral)
        val key = LomCrypto.hkdf(LomCrypto.x25519Agree(ephemeralPrivate, recipientX), aad)
        val nonce = ByteArray(12).also(random::nextBytes)
        val body = nonce + LomCrypto.chachaSeal(key, nonce, plaintext, aad)
        val signature = Base64Url.encode(self.sign(aad + body))
        return LomEnvelope(id, self.keys, recipientKeys, ephemeral, Base64Url.encode(body), signature)
    }
}
