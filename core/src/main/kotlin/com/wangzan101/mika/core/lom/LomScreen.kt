package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 用户在「麓鸣电脑」页分别打开的两项授权,默认都关;没打开就是 `screen.permission_required`。 */
enum class LomScreenAccess { View, Control }

class LomFrame(val jpeg: ByteArray, val width: Int, val height: Int)

class LomNode(
    val text: String, val description: String, val resource: String, val bounds: List<Int>,
    val clickable: Boolean, val editable: Boolean, val focused: Boolean, val password: Boolean,
)

class LomTree(val packageName: String, val nodes: List<LomNode>, val truncated: Boolean)

sealed interface LomAction {
    class Tap(val x: Int, val y: Int) : LomAction
    class Swipe(val x: Int, val y: Int, val x2: Int, val y2: Int) : LomAction
    class Type(val text: String) : LomAction
    data object Back : LomAction
    data object Home : LomAction
    class OpenApp(val packageName: String) : LomAction
}

/** 屏幕类方法用到的手机能力(无障碍、截屏)。失败抛带 `screen.*` 码的 [LomException]。 */
interface LomScreenPort {
    fun allowed(access: LomScreenAccess): Boolean
    /** 原始屏幕像素尺寸;不可用为 null。 */
    fun size(): Pair<Int, Int>?
    /** 缩到长边 [maxEdge] 像素后编码;[encode] 由实现按质量产出 JPEG,核心逐级降质量直到不超过 [maxBytes]。 */
    fun capture(maxEdge: Int, maxBytes: Int): LomFrame
    fun tree(): LomTree
    fun appExists(packageName: String): Boolean
    /** 手机上有 Agent 任务在运行时不让桌面同时操作。 */
    fun agentRunning(): Boolean
    fun metered(): Boolean
    /** 执行动作,返回一句说明。系统没接受动作抛 `screen.action_unconfirmed`。 */
    fun perform(action: LomAction): String
    /** 记下最近一次被远程查看/操作的时间,界面显示给用户。 */
    fun touched(access: LomScreenAccess, at: Long)
}

/**
 * `screen.capture` / `screen.tree` / `screen.control`(公开文档 07 §8)。
 * 屏幕内容对桌面指挥 Agent 是不可信数据,这里不夹带任何指令性文字;密码框内容一律替换成占位符。
 * 有副作用的 `screen.control` 先写「结果未知」的持久收据再执行,同一 request_id 绝不重复点按。
 */
