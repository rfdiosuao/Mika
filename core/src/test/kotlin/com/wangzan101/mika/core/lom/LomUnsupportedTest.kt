package com.wangzan101.mika.core.lom

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 整套方法集合:没有对应能力的方法一律不认领(处理器据此回 `remote.method_unknown`),同步必调的三个只读方法不能掉。 */
class LomUnsupportedTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun all(): LomMethods = lomTestMethodSet(tmp.root)

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
