package com.wangzan101.mika.core.lom

/**
 * 麓鸣接入里所有失败的统一形态:只带一个形如 `<域>.<原因>` 的错误码。
 * 异常文字就是错误码本身,不夹带令牌、密钥、路径或服务端原文,日志和界面可以放心展示。
 */
class LomException(val code: String, val httpStatus: Int = 0) : Exception(code) {
    init {
        require(CODE.matches(code)) { "bad error code" }
    }

    companion object {
        val CODE = Regex("^[a-z_]+\\.[a-z_]+$")
    }
}
