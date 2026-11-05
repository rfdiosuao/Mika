package com.wangzan101.mika.lom

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.lom.EncryptedFileLomStore
import com.wangzan101.mika.core.lom.FileReplayStore
import com.wangzan101.mika.core.lom.EncryptedFileLomKv
import com.wangzan101.mika.core.lom.LomAppsPort
import com.wangzan101.mika.core.lom.LomBasicMethods
import com.wangzan101.mika.core.lom.LomChatMethods
import com.wangzan101.mika.core.lom.LomFileMethods
import com.wangzan101.mika.core.lom.LomScreenMethods
import com.wangzan101.mika.core.lom.LomDeviceMethods
import com.wangzan101.mika.core.lom.LomKv
import com.wangzan101.mika.core.lom.LomMethodSet
import com.wangzan101.mika.core.lom.LomMethods
import com.wangzan101.mika.core.lom.LomModelMethods
import com.wangzan101.mika.core.lom.LomCloud
import com.wangzan101.mika.core.lom.LomDeviceInfo
import com.wangzan101.mika.core.lom.LomPaired
import com.wangzan101.mika.core.lom.LomPairingFlow
import com.wangzan101.mika.core.lom.LomRelay
import com.wangzan101.mika.core.lom.LomRelayState
import com.wangzan101.mika.core.lom.LomRpcProcessor
import com.wangzan101.mika.core.lom.LomStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

/**
 * 麓鸣接入在 App 里的总装:身份与配对存储(Keystore 包裹)、配对流程、中转连接、RPC 处理器。
 * 配对页(扫码/相册/深链/配对码)、设置里的管理页、前台服务都只和它打交道。
 */
