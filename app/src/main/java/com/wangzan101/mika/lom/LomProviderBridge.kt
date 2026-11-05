package com.wangzan101.mika.lom

import com.wangzan101.mika.core.lom.LomKv
import com.wangzan101.mika.core.lom.LomModelConfig
import com.wangzan101.mika.core.lom.LomModelPort
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelRole
import com.wangzan101.mika.core.model.PromptCaching
import com.wangzan101.mika.settings.ProviderConfig
import com.wangzan101.mika.settings.ProviderStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * 「麓鸣」服务商:`model.sync` 推来的模型作为 Mika 的一个普通服务商(按协议选 OpenAI 兼容/Responses/Anthropic/Gemini),
 * 密钥交给 [ProviderStore](Keystore 加密的凭据库),这里不存、不记日志。
 * 首次安装时选为主模型,并记下用户之前选的主模型;解除配对时移除并在主模型还指着它时恢复之前的选择。
 */
class LomProviderBridge(private val providers: ProviderStore, private val kv: LomKv) : LomModelPort {
    @Synchronized override fun install(cfg: LomModelConfig) {
        val kind = kindOf(cfg.platform)
        val first = kv.get(PREV) == null
        if (first) kv.put(PREV, snapshot())
        val old = kv.get(PROVIDER)?.let(providers::provider)
        // 用户没把主模型改走(或还没选过)才选「麓鸣」;用户另选了别的就不抢
        var select = first || providers.configFor(ModelRole.Main) == null || (old != null && usesLom(old.id))
        if (old != null && (old.kind != kind || old.baseUrl != cfg.baseUrl)) {
            // 协议或地址变了:旧服务商整个换掉(密钥随服务商一起删)
            providers.removeProvider(old.id)
        }
        val p = providers.connect(NAME, kind, cfg.baseUrl, cfg.apiKey, listOf(ListedModel(cfg.model)))
        kv.put(PROVIDER, p.id)
        if (select) providers.use(ModelRole.Main, p.id, cfg.model, CAPS)
    }

    @Synchronized override fun remove() {
        val id = kv.get(PROVIDER)
        val prev = kv.get(PREV)
        if (id != null && providers.provider(id) != null) {
            val wasMain = usesLom(id)
            providers.removeProvider(id)
            if (wasMain) restore(prev)
        }
        kv.remove(PROVIDER)
        kv.remove(PREV)
    }

    override fun installed(): Boolean = kv.get(PROVIDER)?.let { providers.provider(it) } != null

    private fun usesLom(providerId: String) = providers.configFor(ModelRole.Main)?.providerId == providerId

    private fun snapshot(): String {
        val c = providers.configFor(ModelRole.Main) ?: return "none"
        // 之前的主模型属于麓鸣自己(上次异常退出留下)时不当作「用户的选择」
        if (c.providerId == kv.get(PROVIDER)) return "none"
        val caps = c.capabilities
        return buildJsonObject {
            put("provider", c.providerId); put("model", c.model)
            put("tool", caps.toolCalls); put("vision", caps.vision); put("context", caps.contextTokens)
            put("reasoning", caps.reasoning); put("caching", caps.promptCaching.wire)
        }.toString()
    }

    private fun restore(prev: String?) {
        val o = prev?.takeIf { it != "none" }?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() } ?: return
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val provider = s("provider") ?: return
        val model = s("model") ?: return
        if (providers.provider(provider) == null) return
        val caps = Capabilities(
            toolCalls = (o["tool"] as? JsonPrimitive)?.booleanOrNull ?: true, vision = (o["vision"] as? JsonPrimitive)?.booleanOrNull ?: false,
            contextTokens = (o["context"] as? JsonPrimitive)?.intOrNull ?: 64_000, reasoning = (o["reasoning"] as? JsonPrimitive)?.booleanOrNull ?: false,
            promptCaching = PromptCaching.of(s("caching")),
        )
        providers.use(ModelRole.Main, provider, model, caps)
    }

    companion object {
        const val NAME = "麓鸣"
        const val PROVIDER = "model.provider"
        const val PREV = "model.prev"
        private val CAPS = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false, promptCaching = PromptCaching.Implicit)

        fun kindOf(platform: String): ProviderConfig.Kind = when (platform) {
            "openai" -> ProviderConfig.Kind.OpenAiCompat
            "openai-responses" -> ProviderConfig.Kind.OpenAiResponses
            "anthropic" -> ProviderConfig.Kind.Anthropic
            "gemini" -> ProviderConfig.Kind.Gemini
            else -> throw com.wangzan101.mika.core.lom.LomException("model.unsupported_protocol")
        }
    }
}
