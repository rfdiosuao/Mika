package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** 测试向量来自公开文档 `docs/open/phone-access/testvectors/`(原样复制,在 resources/lom/)。 */
internal object Vectors {
    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(Vectors::class.java.getResource("/lom/$name")!!.readText()).jsonObject

    val pairing: JsonObject by lazy { load("pairing-v1.json") }
    val envelope: JsonObject by lazy { load("remote-envelope-v1.json") }

    /** 向量里的两把身份用重复字节的种子造出(仅限测试)。 */
    val vectorDesktop = LomIdentity(ByteArray(32) { 7 }, ByteArray(32) { 8 })
    val vectorPhone = LomIdentity(ByteArray(32) { 9 }, ByteArray(32) { 10 })
}

internal fun hex32(c: Char): String = c.toString().repeat(32)
