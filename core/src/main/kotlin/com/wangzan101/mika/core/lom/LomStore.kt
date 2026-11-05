package com.wangzan101.mika.core.lom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.security.SecureRandom

/** 被批准的桌面:只有这些公钥发来的信封才会被接受。设计成集合,官方做法是同一时刻只留一台。 */
data class LomDesktop(val keys: String, val name: String, val approvedAtMs: Long)

/** 配对结果。[deviceToken] 是秘密:不进日志、不进 toString。 */
class LomPaired(
    val host: String,
    val deviceId: String,
    val deviceToken: String,
    val tokenExpiresAtSec: Long,
    val desktops: List<LomDesktop>,
) {
    val trustedKeys: Set<String> get() = desktops.mapTo(LinkedHashSet()) { it.keys }
    override fun toString() = "LomPaired(host=$host, deviceId=$deviceId, desktops=${desktops.size})"
}

/** 把字节包裹成密文再落盘。安卓上用 Keystore 里不可导出的 AES-256-GCM 密钥。 */
interface LomSecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(blob: ByteArray): ByteArray
}

/** 身份密钥与配对记录的持久化。所有方法线程安全。 */
interface LomStore {
    /** 取身份密钥对,首次调用时生成并保存。 */
    fun identity(): LomIdentity
    fun pairing(): LomPaired?
    /** 落盘失败抛 `pairing.store_failed`。成功后才能向用户显示「已连接」。 */
    fun savePairing(pairing: LomPaired)
    /** 解除配对:清除设备凭据与所有被批准的桌面公钥;身份密钥保留。 */
    fun clearPairing()
    /** 当前配对状态的流,界面据此刷新。 */
    val state: StateFlow<LomPaired?>
}

/**
 * [LomStore] 的文件实现:一个 JSON 文件,整体经 [LomSecretBox] 加密。文件放在不参与备份的目录。
 * 解不开(密钥丢了、文件被改)时按未配对处理并保留原文件不覆盖,下一次保存才替换。
 */
class EncryptedFileLomStore(private val file: File, private val box: LomSecretBox, private val random: SecureRandom = SecureRandom()) : LomStore {
    private val lock = Any()
    private var identity: LomIdentity? = null
    private val flow = MutableStateFlow<LomPaired?>(null)
    private var loaded = false

    override val state: StateFlow<LomPaired?> get() { ensureLoaded(); return flow }

    private fun ensureLoaded() {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            val root = readRoot() ?: return
            identity = root["identity"]?.let(::identityOf)
            flow.value = root["pairing"]?.let(::pairingOf)
        }
    }

    override fun identity(): LomIdentity = synchronized(lock) {
        ensureLoaded()
        identity ?: LomIdentity.generate(random).also {
            identity = it
            writeRoot()
        }
    }

    override fun pairing(): LomPaired? { ensureLoaded(); return flow.value }

    override fun savePairing(pairing: LomPaired) {
      synchronized(lock) {
        ensureLoaded()
        val ident = identity()
        val previous = flow.value
        flow.value = pairing
        try { writeRoot(ident) } catch (e: LomException) { flow.value = previous; throw e }
      }
    }

    override fun clearPairing() {
      synchronized(lock) {
        ensureLoaded()
        val previous = flow.value
        flow.value = null
        try { writeRoot() } catch (e: LomException) { flow.value = previous; throw e }
      }
    }

    private fun readRoot(): JsonObject? {
        if (!file.isFile) return null
        return try {
            Json.parseToJsonElement(String(box.open(file.readBytes()), Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            // 解不开就当没有,不吞掉:调用方拿到的是未配对,界面会让用户重新配对
            null
        }
    }

    private fun writeRoot(ident: LomIdentity? = identity) {
        val fields = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        ident?.let { fields["identity"] = identityJson(it) }
        flow.value?.let { fields["pairing"] = pairingJson(it) }
        val bytes = box.seal(Json.encodeToString(JsonObject.serializer(), JsonObject(fields)).toByteArray(Charsets.UTF_8))
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("rename") }
        } catch (_: IOException) {
            throw LomException("pairing.store_failed")
        }
    }

    private fun identityJson(i: LomIdentity): JsonObject {
        val (x, e) = i.exportSecrets()
        return JsonObject(mapOf("x25519" to JsonPrimitive(Base64Url.encode(x)), "ed25519" to JsonPrimitive(Base64Url.encode(e))))
    }

    private fun identityOf(e: kotlinx.serialization.json.JsonElement): LomIdentity? = runCatching {
        val o = e.jsonObject
        LomIdentity(Base64Url.decodeExactly(o.getValue("x25519").jsonPrimitive.content, 32)!!, Base64Url.decodeExactly(o.getValue("ed25519").jsonPrimitive.content, 32)!!)
    }.getOrNull()

    private fun pairingJson(p: LomPaired) = JsonObject(
        mapOf(
            "host" to JsonPrimitive(p.host), "device_id" to JsonPrimitive(p.deviceId), "device_token" to JsonPrimitive(p.deviceToken),
            "token_expires_at" to JsonPrimitive(p.tokenExpiresAtSec),
            "desktops" to JsonArray(p.desktops.map {
                JsonObject(mapOf("keys" to JsonPrimitive(it.keys), "name" to JsonPrimitive(it.name), "approved_at" to JsonPrimitive(it.approvedAtMs)))
            }),
        )
    )

    private fun pairingOf(e: kotlinx.serialization.json.JsonElement): LomPaired? = runCatching {
        val o = e.jsonObject
        LomPaired(
            o.getValue("host").jsonPrimitive.content, o.getValue("device_id").jsonPrimitive.content,
            o.getValue("device_token").jsonPrimitive.content, o.getValue("token_expires_at").jsonPrimitive.longOrNull ?: 0L,
            o.getValue("desktops").jsonArray.map {
                val d = it.jsonObject
                LomDesktop(d.getValue("keys").jsonPrimitive.content, d["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), d["approved_at"]?.jsonPrimitive?.longOrNull ?: 0L)
            },
        )
    }.getOrNull()
}
