package com.wangzan101.mika.core.lom

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LomQrDecoderTest {
    private val qr = (Vectors.pairing["cases"] as JsonArray)[0].jsonObject.getValue("qr").jsonPrimitive.content

    /** 按文档 02 §1:纠错级别 M。scale 个像素一个模块,四周留 4 模块静区。 */
    private fun render(text: String, scale: Int, invert: Boolean = false): Triple<IntArray, Int, Int> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
        val w = m.width * scale; val h = m.height * scale
        val px = IntArray(w * h) { i ->
            val dark = m[(i % w) / scale, (i / w) / scale]
            if (dark != invert) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        return Triple(px, w, h)
    }

    @Test fun `相册图片 ARGB 解出向量里的链接`() {
        val (px, w, h) = render(qr, 4)
        assertEquals(qr, LomQrDecoder.fromArgb(px, w, h))
        assertEquals(qr, LomPairLink.parse(LomQrDecoder.fromArgb(px, w, h)!!).let { qr })
    }

    @Test fun `相机亮度平面带行跨度也能解`() {
        val (px, w, h) = render(qr, 3)
        val stride = w + 13
        val luma = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) luma[y * stride + x] = if (px[y * w + x] == 0xFF000000.toInt()) 0 else 0xFF.toByte()
        assertEquals(qr, LomQrDecoder.fromLuma(luma, stride, w, h))
    }

    @Test fun `反色的码也能解`() {
        val (px, w, h) = render(qr, 4, invert = true)
        assertEquals(qr, LomQrDecoder.fromArgb(px, w, h))
    }

    @Test fun `没有二维码或参数不对返回 null 不抛异常`() {
        assertNull(LomQrDecoder.fromArgb(IntArray(100 * 100) { -1 }, 100, 100))
        assertNull(LomQrDecoder.fromArgb(IntArray(10), 100, 100))
        assertNull(LomQrDecoder.fromLuma(ByteArray(10), 5, 100, 100))
    }
}
