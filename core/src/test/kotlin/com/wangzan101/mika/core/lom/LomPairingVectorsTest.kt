package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 公开文档 04 / 02 的向量:公钥串、指纹、key_proof、确认码、二维码解析。 */
class LomPairingVectorsTest {
    private val cases = Vectors.pairing["cases"]!!.jsonArray.map { it.jsonObject }

    private fun str(o: kotlinx.serialization.json.JsonObject, k: String) = o.getValue(k).jsonPrimitive.content

    @Test
    fun `向量至少两组`() = assertTrue(cases.size >= 2)

    @Test
    fun `公钥串与指纹与向量一致`() {
        for (c in cases) for (side in listOf("desktop", "phone")) {
            val o = c.getValue(side).jsonObject
            val x = Base64Url.decodeExactly(str(o, "x25519"), 32)!!
            val e = Base64Url.decodeExactly(str(o, "ed25519"), 32)!!
            val keys = LomKeys.keysOf(x, e)
            assertEquals(str(o, "keys"), keys)
            assertEquals(86, keys.length)
            assertTrue(LomKeys.isValid(keys))
            assertEquals(str(o, "fingerprint"), LomKeys.fingerprintText(keys))
        }
    }

    @Test
    fun `key_proof 与向量一致`() {
        for (c in cases) {
            val psk = Base64Url.decodeExactly(str(c, "psk"), 32)!!
            val proof = LomKeys.keyProof(psk, str(c, "pairing_id"), str(c.getValue("desktop").jsonObject, "keys"), str(c.getValue("phone").jsonObject, "keys"))
            assertEquals(str(c, "key_proof"), proof)
        }
    }

    @Test
    fun `key_proof 随任一输入变化`() {
        val c = cases[0]
        val psk = Base64Url.decodeExactly(str(c, "psk"), 32)!!
        val d = str(c.getValue("desktop").jsonObject, "keys")
        val p = str(c.getValue("phone").jsonObject, "keys")
        val good = LomKeys.keyProof(psk, str(c, "pairing_id"), d, p)
        assertFalse(good == LomKeys.keyProof(psk, "x" + str(c, "pairing_id").drop(1), d, p))
        assertFalse(good == LomKeys.keyProof(ByteArray(32), str(c, "pairing_id"), d, p))
        assertFalse(good == LomKeys.keyProof(psk, str(c, "pairing_id"), p, d))
    }

    @Test
    fun `确认码与向量一致且是六位数字`() {
        for (c in cases) {
            val sas = LomKeys.sas(str(c, "pairing_id"), str(c.getValue("desktop").jsonObject, "keys"), str(c.getValue("phone").jsonObject, "keys"))
            assertEquals(str(c, "sas"), sas)
            assertTrue(Regex("\\d{6}").matches(sas))
        }
        assertEquals("643266", str(cases[0], "sas"))
    }

    @Test
    fun `二维码解析出向量里的各字段`() {
        val c = cases[0]
        val link = LomPairLink.parse(str(c, "qr"))
        assertEquals(str(c, "host"), link.host)
        assertEquals(str(c, "pairing_id"), link.pairingId)
        assertEquals(str(c, "claim_token"), link.claimToken)
        assertArrayEquals(Base64Url.decode(str(c, "psk")), link.psk())
        assertArrayEquals(Base64Url.decode(str(c.getValue("desktop").jsonObject, "fingerprint")), link.desktopFingerprint)
    }

    @Test
    fun `白名单之外的主机被拒绝但可注入测试主机`() {
        val c = cases[1]
        assertEquals("relay.example.cn", str(c, "host"))
        expectInvalid(str(c, "qr")) // 默认白名单只有官方主机
        val link = LomPairLink.parse(str(c, "qr"), setOf("relay.example.cn"))
        assertEquals("relay.example.cn", link.host)
    }

