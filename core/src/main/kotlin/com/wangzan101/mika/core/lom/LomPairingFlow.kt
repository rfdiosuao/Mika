package com.wangzan101.mika.core.lom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 配对路径:扫码(核对指纹 + `key_proof`)或配对码(用户比对确认码)。 */
enum class LomPairPath { Qr, Code }

/**
 * 已认领、等用户在手机上点「确认连接」的配对。界面据此展示桌面名称、脱敏账号,
 * 配对码路径还要展示 [sas] 让用户对照桌面屏幕。必须由用户明确点击才调 [confirm]。
 */
class LomPending internal constructor(
    val path: LomPairPath,
    val desktopName: String,
    val maskedAccount: String,
    /** 6 位确认码;扫码路径为 null(不显示)。 */
    val sas: String?,
    val expiresAtMs: Long,
    internal val host: String,
    internal val pairingId: String,
    internal val claimSecret: String,
    internal val desktopKeys: String,
) {
    override fun toString() = "LomPending(path=$path, pairingId=$pairingId)"
}

/**
 * 配对流程(公开文档 03):inspect → 核对指纹 → claim(带 `key_proof`)→ 用户确认 → confirm。
 * 所有阻塞网络调用都切到 IO 线程;失败一律抛 [LomException],只带错误码。
 */
class LomPairingFlow(
    private val cloud: LomCloud,
    private val store: LomStore,
    private val deviceInfo: () -> LomDeviceInfo,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val allowedHosts: Set<String> = LomPairLink.DEFAULT_ALLOWED_HOSTS,
) {
    /** 扫码/深链/相册得到的链接文本 → 认领。已配对时拒绝(先解除)。 */
    suspend fun beginWithLink(text: String): LomPending {
        val link = LomPairLink.parse(text.trim(), allowedHosts) // 校验不过时不发任何网络请求
        ensureUnpaired()
        val self = store.identity()
        return io {
            val seen = cloud.inspect(link.host, link.pairingId, link.claimToken)
            // 核对:pairing_id 回显、公钥串合法、指纹与二维码 f 恒定时间相等
            if (seen.pairingId != link.pairingId || !LomKeys.isValid(seen.desktopKeys) ||
                !java.security.MessageDigest.isEqual(LomKeys.fingerprint(seen.desktopKeys), link.desktopFingerprint)
            ) throw LomException("pairing.key_mismatch")
            val proof = LomKeys.keyProof(link.psk(), link.pairingId, seen.desktopKeys, self.keys)
            val claimed = cloud.claim(link.host, link.pairingId, link.claimToken, self.keys, deviceInfo(), proof)
            LomPending(LomPairPath.Qr, seen.desktopName, seen.maskedAccount, null, claimed.expiresAtSec * 1000, link.host, link.pairingId, claimed.claimSecret, seen.desktopKeys)
        }
    }

    /** 没有二维码时:凭配对码认领,用户需比对确认码。 */
    suspend fun beginWithCode(input: String): LomPending {
        val code = LomManualCode.normalize(input)
        ensureUnpaired()
        val self = store.identity()
        val host = LomPairLink.OFFICIAL_HOST
        return io {
            val r = cloud.claimByCode(host, code, self.keys, deviceInfo())
            if (!LomKeys.isValid(r.desktopKeys)) throw LomException("pairing.response_invalid")
            val sas = LomKeys.sas(r.pairingId, r.desktopKeys, self.keys)
            LomPending(LomPairPath.Code, r.desktopName, r.maskedAccount, sas, r.expiresAtSec * 1000, host, r.pairingId, r.claimSecret, r.desktopKeys)
        }
    }

    /**
     * 用户点「确认连接」后调用。扫码路径可能收到 `pairing.awaiting_desktop`(桌面还没核对完):
     * 每秒重试,最多 15 次,仍不过抛 `pairing.desktop_timeout`;网络失败额外重试最多 3 次。
     * 凭据先持久化成功才返回;落盘失败抛 `pairing.store_failed`,可再次调用(同一 claim_secret 会重新签发令牌)。
     */
    suspend fun confirm(pending: LomPending): LomPaired {
        if (clock() > pending.expiresAtMs) throw LomException("pairing.expired")
        ensureUnpaired()
        var awaiting = 0
        var network = 0
        while (true) {
            try {
                val c = io { cloud.confirm(pending.host, pending.pairingId, pending.claimSecret) }
                val paired = LomPaired(
                    pending.host, c.deviceId, c.deviceToken, c.tokenExpiresAtSec,
                    listOf(LomDesktop(pending.desktopKeys, c.desktopName.ifEmpty { pending.desktopName }, clock())),
                )
                store.savePairing(paired)
                return paired
            } catch (e: LomException) {
                when {
                    e.code == "pairing.awaiting_desktop" -> if (++awaiting > AWAIT_RETRIES) throw LomException("pairing.desktop_timeout")
                    e.code == "net.unreachable" -> if (++network > NETWORK_RETRIES) throw e
                    else -> throw e
                }
                pause(1000)
            }
        }
    }

    /** 用户点「取消」。取消失败不影响本地:会话 5 分钟后自行过期。 */
    suspend fun cancel(pending: LomPending) {
        try {
            io { cloud.cancel(pending.host, pending.pairingId, pending.claimSecret) }
        } catch (_: LomException) {
            // 取消没送达也无妨:会话到期自行失效,本地没有留下任何凭据
        }
    }

    /**
     * 解除配对:先尽力通知云端作废凭据(失败也继续),再清除本地凭据与桌面公钥。
     * 云端没通知上时返回 false,界面应提示「云端可能仍显示在线,可在电脑上移除这台手机」。
     */
    suspend fun unpair(): Boolean {
        val p = store.pairing() ?: return true
        val notified = try {
            io { cloud.unpair(p.host, p.deviceId, p.deviceToken) }
            true
        } catch (e: LomException) {
            e.code == "relay.device_revoked" // 云端早已作废这份凭据,等同已通知
        }
        store.clearPairing()
        return notified
    }

    private fun ensureUnpaired() {
        if (store.pairing() != null) throw LomException("pairing.already_paired")
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        const val AWAIT_RETRIES = 15
        const val NETWORK_RETRIES = 3
    }
}
