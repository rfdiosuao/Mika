package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LomChatTest {
    private class Port : LomChatPort {
        val tasks = LinkedHashMap<String, MutableList<LomTaskView>>() // 会话 → 任务
        val turns = HashMap<String, MutableList<LomTurnView>>()
        val titles = HashMap<String, String>()
        val pinned = HashSet<String>()
        val started = mutableListOf<Triple<String, String, String>>()
        val answers = mutableListOf<Pair<String, String>>()
        val cancelled = mutableListOf<String>()
        var deleted = mutableListOf<String>()
        var failStart: LomException? = null
        var cancelFinishes = true
        override fun start(requestId: String, goal: String, conversation: String) {
            failStart?.let { throw it }
            started += Triple(requestId, goal, conversation)
            // 同编号再来:任务服务去重,不会新增
            if (tasks[conversation]?.any { it.id == requestId } != true) tasks.getOrPut(conversation) { mutableListOf() } += LomTaskView(requestId, goal, 1000L, LomPhase.Running, "")
        }
        override fun answer(taskId: String, text: String): Boolean { answers += taskId to text; return true }
        override fun tasks(conversation: String) = tasks[conversation].orEmpty().toList()
        override fun cancel(taskId: String) {
            cancelled += taskId
            if (cancelFinishes) tasks.values.forEach { l -> l.replaceAll { if (it.id == taskId) LomTaskView(it.id, it.goal, it.startedAt, LomPhase.Cancelled, "") else it } }
        }
        override fun turns(conversation: String) = turns[conversation]
        override fun summary(conversation: String) =
            if (turns[conversation] != null || titles.containsKey(conversation)) LomSummary(titles[conversation], 5000L, conversation in pinned) else null
        override fun rename(conversation: String, title: String): Boolean { titles[conversation] = title; return true }
        override fun pin(conversation: String, pinned: Boolean): Boolean { if (pinned) this.pinned += conversation else this.pinned -= conversation; return true }
        override fun delete(conversation: String) { deleted += conversation; tasks.remove(conversation); turns.remove(conversation); titles.remove(conversation) }
    }

    private val port = Port()
    private val kv = MemoryLomKv()
    private var ready = true
    private var now = 10_000L
    private val sleeps = mutableListOf<Long>()
    private val chat = LomChatMethods(port, kv, { ready }, { now }, { sleeps += it; now += it })
    private val a = "A".repeat(86)
    private val b = "B".repeat(86)
    private val cid = "11111111-2222-3333-4444-555555555555"

    private fun call(m: String, vararg p: Pair<String, Any?>, sender: String = a): JsonObject =
        Json.parseToJsonElement(chat.call(m, LomPayload.obj(*p), sender)!!).jsonObject
    private fun code(m: String, vararg p: Pair<String, Any?>, sender: String = a): String =
        try { chat.call(m, LomPayload.obj(*p), sender); "" } catch (e: LomException) { e.code }
    private fun send(text: String = "你好", session: String = "", client: String = cid, sender: String = a) =
        call("chat.send", "client_id" to client, "session_id" to session, "text" to text, "files" to "[]", sender = sender)

    @Test fun `send creates a session and starts one task`() {
        val r = send()
        assertEquals("accepted", r["status"]!!.jsonPrimitive.content)
        assertEquals(cid, r["client_id"]!!.jsonPrimitive.content)
        val session = r["session_id"]!!.jsonPrimitive.content
        assertTrue(session.startsWith("mcp-"))
        assertEquals(listOf("你好"), port.started.map { it.second })
        assertEquals(session, port.started.single().third)
    }

    @Test fun `same client_id and content is a replay and never runs twice`() {
        val s1 = send()["session_id"]!!.jsonPrimitive.content
        val s2 = send()["session_id"]!!.jsonPrimitive.content
        assertEquals(s1, s2)
        assertEquals(1, port.tasks[s1]!!.size)
        // 重放时任务编号与首次一致(任务服务据此去重)
        assertEquals(1, port.started.map { it.first }.toSet().size)
    }

    @Test fun `same client_id with different content is rejected`() {
        send("甲")
        assertEquals("chat.id_reused", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "乙", "files" to "[]"))
        assertEquals(1, port.tasks.values.sumOf { it.size })
    }

    @Test fun `request ids are per desktop and per client_id`() {
        send(); send(client = "99999999-2222-3333-4444-555555555555"); send(sender = b)
        assertEquals(3, port.started.map { it.first }.toSet().size)
    }

    @Test fun `validation errors come before any side effect`() {
        assertEquals("chat.invalid_id", code("chat.send", "client_id" to "short", "session_id" to "", "text" to "x"))
        assertEquals("chat.invalid_id", code("chat.send", "client_id" to "has space has space has space", "session_id" to "", "text" to "x"))
        assertEquals("chat.invalid_text", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "   "))
        assertEquals("chat.invalid_text", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "字".repeat(4_001)))
        assertEquals("chat.session_missing", code("chat.send", "client_id" to cid, "session_id" to "mcp-nope", "text" to "x"))
        assertEquals("file.too_many", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x", "files" to (1..11).joinToString(",", "[", "]") { "\"id$it\"" }))
        assertEquals("file.invalid_id", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x", "files" to "not json"))
        assertEquals("chat.attachments_unsupported", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x", "files" to "[\"abcdefabcdefabcdef\"]"))
        assertTrue(port.started.isEmpty())
        assertTrue(kv.keys("chat.").isEmpty()) // 失败也不留收据
    }

    @Test fun `no model synced means chat_model_missing and the same client_id works later`() {
        ready = false
        assertEquals("chat.model_missing", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x"))
        ready = true
        send("x") // 桌面重推模型后用同一个 client_id 重试
        assertEquals(1, port.started.size)
    }

    @Test fun `running session is busy, other desktops cannot touch it`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        assertEquals("chat.busy", code("chat.send", "client_id" to "aaaaaaaa-2222-3333-4444-555555555555", "session_id" to s, "text" to "再来"))
        assertEquals("chat.session_missing", code("chat.read", "session_id" to s, "offset" to 0, sender = b))
        assertEquals("chat.session_missing", code("chat.cancel", "session_id" to s, sender = b))
        assertEquals("chat.session_missing", code("chat.rename", "session_id" to s, "title" to "x", sender = b))
    }

    @Test fun `start failure rolls the receipt back so the same client_id can retry`() {
        port.failStart = LomException("remote.not_ready")
        assertEquals("remote.not_ready", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x"))
        assertTrue(kv.keys("chat.").isEmpty())
        port.failStart = null
        send("x")
        assertEquals(1, port.started.size)
    }

    @Test fun `receipt write failure means nothing is started`() {
        kv.failWrites = true
        assertEquals("chat.receipt_failed", code("chat.send", "client_id" to cid, "session_id" to "", "text" to "x"))
        assertTrue(port.started.isEmpty())
    }

    @Test fun `a task waiting for an answer takes the next message as the answer`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        val t = port.tasks[s]!!.single()
        port.tasks[s]!![0] = LomTaskView(t.id, t.goal, t.startedAt, LomPhase.WaitingAnswer, "要不要继续？")
        call("chat.send", "client_id" to "aaaaaaaa-2222-3333-4444-555555555555", "session_id" to s, "text" to "继续", "files" to "[]")
        assertEquals(listOf(t.id to "继续"), port.answers)
        assertEquals(1, port.started.size) // 没有另开任务
        // 重发同一条不会再回答一次
        call("chat.send", "client_id" to "aaaaaaaa-2222-3333-4444-555555555555", "session_id" to s, "text" to "继续", "files" to "[]")
        assertEquals(1, port.answers.size)
    }

    @Test fun `read returns running progress then stable ids after the turn is stored`() {
        val s = send("查天气")["session_id"]!!.jsonPrimitive.content
        var r = call("chat.read", "session_id" to s, "offset" to 0)
        assertTrue(r["running"]!!.jsonPrimitive.boolean); assertEquals("running", r["status"]!!.jsonPrimitive.content)
        val items = r["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("user", "assistant"), items.map { it["role"]!!.jsonPrimitive.content })
        assertEquals("查天气", items[0]["text"]!!.jsonPrimitive.content)
        val ids = items.map { it["id"]!!.jsonPrimitive.content }
        // 任务完成并存成一轮:消息 ID 不变,内容变成答案
        val t = port.tasks[s]!![0]
        port.tasks[s]!![0] = LomTaskView(t.id, t.goal, t.startedAt, LomPhase.Completed, "晴")
        port.turns[s] = mutableListOf(LomTurnView("查天气", "晴，22 度", LomPhase.Completed))
        r = call("chat.read", "session_id" to s, "offset" to 0)
        assertFalse(r["running"]!!.jsonPrimitive.boolean); assertEquals("completed", r["status"]!!.jsonPrimitive.content); assertEquals("", r["error"]!!.jsonPrimitive.content)
        val after = r["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(ids, after.map { it["id"]!!.jsonPrimitive.content })
        assertEquals("晴，22 度", after[1]["text"]!!.jsonPrimitive.content)
        assertEquals(2, r["total"]!!.jsonPrimitive.int); assertEquals(2, r["next"]!!.jsonPrimitive.int)
    }

    @Test fun `read paginates by 50 and always makes progress`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        port.tasks[s]!![0] = LomTaskView(port.tasks[s]!![0].id, "q", 1L, LomPhase.Completed, "")
        port.turns[s] = (1..40).map { LomTurnView("q$it", "a$it", LomPhase.Completed) }.toMutableList()
        val first = call("chat.read", "session_id" to s, "offset" to 0)
        assertEquals(50, first["items"]!!.jsonArray.size); assertEquals(80, first["total"]!!.jsonPrimitive.int); assertEquals(50, first["next"]!!.jsonPrimitive.int)
        val second = call("chat.read", "session_id" to s, "offset" to 50)
        assertEquals(30, second["items"]!!.jsonArray.size); assertEquals(80, second["next"]!!.jsonPrimitive.int)
        val past = call("chat.read", "session_id" to s, "offset" to 999)
        assertEquals(0, past["items"]!!.jsonArray.size)
    }

    @Test fun `read keeps each page under the reply budget`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        port.tasks[s]!![0] = LomTaskView(port.tasks[s]!![0].id, "q", 1L, LomPhase.Completed, "")
        port.turns[s] = (1..30).map { LomTurnView("q", "长".repeat(25_000), LomPhase.Completed) }.toMutableList()
        val r = call("chat.read", "session_id" to s, "offset" to 0)
        assertTrue(r["items"]!!.jsonArray.size in 1..49)
        assertTrue(r.toString().toByteArray().size <= 350_000)
        assertTrue(r["next"]!!.jsonPrimitive.int > 0)
    }

    @Test fun `failed task reports a generic error not internal details`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        port.tasks[s]!![0] = LomTaskView(port.tasks[s]!![0].id, "x", 1L, LomPhase.Failed, "Authorization: Bearer sk-secret")
        val r = call("chat.read", "session_id" to s, "offset" to 0)
        assertEquals("failed", r["status"]!!.jsonPrimitive.content)
        assertFalse(r["error"]!!.jsonPrimitive.content.contains("sk-secret"))
        assertNotEquals("", r["error"]!!.jsonPrimitive.content)
    }

    @Test fun `interrupted and cancelled and timeout statuses map through`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        for ((phase, wire) in listOf(LomPhase.Interrupted to "interrupted", LomPhase.Cancelled to "cancelled", LomPhase.Timeout to "timeout")) {
            port.tasks[s]!![0] = LomTaskView(port.tasks[s]!![0].id, "x", 1L, phase, "")
            assertEquals(wire, call("chat.read", "session_id" to s, "offset" to 0)["status"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `list shows only this desktops sessions, newest first, with title pinned running`() {
        val s1 = send("第一个", client = "aaaaaaaa-2222-3333-4444-555555555555")["session_id"]!!.jsonPrimitive.content
        port.tasks[s1]!![0] = LomTaskView(port.tasks[s1]!![0].id, "第一个", 100L, LomPhase.Completed, "ok")
        port.turns[s1] = mutableListOf(LomTurnView("第一个", "ok", LomPhase.Completed))
        port.pinned += s1
        val s2 = send("第二个", client = "bbbbbbbb-2222-3333-4444-555555555555")["session_id"]!!.jsonPrimitive.content
        send("别人的", client = "cccccccc-2222-3333-4444-555555555555", sender = b)
        val r = call("chat.list", "offset" to 0)
        val items = r["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, r["total"]!!.jsonPrimitive.int)
        assertEquals(listOf(s1, s2), items.map { it["id"]!!.jsonPrimitive.content }) // s1 更新时间 5000 > s2 的 1000
        assertTrue(items[0]["pinned"]!!.jsonPrimitive.boolean); assertFalse(items[0]["running"]!!.jsonPrimitive.boolean)
        assertTrue(items[1]["running"]!!.jsonPrimitive.boolean); assertEquals("第二个", items[1]["title"]!!.jsonPrimitive.content)
    }

    @Test fun `sessions deleted on the phone drop out of the list`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        port.tasks.remove(s) // 手机上删了:没有任务也没有对话
        now += 120_000
        assertEquals(0, call("chat.list", "offset" to 0)["total"]!!.jsonPrimitive.int)
        assertNull(kv.get("chat.s.${LomSender.key(a)}.$s"))
    }

    @Test fun `cancel waits for the task to really stop`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        val r = call("chat.cancel", "session_id" to s)
        assertEquals("cancelled", r["status"]!!.jsonPrimitive.content)
        assertEquals(1, port.cancelled.size)
        // 本来没在运行也返回成功
        assertEquals("cancelled", call("chat.cancel", "session_id" to s)["status"]!!.jsonPrimitive.content)
    }

    @Test fun `cancel that cannot be confirmed is an error, not a fake stop`() {
        port.cancelFinishes = false
        val s = send()["session_id"]!!.jsonPrimitive.content
        assertEquals("chat.cancel_unconfirmed", code("chat.cancel", "session_id" to s))
        assertTrue(sleeps.sum() >= LomChatMethods.CANCEL_WAIT_MS)
    }

    @Test fun `rename pin delete`() {
        val s = send()["session_id"]!!.jsonPrimitive.content
        assertEquals("chat.invalid_title", code("chat.rename", "session_id" to s, "title" to "  "))
        assertEquals("chat.invalid_title", code("chat.rename", "session_id" to s, "title" to "x".repeat(201)))
        assertEquals("saved", call("chat.rename", "session_id" to s, "title" to "  新名字 ")["status"]!!.jsonPrimitive.content)
        assertEquals("新名字", port.titles[s])
        assertEquals("saved", call("chat.pin", "session_id" to s, "pinned" to true)["status"]!!.jsonPrimitive.content)
        assertTrue(s in port.pinned)
        assertEquals("remote.operation_failed", code("chat.pin", "session_id" to s, "pinned" to "true")) // 必须是 JSON 布尔
        assertEquals("chat.busy", code("chat.delete", "session_id" to s)) // 运行中拒绝删除
        port.tasks[s]!![0] = LomTaskView(port.tasks[s]!![0].id, "x", 1L, LomPhase.Completed, "")
        assertEquals("deleted", call("chat.delete", "session_id" to s)["status"]!!.jsonPrimitive.content)
        assertEquals(listOf(s), port.deleted)
        assertEquals("deleted", call("chat.delete", "session_id" to s)["status"]!!.jsonPrimitive.content) // 已不存在也是 deleted
        assertEquals(1, port.deleted.size)
    }

    @Test fun `delete never touches a session the desktop does not own`() {
        port.turns["mcp-other"] = mutableListOf(LomTurnView("q", "a", LomPhase.Completed))
        assertEquals("deleted", call("chat.delete", "session_id" to "mcp-other")["status"]!!.jsonPrimitive.content)
        assertTrue(port.deleted.isEmpty())
    }

    @Test fun `chat_file and chat_image are not claimed`() {
        assertNull(chat.call("chat.file", "{}", a))
        assertNull(chat.call("chat.image", "{}", a))
    }

    @Test fun `receipts are bounded`() {
        for (i in 0 until 520) send("m$i", client = "%08d-2222-3333-4444-555555555555".format(i))
        assertTrue(kv.keys(LomChatMethods.RECEIPT).size <= LomChatMethods.MAX_RECEIPTS)
    }
}
