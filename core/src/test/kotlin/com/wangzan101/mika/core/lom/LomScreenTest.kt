package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LomScreenTest {
    private class Port : LomScreenPort {
        val allow = mutableSetOf(LomScreenAccess.View, LomScreenAccess.Control)
        var jpeg = ByteArray(1000) { 1 }
        var treeValue = LomTree("com.x", emptyList(), false)
        var running = false
        var metered = false
        var apps = setOf("com.ok.app")
        val performed = mutableListOf<LomAction>()
        var failWith: LomException? = null
        var crashAfterEffect = false
        val captures = mutableListOf<Pair<Int, Int>>()
        override fun allowed(access: LomScreenAccess) = access in allow
        override fun size() = 1080 to 2400
        override fun capture(maxEdge: Int, maxBytes: Int): LomFrame { captures += maxEdge to maxBytes; return LomFrame(jpeg, 1080, 2400) }
        override fun tree() = treeValue
        override fun appExists(packageName: String) = packageName in apps
        override fun agentRunning() = running
        override fun metered() = metered
        override fun perform(action: LomAction): String {
            failWith?.let { throw it }
            performed += action
            if (crashAfterEffect) throw IllegalStateException("boom")
            return "ok"
        }
        override fun touched(access: LomScreenAccess, at: Long) {}
    }

    private val port = Port()
    private val kv = MemoryLomKv()
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()
    private val screen = LomScreenMethods(port, kv, { now }, { sleeps += it; now += it })
    private val a = "A".repeat(86)
    private val rid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

    private fun ctl(vararg p: Pair<String, Any?>, id: String = rid, sender: String = a): JsonObject =
        Json.parseToJsonElement(screen.call("screen.control", LomPayload.obj("request_id" to id, *p), sender)!!).jsonObject
    private fun ctlCode(vararg p: Pair<String, Any?>, id: String = rid): String =
        try { ctl(*p, id = id); "" } catch (e: LomException) { e.code }
    private fun tap(x: String = "10", y: String = "20") = arrayOf<Pair<String, Any?>>("action" to "tap", "x" to x, "y" to y, "x2" to "", "y2" to "", "text" to "", "package_name" to "")

    @Test fun `without the users switches everything is permission_required`() {
        port.allow.clear()
        for ((m, p) in listOf("screen.capture" to "{}", "screen.tree" to "{}", "screen.control" to """{"request_id":"$rid","action":"home"}"""))
            assertEquals(m, "screen.permission_required", try { screen.call(m, p, a); "" } catch (e: LomException) { e.code })
        assertTrue(port.performed.isEmpty())
        port.allow += LomScreenAccess.View // 只开了查看:操作仍然被拒
        assertEquals("screen.permission_required", ctlCode("action" to "home"))
    }

    @Test fun `capture response shape and original size`() {
        val o = Json.parseToJsonElement(screen.call("screen.capture", """{"quality":"high","cellular_cap":"false"}""", a)!!).jsonObject
        assertEquals("image/jpeg", o["mime"]!!.jsonPrimitive.content)
        assertEquals("1080", o["width"]!!.jsonPrimitive.content); assertEquals("2400", o["height"]!!.jsonPrimitive.content)
        assertTrue(o["image"]!!.jsonPrimitive.content.matches(Regex("^[A-Za-z0-9+/=]+$")))
        assertEquals(1080 to LomScreenMethods.MAX_JPEG, port.captures.single())
        assertTrue(o["captured_at"]!!.jsonPrimitive.content.toLong() > 0)
    }

    @Test fun `capture quality edges and bad quality`() {
        for ((q, edge) in listOf("low" to 480, "balanced" to 720, "high" to 1080)) {
            port.captures.clear(); now += 1000
            screen.call("screen.capture", """{"quality":"$q"}""", a)
            assertEquals(edge, port.captures.single().first)
        }
        assertEquals("screen.invalid_request", try { screen.call("screen.capture", """{"quality":"ultra"}""", a); "" } catch (e: LomException) { e.code })
    }

    @Test fun `cellular cap on a metered network drops to low and spaces shots 2s apart`() {
        port.metered = true
        screen.call("screen.capture", """{"quality":"high","cellular_cap":"true"}""", a)
        assertEquals(480 to LomScreenMethods.CELLULAR_JPEG, port.captures.single())
        sleeps.clear()
        screen.call("screen.capture", """{"quality":"high","cellular_cap":"true"}""", a)
        assertEquals(LomScreenMethods.CELLULAR_GAP_MS, sleeps.single()) // 两次至少隔 2 秒
        port.metered = false; port.captures.clear(); now += 10_000
        screen.call("screen.capture", """{"quality":"high","cellular_cap":"true"}""", a) // 不是计费网络:不降级
        assertEquals(1080, port.captures.single().first)
    }

    @Test fun `normal shots are at least 400ms apart`() {
        screen.call("screen.capture", "{}", a); sleeps.clear()
        screen.call("screen.capture", "{}", a)
        assertEquals(LomScreenMethods.MIN_GAP_MS, sleeps.single())
    }

    @Test fun `oversized frame is an error and the ladder lowers quality`() {
        port.jpeg = ByteArray(300_000)
        assertEquals("screen.frame_too_large", try { screen.call("screen.capture", "{}", a); "" } catch (e: LomException) { e.code })
        val used = mutableListOf<Int>()
        val out = LomScreenMethods.fit(100) { q -> used += q; ByteArray(if (q <= 50) 90 else 500) }
        assertEquals(90, out.size); assertEquals(listOf(80, 70, 60, 50), used)
        assertEquals("screen.frame_too_large", try { LomScreenMethods.fit(10) { ByteArray(500) }; "" } catch (e: LomException) { e.code })
    }

    @Test fun `tree redacts password fields, caps nodes and text`() {
        val nodes = (1..350).map { LomNode("t".repeat(300), "d", "id/x$it", listOf(0, 0, 10, 10), true, false, false, false) } +
            LomNode("hunter2", "secret desc", "id/pw", listOf(0, 0, 1, 1), false, true, true, true)
        port.treeValue = LomTree("com.x", listOf(LomNode("hunter2", "secret desc", "id/pw", listOf(0, 0, 1, 1), false, true, true, true)) + nodes, false)
        val o = Json.parseToJsonElement(screen.call("screen.tree", "{}", a)!!).jsonObject
        val arr = o["nodes"]!!.jsonArray
        assertEquals(300, arr.size); assertTrue(o["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("[password]", arr[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("", arr[0].jsonObject["description"]!!.jsonPrimitive.content)
        assertEquals(160, arr[1].jsonObject["text"]!!.jsonPrimitive.content.length)
        assertFalse(o.toString().contains("hunter2")); assertFalse(o.toString().contains("secret desc"))
        assertEquals("com.x", o["package"]!!.jsonPrimitive.content)
    }

    @Test fun `control completes and echoes action`() {
        val r = ctl(*tap())
        assertEquals("completed", r["status"]!!.jsonPrimitive.content); assertEquals("tap", r["action"]!!.jsonPrimitive.content)
        assertTrue(r["at"]!!.jsonPrimitive.long > 0)
        val t = port.performed.single() as LomAction.Tap
        assertEquals(10 to 20, t.x to t.y)
    }

    @Test fun `all six actions parse`() {
        ctl("action" to "swipe", "x" to "1", "y" to "2", "x2" to "3", "y2" to "4", id = "a".repeat(16))
        ctl("action" to "type", "text" to "你好", id = "b".repeat(16))
        ctl("action" to "back", id = "c".repeat(16)); ctl("action" to "home", id = "d".repeat(16))
        ctl("action" to "open_app", "package_name" to "com.ok.app", id = "e".repeat(16))
        assertEquals(5, port.performed.size)
        assertTrue(port.performed[4] is LomAction.OpenApp)
    }

    @Test fun `validation codes`() {
        assertEquals("screen.invalid_action", ctlCode("action" to "shell"))
        assertEquals("screen.invalid_action", ctlCode("action" to "TAP"))
        assertEquals("screen.invalid_coordinate", ctlCode(*tap("1080", "5"))) // 超出屏幕
        assertEquals("screen.invalid_coordinate", ctlCode(*tap("-1", "5")))
        assertEquals("screen.invalid_coordinate", ctlCode(*tap("", "5")))
        assertEquals("screen.invalid_coordinate", ctlCode(*tap("1e3", "5")))
        assertEquals("screen.text_too_long", ctlCode("action" to "type", "text" to "x".repeat(8_001)))
        assertEquals("screen.invalid_package", ctlCode("action" to "open_app", "package_name" to "../etc"))
        assertEquals("screen.invalid_package", ctlCode("action" to "open_app", "package_name" to "nodots"))
        assertEquals("screen.app_missing", ctlCode("action" to "open_app", "package_name" to "com.not.installed"))
        assertEquals("screen.invalid_request", ctlCode("action" to "home", id = "short"))
        assertTrue(port.performed.isEmpty()); assertTrue(kv.keys("screen.r.").isEmpty())
    }

    @Test fun `same request_id twice taps once and returns the original answer`() {
        val first = ctl(*tap()); now += 5000
        val second = ctl(*tap())
        assertEquals(first, second)
        assertEquals(1, port.performed.size)
    }

    @Test fun `same request_id with other content is id_reused`() {
        ctl(*tap("1", "1"))
        assertEquals("screen.id_reused", ctlCode(*tap("2", "2")))
        assertEquals(1, port.performed.size)
    }

    @Test fun `a crash after the effect leaves the receipt unknown and a resend is refused`() {
        port.crashAfterEffect = true
        assertEquals("remote.operation_failed", try { screen.call("screen.control", LomPayload.obj("request_id" to rid, *tap()), a); "" } catch (e: Exception) { "remote.operation_failed" })
        port.crashAfterEffect = false
        assertEquals("screen.previous_result_unknown", ctlCode(*tap()))
        assertEquals(1, port.performed.size) // 绝不重复点按
    }

    @Test fun `receipt is written before the action runs`() {
        var seen: String? = null
        val spy = object : LomScreenPort by port {
            override fun perform(action: LomAction): String { seen = kv.keys("screen.r.").firstOrNull()?.let(kv::get); return "ok" }
        }
        LomScreenMethods(spy, kv, { now }, {}).call("screen.control", LomPayload.obj("request_id" to rid, *tap()), a)
        assertTrue(seen!!.startsWith("unknown|"))
    }

    @Test fun `definitely-not-executed failures can be retried with the same id`() {
        port.failWith = LomException("screen.action_unconfirmed")
        assertEquals("screen.action_unconfirmed", ctlCode(*tap()))
        port.failWith = null
        assertEquals("completed", ctl(*tap())["status"]!!.jsonPrimitive.content)
        assertEquals(1, port.performed.size)
    }

    @Test fun `receipt write failure refuses to act`() {
        kv.failWrites = true
        assertEquals("screen.receipt_failed", ctlCode(*tap()))
        assertTrue(port.performed.isEmpty())
    }

    @Test fun `phone busy with an agent task refuses control but not viewing`() {
        port.running = true
        assertEquals("screen.agent_running", ctlCode(*tap()))
        screen.call("screen.capture", "{}", a)
        assertTrue(port.performed.isEmpty())
    }

    @Test fun `receipts are per desktop`() {
        ctl(*tap(), sender = a); ctl(*tap(), sender = "B".repeat(86))
        assertEquals(2, port.performed.size)
    }

    @Test fun `other methods not claimed`() {
        assertNull(screen.call("screen.nope", "{}", a))
    }
}
