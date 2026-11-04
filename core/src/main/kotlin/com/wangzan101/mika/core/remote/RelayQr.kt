package com.wangzan101.mika.core.remote

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** 网关页面上的二维码：`mika://relay?url=<网关地址>&code=<一次性配对码>`。 */
class RelayQrPairing(val gateway: String, val code: String) {
    override fun toString() = "RelayQrPairing($gateway, [REDACTED])"
}

object RelayQr {
    private const val MAX_LENGTH = 2048
    private val hosts = setOf("relay", "pair")

    /** 解析二维码原文；地址和配对码都按现有手动输入页面的规则校验。 */
    fun parse(raw: String, allowLoopbackHttp: Boolean = false): RelayQrPairing? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > MAX_LENGTH) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        if (!uri.scheme.equals("mika", ignoreCase = true)) return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in hosts) return null
        if (uri.path?.isNotEmpty() == true || uri.fragment != null) return null
        val query = parseQuery(uri.rawQuery ?: return null)
        val rawUrl = query["url"] ?: return null
        val code = query["code"]?.trim() ?: return null
        if (code.length !in 8..64 || code.any { it.isWhitespace() || it.isISOControl() }) return null
        val gateway = runCatching { RelayGateway.parse(rawUrl, allowLoopbackHttp) }.getOrNull() ?: return null
        return RelayQrPairing(gateway.toString(), code)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        val values = mutableMapOf<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val key = decode(part.substring(0, eq)) ?: return emptyMap()
            val value = decode(part.substring(eq + 1)) ?: return emptyMap()
            if (values.put(key, value) != null) return emptyMap()
        }
        return values
    }

    private fun decode(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrNull()
}
