package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 方法实现。返回结果 JSON 文本;失败抛 [LomException](错误码原样进回复)。 */
fun interface LomMethods {
    /** @param payload 请求载荷的 JSON 文本;@param sender 发送方桌面的公钥串(按它隔离状态)。 */
    fun call(method: String, payload: String, sender: String): String?

    /**
     * 这组实现认领的全部方法名。`device.capabilities` 据此自动生成清单,不另写一份;
     * 实现新增或删除方法时必须同步这里(LomCapabilitiesTest 会把每个方法逐个探一遍,不一致就失败)。
     */
    val methods: Set<String> get() = emptySet()

    companion object {
        /** 一个方法都不支持。 */
        val NONE = LomMethods { _, _, _ -> null }
    }
}

/**
 * 收到 `task.rpc` 信封后的完整处理(公开文档 06 §5)。
 * 返回 null 表示「不回复」:验签、信任、解密任何一步失败时桌面只会超时,不能给探测者任何信息。
 * 一旦验签解密成功,就一定回一个信封(成功或带错误码)。
 */
class LomRpcProcessor(
    private val identity: () -> LomIdentity,
    private val trusted: () -> Set<String>,
    private val replay: LomReplayStore,
    private val methods: LomMethods,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onDrop: (String) -> Unit = {},
) {
    fun process(envelope: LomEnvelope): LomEnvelope? {
        val self = identity()
        val opened = LomEnvelopes.open(envelope, self, trusted())
        if (opened !is LomOpen.Ok) {
            onDrop((opened as LomOpen.Drop).reason)
            return null
        }
        val response = respond(opened)
        return try {
            LomEnvelopes.seal(response.toJson().toByteArray(Charsets.UTF_8), opened.id, self, opened.sender)
        } catch (_: LomException) {
            // 回复太大等:换成一个小的错误回复,否则桌面只能超时
            LomEnvelopes.seal(Response.error("remote.operation_failed").toJson().toByteArray(Charsets.UTF_8), opened.id, self, opened.sender)
        }
    }

    private fun respond(opened: LomOpen.Ok): Response {
        val request = parse(opened.plaintext) ?: return Response.error("remote.operation_failed")
        val now = clock()
        val issued = request.issuedAt.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } && it.length <= 18 }?.toLong()
            ?: return Response.error("remote.operation_failed")
        if (kotlin.math.abs(now - issued) > WINDOW_MS) return Response.error("remote.expired")
        // 处理期间配对可能刚被解除:发送方必须仍然被批准
        if (trusted().none { LomCrypto.equal(it, opened.sender) }) return Response.error("remote.revoked")
        val fresh = try { replay.registerIfNew(opened.sender + ":" + opened.id, now) } catch (e: LomException) { return Response.error(e.code) }
        if (!fresh) return Response.error("remote.replayed")
        return try {
            val result = methods.call(request.method, request.payload, opened.sender) ?: return Response.error("remote.method_unknown")
            Response("ok", result, "")
        } catch (e: LomException) {
            Response.error(e.code)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // 异常文字不进回复:只给统一错误码
            Response.error("remote.operation_failed")
        }
    }

    private fun parse(plaintext: ByteArray): Request? {
        return try {
            val o = Json.parseToJsonElement(String(plaintext, Charsets.UTF_8)).jsonObject
            fun s(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            Request(s("method") ?: return null, s("payload") ?: "{}", s("issued_at").orEmpty())
        } catch (_: Exception) {
            null
        }
    }

    private class Request(val method: String, val payload: String, val issuedAt: String)

    class Response(val status: String, val payload: String, val error: String) {
        fun toJson(): String = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(linkedMapOf("status" to JsonPrimitive(status), "payload" to JsonPrimitive(payload), "error" to JsonPrimitive(error))),
        )

        companion object {
            fun error(code: String) = Response("error", "{}", code)
        }
    }

    companion object {
        const val WINDOW_MS = 300_000L
    }
}

/**
 * 本阶段唯一实现的方法(公开文档 07 §1.3):桌面每轮同步必调的三个只读方法,不支持时必须返回空集合而不是 method_unknown,
 * 否则整轮同步被判失败。其余方法一律不支持,返回 `remote.method_unknown`,**不假装成功**。
 * `device.status` 是只读无副作用的可选方法,电量/网络由 [status] 提供(未知就报未知,不编造)。
 */
class LomBasicMethods(
    private val timezone: () -> String = { java.util.TimeZone.getDefault().id },
    private val status: () -> DeviceStatus = { DeviceStatus("", "unknown") },
    private val clock: () -> Long = System::currentTimeMillis,
) : LomMethods {
    class DeviceStatus(val battery: String, val network: String)

    override val methods = setOf("document.list", "document.read", "schedule.list", "device.status")

    override fun call(method: String, payload: String, sender: String): String? = when (method) {
        "document.list" -> """{"items":[]}"""
        // 不支持文档:返回空文本,而不是 method_unknown(同步会据此判失败)
        "document.read" -> {
            val name = runCatching { Json.parseToJsonElement(payload).jsonObject["name"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
            if (!DOC_NAME.matches(name)) throw LomException("sync.invalid_name")
            JsonObject(linkedMapOf(
                "name" to JsonPrimitive(name), "text" to JsonPrimitive(""),
                "revision" to JsonPrimitive(EMPTY_REVISION), "modified" to JsonPrimitive(0),
            )).toString()
        }
        "schedule.list" -> JsonObject(linkedMapOf(
            "items" to JsonPrimitive("[]"), "timezone" to JsonPrimitive(timezone()), "exact" to JsonPrimitive("false"),
        )).toString()
        "device.status" -> status().let {
            JsonObject(linkedMapOf(
                "battery" to JsonPrimitive(it.battery), "network" to JsonPrimitive(it.network),
                "sampled_at" to JsonPrimitive(clock().toString()),
            )).toString()
        }
        else -> null
    }

    companion object {
        private val DOC_NAME = Regex("SOUL\\.md|GLOBAL\\.md|\\d{4}-\\d{2}-\\d{2}\\.md")
        /** 空文本的 SHA-256 十六进制:文档不存在按空文本处理。 */
        const val EMPTY_REVISION = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
