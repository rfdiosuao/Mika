package com.wangzan101.mika.core.lom

/** 可启动的应用清单与无障碍状态,由 App 一侧提供(只读,不读其他应用的数据)。 */
interface LomAppsPort {
    /** (包名, 应用名),已按 Mika「应用权限」过滤掉用户禁用的应用。 */
    fun launchable(): List<Pair<String, String>>
    fun accessibilityEnabled(): Boolean
}

/**
 * `device.rename` 与 `apps.list`(公开文档 07 §6)。
 * 改名只改 Mika 里记的设备名(在「麓鸣电脑」页可见),原样保存、原样回显:不能接受的名称返回错误,不悄悄改写。
 */
class LomDeviceMethods(
    private val kv: LomKv,
    private val apps: LomAppsPort,
    private val clock: () -> Long = System::currentTimeMillis,
) : LomMethods {
    override val methods = setOf("device.rename", "apps.list")

    override fun call(method: String, payload: String, sender: String): String? = when (method) {
        "device.rename" -> rename(payload, sender)
        "apps.list" -> list()
        else -> null
    }

    private fun rename(payload: String, sender: String): String {
        val name = LomPayload.str(LomPayload.parse(payload, "device.invalid_name"), "name")
        if (name.isNullOrBlank() || name.length > MAX_NAME || name != name.trim() || name.any { it.isISOControl() }) throw LomException("device.invalid_name")
        kv.put(NAME + LomSender.key(sender), name)
        return LomPayload.obj("name" to name)
    }

    private fun list(): String {
        val items = apps.launchable().distinctBy { it.first }.sortedBy { it.second.lowercase() }.take(MAX_APPS)
            .map { (pkg, label) -> mapOf("package" to pkg, "label" to label.take(MAX_LABEL)).let { m -> LomPayload.obj(*m.toList().toTypedArray()) } }
        val arr = kotlinx.serialization.json.Json.parseToJsonElement("[" + items.joinToString(",") + "]")
        return LomPayload.obj(
            "items" to arr, "accessibility" to apps.accessibilityEnabled().toString(), "sampled_at" to clock().toString(),
        )
    }

    companion object {
        const val NAME = "device.name."
        const val MAX_NAME = 100
        const val MAX_LABEL = 100
        const val MAX_APPS = 1_000
        fun savedName(kv: LomKv, sender: String): String? = kv.get(NAME + LomSender.key(sender))
    }
}
