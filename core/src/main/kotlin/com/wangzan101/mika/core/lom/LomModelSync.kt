package com.wangzan101.mika.core.lom

import java.net.URI

/** 桌面推送的模型配置。[apiKey] 是秘密:不进 toString、日志和错误信息。 */
class LomModelConfig(val baseUrl: String, val apiKey: String, val model: String, val platform: String, val revision: Long) {
    override fun toString() = "LomModelConfig(model=$model, platform=$platform, revision=$revision)"
}

/** 把模型配置装进 Mika 的服务商体系。实现在 App 一侧(依赖服务商存储);核心只管校验与版本。 */
interface LomModelPort {
    /** 安装或更新「麓鸣」服务商;首次安装时选为主模型并记下用户之前的选择。 */
    fun install(cfg: LomModelConfig)

    /** 移除「麓鸣」服务商;主模型正用着它时恢复用户之前的选择。没装过什么都不做。 */
    fun remove()

    /** 「麓鸣」服务商此刻还在不在(用户可能手动删掉)。 */
    fun installed(): Boolean
}

/**
 * `model.sync`(公开文档 07 §3)。配置按发送方桌面隔离保存版本号,版本只增不减;
 * 密钥只交给 [LomModelPort](进服务商存储的加密凭据库),这里不落盘、不记日志。
 */
class LomModelMethods(private val port: LomModelPort, private val kv: LomKv, private val onChange: (String?) -> Unit = {}) : LomMethods {
    override val methods = setOf("model.sync")

    override fun call(method: String, payload: String, sender: String): String? {
        if (method !in methods) return null
        val o = LomPayload.parse(payload, "model.invalid_config")
        val platform = LomPayload.str(o, "platform").orEmpty()
        if (platform !in PLATFORMS) throw LomException("model.unsupported_protocol")
        val baseUrl = cleanUrl(LomPayload.str(o, "base_url"))
        val key = LomPayload.str(o, "api_key").orEmpty()
        val model = LomPayload.str(o, "model").orEmpty()
        val revText = LomPayload.str(o, "revision").orEmpty()
        if (key.isEmpty() || key.length > MAX_KEY || model.isEmpty() || model.length > MAX_MODEL ||
            revText.isEmpty() || revText.length > 18 || !revText.all { it in '0'..'9' }) throw LomException("model.invalid_config")
        val revision = revText.toLong()
        val slot = REV + LomSender.key(sender)
        val saved = kv.get(slot)?.toLongOrNull()
        // 小于已保存的版本一律拒绝(旧推送晚到不能盖掉新配置);等于或大于接受
        if (saved != null && revision < saved) throw LomException("model.stale_revision")
        port.install(LomModelConfig(baseUrl, key, model, platform, revision))
        kv.put(slot, revision.toString())
        onChange(model)
        return LomPayload.obj("status" to "saved", "model" to model, "revision" to revText)
    }

    /** 这台桌面是否已经推送过模型(`chat.send` 据此返回 `chat.model_missing`)。 */
    fun hasModel(sender: String): Boolean = kv.get(REV + LomSender.key(sender)) != null && port.installed()

    /** 解除配对或收到 `device.revoked`:移除「麓鸣」服务商并清掉版本记录。幂等。 */
    fun clear() {
        port.remove()
        kv.keys(REV).forEach(kv::remove)
        onChange(null)
    }

    companion object {
        val PLATFORMS = setOf("openai", "openai-responses", "anthropic", "gemini")
        const val REV = "model.rev."
        private const val MAX_KEY = 16_384
        private const val MAX_MODEL = 256

        /** 必须 https、不含用户名密码/查询串/片段;去掉末尾 `/`。 */
        fun cleanUrl(raw: String?): String {
            val s = raw?.takeIf { it.isNotEmpty() && it.length <= 2048 && it.none { c -> c.isWhitespace() || c.isISOControl() } }
                ?: throw LomException("model.invalid_url")
            val uri = try { URI(s) } catch (_: Exception) { throw LomException("model.invalid_url") }
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || s.contains('?') || s.contains('#')) throw LomException("model.invalid_url")
            return s.trimEnd('/')
        }
    }
}
