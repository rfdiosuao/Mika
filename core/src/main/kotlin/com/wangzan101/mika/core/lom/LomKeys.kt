package com.wangzan101.mika.core.lom

import java.security.SecureRandom

/**
 * 手机的长期身份:一对 X25519(解信封)加一对 Ed25519(给回复签名)。
 * 私钥只在内存里以字节数组存在,落盘由 [LomStore] 负责(Keystore 包裹),这里没有任何输出私钥的方法。
 */
class LomIdentity(x25519Private: ByteArray, ed25519Seed: ByteArray) {
    private val x = x25519Private.copyOf()
    private val e = ed25519Seed.copyOf()
    val x25519Public: ByteArray = LomCrypto.x25519Public(x)
    val ed25519Public: ByteArray = LomCrypto.ed25519Public(e)

    /** 公钥串 K:`b64url(x25519 公钥 ‖ ed25519 公钥)`,86 字符。 */
    val keys: String = LomKeys.keysOf(x25519Public, ed25519Public)

    internal fun agree(peerPublic: ByteArray): ByteArray = LomCrypto.x25519Agree(x, peerPublic)
    internal fun sign(message: ByteArray): ByteArray = LomCrypto.ed25519Sign(e, message)

    /** 仅供 [LomStore] 落盘用。 */
    internal fun exportSecrets(): Pair<ByteArray, ByteArray> = x.copyOf() to e.copyOf()

    override fun toString() = "LomIdentity(${LomKeys.fingerprintText(keys)})"

    companion object {
        fun generate(random: SecureRandom = SecureRandom()): LomIdentity {
            val x = ByteArray(32).also(random::nextBytes)
            val e = ByteArray(32).also(random::nextBytes)
            return LomIdentity(x, e)
        }
    }
}

/** 公钥串、指纹、`key_proof`、确认码。算法与域分隔字符串见公开文档 04。 */
object LomKeys {
    const val KEYS_LENGTH = 86

    fun keysOf(x25519Public: ByteArray, ed25519Public: ByteArray): String {
        require(x25519Public.size == 32 && ed25519Public.size == 32)
        return Base64Url.encode(x25519Public + ed25519Public)
    }

    /** 收到的 K 是否合法:86 字符、规范 base64url、解码后 64 字节。 */
    fun isValid(keys: String): Boolean = keys.length == KEYS_LENGTH && Base64Url.decodeExactly(keys, 64) != null

    internal fun x25519Of(keys: String): ByteArray = (Base64Url.decodeExactly(keys, 64) ?: throw LomException("remote.invalid_key")).copyOfRange(0, 32)

    internal fun ed25519Of(keys: String): ByteArray = (Base64Url.decodeExactly(keys, 64) ?: throw LomException("remote.invalid_key")).copyOfRange(32, 64)

    /** `SHA-256("LOM-KEY-FP-V1\n" + K)` 的前 16 字节。 */
    fun fingerprint(keys: String): ByteArray = LomCrypto.sha256("LOM-KEY-FP-V1\n$keys".toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)

    fun fingerprintText(keys: String): String = Base64Url.encode(fingerprint(keys))

    /** 扫码路径里手机用 `psk` 证明「我扫了这个二维码」。`pskBytes` 是解码后的 32 字节。 */
    fun keyProof(pskBytes: ByteArray, pairingId: String, desktopKeys: String, deviceKeys: String): String {
        val message = "LOM-PAIR-PROOF-V1\n$pairingId\n$desktopKeys\n$deviceKeys".toByteArray(Charsets.UTF_8)
        return Base64Url.encode(LomCrypto.hmacSha256(pskBytes, message))
    }

    /** 配对码路径的 6 位确认码:两端各自计算,由用户肉眼比对。 */
    fun sas(pairingId: String, desktopKeys: String, deviceKeys: String): String {
        val d = LomCrypto.sha256("LOM-PAIR-SAS-V1\n$pairingId\n$desktopKeys\n$deviceKeys".toByteArray(Charsets.UTF_8))
        val n = ((d[0].toLong() and 0xff) shl 24) or ((d[1].toLong() and 0xff) shl 16) or ((d[2].toLong() and 0xff) shl 8) or (d[3].toLong() and 0xff)
        return (n % 1_000_000L).toString().padStart(6, '0')
    }
}
