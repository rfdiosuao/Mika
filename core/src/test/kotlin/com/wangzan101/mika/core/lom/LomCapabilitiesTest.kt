package com.wangzan101.mika.core.lom

import com.wangzan101.mika.core.remote.RemoteTaskService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** `device.capabilities`(公开文档 07 §6):清单由实际认领的方法自动生成,不手写。 */
class LomCapabilitiesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val sender = "S".repeat(86)
    private val limits = mapOf("chat.send.max_text_chars" to RemoteTaskService.MAX_GOAL.toLong(), "file.max_bytes" to LomFileMethods.MAX_FILE)

    private fun answer(m: LomMethods): JsonObject =
        Json.parseToJsonElement(requireNotNull(m.call("device.capabilities", "{}", sender))).jsonObject

    private fun names(o: JsonObject): List<String> = o.getValue("methods").jsonArray.map { it.jsonPrimitive.content }

    @Test fun `answers with version 1, the claimed methods and numeric limits`() {
        val o = answer(lomTestMethodSet(tmp.root, limits))
        assertEquals("1", (o.getValue("version") as JsonPrimitive).content)
        assertFalse((o.getValue("version") as JsonPrimitive).isString) // 真实的 JSON 数字
        val methods = names(o)
        for (m in listOf("device.capabilities", "model.sync", "chat.send", "chat.read", "chat.list", "chat.cancel", "document.list", "document.read", "schedule.list", "device.status", "screen.capture", "file.begin")) {
            assertTrue(m, m in methods)
        }
        assertEquals(methods.sorted(), methods)
        assertEquals(methods.distinct(), methods)
        val lim = o.getValue("limits").jsonObject
        assertEquals("4000", lim.getValue("chat.send.max_text_chars").jsonPrimitive.content)
        assertFalse(lim.getValue("file.max_bytes").jsonPrimitive.isString)
        assertEquals("104857600", lim.getValue("file.max_bytes").jsonPrimitive.content)
    }

    @Test fun `unsupported features are absent from the list`() {
        val methods = names(answer(lomTestMethodSet(tmp.root, limits)))
        for (gone in listOf("chat.file", "chat.image", "document.write", "skills.list", "skills.install", "schedule.save", "schedule.toggle", "schedule.delete", "schedule.run", "subagent.list", "subagent.save")) {
            assertFalse(gone, gone in methods)
        }
    }

    @Test fun `limits are omitted when there are none`() {
        val o = answer(lomTestMethodSet(tmp.root))
        assertFalse("limits" in o)
        assertTrue(o.getValue("methods") is JsonArray)
    }

    @Test fun `the advertised limit is the one chat send actually enforces`() {
        assertEquals(RemoteTaskService.MAX_GOAL, LomChatMethods.MAX_GOAL)
    }

    /** 清单与分派表不能漂移:协议里列出的每个方法,「认领」与「在清单里」必须一致。 */
    @Test fun `every protocol method is claimed exactly when it is listed`() {
        val m = lomTestMethodSet(tmp.root, limits)
        val listed = names(answer(m)).toSet()
        for (method in PROTOCOL_METHODS) {
            val claimed = try { m.call(method, "{}", sender) != null } catch (_: LomException) { true }
            assertEquals(method, claimed, method in listed)
        }
        assertTrue("清单里的方法都应在协议方法表里", listed.all { it in PROTOCOL_METHODS })
    }

    @Test fun `a set of groups is the union of its parts plus the capabilities method`() {
        val a = LomMethods.NONE
        assertEquals(setOf("device.capabilities"), LomMethodSet(a).methods)
    }

    private companion object {
        val PROTOCOL_METHODS = listOf(
            "device.capabilities", "device.status", "device.rename", "apps.list", "model.sync",
            "chat.send", "chat.read", "chat.list", "chat.cancel", "chat.rename", "chat.pin", "chat.delete", "chat.file", "chat.image",
            "document.list", "document.read", "document.write",
            "file.begin", "file.chunk", "file.finish", "file.status", "file.read", "file.list", "file.delete", "file.save_gallery",
            "screen.capture", "screen.tree", "screen.control",
            "skills.list", "skills.read", "skills.install", "skills.toggle", "skills.delete",
            "schedule.list", "schedule.save", "schedule.toggle", "schedule.delete", "schedule.run",
            "subagent.list", "subagent.save", "subagent.toggle", "subagent.delete",
        )
    }
}
