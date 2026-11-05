package com.wangzan101.mika.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.wangzan101.mika.core.lom.LomQrDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 相机取景 + 二维码解码。解码用 ZXing core(Apache-2.0,不依赖 Google Play 服务);
 * 只分析最新一帧,每 [INTERVAL_MS] 毫秒最多解一次,解出文本就回调([onText] 在主线程)。
 * 文本不在这里记录或校验。离开页面时释放相机。
 */
@Composable
fun LomCameraScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val stopped = remember { AtomicBoolean(false) }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            if (stopped.get()) return@addListener
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            var last = 0L
            analysis.setAnalyzer(executor) { image ->
                try {
                    val now = System.currentTimeMillis()
                    if (now - last >= INTERVAL_MS) {
                        last = now
                        decode(image)?.let { text -> view.post { if (!stopped.get()) onText(text) } }
                    }
                } finally {
                    image.close()
                }
            }
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            stopped.set(true)
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView({ view }, modifier.fillMaxSize())
}

private const val INTERVAL_MS = 120L

private fun decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null // Y 平面就是亮度
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    return LomQrDecoder.fromLuma(bytes, plane.rowStride, image.width, image.height)
}

/** 相册选图解码:先缩到长边 2048 以内再解,解不出再试 1024(截图和照片的清晰度差别大)。读不了图返回 null,没找到码返回空串。 */
suspend fun decodeQrFromImage(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
    for (side in intArrayOf(2048, 1024)) {
        val bitmap = loadScaled(context, uri, side) ?: return@withContext null
        try {
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            LomQrDecoder.fromArgb(pixels, w, h)?.let { return@withContext it }
        } finally {
            bitmap.recycle()
        }
    }
    ""
}

private fun loadScaled(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val big = maxOf(info.size.width, info.size.height)
        if (big > maxSide) {
            val scale = maxSide.toFloat() / big
            decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
        }
    }
} catch (_: Exception) {
    null
} catch (_: OutOfMemoryError) {
    null
}
