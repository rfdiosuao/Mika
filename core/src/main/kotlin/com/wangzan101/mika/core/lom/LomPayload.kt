package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** 请求载荷的解析与响应的拼装。载荷里的一切都是不可信输入:类型不对就当缺失,未知字段忽略。 */
internal object LomPayload {
    fun parse(text: String, code: String = "remote.operation_failed"): JsonObject =
        try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw LomException(code) }

    /** 字符串字段;不是 JSON 字符串(数字、布尔、对象)一律当缺失,文档要求这些字段按字符串传。 */
    fun str(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** JSON 数字字段(`chat.*` 的 offset 等)。 */
    fun long(o: JsonObject, key: String): Long? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull()

    fun bool(o: JsonObject, key: String): Boolean? = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.let {
        when (it) { "true" -> true; "false" -> false; else -> null }
    }

    fun jobj(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(linkedMapOf<String, JsonElement>().also { m ->
        pairs.forEach { (k, v) -> m[k] = element(v) }
    })

    fun obj(vararg pairs: Pair<String, Any?>): String = jobj(*pairs).toString()

    fun element(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is String -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map(::element))
        else -> error("unsupported json value")
    }
}

/**
 * 按顺序问多组方法,第一个认领的回答;都不认返回 null(统一的 `remote.method_unknown`)。
 * 同时回答 `device.capabilities`(公开文档 07 §6):`methods` 由各组实际认领的方法合并而来,不手写清单;
 * [limits] 是可选的数值上限(键名 `<方法>.<含义>`)。
 */
class LomMethodSet(private val limits: Map<String, Long>, private vararg val parts: LomMethods) : LomMethods {
    constructor(vararg parts: LomMethods) : this(emptyMap(), *parts)

    override val methods: Set<String> = parts.flatMapTo(sortedSetOf(CAPABILITIES)) { it.methods }

    override fun call(method: String, payload: String, sender: String): String? {
        if (method == CAPABILITIES) return capabilities()
        for (p in parts) p.call(method, payload, sender)?.let { return it }
        return null
    }

    private fun capabilities(): String = LomPayload.obj(
        "version" to 1,
        "methods" to methods.toList(),
        *(if (limits.isEmpty()) emptyArray() else arrayOf("limits" to LomPayload.jobj(*limits.toSortedMap().toList().toTypedArray()))),
    )

    companion object {
        const val CAPABILITIES = "device.capabilities"
    }
}
