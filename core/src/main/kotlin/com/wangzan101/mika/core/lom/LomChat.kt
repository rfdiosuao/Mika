package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/** Mika 任务此刻的样子(对麓鸣的说法)。等待回答也算「在运行」,等手机上操作的/排队的/暂停的同样。 */
enum class LomPhase(val wire: String) {
    Running("running"), WaitingAnswer("running"), Completed("completed"), Failed("failed"),
    Timeout("timeout"), Cancelled("cancelled"), Interrupted("interrupted");
    val active get() = this == Running || this == WaitingAnswer
}

/** 一次麓鸣发来的任务(Mika 的远程任务回执)。[text]:运行中是进度,等回答时是问题,完成是答案,失败是原因。 */
class LomTaskView(val id: String, val goal: String, val startedAt: Long, val phase: LomPhase, val text: String)

/** 对话里已存下的一轮。 */
class LomTurnView(val userText: String, val outcome: String, val phase: LomPhase)

class LomSummary(val title: String?, val updated: Long, val pinned: Boolean)

/** `chat.*` 要用到的 Mika 内部能力。任务经 Mika 自己的任务服务(不经 MCP),对话记录读 Mika 的对话存储。 */
interface LomChatPort {
    /** 按 [requestId] 幂等地开始一个任务:同编号同内容再调用不会重复执行。失败抛带 `chat.*`/`remote.*` 码的 [LomException]。 */
    fun start(requestId: String, goal: String, conversation: String)
    /** 回答任务的追问;追问已经没了返回 false。 */
    fun answer(taskId: String, text: String): Boolean
    /** 这个对话里麓鸣发来的任务,旧的在前。 */
    fun tasks(conversation: String): List<LomTaskView>
    fun cancel(taskId: String)
    /** 已存下的轮次;对话不存在为 null。 */
    fun turns(conversation: String): List<LomTurnView>?
    fun summary(conversation: String): LomSummary?
    fun rename(conversation: String, title: String): Boolean
    fun pin(conversation: String, pinned: Boolean): Boolean
    /** 停掉其中的任务、删工作区和记录(与手机上删除对话同一条路)。 */
    fun delete(conversation: String)
}

/**
 * `chat.send` / `chat.read` / `chat.list` / `chat.cancel` / `chat.rename` / `chat.pin` / `chat.delete`(公开文档 07 §4)。
 *
 * 映射:麓鸣的会话 ID 就是 Mika 的对话 ID(`mcp-<uuid>`);一次 `chat.send` 是该对话里的一个 Mika 任务,
 * 任务编号由「桌面公钥 + client_id」确定性地推出,所以桌面超时重发同一个 `client_id` 不会重复执行。
 * 哪些会话属于哪台桌面、哪个 client_id 对应什么内容,都先落盘([LomKv])再启动任务。
 * 桌面发来的文字只当用户消息交给任务,这里不解释其中的任何指令。
 */
