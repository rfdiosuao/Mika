package com.wangzan101.mika.core.lom

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 公开文档 06 的向量与反例:验签在解密之前、收件人、信任集合、篡改、回复可被发送方打开。 */
class LomEnvelopeTest {
    private val v = Vectors.envelope
    private val envelope = LomEnvelope.fromJson(v.getValue("envelope"))!!
    private val desktop = Vectors.vectorDesktop
    private val phone = Vectors.vectorPhone

    private fun open(e: LomEnvelope = envelope, trusted: Set<String> = setOf(desktop.keys), self: LomIdentity = phone) = LomEnvelopes.open(e, self, trusted)

    private fun assertDrop(result: LomOpen, reason: String) {
        assertTrue("应丢弃", result is LomOpen.Drop)
        assertEquals(reason, (result as LomOpen.Drop).reason)
    }

    @Test
    fun `种子推出的公钥串等于向量里的 sender 和 recipient`() {
        assertEquals(v.getValue("sender").jsonPrimitive.content, desktop.keys)
        assertEquals(v.getValue("recipient").jsonPrimitive.content, phone.keys)
    }

    @Test
    fun `向量信封能打开并得到约定明文`() {
        val r = open() as LomOpen.Ok
        assertEquals("模型同步：test-key", String(r.plaintext, Charsets.UTF_8))
        assertEquals(envelope.id, r.id)
    }

    @Test
    fun `篡改任一字段都打不开`() {
        val flipped = { s: String -> (if (s[5] == 'A') "B" else "A").let { s.substring(0, 5) + it + s.substring(6) } }
        assertTrue(open(envelope.copy(id = hex32('b'))) is LomOpen.Drop)
        assertTrue(open(envelope.copy(ephemeral = flipped(envelope.ephemeral))) is LomOpen.Drop)
        assertTrue(open(envelope.copy(ciphertext = flipped(envelope.ciphertext))) is LomOpen.Drop)
        assertTrue(open(envelope.copy(signature = flipped(envelope.signature))) is LomOpen.Drop)
    }

    @Test
    fun `发送方不在信任集合里打不开`() {
        assertDrop(open(trusted = emptySet()), "sender_untrusted")
        assertDrop(open(trusted = setOf(phone.keys)), "sender_untrusted")
    }

    @Test
    fun `收件人不是自己打不开`() {
        assertDrop(open(self = desktop), "recipient")
        assertDrop(open(envelope.copy(recipient = desktop.keys)), "recipient")
    }

    @Test
    fun `验签发生在解密之前 密文被改时报签名错误而不是解密错误`() {
        val body = Base64Url.decode(envelope.ciphertext)!!
        body[body.size - 1] = (body.last().toInt() xor 1).toByte()
        assertDrop(open(envelope.copy(ciphertext = Base64Url.encode(body))), "signature")
    }

    @Test
    fun `格式不对的字段被丢弃`() {
        assertDrop(open(envelope.copy(id = "abc")), "id")
        assertDrop(open(envelope.copy(id = "z".repeat(32))), "id")
        assertDrop(open(envelope.copy(ephemeral = "AAAA")), "ephemeral")
        assertDrop(open(envelope.copy(ciphertext = Base64Url.encode(ByteArray(27)))), "ciphertext_size")
        assertDrop(open(envelope.copy(signature = "AAAA")), "signature_format")
    }

    @Test
    fun `小阶点的临时公钥被拒绝`() {
        // 以桌面身份签名一个 ephemeral 全零的信封:验签通过,但共享密钥全零必须丢弃
        val id = hex32('c')
        val eph = Base64Url.encode(ByteArray(32))
        val aad = "LOM-REMOTE-V1\n$id\n${desktop.keys}\n${phone.keys}\n$eph".toByteArray()
        val body = ByteArray(40) { 1 }
        val sig = Base64Url.encode(desktop.sign(aad + body))
        val e = LomEnvelope(id, desktop.keys, phone.keys, eph, Base64Url.encode(body), sig)
        assertDrop(open(e), "invalid_key")
    }

    @Test
    fun `回复沿用 id 且能被发送方打开`() {
        val reply = LomEnvelopes.seal("回复".toByteArray(), envelope.id, phone, desktop.keys)
        assertEquals(envelope.id, reply.id)
        assertEquals(phone.keys, reply.sender)
        assertEquals(desktop.keys, reply.recipient)
        val r = LomEnvelopes.open(reply, desktop, setOf(phone.keys)) as LomOpen.Ok
        assertEquals("回复", String(r.plaintext))
    }

    @Test
    fun `每个回复用新的临时密钥和随机数`() {
        val a = LomEnvelopes.seal(ByteArray(4), envelope.id, phone, desktop.keys)
        val b = LomEnvelopes.seal(ByteArray(4), envelope.id, phone, desktop.keys)
        assertTrue(a.ephemeral != b.ephemeral && a.ciphertext != b.ciphertext)
    }

    @Test
    fun `明文超过上限不能封装`() {
        try {
            LomEnvelopes.seal(ByteArray(LomEnvelopes.MAX_PLAINTEXT + 1), envelope.id, phone, desktop.keys)
            throw AssertionError("应当失败")
        } catch (e: LomException) {
            assertEquals("remote.payload_invalid", e.code)
        }
    }

    @Test
    fun `信封 JSON 必须恰好六个字符串字段`() {
        val ok = envelope.toJson().toString()
        assertTrue(LomEnvelope.parse(ok) != null)
        assertEquals(null, LomEnvelope.parse(ok.replace("}", ",\"extra\":\"x\"}")))
        assertEquals(null, LomEnvelope.parse(ok.replace("\"signature\"", "\"sig\"")))
        assertEquals(null, LomEnvelope.parse(ok.replace("\"id\":\"" + envelope.id + "\"", "\"id\":1")))
        assertEquals(null, LomEnvelope.parse("[]"))
        assertEquals(null, LomEnvelope.parse("not json"))
        assertEquals(envelope.id, v.getValue("envelope").jsonObject.getValue("id").jsonPrimitive.content)
    }
}
