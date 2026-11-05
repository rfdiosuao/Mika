package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LomDeviceMethodsTest {
    private val kv = MemoryLomKv()
    private var access = true
    private var apps = listOf("com.b" to "Banana", "com.a" to "apple", "com.b" to "dup")
    private val methods = LomDeviceMethods(kv, object : LomAppsPort {
        override fun launchable() = apps
        override fun accessibilityEnabled() = access
    }) { 1767222000000L }
    private val sender = "S".repeat(86)

    private fun code(block: () -> Any?): String = try { block(); "" } catch (e: LomException) { e.code }

    @Test fun `apps_list has the documented shape`() {
        val o = Json.parseToJsonElement(methods.call("apps.list", "{}", sender)!!).jsonObject
        val items = o["items"]!!.jsonArray
        assertEquals(listOf("com.a", "com.b"), items.map { it.jsonObject["package"]!!.jsonPrimitive.content })
        assertEquals("apple", items[0].jsonObject["label"]!!.jsonPrimitive.content)
        assertEquals("true", o["accessibility"]!!.jsonPrimitive.content)
        assertEquals("1767222000000", o["sampled_at"]!!.jsonPrimitive.content)
        access = false
        assertEquals("false", Json.parseToJsonElement(methods.call("apps.list", "{}", sender)!!).jsonObject["accessibility"]!!.jsonPrimitive.content)
    }

    @Test fun `apps_list is capped`() {
        apps = (1..1500).map { "com.app$it" to "App $it" }
        assertEquals(LomDeviceMethods.MAX_APPS, Json.parseToJsonElement(methods.call("apps.list", "{}", sender)!!).jsonObject["items"]!!.jsonArray.size)
    }

    @Test fun `rename echoes the exact name and persists it`() {
        val r = Json.parseToJsonElement(methods.call("device.rename", """{"name":"书房的手机"}""", sender)!!).jsonObject
        assertEquals("书房的手机", r["name"]!!.jsonPrimitive.content)
        assertEquals("书房的手机", LomDeviceMethods.savedName(kv, sender))
        assertNull(LomDeviceMethods.savedName(kv, "T".repeat(86))) // 按桌面隔离
    }

    @Test fun `rename rejects instead of rewriting`() {
        for (n in listOf("", "  ", " pad ", "a\nb", "x".repeat(101)))
            assertEquals("name=$n", "device.invalid_name", code { methods.call("device.rename", """{"name":${Json.encodeToString(kotlinx.serialization.serializer<String>(), n)}}""", sender) })
        assertEquals("device.invalid_name", code { methods.call("device.rename", """{"name":5}""", sender) })
        assertNull(LomDeviceMethods.savedName(kv, sender))
    }

    @Test fun `unknown methods fall through`() {
        assertNull(methods.call("screen.capture", "{}", sender))
        assertNull(LomMethodSet(methods).call("skills.list", "{}", sender))
    }
}
