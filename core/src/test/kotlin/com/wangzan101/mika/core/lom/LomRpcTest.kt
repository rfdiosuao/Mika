package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LomRpcTest {
    private val desktop = LomIdentity.generate()
    private val phone = LomIdentity.generate()
    private var now = 1_800_000_000_000L
    private var trusted = setOf(desktop.keys)
    private val drops = mutableListOf<String>()
    private var seq = 0

    private fun processor(methods: LomMethods = LomBasicMethods(timezone = { "Asia/Shanghai" }, clock = { now }), replay: LomReplayStore = MemoryReplayStore()) =
        LomRpcProcessor({ phone }, { trusted }, replay, methods, { now }, { drops += it })

    private fun request(method: String, payload: String = "{}", issuedAt: String = now.toString(), id: String = "%032x".format(++seq)): LomEnvelope {
        val plain = JsonObject(linkedMapOf("method" to JsonPrimitive(method), "payload" to JsonPrimitive(payload), "issued_at" to JsonPrimitive(issuedAt)))
        return LomEnvelopes.seal(plain.toString().toByteArray(), id, desktop, phone.keys)
    }

    private fun reply(e: LomEnvelope?): JsonObject {
        assertNotNull("应当有回复", e)
        val open = LomEnvelopes.open(e!!, desktop, setOf(phone.keys)) as LomOpen.Ok
        return Json.parseToJsonElement(String(open.plaintext)).jsonObject
    }

    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

    @Test
    fun `不支持的方法回 method_unknown 而不是超时或假成功`() {
        for (m in listOf("model.sync", "chat.send", "chat.read", "chat.list", "chat.cancel", "screen.capture", "screen.control", "file.begin", "skills.list", "subagent.list", "schedule.run", "no.such")) {
            val r = reply(processor().process(request(m)))
            assertEquals(m, "error", r.str("status"))
            assertEquals(m, "remote.method_unknown", r.str("error"))
            assertEquals("{}", r.str("payload"))
        }
    }

    @Test
    fun `回复沿用请求 id 并且只含三个字段`() {
        val req = request("chat.list")
        val out = processor().process(req)!!
        assertEquals(req.id, out.id)
        assertEquals(setOf("status", "payload", "error"), reply(out).keys)
    }

    @Test
    fun `基础只读方法返回空集合而不是 method_unknown`() {
        val p = processor()
        assertEquals("""{"items":[]}""", reply(p.process(request("document.list"))).str("payload"))
        val sched = Json.parseToJsonElement(reply(p.process(request("schedule.list"))).str("payload")).jsonObject
        assertEquals("[]", sched.str("items")); assertEquals("Asia/Shanghai", sched.str("timezone")); assertEquals("false", sched.str("exact"))
        val doc = Json.parseToJsonElement(reply(p.process(request("document.read", """{"name":"SOUL.md"}"""))).str("payload")).jsonObject
        assertEquals("", doc.str("text")); assertEquals("SOUL.md", doc.str("name"))
        val bad = reply(p.process(request("document.read", """{"name":"../x"}""")))
        assertEquals("sync.invalid_name", bad.str("error"))
        val st = Json.parseToJsonElement(reply(p.process(request("device.status"))).str("payload")).jsonObject
        assertEquals("unknown", st.str("network")); assertEquals(now.toString(), st.str("sampled_at"))
    }

    @Test
    fun `过期请求回 expired 且不登记重放`() {
        val replay = MemoryReplayStore()
        val p = processor(replay = replay)
        val old = request("chat.list", issuedAt = (now - 300_001).toString(), id = hex32('d'))
        assertEquals("remote.expired", reply(p.process(old)).str("error"))
        // 窗口边界内的同一个 id 仍可通过:过期的没有被登记
        val edge = request("chat.list", issuedAt = (now - 300_000).toString(), id = hex32('d'))
        assertEquals("remote.method_unknown", reply(p.process(edge)).str("error"))
        val future = request("chat.list", issuedAt = (now + 300_001).toString())
        assertEquals("remote.expired", reply(p.process(future)).str("error"))
    }

    @Test
    fun `issued_at 缺失或不是整数回 operation_failed`() {
        assertEquals("remote.operation_failed", reply(processor().process(request("chat.list", issuedAt = ""))).str("error"))
        assertEquals("remote.operation_failed", reply(processor().process(request("chat.list", issuedAt = "12.5"))).str("error"))
        assertEquals("remote.operation_failed", reply(processor().process(request("chat.list", issuedAt = "abc"))).str("error"))
    }

    @Test
    fun `同一个信封第二次回 replayed`() {
        val p = processor()
        val req = request("document.list")
        assertEquals("ok", reply(p.process(req)).str("status"))
        assertEquals("remote.replayed", reply(p.process(req)).str("error"))
    }

    @Test
    fun `失败的执行也已登记 不会第二次执行`() {
        var calls = 0
        val p = processor(methods = { _, _, _ -> calls++; throw LomException("chat.session_missing") })
        val req = request("chat.read")
        assertEquals("chat.session_missing", reply(p.process(req)).str("error"))
        assertEquals("remote.replayed", reply(p.process(req)).str("error"))
        assertEquals(1, calls)
    }

    @Test
    fun `处理期间配对被解除回 revoked`() {
        var calls = 0
        val p = LomRpcProcessor({ phone }, { if (calls++ == 0) setOf(desktop.keys) else emptySet() }, MemoryReplayStore(), LomMethods.NONE, { now })
        assertEquals("remote.revoked", reply(p.process(request("chat.list"))).str("error"))
    }

    @Test
    fun `不可信发送方 错误收件人 篡改都不回复`() {
        val p = processor()
        trusted = emptySet()
        assertNull(p.process(request("chat.list")))
        trusted = setOf(desktop.keys)
        val stranger = LomIdentity.generate()
        val plain = """{"method":"chat.list","payload":"{}","issued_at":"$now"}""".toByteArray()
        assertNull(p.process(LomEnvelopes.seal(plain, hex32('e'), stranger, phone.keys)))
        assertNull(p.process(LomEnvelopes.seal(plain, hex32('e'), desktop, stranger.keys)))
        val good = request("chat.list")
        assertNull(p.process(good.copy(signature = good.signature.reversed())))
        assertEquals(4, drops.size)
    }

    @Test
    fun `解密成功但内层不是合法请求回 operation_failed`() {
        val e = LomEnvelopes.seal("模型同步：test-key".toByteArray(), hex32('f'), desktop, phone.keys)
        assertEquals("remote.operation_failed", reply(processor().process(e)).str("error"))
    }

    @Test
    fun `方法抛出的普通异常只回统一错误码不带异常文字`() {
        val p = processor(methods = { _, _, _ -> error("/secret/path sk-123") })
        val r = reply(p.process(request("chat.list")))
        assertEquals("remote.operation_failed", r.str("error"))
        assertTrue(!r.toString().contains("secret"))
    }

    @Test
    fun `回复超过上限时换成小的错误回复`() {
        val p = processor(methods = { _, _, _ -> "x".repeat(LomEnvelopes.MAX_PLAINTEXT + 10) })
        assertEquals("remote.operation_failed", reply(p.process(request("chat.list"))).str("error"))
    }

    @Test
    fun `去重表重启后仍然有效 且过期项会被清理`() {
        val f = File.createTempFile("replay", ".txt")
        try {
            assertTrue(FileReplayStore(f).registerIfNew("k1", 1_000))
            val reopened = FileReplayStore(f)
            assertTrue(!reopened.registerIfNew("k1", 2_000))
            assertTrue(reopened.registerIfNew("k2", 2_000))
            assertTrue(FileReplayStore(f).registerIfNew("k1", 1_000 + LomReplay.RETENTION_MS + 1))
        } finally { f.delete() }
    }

    @Test
    fun `并发登记同一个键只有一个通过`() {
        val s = MemoryReplayStore()
        val wins = java.util.concurrent.atomic.AtomicInteger()
        val threads = (1..16).map { Thread { if (s.registerIfNew("same", 1)) wins.incrementAndGet() } }
        threads.forEach(Thread::start); threads.forEach(Thread::join)
        assertEquals(1, wins.get())
    }
}
