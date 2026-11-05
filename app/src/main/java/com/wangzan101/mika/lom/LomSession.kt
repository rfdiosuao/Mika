package com.wangzan101.mika.lom

import com.wangzan101.mika.core.lom.LomException
import com.wangzan101.mika.core.lom.LomPairLink
import com.wangzan101.mika.core.lom.LomPairingFlow
import com.wangzan101.mika.core.lom.LomPending
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** 配对页的状态。活在 Application 里,转屏、切后台不丢。 */
sealed interface LomPairUi {
    /** 等用户扫码/选图/输配对码。 */
    data object Idle : LomPairUi
    /** 正在向云端核对/认领。 */
    data object Working : LomPairUi
    /** 已认领,等用户明确点「确认连接」。[waiting] 为 true 时已点过、正在等电脑核对。 */
    data class Confirm(val pending: LomPending, val waiting: Boolean = false, val saving: Boolean = false) : LomPairUi
    data class Done(val desktopName: String) : LomPairUi
    /** [code] 是 `<域>.<原因>` 错误码,文案由界面映射。 */
    data class Failed(val code: String) : LomPairUi
}

/** 一次配对的流程控制,界面只和它说话;不持有任何令牌或密钥。 */
class LomSession(private val flow: LomPairingFlow, private val scope: CoroutineScope, private val onPaired: () -> Unit) {
    private val _ui = MutableStateFlow<LomPairUi>(LomPairUi.Idle)
    val ui: StateFlow<LomPairUi> = _ui
    private var job: Job? = null
    private var expiry: Job? = null

    /** 扫码、相册、深链得到的文本。不是麓鸣链接的(别的二维码)只提示,不进入流程。 */
    fun submitLink(text: String) = start {
        flow.beginWithLink(text)
    }

    fun submitCode(code: String) = start { flow.beginWithCode(code) }

    private fun start(begin: suspend () -> LomPending) {
        if (_ui.value is LomPairUi.Working || (_ui.value as? LomPairUi.Confirm)?.waiting == true) return
        job?.cancel()
        _ui.value = LomPairUi.Working
        job = scope.launch {
            try {
                val pending = begin()
                _ui.value = LomPairUi.Confirm(pending)
                expire(pending)
            } catch (e: LomException) {
                _ui.value = LomPairUi.Failed(e.code)
            }
        }
    }

    /** 用户明确点了「确认连接」。 */
    fun confirm() {
        val cur = _ui.value as? LomPairUi.Confirm ?: return
        if (cur.waiting) return
        _ui.value = cur.copy(waiting = true)
        job = scope.launch {
            try {
                val paired = flow.confirm(cur.pending)
                expiry?.cancel()
                _ui.value = LomPairUi.Done(paired.desktops.firstOrNull()?.name.orEmpty())
                onPaired()
            } catch (e: LomException) {
                _ui.value = LomPairUi.Failed(e.code)
            }
        }
    }

    /** 用户点「取消」或离开配对页。 */
    fun cancel() {
        val cur = _ui.value
        job?.cancel(); expiry?.cancel()
        _ui.value = LomPairUi.Idle
        if (cur is LomPairUi.Confirm) scope.launch { flow.cancel(cur.pending) }
    }

    /** 回到可以重新扫码的状态(失败或完成之后)。 */
    fun reset() {
        job?.cancel(); expiry?.cancel()
        _ui.value = LomPairUi.Idle
    }

    private fun expire(pending: LomPending) {
        expiry?.cancel()
        expiry = scope.launch {
            delay((pending.expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0))
            val cur = _ui.value
            if (cur is LomPairUi.Confirm && cur.pending === pending && !cur.waiting) _ui.value = LomPairUi.Failed("pairing.expired")
        }
    }

    companion object {
        /** 二维码文本是不是麓鸣的:不是就由界面提示「这不是麓鸣二维码」,继续扫。 */
        fun isLom(text: String) = LomPairLink.looksLikeLom(text)
    }
}
