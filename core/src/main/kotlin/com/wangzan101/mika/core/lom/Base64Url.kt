package com.wangzan101.mika.core.lom

import java.util.Base64

/**
 * 协议里所有二进制值的编码:base64url(RFC 4648 §5),不带填充。
 * 解码必须严格:只认字母表内字符,并且必须是规范编码(解码再编码与原文逐字相同)。
 */
object Base64Url {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(data)

    /** 不合法返回 null(带 `=` 填充、标准字母 `+/`、尾部多余的非零位都算不合法)。 */
    fun decode(text: String): ByteArray? {
        if (text.isEmpty() || text.any { it !in ALPHABET }) return null
        // 长度 mod 4 == 1 不可能是任何字节串的编码
        if (text.length % 4 == 1) return null
        val data = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
        return data.takeIf { encode(it) == text }
    }

    /** 解码后恰为 [size] 个字节才返回。 */
    fun decodeExactly(text: String, size: Int): ByteArray? = decode(text)?.takeIf { it.size == size }
}
