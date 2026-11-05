package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException

/**
 * 麓鸣接入的小块持久状态(模型版本号、设备名、会话映射等)。键值都是字符串。
 * 落盘失败抛 `remote.operation_failed`:调用方据此返回失败,不假装已保存。
 */
interface LomKv {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
    fun keys(prefix: String): List<String>
}

/** 整份 JSON 经 [LomSecretBox](安卓上是 Keystore 包裹)加密后落盘;解不开按空处理,下一次写入才替换。 */
class EncryptedFileLomKv(private val file: File, private val box: LomSecretBox) : LomKv {
    private val lock = Any()
    private var cache: MutableMap<String, String>? = null

    private fun load(): MutableMap<String, String> = cache ?: run {
        val m = LinkedHashMap<String, String>()
        if (file.isFile) {
            try {
                Json.parseToJsonElement(String(box.open(file.readBytes()), Charsets.UTF_8)).jsonObject
                    .forEach { (k, v) -> m[k] = v.jsonPrimitive.content }
            } catch (_: Exception) {
                // 解不开就当没有;不覆盖原文件,直到下一次成功写入
                m.clear()
            }
        }
        m.also { cache = it }
    }

    override fun get(key: String): String? = synchronized(lock) { load()[key] }
    override fun keys(prefix: String): List<String> = synchronized(lock) { load().keys.filter { it.startsWith(prefix) } }

    override fun put(key: String, value: String) = synchronized(lock) {
        val m = load()
        val old = m.put(key, value)
        try { flush(m) } catch (e: LomException) { if (old == null) m.remove(key) else m[key] = old; throw e }
    }

    override fun remove(key: String) = synchronized(lock) {
        val m = load()
        val old = m.remove(key) ?: return@synchronized
        try { flush(m) } catch (e: LomException) { m[key] = old; throw e }
    }

    private fun flush(m: Map<String, String>) {
        val json = Json.encodeToString(JsonObject.serializer(), JsonObject(m.mapValues { JsonPrimitive(it.value) }))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(box.seal(json.toByteArray(Charsets.UTF_8)))
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("rename") }
        } catch (_: IOException) {
            throw LomException("remote.operation_failed")
        }
    }
}

/** 按发送方桌面公钥隔离状态用的短标识(不可逆,不含公钥原文)。 */
object LomSender {
    fun key(sender: String): String =
        LomCrypto.sha256(sender.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }
}
