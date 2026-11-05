package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 设备信息:桌面上显示的设备名。云端会截断到 64 字符、去控制字符。 */
data class LomDeviceInfo(val name: String, val model: String, val os: String, val appVersion: String)

class LomInspect(val pairingId: String, val desktopName: String, val desktopKeys: String, val maskedAccount: String, val expiresAtSec: Long)
class LomClaim(val claimSecret: String, val expiresAtSec: Long)
class LomClaimByCode(val pairingId: String, val desktopName: String, val desktopKeys: String, val maskedAccount: String, val expiresAtSec: Long, val claimSecret: String)

/** `confirm` 的结果。[deviceToken] 是秘密,不进日志、不进 toString。 */
class LomConfirm(val deviceId: String, val deviceToken: String, val tokenExpiresAtSec: Long, val desktopName: String) {
    override fun toString() = "LomConfirm(deviceId=$deviceId)"
}

/**
 * 云端 HTTP 接口(公开文档 03、05)。只走 HTTPS;网络层失败统一映射 `net.unreachable`,
 * 非 JSON 或缺字段的响应映射 `pairing.response_invalid`,云端错误码原样抛 [LomException]。
 * [endpoint] 把主机名变成基础地址,生产里固定 https,测试里指向本机假服务。
 */
class LomCloud(
    http: OkHttpClient = OkHttpClient(),
    private val endpoint: (String) -> HttpUrl = { host -> HttpUrl.Builder().scheme("https").host(host).build() },
) {
    val client: OkHttpClient = http.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun inspect(host: String, pairingId: String, claimToken: String): LomInspect {
        val d = post(host, "/v1/pairing/sessions/$pairingId/inspect", obj("claim_token" to claimToken))
        return LomInspect(d.str("pairing_id"), d.str("desktop_name"), d.str("desktop_keys"), d.str("masked_account"), d.long("expires_at"))
    }

    fun claim(host: String, pairingId: String, claimToken: String, deviceKeys: String, info: LomDeviceInfo, keyProof: String): LomClaim {
        val d = post(host, "/v1/pairing/sessions/$pairingId/claim", obj(
            "claim_token" to claimToken, "device_keys" to deviceKeys, "device_info" to infoJson(info), "key_proof" to keyProof,
        ))
        return LomClaim(d.str("claim_secret"), d.long("expires_at"))
    }

    fun claimByCode(host: String, manualCode: String, deviceKeys: String, info: LomDeviceInfo): LomClaimByCode {
        val d = post(host, "/v1/pairing/claim-by-code", obj("manual_code" to manualCode, "device_keys" to deviceKeys, "device_info" to infoJson(info)))
        return LomClaimByCode(d.str("pairing_id"), d.str("desktop_name"), d.str("desktop_keys"), d.str("masked_account"), d.long("expires_at"), d.str("claim_secret"))
    }

    fun confirm(host: String, pairingId: String, claimSecret: String): LomConfirm {
        val d = post(host, "/v1/pairing/sessions/$pairingId/confirm", obj("claim_secret" to claimSecret))
        val id = d.str("device_id")
        val token = d.str("device_token")
        if (!DEVICE_ID.matches(id) || Base64Url.decodeExactly(token, 32) == null) throw LomException("pairing.response_invalid")
        return LomConfirm(id, token, d.long("token_expires_at"), d.str("desktop_name"))
    }

    fun cancel(host: String, pairingId: String, claimSecret: String) {
        post(host, "/v1/pairing/sessions/$pairingId/cancel", obj("claim_secret" to claimSecret), needData = false)
    }

    /** 用设备凭据换 1 小时的中转令牌。 */
    fun relayToken(host: String, deviceId: String, deviceToken: String): String {
        val d = post(host, "/v1/relay/token", JsonObject(emptyMap()), auth = "Device $deviceId.$deviceToken")
        return d.str("relay_token")
    }

    /** 手机主动解除配对;云端同时作废凭据并关闭 WebSocket。 */
    fun unpair(host: String, deviceId: String, deviceToken: String) {
        call(host, "/v1/devices/me", null, "DELETE", "Device $deviceId.$deviceToken", needData = false)
    }

    /** WebSocket 握手请求。令牌只放请求头。 */
    fun relayRequest(host: String, relayToken: String): Request =
        Request.Builder().url(endpoint(host).newBuilder().addPathSegments("v1/relay").build())
            .header("Authorization", "Bearer $relayToken").build()

    private fun post(host: String, path: String, body: JsonObject, auth: String? = null, needData: Boolean = true) =
        call(host, path, body, "POST", auth, needData)

    private fun call(host: String, path: String, body: JsonObject?, method: String, auth: String?, needData: Boolean): JsonObject {
        val url = endpoint(host).newBuilder().encodedPath(path).build()
        val req = Request.Builder().url(url).apply {
            auth?.let { header("Authorization", it) }
            if (body != null) post(Json.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON))
            else method(method, null)
        }.build()
        val response: Response = try { client.newCall(req).execute() } catch (_: IOException) { throw LomException("net.unreachable") }
        response.use { r ->
            val text = try { r.peekBody(MAX_BODY).string() } catch (_: IOException) { throw LomException("net.unreachable") }
            val root = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { throw LomException("pairing.response_invalid", r.code) }
            val ok = (root["success"] as? JsonPrimitive)?.booleanOrNull
            if (ok == true) return (root["data"] as? JsonObject) ?: if (needData) throw LomException("pairing.response_invalid", r.code) else JsonObject(emptyMap())
            val code = (root["code"] as? JsonPrimitive)?.contentOrNull
            if (ok == false && code != null && LomException.CODE.matches(code)) throw LomException(code, r.code)
            throw LomException("pairing.response_invalid", r.code)
        }
    }

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = JsonObject(pairs.associate { (k, v) ->
        k to (if (v is JsonElement) v else JsonPrimitive(v as String))
    })

    private fun infoJson(i: LomDeviceInfo): JsonObject = obj("name" to i.name, "model" to i.model, "os" to i.os, "app_version" to i.appVersion)

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw LomException("pairing.response_invalid")

    private fun JsonObject.long(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: throw LomException("pairing.response_invalid")

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_BODY = 1_048_576L
        private val DEVICE_ID = Regex("dev_[0-9a-f]{32}")
    }
}
