package com.wangzan101.mika.lom

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.net.ConnectivityManager
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.cap.phone.a11y.MainScreenCapture
import com.wangzan101.mika.cap.phone.a11y.ScreenAccessibility
import com.wangzan101.mika.core.lom.LomAction
import com.wangzan101.mika.core.lom.LomException
import com.wangzan101.mika.core.lom.LomFrame
import com.wangzan101.mika.core.lom.LomKv
import com.wangzan101.mika.core.lom.LomNode
import com.wangzan101.mika.core.lom.LomScreenAccess
import com.wangzan101.mika.core.lom.LomScreenMethods
import com.wangzan101.mika.core.lom.LomScreenPort
import com.wangzan101.mika.core.lom.LomTree
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream

/**
 * `screen.*` 接到 Mika 的无障碍服务(主屏,用户看着的那块屏)。三道门:
 * 1) 用户在「麓鸣电脑」页分别打开「查看屏幕」「操作屏幕」(默认关);2) 无障碍服务已开启;
 * 3) 遵守「应用权限」:用户禁用的应用不截、不读、不操作、不打开。
 * 桌面是否该在手机有任务时下发由桌面决定,手机自己也拒绝并发。
 */
class AndroidLomScreen(private val context: Context, private val kv: LomKv) : LomScreenPort {
    private val app get() = context.applicationContext as MikaApp
    private val service: ScreenAccessibility get() = ScreenAccessibility.instance.value ?: throw LomException("screen.permission_required")

    override fun allowed(access: LomScreenAccess) =
        kv.get(flag(access)) == "1" && ScreenAccessibility.instance.value != null

    override fun size(): Pair<Int, Int>? {
        val s = ScreenAccessibility.instance.value ?: return null
        val b = s.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return b.width() to b.height()
    }

    override fun capture(maxEdge: Int, maxBytes: Int): LomFrame {
        val svc = service
        val sources = MainScreenCapture.sourcePackages(svc) ?: throw LomException("screen.capture_failed")
        val access = app.appAccess.current()
        // 屏上有被用户禁用(且没单独允许分享)的应用,或认不出归属:不截
        if (sources.any { !access.canShare(it) } || sources.contains(context.packageName)) throw LomException("screen.capture_failed")
        val shot = runBlocking { svc.takeMainScreenshot() }
        val bmp = (shot as? ScreenAccessibility.MainShot.Ok)?.bitmap ?: throw LomException("screen.capture_failed") // 防截屏窗口也走这里
        try {
            val scale = maxEdge.toFloat() / maxOf(bmp.width, bmp.height)
            val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt().coerceAtLeast(1), (bmp.height * scale).toInt().coerceAtLeast(1), true) else bmp
            try {
                val jpeg = LomScreenMethods.fit(maxBytes) { q -> ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray() }
                return LomFrame(jpeg, bmp.width, bmp.height)
            } finally { if (small !== bmp) small.recycle() }
        } finally { bmp.recycle() }
    }

    override fun tree(): LomTree {
        val svc = service
        val windows = svc.windowsOn(Display.DEFAULT_DISPLAY).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val roots = windows.mapNotNull { it.root }
        val pkg = roots.firstOrNull()?.packageName?.toString().orEmpty()
        if (pkg.isEmpty()) throw LomException("screen.no_window")
        if (app.appAccess.current().denial(pkg) != null || roots.any { app.appAccess.current().denial(it.packageName?.toString()) != null }) throw LomException("screen.app_blocked")
        val nodes = ArrayList<LomNode>()
        var truncated = false
        val rect = android.graphics.Rect()
        fun visit(n: AccessibilityNodeInfo, depth: Int) {
            if (nodes.size >= LomScreenMethods.MAX_NODES + 1 || depth > MAX_DEPTH) { truncated = true; return }
            if (n.isVisibleToUser) {
                n.getBoundsInScreen(rect)
                val secret = n.isPassword
                nodes += LomNode(
                    if (secret) "" else n.text?.toString().orEmpty(), if (secret) "" else n.contentDescription?.toString().orEmpty(),
                    n.viewIdResourceName?.substringAfter(":", n.viewIdResourceName.orEmpty()).orEmpty(),
                    listOf(rect.left, rect.top, rect.right, rect.bottom), n.isClickable, n.isEditable, n.isFocused, secret,
                )
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1) }
        }
        roots.forEach { visit(it, 0) }
        return LomTree(pkg, nodes, truncated)
    }

    override fun appExists(packageName: String) = context.packageManager.getLaunchIntentForPackage(packageName) != null
    override fun agentRunning() = app.taskState.activeConversations.value.isNotEmpty()
    override fun metered() = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

    override fun touched(access: LomScreenAccess, at: Long) { runCatching { kv.put(last(access), at.toString()) } }

    override fun perform(action: LomAction): String {
        val svc = service
        val foreground = ScreenAccessibility.instance.value?.windowsOn(Display.DEFAULT_DISPLAY)
            ?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.firstNotNullOfOrNull { it.root?.packageName?.toString() }
        val access = app.appAccess.current()
        // 前台应用被用户禁用,或认不出前台是谁:不动手(打开应用和回桌面除外)
        if (action !is LomAction.OpenApp && action != LomAction.Home && access.denial(foreground) != null) throw LomException("screen.app_blocked")
        return when (action) {
            is LomAction.Tap -> gesture(svc, Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()) }, 60, "已点按")
            is LomAction.Swipe -> gesture(svc, Path().apply { moveTo(action.x.toFloat(), action.y.toFloat()); lineTo(action.x2.toFloat(), action.y2.toFloat()) }, 400, "已滑动")
            is LomAction.Type -> typeText(svc, action.text)
            LomAction.Back -> global(svc, AccessibilityService.GLOBAL_ACTION_BACK, "已返回")
            LomAction.Home -> global(svc, AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面")
            is LomAction.OpenApp -> {
                if (access.denial(action.packageName) != null) throw LomException("screen.app_blocked")
                val intent = context.packageManager.getLaunchIntentForPackage(action.packageName) ?: throw LomException("screen.app_missing")
                try { svc.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { throw LomException("screen.action_unconfirmed") }
                "已请求打开应用"
            }
        }
    }

    private fun gesture(svc: ScreenAccessibility, path: Path, ms: Long, ok: String): String {
        if (svc.windowsOn(Display.DEFAULT_DISPLAY).isEmpty()) throw LomException("screen.no_window")
        if (!runBlocking { svc.gesture(Display.DEFAULT_DISPLAY, path, ms) }) throw LomException("screen.action_unconfirmed")
        return ok
    }

    private fun global(svc: ScreenAccessibility, id: Int, ok: String): String {
        if (!svc.performGlobalAction(id)) throw LomException("screen.action_unconfirmed")
        return ok
    }

    private fun typeText(svc: ScreenAccessibility, text: String): String {
        val node = svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (node == null || !node.isEditable || node.isPassword) throw LomException("screen.no_focused_input") // 密码框不替人输入
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) throw LomException("screen.action_unconfirmed")
        return "已输入文字"
    }

    companion object {
        private const val MAX_DEPTH = 20
        fun flag(a: LomScreenAccess) = "screen.allow." + a.name.lowercase()
        fun last(a: LomScreenAccess) = "screen.last." + a.name.lowercase()
    }
}