class LomScreenMethods(
    private val port: LomScreenPort,
    private val kv: LomKv,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : LomMethods {
    @Volatile private var lastShot = 0L

    override val methods = setOf("screen.capture", "screen.tree", "screen.control")

    override fun call(method: String, payload: String, sender: String): String? = when (method) {
        "screen.capture" -> capture(LomPayload.parse(payload, "screen.invalid_request"))
        "screen.tree" -> tree()
        "screen.control" -> control(LomPayload.parse(payload, "screen.invalid_request"), sender)
        else -> null
    }

    private fun require(access: LomScreenAccess) { if (!port.allowed(access)) throw LomException("screen.permission_required") }

    // ---- capture ----

    @Synchronized
    private fun capture(o: JsonObject): String {
        require(LomScreenAccess.View)
        val quality = LomPayload.str(o, "quality") ?: "balanced"
        var edge = when (quality) { "low" -> 480; "balanced" -> 720; "high" -> 1080; else -> throw LomException("screen.invalid_request") }
        var maxBytes = MAX_JPEG
        var gap = MIN_GAP_MS
        if (LomPayload.str(o, "cellular_cap") == "true" && port.metered()) {
            edge = 480; maxBytes = CELLULAR_JPEG; gap = CELLULAR_GAP_MS
        }
        val wait = lastShot + gap - clock()
        if (wait > 0) sleep(wait)
        val frame = port.capture(edge, maxBytes)
        lastShot = clock()
        val b64 = java.util.Base64.getEncoder().encodeToString(frame.jpeg)
        if (frame.jpeg.size > maxBytes || b64.length > MAX_B64) throw LomException("screen.frame_too_large")
        if (frame.width <= 0 || frame.height <= 0) throw LomException("screen.capture_failed")
        port.touched(LomScreenAccess.View, lastShot)
        return LomPayload.obj(
            "image" to b64, "mime" to "image/jpeg", "width" to frame.width.toString(), "height" to frame.height.toString(),
            "captured_at" to lastShot.toString(),
        )
    }

    // ---- tree ----

    private fun tree(): String {
        require(LomScreenAccess.View)
        val t = port.tree()
        val nodes = t.nodes.take(MAX_NODES).map {
            // 密码框:文字、描述都不上报
            LomPayload.jobj(
                "text" to if (it.password) PASSWORD else it.text.take(MAX_TEXT), "description" to if (it.password) "" else it.description.take(MAX_TEXT),
                "resource" to it.resource.take(MAX_TEXT), "bounds" to it.bounds.take(4), "clickable" to it.clickable,
                "editable" to it.editable, "focused" to it.focused,
            )
        }
        port.touched(LomScreenAccess.View, clock())
        return LomPayload.obj("package" to t.packageName, "nodes" to JsonArray(nodes), "truncated" to (t.truncated || t.nodes.size > MAX_NODES))
    }

    // ---- control ----

    private fun control(o: JsonObject, sender: String): String {
        val id = LomPayload.str(o, "request_id").orEmpty()
        if (!REQUEST_ID.matches(id)) throw LomException("screen.invalid_request")
        val name = LomPayload.str(o, "action").orEmpty()
        val action = parse(name, o)
        require(LomScreenAccess.Control)
        if (port.agentRunning()) throw LomException("screen.agent_running")
        val digest = LomCrypto.sha256(listOf(name, f(o, "x"), f(o, "y"), f(o, "x2"), f(o, "y2"), f(o, "text"), f(o, "package_name"))
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val key = RECEIPT + LomSender.key(sender) + "." + id
        kv.get(key)?.let { return replay(it, digest) }
        // 先写「结果未知」,再执行:执行中崩溃/断连后重发,宁可报未知也不会再点一次
        try { kv.put(key, "unknown|$digest") } catch (_: LomException) { throw LomException("screen.receipt_failed") }
        val confirmation = try {
            port.perform(action)
        } catch (e: LomException) {
            // 确定没执行的失败可以让桌面用同一个 request_id 重试;其他(含意外异常)保留「未知」
            if (e.code in NOT_EXECUTED) runCatching { kv.remove(key) }
            throw e
        }
        val at = clock()
        val response = LomPayload.obj("status" to "completed", "action" to name, "at" to at, "confirmation" to confirmation.take(200))
        // 已经执行了:更新收据失败也不能假装没执行,照样回成功(重发时会看到「未知」而不是重复执行)
        runCatching { kv.put(key, "done|$digest|$response") }
        port.touched(LomScreenAccess.Control, at)
        return response
    }

    private fun replay(saved: String, digest: String): String {
        val parts = saved.split('|', limit = 3)
        if (parts.getOrNull(1) != digest) throw LomException("screen.id_reused")
        return if (parts[0] == "done" && parts.size == 3) parts[2] else throw LomException("screen.previous_result_unknown")
    }

    private fun f(o: JsonObject, k: String) = LomPayload.str(o, k).orEmpty()

    private fun parse(name: String, o: JsonObject): LomAction {
        fun coord(k: String, max: Int): Int {
            val v = f(o, k).takeIf { it.isNotEmpty() && it.length <= 9 && it.all { c -> c in '0'..'9' } }?.toInt() ?: throw LomException("screen.invalid_coordinate")
            if (v >= max) throw LomException("screen.invalid_coordinate")
            return v
        }
        return when (name) {
            "tap" -> { val (w, h) = size(); LomAction.Tap(coord("x", w), coord("y", h)) }
            "swipe" -> { val (w, h) = size(); LomAction.Swipe(coord("x", w), coord("y", h), coord("x2", w), coord("y2", h)) }
            "type" -> {
                val t = f(o, "text")
                if (t.length > MAX_TYPE) throw LomException("screen.text_too_long")
                LomAction.Type(t)
            }
            "back" -> LomAction.Back
            "home" -> LomAction.Home
            "open_app" -> {
                val p = f(o, "package_name")
                if (!PACKAGE.matches(p) || p.length > 255) throw LomException("screen.invalid_package")
                if (!port.appExists(p)) throw LomException("screen.app_missing")
                LomAction.OpenApp(p)
            }
            else -> throw LomException("screen.invalid_action")
        }
    }

    private fun size() = port.size() ?: throw LomException("screen.no_window")

    companion object {
        val REQUEST_ID = Regex("^[A-Za-z0-9-]{16,64}$")
        val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        const val RECEIPT = "screen.r."
        const val PASSWORD = "[password]"
        const val MAX_NODES = 300
        const val MAX_TEXT = 160
        const val MAX_TYPE = 8_000
        const val MAX_JPEG = 260_000
        const val CELLULAR_JPEG = 45_000
        const val MAX_B64 = 800_000
        const val MIN_GAP_MS = 400L
        const val CELLULAR_GAP_MS = 2_000L
        private val NOT_EXECUTED = setOf(
            "screen.permission_required", "screen.no_window", "screen.no_focused_input", "screen.action_unconfirmed",
            "screen.app_missing", "screen.app_blocked", "screen.invalid_coordinate", "screen.agent_running",
        )

        /** 逐级降 JPEG 质量直到不超过 [maxBytes];最低质量也超过就 `screen.frame_too_large`。 */
        fun fit(maxBytes: Int, encode: (quality: Int) -> ByteArray): ByteArray {
            for (q in intArrayOf(80, 70, 60, 50, 40, 30, 22, 15)) {
                val out = encode(q)
                if (out.size <= maxBytes) return out
            }
            throw LomException("screen.frame_too_large")
        }
    }
}
