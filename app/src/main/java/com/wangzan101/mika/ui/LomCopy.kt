package com.wangzan101.mika.ui

/**
 * 「麓鸣」接入的界面文案。Lom 版新增的功能,文案固定为中文(不进多语言资源,保持和上游资源文件零冲突);
 * 错误提示用公开文档 08 推荐的文案,按错误码映射。
 */
object LomCopy {
    const val NAME = "麓鸣"
    const val SCAN_BUTTON = "扫码对接麓鸣"
    const val MODEL_ROW_SUB = "配对后使用麓鸣推送的模型"
    const val PAGE_TITLE = "对接麓鸣"
    const val MANAGE_TITLE = "麓鸣电脑"
    const val SETTINGS_ROW = "麓鸣电脑"
    /** 侧栏入口文案:已对接时显示电脑名,未对接/名字为空时显示通用名。 */
    fun drawerLabel(desktopName: String?): String = if (desktopName.isNullOrBlank()) SETTINGS_ROW else desktopName
    const val PAIRED = "已对接"
    const val NOT_PAIRED = "未对接"

    const val SCAN_HINT = "在电脑上的麓鸣点「添加手机 → 扫一扫」，把屏幕上的二维码放进取景框。"
    const val FROM_GALLERY = "从相册选择"
    const val ENTER_CODE = "输入配对码"
    const val CODE_TITLE = "没有二维码？输入电脑上显示的配对码"
    const val CODE_LABEL = "配对码"
    const val CODE_HINT = "XXXX-XXXX"
    const val CODE_SUBMIT = "继续"
    const val BACK_TO_SCAN = "返回扫码"

    const val CAMERA_NEEDED = "扫码需要使用相机。你也可以从相册选择二维码截图，或输入配对码，不需要相机权限。"
    const val CAMERA_ALLOW = "允许使用相机"
    const val CAMERA_SETTINGS = "去系统设置打开"
    const val NOT_LOM_QR = "这不是麓鸣二维码"
    const val NO_QR_IN_IMAGE = "这张图片里没有找到二维码，换一张更清晰的试试。"
    const val IMAGE_UNREADABLE = "读不了这张图片，请换一张。"

    const val WORKING = "正在和麓鸣核对…"
    const val CONFIRM_TITLE = "连接到这台电脑？"
    const val DESKTOP = "电脑"
    const val ACCOUNT = "账号"
    const val SAS_LABEL = "确认码"
    const val SAS_NOTE = "请确认电脑和手机上显示的确认码一致。不一致就点「取消」。"
    const val CONFIRM = "确认连接"
    const val CANCEL = "取消"
    const val WAITING_DESKTOP = "正在等待电脑核对，请稍候…"
    const val DONE_TITLE = "已连接"
    fun doneBody(name: String) = "这台手机已对接${if (name.isBlank()) "麓鸣电脑" else "「$name」"}，保持联网即可。"
    const val DONE = "完成"
    const val RETRY = "重新扫码"

    const val NOTIF_TITLE = "麓鸣已对接"
    fun notifBody(name: String) = if (name.isBlank()) "保持与麓鸣电脑的连接，打开 Mika 可查看状态" else "保持与「$name」的连接，打开 Mika 可查看状态"

