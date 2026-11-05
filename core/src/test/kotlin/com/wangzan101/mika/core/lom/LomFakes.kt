package com.wangzan101.mika.core.lom

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 内存里的 [LomStore],可注入落盘失败。 */
class FakeLomStore(private val ident: LomIdentity = LomIdentity.generate(), initial: LomPaired? = null) : LomStore {
    private val flow = MutableStateFlow(initial)
    var failSave = false
    override fun identity() = ident
    override fun pairing() = flow.value
    override fun savePairing(pairing: LomPaired) {
        if (failSave) throw LomException("pairing.store_failed")
        flow.value = pairing
    }
    override fun clearPairing() { flow.value = null }
    override val state: StateFlow<LomPaired?> get() = flow
}

/** 测试用的 AES-GCM 包裹,模拟安卓 Keystore 的行为。 */
class TestSecretBox(private val key: ByteArray = ByteArray(32) { 3 }) : LomSecretBox {
    override fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }
    override fun open(blob: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return c.doFinal(blob, 12, blob.size - 12)
    }
}

/** 内存里的 [LomKv],可注入落盘失败。 */
class MemoryLomKv : LomKv {
    val map = LinkedHashMap<String, String>()
    var failWrites = false
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { if (failWrites) throw LomException("remote.operation_failed"); map[key] = value }
    override fun remove(key: String) { map.remove(key) }
    override fun keys(prefix: String) = map.keys.filter { it.startsWith(prefix) }
}
