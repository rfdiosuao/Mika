package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LomModelSyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakePort : LomModelPort {
        val installs = mutableListOf<LomModelConfig>()
        var removed = 0
        var present = false
        var failInstall = false
        override fun install(cfg: LomModelConfig) { if (failInstall) throw LomException("remote.operation_failed"); installs += cfg; present = true }
        override fun remove() { removed++; present = false }
        override fun installed() = present
    }

    private val port = FakePort()
    private val kv = MemoryLomKv()
    private val changes = mutableListOf<String?>()
    private val methods = LomModelMethods(port, kv) { changes += it }
    private val a = "A".repeat(86)
    private val b = "B".repeat(86)

    private fun sync(rev: String = "100", sender: String = a, url: String = "https://api.example.com/v1/", platform: String = "openai",
                     key: String = "sk-secret-123", model: String = "gpt-x") =
        methods.call("model.sync", """{"base_url":"$url","api_key":"$key","model":"$model","platform":"$platform","revision":"$rev"}""", sender)

    private fun code(block: () -> Any?): String = try { block(); fail("expected LomException"); "" } catch (e: LomException) { e.code }

    @Test fun `model_sync saves and echoes status model revision`() {
        val r = Json.parseToJsonElement(sync()!!).jsonObject
        assertEquals("saved", r["status"]!!.jsonPrimitive.content)
        assertEquals("gpt-x", r["model"]!!.jsonPrimitive.content)
        assertEquals("100", r["revision"]!!.jsonPrimitive.content)
        assertEquals("https://api.example.com/v1", port.installs.single().baseUrl) // 末尾斜杠去掉
        assertEquals(listOf<String?>("gpt-x"), changes)
    }

    @Test fun `revision never goes backwards, equal and newer are accepted`() {
        sync("100"); sync("100"); sync("200")
        assertEquals("model.stale_revision", code { sync("199") })
        assertEquals(3, port.installs.size) // 旧版本没有被安装
        assertEquals(200L, port.installs.last().revision)
    }

    @Test fun `revision is tracked per desktop`() {
        sync("500", sender = a)
        sync("1", sender = b) // 另一台桌面不受 A 的版本影响
        assertEquals("model.stale_revision", code { sync("2", sender = a) })
    }

    @Test fun `failed install does not advance the saved revision`() {
        port.failInstall = true
        code { sync("300") }
        port.failInstall = false
        sync("100") // 300 没保存成功,100 仍然可以接受
        assertEquals(1, port.installs.size)
    }

    @Test fun `url rules from the public doc`() {
        for (u in listOf("http://api.example.com", "https://u:p@api.example.com", "https://api.example.com/v1?x=1", "https://api.example.com/#f",
            "ftp://x", "https://", "", "not a url"))
            assertEquals("url=$u", "model.invalid_url", code { sync(url = u) })
        assertEquals("https://api.example.com", LomModelMethods.cleanUrl("https://api.example.com///"))
    }

    @Test fun `config limits and protocol`() {
        assertEquals("model.unsupported_protocol", code { sync(platform = "cohere") })
        assertEquals("model.invalid_config", code { sync(key = "") })
        assertEquals("model.invalid_config", code { sync(key = "k".repeat(16_385)) })
        assertEquals("model.invalid_config", code { sync(model = "") })
        assertEquals("model.invalid_config", code { sync(model = "m".repeat(257)) })
        assertEquals("model.invalid_config", code { sync(rev = "12a") })
        assertEquals("model.invalid_config", code { sync(rev = "") })
        assertEquals("model.invalid_config", code { methods.call("model.sync", """{"base_url":"https://x.com","api_key":"k","model":"m","platform":"openai","revision":100}""", a) })
        for (p in LomModelMethods.PLATFORMS) sync(platform = p)
    }

    @Test fun `other methods are not claimed`() {
        assertNull(methods.call("chat.send", "{}", a))
    }

    @Test fun `hasModel needs a sync from that desktop and a provider still present`() {
        assertFalse(methods.hasModel(a))
        sync()
        assertTrue(methods.hasModel(a))
        assertFalse(methods.hasModel(b))
        port.present = false // 用户在服务商页手动删掉
        assertFalse(methods.hasModel(a))
    }

    @Test fun `clear removes the provider and every version record`() {
        sync(sender = a); sync(sender = b)
        methods.clear()
        assertEquals(1, port.removed)
        assertTrue(kv.keys(LomModelMethods.REV).isEmpty())
        assertEquals(null, changes.last())
        sync("1") // 重新配对后版本从头来
    }

    @Test fun `secrets stay out of errors and toString`() {
        val e = try { sync(url = "https://u:sk-secret-123@x.com"); null } catch (e: LomException) { e }
        assertFalse(e!!.message!!.contains("sk-secret"))
        sync()
        assertFalse(port.installs.single().toString().contains("sk-secret"))
        assertFalse(kv.map.values.any { it.contains("sk-secret") }) // 核心不保存密钥
    }

    @Test fun `kv on disk is ciphertext and survives reopen`() {
        val f = tmp.newFile("kv.bin")
        val box = TestSecretBox()
        EncryptedFileLomKv(f, box).apply { put("model.rev.x", "42"); put("device.name.x", "我的手机") }
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("42"))
        val again = EncryptedFileLomKv(f, box)
        assertEquals("42", again.get("model.rev.x"))
        assertEquals(listOf("device.name.x"), again.keys("device."))
        again.remove("model.rev.x")
        assertNull(EncryptedFileLomKv(f, box).get("model.rev.x"))
    }
}
