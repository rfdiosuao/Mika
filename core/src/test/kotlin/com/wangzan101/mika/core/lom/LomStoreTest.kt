package com.wangzan101.mika.core.lom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LomStoreTest {
    private fun tmp() = File.createTempFile("lom", ".bin").also { it.delete() }
    private val paired = LomPaired("lom.entermodetwo.com", "dev_" + "a".repeat(32), "TOKEN-SECRET-VALUE", 1_900_000_000, listOf(LomDesktop("K".repeat(86), "书房电脑", 5)))

    @Test fun `身份密钥首次生成后保持不变`() {
        val f = tmp()
        val a = EncryptedFileLomStore(f, TestSecretBox()).identity()
        val b = EncryptedFileLomStore(f, TestSecretBox()).identity()
        assertEquals(a.keys, b.keys)
        assertEquals(86, a.keys.length)
        f.delete()
    }

    @Test fun `配对记录重启后还在 并且文件里没有明文密钥和令牌`() {
        val f = tmp()
        val s = EncryptedFileLomStore(f, TestSecretBox())
        val id = s.identity()
        s.savePairing(paired)
        val raw = f.readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        assertFalse(text.contains("TOKEN-SECRET-VALUE")); assertFalse(text.contains("书房")); assertFalse(text.contains("device_token"))
        val again = EncryptedFileLomStore(f, TestSecretBox())
        assertEquals(id.keys, again.identity().keys)
        val p = again.pairing()!!
        assertEquals(paired.deviceId, p.deviceId); assertEquals("TOKEN-SECRET-VALUE", p.deviceToken)
        assertEquals(setOf("K".repeat(86)), p.trustedKeys)
        f.delete()
    }

    @Test fun `解除配对清掉凭据和桌面公钥但保留身份`() {
        val f = tmp()
        val s = EncryptedFileLomStore(f, TestSecretBox())
        val keys = s.identity().keys
        s.savePairing(paired)
        s.clearPairing()
        val again = EncryptedFileLomStore(f, TestSecretBox())
        assertNull(again.pairing()); assertEquals(keys, again.identity().keys)
        assertNull(again.state.value)
        f.delete()
    }

    @Test fun `密钥丢失解不开时按未配对处理 不崩溃`() {
        val f = tmp()
        EncryptedFileLomStore(f, TestSecretBox()).also { it.identity(); it.savePairing(paired) }
        val other = EncryptedFileLomStore(f, TestSecretBox(ByteArray(32) { 9 }))
        assertNull(other.pairing())
        assertNotNull(other.identity()) // 重新生成,等于一台新设备
        f.delete()
    }

    @Test fun `toString 不含令牌和私钥`() {
        assertFalse(paired.toString().contains("TOKEN"))
        val id = LomIdentity.generate()
        assertFalse(id.toString().contains(id.keys))
        assertTrue(LomConfirm("dev_x", "TOKEN", 1, "n").toString().none { false } && !LomConfirm("dev_x", "TOKEN", 1, "n").toString().contains("TOKEN"))
    }

    @Test fun `写盘失败时回滚内存状态并报 store_failed`() {
        val dir = tmp().also { it.mkdirs() }
        val f = File(dir, "state.bin")
        val s = EncryptedFileLomStore(f, TestSecretBox())
        s.identity()
        File(dir, "state.bin.tmp").mkdirs() // 临时文件位置被目录占住,写入必然失败
        val c = try { s.savePairing(paired); "" } catch (e: LomException) { e.code }
        assertEquals("pairing.store_failed", c)
        assertNull(s.pairing())
        dir.deleteRecursively()
    }
}
