package com.wangzan101.mika.lom

import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.FailKind
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.core.lom.LomChatPort
import com.wangzan101.mika.core.lom.LomException
import com.wangzan101.mika.core.lom.LomPhase
import com.wangzan101.mika.core.lom.LomSummary
import com.wangzan101.mika.core.lom.LomTaskView
import com.wangzan101.mika.core.lom.LomTurnView
import com.wangzan101.mika.core.remote.RemoteStatus
import com.wangzan101.mika.core.remote.RemoteTask
import com.wangzan101.mika.core.remote.RemoteTaskError
import com.wangzan101.mika.core.remote.RemoteTaskService
import kotlinx.coroutines.runBlocking

/**
 * `chat.*` 接到 Mika 内部:任务经 [RemoteTaskService] → 任务服务(和对话界面同一条路,不经 MCP 自己调自己),
 * 对话记录读 [ConversationStore]。麓鸣的会话 ID 就是 Mika 的对话 ID。
 */
class AndroidChat(private val app: MikaApp) : LomChatPort {
    private val service by lazy { RemoteTaskService(app.remoteTasks) }
    private val store: ConversationStore get() = app.conversations

    override fun start(requestId: String, goal: String, conversation: String) {
        try {
            service.start(goal, conversation, requestId)
        } catch (e: RemoteTaskError) {
            throw LomException(when {
                e is RemoteTaskError.General && e.code == RemoteTaskError.Code.CONFLICT -> "chat.id_reused"
                e.code == RemoteTaskError.Code.INVALID_ARGUMENT -> "chat.invalid_text"
                e.code == RemoteTaskError.Code.GONE -> "chat.session_missing"
                else -> "remote.not_ready" // 任务服务现在接不了(手机在忙、存储满…),没有启动
            })
        }
    }

    override fun answer(taskId: String, text: String): Boolean = try {
        service.answer(taskId, text); true
    } catch (_: RemoteTaskError) { false }

    override fun tasks(conversation: String): List<LomTaskView> =
        runCatching { app.remoteTasks.list(RemoteTaskService.MAX_LIST) }.getOrDefault(emptyList())
            .filter { it.conversation == conversation }.reversed().map(::view)

    override fun cancel(taskId: String) { runCatching { service.cancel(taskId) } }

    override fun turns(conversation: String): List<LomTurnView>? {
        if (store.list().none { it.id == conversation }) return null
        return store.turns(conversation).map { LomTurnView(it.userText, it.outcome, phaseOf(it.status)) }
    }

    override fun summary(conversation: String): LomSummary? =
        store.list().firstOrNull { it.id == conversation }?.let { LomSummary(it.title, it.updatedAt, it.pinnedAt != null) }

    override fun rename(conversation: String, title: String) = store.rename(conversation, title)
    override fun pin(conversation: String, pinned: Boolean) = store.setPinned(conversation, pinned) || store.pinnedAt(conversation) != null == pinned
    override fun delete(conversation: String) = runBlocking { app.conversationDeleter.delete(conversation) }

    private fun view(t: RemoteTask): LomTaskView {
        val s = t.state
        val phase = when (RemoteStatus.of(s)) {
            RemoteStatus.DONE -> LomPhase.Completed
            RemoteStatus.FAILED -> when ((s as? TaskState.Failed)?.kind) {
                FailKind.Interrupted -> LomPhase.Interrupted
                FailKind.StepLimit -> LomPhase.Timeout
                else -> LomPhase.Failed
            }
            RemoteStatus.CANCELLED -> LomPhase.Cancelled
            RemoteStatus.WAITING_FOR_ANSWER -> LomPhase.WaitingAnswer
            else -> LomPhase.Running
        }
        val text = RemoteStatus.describe(t).let {
            fun f(k: String) = (it[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            when (phase) {
                LomPhase.Completed -> f("answer"); LomPhase.WaitingAnswer -> f("question")
                LomPhase.Failed, LomPhase.Timeout, LomPhase.Interrupted -> f("reason"); else -> f("progress")
            }
        }
        return LomTaskView(t.id, t.goal, t.createdAtMs, phase, text)
    }

    private fun phaseOf(status: String) = when (status) {
        "succeeded", "unverified" -> LomPhase.Completed
        "cancelled" -> LomPhase.Cancelled
        else -> LomPhase.Failed
    }
}
