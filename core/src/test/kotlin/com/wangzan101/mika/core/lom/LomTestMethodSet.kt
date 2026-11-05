package com.wangzan101.mika.core.lom

import java.io.File

/** 整套方法集合(各组都接上空实现),给方法认领与 `device.capabilities` 的测试共用。 */
fun lomTestMethodSet(root: File, limits: Map<String, Long> = emptyMap()): LomMethods {
    val kv = MemoryLomKv()
    val none = object : LomChatPort {
        override fun start(requestId: String, goal: String, conversation: String) = Unit
        override fun answer(taskId: String, text: String) = false
        override fun tasks(conversation: String) = emptyList<LomTaskView>()
        override fun cancel(taskId: String) = Unit
        override fun turns(conversation: String): List<LomTurnView>? = null
        override fun summary(conversation: String): LomSummary? = null
        override fun rename(conversation: String, title: String) = false
        override fun pin(conversation: String, pinned: Boolean) = false
        override fun delete(conversation: String) = Unit
    }
    val screen = object : LomScreenPort {
        override fun allowed(access: LomScreenAccess) = false
        override fun size(): Pair<Int, Int>? = null
        override fun capture(maxEdge: Int, maxBytes: Int) = throw LomException("screen.capture_failed")
        override fun tree() = throw LomException("screen.no_window")
        override fun appExists(packageName: String) = false
        override fun agentRunning() = false
        override fun metered() = false
        override fun perform(action: LomAction) = throw LomException("screen.action_unconfirmed")
        override fun touched(access: LomScreenAccess, at: Long) = Unit
    }
    val model = object : LomModelPort { override fun install(cfg: LomModelConfig) = Unit; override fun remove() = Unit; override fun installed() = false }
    return LomMethodSet(
        limits, LomModelMethods(model, kv),
        LomDeviceMethods(kv, object : LomAppsPort { override fun launchable() = emptyList<Pair<String, String>>(); override fun accessibilityEnabled() = false }),
        LomChatMethods(none, kv, { false }), LomScreenMethods(screen, kv), LomFileMethods(root), LomBasicMethods(),
    )
}
