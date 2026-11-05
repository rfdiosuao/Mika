package com.wangzan101.mika.core.lom

/**
 * 配对链接 `lom://pair?v=1&h=…&p=…&t=…&k=…&f=…`(二维码内容、深链、相册解码出的字符串都是它)。
 * 严格校验见公开文档 02 §3:任何一步不过都抛 `pairing.qr_invalid`,此时不得发任何网络请求。
 * [psk] 与 [claimToken] 是临时口令,不进日志、不进 toString。
 */
class LomPairLink private constructor(
    val host: String,
    val pairingId: String,
    val claimToken: String,
    private val pskBytes: ByteArray,
    val desktopFingerprint: ByteArray,
) {
    internal fun psk(): ByteArray = pskBytes.copyOf()

    override fun toString() = "LomPairLink(host=$host, pairingId=$pairingId)"

    companion object {
        /** 内置主机白名单,不得从二维码或网络动态取。 */
        const val OFFICIAL_HOST = "lom.entermodetwo.com"
        val DEFAULT_ALLOWED_HOSTS: Set<String> = setOf(OFFICIAL_HOST)
        const val MAX_LENGTH = 2048
        private val FIELDS = setOf("v", "h", "p", "t", "k", "f")
        private val HOST = Regex("[a-z0-9]+([.-][a-z0-9]+)*")

        private fun invalid(): Nothing = throw LomException("pairing.qr_invalid")

        /** 是否像麓鸣链接(只看 scheme),界面据此区分「不是麓鸣二维码」与普通无关文本。 */
        fun looksLikeLom(text: String): Boolean = text.trimStart().startsWith("lom://", ignoreCase = true)

        /** 版本不是 1 的链接:界面应提示「二维码版本较新,请升级 App」而不是「无效」。 */
        fun isNewerVersion(text: String): Boolean = runCatching { fields(text) }.getOrNull()?.get("v")?.let { it != "1" && it.toIntOrNull() != null } == true

        fun parse(text: String, allowedHosts: Set<String> = DEFAULT_ALLOWED_HOSTS): LomPairLink {
            val f = fields(text)
            if (f["v"] != "1") invalid()
            val hostBytes = Base64Url.decode(f.getValue("h")) ?: invalid()
            val host = decodeUtf8(hostBytes) ?: invalid()
            if (!HOST.matches(host)) invalid()
            if (host !in allowedHosts) invalid()
            val p = f.getValue("p")
            val t = f.getValue("t")
            Base64Url.decodeExactly(p, 16) ?: invalid()
            Base64Url.decodeExactly(t, 32) ?: invalid()
            val psk = Base64Url.decodeExactly(f.getValue("k"), 32) ?: invalid()
            val fp = Base64Url.decodeExactly(f.getValue("f"), 16) ?: invalid()
            return LomPairLink(host, p, t, psk, fp)
        }

        private fun decodeUtf8(bytes: ByteArray): String? = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }

        /** 结构层:scheme/authority/路径/fragment/查询串字段恰好六个且不重复。 */
        private fun fields(text: String): Map<String, String> {
            if (text.length > MAX_LENGTH) invalid()
            val prefix = "lom://pair?"
            // scheme 与 authority 区分大小写由 URI 规范忽略;但路径必须为空、不得有 fragment
            if (!text.startsWith(prefix, ignoreCase = false)) invalid()
            val query = text.substring(prefix.length)
            if (query.isEmpty() || '#' in query) invalid()
            val out = LinkedHashMap<String, String>()
            for (part in query.split('&')) {
                val i = part.indexOf('=')
                if (i <= 0) invalid()
                val key = part.substring(0, i)
                val value = part.substring(i + 1)
                if (key !in FIELDS || key in out || value.isEmpty()) invalid()
                out[key] = value
            }
            if (out.keys != FIELDS) invalid()
            return out
        }
    }
}

/** 配对码(没有二维码时手输):8 个字符,显示为 `XXXX-XXXX`,字母表去掉易混淆的 0 O 1 I L。 */
object LomManualCode {
    const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"

    /** 规范化成 `XXXX-XXXX`;格式不对抛 `pairing.code_invalid`(不必请求云端)。 */
    fun normalize(input: String): String {
        val s = input.filter { it != '-' && !it.isWhitespace() }.uppercase()
        if (s.length != 8 || s.any { it !in ALPHABET }) throw LomException("pairing.code_invalid")
        return s.substring(0, 4) + "-" + s.substring(4)
    }
}