    private fun expectInvalid(text: String) {
        try {
            LomPairLink.parse(text)
            fail("应当被拒绝: ${text.take(40)}")
        } catch (e: LomException) {
            assertEquals("pairing.qr_invalid", e.code)
        }
    }

    private fun qr() = str(cases[0], "qr")

    @Test
    fun `缺字段 多字段 重复字段 未知字段都无效`() {
        val q = qr()
        expectInvalid(q.replace(Regex("&f=[^&]+"), ""))
        expectInvalid("$q&x=AAAA")
        expectInvalid("$q&f=" + str(cases[0].getValue("desktop").jsonObject, "fingerprint"))
        expectInvalid(q.replace("&f=", "&g="))
        expectInvalid(q.replace("v=1&", "v=1&v=1&"))
    }

    @Test
    fun `版本不是1 填充 标准base64字母 长度不对 fragment 都无效`() {
        val q = qr()
        expectInvalid(q.replace("v=1", "v=2"))
        expectInvalid(q.replace(Regex("&p=[^&]+"), "&p=OgX7dsvX6Pp6hNZsT6w8Sw=="))
        expectInvalid(q.replace(Regex("&k=[^&]+"), "&k=3yUTIoVtslOrzFoP+u6Mv94XCSibzlDW7C6unULIp3o"))
        expectInvalid(q.replace(Regex("&t=[^&]+"), "&t=AAAA"))
        expectInvalid(q.replace(Regex("&f=[^&]+"), "&f=rp1mfT5Bdyg1iFGecQecVR")) // 尾部非规范位
        expectInvalid("$q#frag")
        expectInvalid("https://pair?" + q.removePrefix("lom://pair?"))
        expectInvalid("lom://pair/x?" + q.removePrefix("lom://pair?"))
        expectInvalid(q + "A".repeat(2100))
        expectInvalid("")
    }

    @Test
    fun `主机名必须是合法小写主机`() {
        val q = qr()
        fun withHost(host: String) = q.replace(Regex("&h=[^&]+"), "&h=" + Base64Url.encode(host.toByteArray()))
        for (bad in listOf("Lom.entermodetwo.com", "lom.entermodetwo.com/x", "lom.entermodetwo.com:8080", "evil.com", "lom.entermodetwo.com.evil.com")) {
            expectInvalid(withHost(bad))
        }
        // 非法 UTF-8
        expectInvalid(q.replace(Regex("&h=[^&]+"), "&h=" + Base64Url.encode(byteArrayOf(0xff.toByte(), 0xfe.toByte()))))
    }

    @Test
    fun `新版本二维码能被识别`() {
        assertTrue(LomPairLink.isNewerVersion(qr().replace("v=1", "v=2")))
        assertFalse(LomPairLink.isNewerVersion(qr()))
        assertFalse(LomPairLink.isNewerVersion("hello"))
    }

    @Test
    fun `base64url 严格解码`() {
        assertNull(Base64Url.decode("AB"))
        assertNull(Base64Url.decode("AA=="))
        assertNull(Base64Url.decode("a+b/"))
        assertNull(Base64Url.decode(""))
        assertNull(Base64Url.decode("A"))
        assertArrayEquals(byteArrayOf(0), Base64Url.decode("AA"))
    }

    @Test
    fun `配对码规范化`() {
        assertEquals("AB3D-9XK2", LomManualCode.normalize("ab3d9xk2"))
        assertEquals("AB3D-9XK2", LomManualCode.normalize(" ab3d - 9xk2 "))
        for (bad in listOf("", "AB3D9XK", "AB3D9XK22", "AB3D-9XK0", "AB3D-9XKO", "AB3D-9XK1", "AB3D-9XKI", "AB3D-9XKL", "AB3D-9XK!")) {
            try {
                LomManualCode.normalize(bad); fail(bad)
            } catch (e: LomException) {
                assertEquals("pairing.code_invalid", e.code)
            }
        }
    }
}
