package com.wangzan101.mika.core.lom

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.InvertedLuminanceSource
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.BarcodeFormat
import com.google.zxing.ChecksumException
import com.google.zxing.FormatException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * 从图像里解出二维码文本。用 ZXing core(Apache-2.0,纯 Java,不依赖 Google Play 服务):
 * 相机帧给亮度平面([fromLuma]),相册图片给 ARGB 像素([fromArgb])。解不出返回 null,不抛异常。
 * 解出的文本不在这里校验或记录,交给 [LomPairLink.parse]。
 */
object LomQrDecoder {
    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    /** 相机预览帧的亮度平面(YUV 的 Y),[rowStride] 可大于宽度。 */
    fun fromLuma(luma: ByteArray, rowStride: Int, width: Int, height: Int): String? {
        if (rowStride < width || luma.size < rowStride * (height - 1) + width) return null
        return decode(PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false))
    }

    /** 相册图片:`Bitmap.getPixels` 得到的 ARGB 像素。 */
    fun fromArgb(pixels: IntArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return decode(RGBLuminanceSource(width, height, pixels))
    }

    private fun decode(source: LuminanceSource): String? {
        // 先正常,再反色(深色背景上的浅色码);每种再换一次二值化,截图和照片的光照差别很大
        val attempts = listOf<() -> BinaryBitmap>(
            { BinaryBitmap(HybridBinarizer(source)) },
            { BinaryBitmap(GlobalHistogramBinarizer(source)) },
            { BinaryBitmap(HybridBinarizer(InvertedLuminanceSource(source))) },
        )
        for (make in attempts) {
            try {
                return QRCodeReader().decode(make(), hints).text
            } catch (_: NotFoundException) {
            } catch (_: ChecksumException) {
            } catch (_: FormatException) {
            }
        }
        return null
    }
}
