package com.wangzan101.mika.core.lom

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整套方法集合:没有对应能力的方法一律不认领(处理器据此回 `remote.method_unknown`),同步必调的三个只读方法不能掉。 */
class LomUnsupportedTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun all(): LomMethods {
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
            LomModelMethods(model, kv),
            LomDeviceMethods(kv, object : LomAppsPort { override fun launchable() = emptyList<Pair<String, String>>(); override fun accessibilityEnabled() = false }),
            LomChatMethods(none, kv, { false }), LomScreenMethods(screen, kv), LomFileMethods(tmp.root), LomBasicMethods(),
        )
    }

    @Test fun `methods without a matching Mika capability are not claimed`() {
        val m = all()
        for (method in listOf(
            "chat.file", "chat.image", "document.write",
            "skills.list", "skills.read", "skills.install", "skills.toggle", "skills.delete",
            "schedule.save", "schedule.toggle", "schedule.delete", "schedule.run",
            "subagent.list", "subagent.save", "subagent.toggle", "subagent.delete",
            "no.such_method",
        )) assertNull(method, m.call(method, "{}", "S".repeat(86)))
    }

    @Test fun `the three read-only sync methods and implemented methods are claimed`() {
        val m = all()
        for (method in listOf("document.list", "schedule.list", "device.status", "apps.list")) assertNotNull(method, m.call(method, "{}", "S".repeat(86)))
        assertNotNull(m.call("document.read", """{"name":"SOUL.md"}""", "S".repeat(86)))
    }
}