    const val STATE_ONLINE = "在线"
    const val STATE_CONNECTING = "正在连接…"
    const val STATE_OFFLINE = "连接已断开，正在自动重连"
    const val STATE_STOPPED = "未连接，打开 Mika 会自动重连"
    const val UNPAIR = "解除配对"
    const val UNPAIR_TITLE = "解除与麓鸣电脑的配对？"
    const val UNPAIR_BODY = "解除后这台电脑不能再向手机派活。手机上的对话和记忆都会保留。解除需要联网通知云端。"
    const val UNPAIR_NOT_NOTIFIED = "已在手机上解除配对，但没能通知云端。电脑上可能还显示这台手机，可以在电脑上移除它。"
    const val PAIRED_NOTE = "电脑可以查看这台手机的状态和应用列表、推送模型。不支持的功能会明确回复「暂不支持」，不会假装完成。"
    const val ALLOW_VIEW = "允许电脑查看屏幕"
    const val ALLOW_VIEW_SUB = "截图和读取界面内容（密码框内容不会上报）"
    const val ALLOW_CONTROL = "允许电脑操作屏幕"
    const val ALLOW_CONTROL_SUB = "点按、滑动、输入、返回、回桌面、打开应用"
    fun lastUsed(text: String) = "最近一次：$text"
    const val SCREEN_NOTE = "默认关闭。你在「应用权限」里禁用的应用，电脑始终看不到也操作不了。手机上有任务在运行时，电脑的操作会被拒绝。"
    fun deviceNameOnDesktop(name: String) = "在电脑上的名称：$name"
    const val MODEL_WAITING = "等待电脑推送模型"
    /** 「配置模型」页入口的副标题:未对接/等待推送/已在使用。 */
    fun modelRowSub(paired: Boolean, desktop: String?, model: String?): String = when {
        !paired -> MODEL_ROW_SUB
        model.isNullOrBlank() -> "$PAIRED${if (desktop.isNullOrBlank()) "" else "：$desktop"} · $MODEL_WAITING"
        else -> "$PAIRED${if (desktop.isNullOrBlank()) "" else "：$desktop"} · 服务商「$NAME」使用 $model"
    }
    const val MANAGE_EMPTY = "还没有对接麓鸣电脑。在电脑上的麓鸣添加手机，然后扫码对接。"
    const val ALREADY_PAIRED_SUB = "已经对接了一台电脑，需要先解除配对。"
    fun approvedAt(text: String) = "对接于 $text"

    const val REVOKED_TITLE = "已解除与麓鸣的配对"
    const val REVOKED_BODY = "这台手机已被电脑移除，或连接凭据已失效。请重新扫码连接。手机上的对话和记忆没有受影响。"
    const val REVOKED_RESCAN = "重新扫码"
    const val REVOKED_OK = "知道了"

    /** 公开文档 08 的推荐文案。拿不到映射时给通用提示,不展示云端原文。 */
    fun error(code: String): String = when (code) {
        "pairing.qr_invalid" -> "这不是有效的麓鸣配对码。"
        "pairing.not_found" -> "配对会话不存在，请在电脑上刷新二维码。"
        "pairing.expired" -> "配对码已过期，请在电脑上刷新。"
        "pairing.cancelled" -> "配对已取消，请在电脑上刷新。"
        "pairing.failed" -> "配对校验失败，请刷新二维码重试。"
        "pairing.already_claimed" -> "这个配对码已被使用，请在电脑上刷新。"
        "pairing.awaiting_desktop", "pairing.desktop_timeout" -> "电脑迟迟没有确认，请重新扫码。"
        "pairing.secret_invalid" -> "配对凭据无效，请重新扫码。"
        "pairing.code_invalid" -> "配对码不正确。"
        "pairing.request_invalid" -> "配对请求格式不正确，请更新 Mika 后重试。"
        "pairing.rate_limited" -> "尝试次数过多，请稍后再试。"
        "pairing.key_mismatch" -> "配对信息不一致，请重新扫码。"
        "pairing.already_paired" -> "这台手机已经连接了一台电脑，请先解除配对。"
        "pairing.store_failed" -> "保存连接信息失败，请重试。"
        "pairing.response_invalid" -> "服务返回了无法识别的内容，请稍后重试。"
        "device.owned_by_other_account" -> "这台手机已连接到另一个账户，请先在原账户的电脑上移除这台手机。"
        "sub.phone_limit" -> "已达到手机上限，请在电脑上升级订阅或移除旧手机。"
        "net.unreachable" -> "无法连接麓鸣服务，请检查网络。"
        "server.internal" -> "服务暂时不可用，请稍后重试。"
        else -> "没能完成配对，请重试。"
    }

    /** 二维码版本较新时给升级提示,而不是「无效」。 */
    const val NEWER_QR = "二维码版本较新，请升级 Mika。"
}
