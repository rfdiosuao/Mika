package com.wangzan101.mika.lom

import com.wangzan101.mika.core.lom.LomKv
import com.wangzan101.mika.core.lom.LomModelConfig
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.settings.AesGcmKeyCipher
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class LomProviderBridgeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val kv = object : LomKv {
        val m = LinkedHashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
        override fun keys(prefix: String) = m.keys.filter { it.startsWith(prefix) }
    }
    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key })
    private val caps = Capabilities(toolCalls = true, vision = true, contextTokens = 200_000, reasoning = true)

    private fun cfg(platform: String = "openai", url: String = "https://api.lom.test/v1", model: String = "lom-model", rev: Long = 1, secret: String = "tok-SECRET") =
        LomModelConfig(url, secret, model, platform, rev)

    /** 用户自己配好的主模型,用来验证恢复。 */
    private fun userMain(s: ProviderStore): String {
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "user-key", emptyList())
        assertTrue(s.use(ModelRole.Main, p.id, "deepseek-chat", caps))
        return p.id
    }

    @Test fun `install adds a provider named Lom and selects it as main`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg())
        val p = s.providers.value.single { it.name == "麓鸣" }
        assertEquals(ProviderConfig.Kind.OpenAiCompat, p.kind)
        assertEquals("https://api.lom.test/v1", p.baseUrl)
        assertEquals("tok-SECRET", s.providerKey(p.id))
        assertEquals(p.id, s.configFor(ModelRole.Main)!!.providerId)
        assertEquals("lom-model", s.configFor(ModelRole.Main)!!.model)
        assertTrue(bridge.installed())
    }

    @Test fun `platforms map to provider kinds`() {
        assertEquals(ProviderConfig.Kind.OpenAiResponses, LomProviderBridge.kindOf("openai-responses"))
        assertEquals(ProviderConfig.Kind.Anthropic, LomProviderBridge.kindOf("anthropic"))
        assertEquals(ProviderConfig.Kind.Gemini, LomProviderBridge.kindOf("gemini"))
    }

    @Test fun `token is only in the keystore-encrypted key file, never plain on disk`() {
        val s = store(); LomProviderBridge(s, kv).install(cfg())
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("${f.name} leaks the token", String(f.readBytes(), Charsets.ISO_8859_1).contains("tok-SECRET"))
        }
        assertFalse(kv.m.values.any { it.contains("tok-SECRET") })
    }

    @Test fun `update changes model in place and keeps one provider`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg(rev = 1)); bridge.install(cfg(model = "lom-2", rev = 2, secret = "tok-NEW"))
        val p = s.providers.value.single { it.name == "麓鸣" }
        assertEquals("tok-NEW", s.providerKey(p.id))
        assertEquals("lom-2", s.configFor(ModelRole.Main)!!.model)
    }

    @Test fun `protocol change replaces the provider instead of keeping two`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg()); bridge.install(cfg(platform = "anthropic", url = "https://api.lom.test"))
        val p = s.providers.value.single { it.name == "麓鸣" }
        assertEquals(ProviderConfig.Kind.Anthropic, p.kind)
        assertEquals(p.id, s.configFor(ModelRole.Main)!!.providerId)
    }

    @Test fun `remove restores the users previous main model`() {
        val s = store(); val before = userMain(s); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg())
        assertEquals("lom-model", s.configFor(ModelRole.Main)!!.model)
        bridge.remove()
        assertTrue(s.providers.value.none { it.name == "麓鸣" })
        val main = s.configFor(ModelRole.Main)!!
        assertEquals(before, main.providerId); assertEquals("deepseek-chat", main.model)
        assertTrue(main.capabilities.vision) // 能力也原样恢复
        assertEquals("user-key", s.providerKey(before))
        assertFalse(bridge.installed())
    }

    @Test fun `remove does not override a main model the user picked meanwhile`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg())
        val other = userMain(s) // 用户之后自己选了别的
        bridge.remove()
        assertEquals(other, s.configFor(ModelRole.Main)!!.providerId)
    }

    @Test fun `later pushes do not steal the main model back from the user`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg(rev = 1))
        val other = userMain(s)
        bridge.install(cfg(rev = 2, model = "lom-2"))
        assertEquals(other, s.configFor(ModelRole.Main)!!.providerId)
        assertEquals("tok-SECRET", s.providerKey(s.providers.value.single { it.name == "麓鸣" }.id)) // 但服务商已更新
    }

    @Test fun `remove with nothing installed is harmless and idempotent`() {
        val s = store(); val before = userMain(s)
        LomProviderBridge(s, kv).apply { remove(); remove() }
        assertEquals(before, s.configFor(ModelRole.Main)!!.providerId)
    }

    @Test fun `user deleting the provider by hand is noticed`() {
        val s = store(); val bridge = LomProviderBridge(s, kv)
        bridge.install(cfg())
        s.removeProvider(s.providers.value.single { it.name == "麓鸣" }.id)
        assertFalse(bridge.installed())
        bridge.install(cfg(rev = 2)) // 下次推送会重新装上
        assertNotNull(s.providers.value.singleOrNull { it.name == "麓鸣" })
    }
}