class LomChatMethods(
    private val port: LomChatPort,
    private val kv: LomKv,
    /** 这台桌面是否已经 `model.sync` 过。 */
    private val modelReady: (sender: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : LomMethods {
    override fun call(method: String, payload: String, sender: String): String? = when (method) {
        "chat.send" -> send(LomPayload.parse(payload, "chat.invalid_id"), sender)
        "chat.read" -> read(LomPayload.parse(payload, "chat.session_missing"), sender)
        "chat.list" -> list(LomPayload.parse(payload, "remote.operation_failed"), sender)
        "chat.cancel" -> cancel(LomPayload.parse(payload, "chat.session_missing"), sender)
        "chat.rename" -> rename(LomPayload.parse(payload, "chat.session_missing"), sender)
        "chat.pin" -> pin(LomPayload.parse(payload, "chat.session_missing"), sender)
        "chat.delete" -> delete(LomPayload.parse(payload, "chat.session_missing"), sender)
        else -> null
    }

    // ---- chat.send ----

    private fun send(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val sk = LomSender.key(sender)
        val clientId = LomPayload.str(o, "client_id").orEmpty()
        if (!CLIENT_ID.matches(clientId)) throw LomException("chat.invalid_id")
        val given = LomPayload.str(o, "session_id").orEmpty()
        val text = LomPayload.str(o, "text").orEmpty().trim()
        val files = attachments(LomPayload.str(o, "files"))
        if (files.isNotEmpty()) throw LomException("chat.attachments_unsupported") // 附件还没接到 Mika 的对话附件上,明确拒绝
        if (text.isEmpty() || text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES || text.length > MAX_GOAL) throw LomException("chat.invalid_text")
        if (given.isNotEmpty() && !owns(sk, given)) throw LomException("chat.session_missing")

        val digest = hex(LomCrypto.sha256("$given\u0000$text".toByteArray(Charsets.UTF_8)))
        val receiptKey = RECEIPT + sk + "." + clientId
        kv.get(receiptKey)?.let { return replay(it, digest, clientId, text) }

        if (!modelReady(sender)) throw LomException("chat.model_missing")
        val session = given.ifEmpty { "mcp-" + uuid("lom-session:$sk:$clientId") }
        val running = if (given.isEmpty()) emptyList() else port.tasks(session).filter { it.phase.active }
        if (running.any { it.phase == LomPhase.Running }) throw LomException("chat.busy")
        val asking = running.firstOrNull { it.phase == LomPhase.WaitingAnswer }
        // 先把收据和会话归属落盘,再启动;落盘失败就不启动
        remember(receiptKey, digest, session, if (asking != null) "a" else "t")
        if (given.isEmpty()) markOwned(sk, session)
        try {
            if (asking != null) {
                // 任务在等回答:这条消息就是回答,不另开任务
                if (!port.answer(asking.id, text)) throw LomException("chat.busy")
            } else {
                port.start(uuid("lom-req:$sk:$clientId"), text, session)
            }
        } catch (e: LomException) {
            // 确定没启动:撤销收据,桌面可以用同一个 client_id 重试
            runCatching { kv.remove(receiptKey); if (given.isEmpty()) kv.remove(SESSION + sk + "." + session) }
            throw e
        }
        return accepted(session, clientId)
    }

    private fun replay(saved: String, digest: String, clientId: String, text: String): String {
        val parts = saved.split('|')
        if (parts.size < 4 || parts[1] != digest) throw LomException("chat.id_reused")
        val session = parts[2]
        // 任务收据由 Mika 的任务服务按同一编号去重,重放不会再跑一遍;上次崩在中途的话这里会补上启动
        if (parts[3] == "t") {
            val sk = parts.getOrNull(4)
            if (sk != null) port.start(uuid("lom-req:$sk:$clientId"), text, session)
        }
        return accepted(session, clientId)
    }

    private fun accepted(session: String, clientId: String) = LomPayload.obj("session_id" to session, "client_id" to clientId, "status" to "accepted")

    private fun attachments(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = try { Json.parseToJsonElement(raw) as? JsonArray } catch (_: Exception) { null } ?: throw LomException("file.invalid_id")
        if (arr.size > MAX_FILES) throw LomException("file.too_many")
        return arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw LomException("file.invalid_id") }
    }

    // ---- chat.read ----

    private fun read(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val session = ownedSession(o, sender)
        val offset = (LomPayload.long(o, "offset") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val tasks = port.tasks(session)
        val turns = port.turns(session) ?: emptyList()
        if (turns.isEmpty() && tasks.isEmpty() && port.summary(session) == null) throw LomException("chat.session_missing")
        val messages = messages(turns, tasks, port.summary(session)?.updated ?: clock())
        val page = ArrayList<kotlinx.serialization.json.JsonElement>()
        var bytes = 0
        for (m in messages.drop(offset).take(PAGE_READ)) {
            val item = LomPayload.jobj("id" to m.id, "role" to m.role, "text" to m.text, "at" to m.at)
            val size = item.toString().toByteArray(Charsets.UTF_8).size
            if (page.isNotEmpty() && bytes + size > READ_BUDGET) break // 回复明文 ≤ 350000 字节:超了留给下一页
            page += item; bytes += size
        }
        val running = tasks.any { it.phase.active }
        val last = tasks.lastOrNull()?.phase ?: turns.lastOrNull()?.phase
        val status = when { running -> "running"; last == null -> "idle"; else -> last.wire }
        return LomPayload.obj(
            "items" to JsonArray(page), "total" to messages.size, "next" to offset + page.size,
            "running" to running, "status" to status, "error" to if (!running && (last == LomPhase.Failed || last == LomPhase.Timeout)) ERROR_TEXT else "",
        )
    }

    class Msg(val id: String, val role: String, val text: String, val at: Long)

    /** 已存下的轮次在前;还没存下的任务(运行中或刚收尾)接在后面。消息 ID 按位置,任务收尾存成一轮后 ID 不变。 */
    internal fun messages(turns: List<LomTurnView>, tasks: List<LomTaskView>, fallbackAt: Long): List<Msg> {
        val out = ArrayList<Msg>()
        turns.forEachIndexed { i, t ->
            val at = tasks.getOrNull(i - (turns.size - tasks.size).coerceAtLeast(0))?.startedAt ?: fallbackAt
            out += Msg("m${2 * i}", "user", clip(t.userText), at)
            out += Msg("m${2 * i + 1}", "assistant", clip(t.outcome), at)
        }
        if (tasks.size > turns.size) tasks.drop(turns.size).forEachIndexed { j, t ->
            val i = turns.size + j
            out += Msg("m${2 * i}", "user", clip(t.goal), t.startedAt)
            val reply = t.text.ifBlank { if (t.phase.active) "正在处理…" else if (t.phase == LomPhase.Cancelled) "已取消" else "" }
            out += Msg("m${2 * i + 1}", "assistant", clip(reply), t.startedAt)
        }
        return out
    }

    // ---- chat.list ----

    private fun list(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val sk = LomSender.key(sender)
        val offset = (LomPayload.long(o, "offset") ?: 0L).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        class Row(val id: String, val title: String, val updated: Long, val pinned: Boolean, val running: Boolean)
        val rows = ArrayList<Row>()
        for (key in kv.keys(SESSION + sk + ".")) {
            val id = key.removePrefix(SESSION + sk + ".")
            val created = kv.get(key)?.toLongOrNull() ?: 0L
            val tasks = port.tasks(id)
            val summary = port.summary(id)
            if (summary == null && tasks.isEmpty()) {
                // 手机上删掉了:从映射里拿掉,桌面下一轮同步会去掉它
                if (clock() - created > GRACE_MS) runCatching { kv.remove(key) }
                continue
            }
            val title = (summary?.title ?: tasks.firstOrNull()?.goal)?.trim()?.takeIf { it.isNotEmpty() }?.take(60) ?: "新的会话"
            rows += Row(id, title, summary?.updated ?: tasks.lastOrNull()?.startedAt ?: created, summary?.pinned == true, tasks.any { it.phase.active })
        }
        rows.sortByDescending { it.updated }
        val page = rows.drop(offset).take(PAGE_LIST).map {
            LomPayload.jobj("id" to it.id, "title" to it.title, "updated" to it.updated, "pinned" to it.pinned, "running" to it.running)
        }
        return LomPayload.obj("items" to JsonArray(page), "total" to rows.size, "next" to offset + page.size)
    }

    // ---- chat.cancel / rename / pin / delete ----

    private fun cancel(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val session = ownedSession(o, sender)
        port.tasks(session).filter { it.phase.active }.forEach { port.cancel(it.id) }
        // 停止要真的完成才回复:等任务收尾;等不到就如实报「未确认」
        var waited = 0L
        while (port.tasks(session).any { it.phase.active }) {
            if (waited >= CANCEL_WAIT_MS) throw LomException("chat.cancel_unconfirmed")
            sleep(CANCEL_STEP_MS); waited += CANCEL_STEP_MS
        }
        return LomPayload.obj("status" to "cancelled", "session_id" to session)
    }

    private fun rename(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val session = ownedSession(o, sender)
        val title = LomPayload.str(o, "title")?.trim().orEmpty()
        if (title.isEmpty() || title.length > MAX_TITLE) throw LomException("chat.invalid_title")
        if (!port.rename(session, title)) throw LomException("remote.operation_failed")
        return LomPayload.obj("status" to "saved", "session_id" to session)
    }

    private fun pin(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val session = ownedSession(o, sender)
        val pinned = LomPayload.bool(o, "pinned") ?: throw LomException("remote.operation_failed")
        if (!port.pin(session, pinned)) throw LomException("remote.operation_failed")
        return LomPayload.obj("status" to "saved", "session_id" to session)
    }

    private fun delete(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val sk = LomSender.key(sender)
        val session = LomPayload.str(o, "session_id").orEmpty()
        // 不是这台桌面的会话、或已经不存在:都按「已删除」回(文档:会话已不存在也返回 deleted),但不会去动别人的会话
        if (owns(sk, session)) {
            if (port.tasks(session).any { it.phase.active }) throw LomException("chat.busy")
            port.delete(session)
            runCatching { kv.remove(SESSION + sk + "." + session) }
        }
        return LomPayload.obj("status" to "deleted")
    }

    // ---- 会话归属 ----

    private fun ownedSession(o: kotlinx.serialization.json.JsonObject, sender: String): String {
        val session = LomPayload.str(o, "session_id").orEmpty()
        if (!owns(LomSender.key(sender), session)) throw LomException("chat.session_missing")
        return session
    }

    private fun owns(sk: String, session: String) = session.isNotEmpty() && kv.get(SESSION + sk + "." + session) != null

    private fun markOwned(sk: String, session: String) = kv.put(SESSION + sk + "." + session, clock().toString())

    private fun remember(key: String, digest: String, session: String, mode: String) {
        try { kv.put(key, "${clock()}|$digest|$session|$mode|${key.removePrefix(RECEIPT).substringBefore('.')}") }
        catch (_: LomException) { throw LomException("chat.receipt_failed") }
        val all = kv.keys(RECEIPT)
        if (all.size > MAX_RECEIPTS) all.sortedBy { kv.get(it)?.substringBefore('|')?.toLongOrNull() ?: 0L }.take(all.size - MAX_RECEIPTS).forEach { runCatching { kv.remove(it) } }
    }

    private fun clip(s: String) = if (s.length <= MAX_TEXT_OUT) s else s.take(MAX_TEXT_OUT) + "…"
    private fun uuid(name: String) = UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object {
        val CLIENT_ID = Regex("^[A-Za-z0-9-]{16,64}$")
        const val SESSION = "chat.s."
        const val RECEIPT = "chat.r."
        const val MAX_TEXT_BYTES = 60_000
        /** Mika 远程任务入口对单条消息的上限(字符);超过的明确拒绝,不截断。 */
        const val MAX_GOAL = 4_000
        const val MAX_FILES = 10
        const val MAX_TITLE = 200
        const val PAGE_READ = 50
        const val PAGE_LIST = 100
        const val READ_BUDGET = 200_000
        const val MAX_TEXT_OUT = 30_000
        const val MAX_RECEIPTS = 500
        const val CANCEL_WAIT_MS = 10_000L
        const val CANCEL_STEP_MS = 200L
        const val GRACE_MS = 60_000L
        const val ERROR_TEXT = "任务没有完成"
    }
}