class LomRuntime(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.noBackupFilesDir, "lom")

    val store: LomStore = EncryptedFileLomStore(File(dir, "state.bin"), LomKeystoreBox())
    private val cloud = LomCloud(OkHttpClient())
    private val flow = LomPairingFlow(cloud, store, ::deviceInfo)

    /** 模型版本、设备名等小块状态,整份经 Keystore 包裹加密。 */
    val kv: LomKv = EncryptedFileLomKv(File(dir, "kv.bin"), LomKeystoreBox())
    private val _modelName = MutableStateFlow<String?>(null)
    /** 「麓鸣」服务商当前用的模型名;还没收到电脑推送为 null。界面据此显示「等待电脑推送模型」。 */
    val modelName: StateFlow<String?> = _modelName
    val modelMethods: LomModelMethods by lazy {
        LomModelMethods(LomProviderBridge((context.applicationContext as com.wangzan101.mika.MikaApp).providers, kv), kv) { _modelName.value = it }
    }

    /** 本版本接入的方法集合;没认领的方法由处理器统一回 `remote.method_unknown`。 */
    private fun methods(): LomMethods = LomMethodSet(
        modelMethods,
        LomDeviceMethods(kv, AndroidApps(context)),
        LomChatMethods(AndroidChat(context.applicationContext as com.wangzan101.mika.MikaApp), kv, modelReady = { modelMethods.hasModel(it) }),
        LomScreenMethods(AndroidLomScreen(context, kv), kv),
        LomFileMethods(File(dir, "files"), AndroidGallery(context)),
        LomBasicMethods(status = ::deviceStatus),
    )

    private val processor = LomRpcProcessor(
        identity = { store.identity() },
        trusted = { store.pairing()?.trustedKeys.orEmpty() },
        replay = FileReplayStore(File(dir, "replay.log")),
        methods = methods(),
        onDrop = { Log.d(TAG, "envelope dropped: $it") }, // 只有内部原因码,没有任何内容
    )
    val relay = LomRelay(cloud, store, processor, scope, log = { Log.d(TAG, it) })

    /** 当前配对;null 表示未配对。 */
    val paired: StateFlow<LomPaired?> get() = store.state
    val relayState: StateFlow<LomRelayState> get() = relay.state
    val session = LomSession(flow, scope) { ensureRunning() }

    private val _revokedNotice = MutableStateFlow(false)
    /** 被桌面移除/凭据失效后,界面要提示一次「已解除配对,请重新扫码」。 */
    val revokedNotice: StateFlow<Boolean> = _revokedNotice

    /** 有人要求打开配对页(设置里的按钮、配置模型页的入口、深链);界面监听后跳转。 */
    private val _openPair = MutableStateFlow(0)
    val openPair: StateFlow<Int> = _openPair

    init {
        // 解除配对、被桌面移除或凭据失效(配对记录变空)时移除「麓鸣」服务商;启动时也核对一次,收拾上次没来得及清的
        scope.launch {
            store.state.collect { p ->
                if (p == null) {
                    runCatching { modelMethods.clear() }.onFailure { Log.w(TAG, "could not remove Lom provider") }
                    clearDesktopState()
                }
                else _modelName.value = if (modelMethods.hasModel(p.trustedKeys.firstOrNull().orEmpty())) currentModel() else null
            }
        }
        scope.launch {
            relay.state.collect { s ->
                if (s == LomRelayState.Revoked) {
                    _revokedNotice.value = true
                    context.stopService(Intent(context, LomService::class.java))
                }
            }
        }
    }

    /** 解除配对:这台桌面的授权、收据、会话归属、暂存文件一并清掉(手机上的对话本身保留);再次配对要重新授权。 */
    private fun clearDesktopState() {
        runCatching {
            listOf("screen.allow.", "screen.last.", "screen.r.", "chat.s.", "chat.r.", "device.name.").forEach { pre -> kv.keys(pre).forEach(kv::remove) }
            File(dir, "files").deleteRecursively()
        }.onFailure { Log.w(TAG, "could not clear desktop state") }
    }

    private fun currentModel(): String? = (context.applicationContext as com.wangzan101.mika.MikaApp).providers
        .configs.value.firstOrNull { it.name == LomProviderBridge.NAME }?.model

    fun requestPairScreen() { _openPair.value = _openPair.value + 1 }

    /** 深链 `lom://pair?…` 进来:交给配对流程,并要求打开配对页。链接内容不进日志。 */
    fun handleDeepLink(text: String) {
        session.submitLink(text)
        requestPairScreen()
    }

    fun acknowledgeRevoked() {
        _revokedNotice.value = false
        relay.acknowledgeRevoked()
    }

    /** 已配对就确保前台服务在跑(被系统停过、从后台没起来时,界面在前台补起)。 */
    fun ensureRunning() {
        if (store.pairing() == null) return
        runCatching { context.startForegroundService(Intent(context, LomService::class.java)) }
            .onFailure { Log.w(TAG, "could not start Lom service", it) }
    }

    /** 解除配对:通知云端并清除本地。返回 false 表示云端没通知上(本地已清除)。 */
    suspend fun unpair(): Boolean {
        relay.stop()
        val notified = flow.unpair()
        context.stopService(Intent(context, LomService::class.java))
        return notified
    }

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** 网络恢复/切换时不等退避立刻重连(公开文档 05 §5)。由服务在运行期间注册。 */
    fun watchNetwork() {
        if (networkCallback != null) return
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { relay.reconnectNow() }
        }
        networkCallback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onFailure { networkCallback = null }
    }

    fun unwatchNetwork() {
        val cb = networkCallback ?: return
        networkCallback = null
        runCatching { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
    }

    private fun deviceInfo(): LomDeviceInfo {
        val name = runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull().orEmpty()
        return LomDeviceInfo(name.ifBlank { Build.MODEL }, Build.MODEL, "Android ${Build.VERSION.RELEASE}", BuildConfig.VERSION_NAME)
    }

    /** 只读采样,不编造:拿不到就报未知。 */
    private fun deviceStatus(): LomBasicMethods.DeviceStatus {
        val battery = runCatching {
            context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }?.toString().orEmpty()
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val network = when {
            cm == null -> "unknown"
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }
        return LomBasicMethods.DeviceStatus(battery, network)
    }

    private companion object { const val TAG = "MikaLom" }
}
