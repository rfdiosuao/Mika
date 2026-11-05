package com.wangzan101.mika.core.lom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** 中转连接状态。界面据此显示在线/重连/已被撤销。 */
enum class LomRelayState { Stopped, Connecting, Online, Offline, Revoked }

/**
 * 到云端中转的长连接(公开文档 05):每次(重)连接前换新的中转令牌,握手后收 `task.rpc`、回 `task.reply`;
 * 断线按 1/2/5/10/30 秒退避(±20% 抖动),握手成功清零;收到 `device.revoked` 或 `relay.device_revoked` 就清除配对、不再重连。
 */
class LomRelay(
    private val cloud: LomCloud,
    private val store: LomStore,
    private val processor: LomRpcProcessor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val random: Random = Random.Default,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(LomRelayState.Stopped)
    val state: StateFlow<LomRelayState> = _state
    private val lock = Any()
    private var job: Job? = null
    private var wake = CompletableDeferred<Unit>()
    private var current: WebSocket? = null

    // 长连接上不设整次调用超时;自己每 20 秒发 Ping,比云端更早发现死连接
    private val wsClient = cloud.client.newBuilder().callTimeout(0, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()

    fun start() {
        synchronized(lock) {
            if (job?.isActive == true || store.pairing() == null) return
            _state.value = LomRelayState.Connecting
            job = scope.launch { loop() }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel(); job = null
            current?.cancel(); current = null
            if (_state.value != LomRelayState.Revoked) _state.value = LomRelayState.Stopped
        }
    }

    /** 网络恢复、屏幕点亮、回到前台:不等退避,立刻重连。 */
    fun reconnectNow() {
        synchronized(lock) {
            if (job?.isActive != true) return
            if (_state.value == LomRelayState.Online) return
            wake.complete(Unit)
        }
    }

    /** 界面看过「已被撤销」的提示后复位。 */
    fun acknowledgeRevoked() { if (_state.value == LomRelayState.Revoked) _state.value = LomRelayState.Stopped }

    private suspend fun loop() {
        var failures = 0
        while (scope.isActive) {
            val paired = store.pairing() ?: run { _state.value = LomRelayState.Stopped; return }
            _state.value = if (failures == 0) LomRelayState.Connecting else LomRelayState.Offline
            val mine = CompletableDeferred<Unit>().also { wake = it }
            val outcome = try { connectOnce(paired) } catch (e: LomException) { if (e.code == "relay.device_revoked") Outcome.Revoked else Outcome.Failed }
            when (outcome) {
                Outcome.Revoked -> { revoked(); return }
                // 握手成功过:失败计数清零,这次断开算新一轮的第 1 次
                Outcome.Connected -> failures = 1
                Outcome.Failed -> failures++
            }
            _state.value = LomRelayState.Offline
            kotlinx.coroutines.withTimeoutOrNull(backoffMs(failures)) { mine.await() }
        }
    }

    private enum class Outcome { Connected, Failed, Revoked }

    private suspend fun connectOnce(paired: LomPaired): Outcome {
        val token = withContext(Dispatchers.IO) { cloud.relayToken(paired.host, paired.deviceId, paired.deviceToken) }
        val closed = CompletableDeferred<Outcome>()
        var handshakeDone = false
        val ws = wsClient.newWebSocket(cloud.relayRequest(paired.host, token), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshakeDone = true
                _state.value = LomRelayState.Online
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > MAX_FRAME) return
                handle(webSocket, text, closed)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // 服务端先关(含被新连接顶替):回关并按断线处理
                webSocket.close(1000, null)
                closed.complete(if (handshakeDone) Outcome.Connected else Outcome.Failed)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete(if (handshakeDone) Outcome.Connected else Outcome.Failed)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val revoked = response?.code == 401 && runCatching {
                    Json.parseToJsonElement(response.peekBody(65_536).string()).jsonObject["code"]
                }.getOrNull()?.let { (it as? JsonPrimitive)?.content } == "relay.device_revoked"
                // 连接建立后掉线算「成功过」,否则按握手失败退避
                closed.complete(if (revoked) Outcome.Revoked else if (handshakeDone) Outcome.Connected else Outcome.Failed)
            }
        })
        synchronized(lock) { current = ws }
        try {
            return closed.await()
        } finally {
            ws.cancel()
            synchronized(lock) { if (current === ws) current = null }
        }
    }

    private fun handle(ws: WebSocket, text: String, closed: CompletableDeferred<Outcome>) {
        val msg = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
        when ((msg["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "device.revoked" -> closed.complete(Outcome.Revoked)
            "task.rpc" -> {
                val envelope = LomEnvelope.fromJson(msg["envelope"]) ?: return
                // 不阻塞读循环:请求可能并发到达,处理器线程安全
                scope.launch {
                    val reply = try { processor.process(envelope) } catch (e: LomException) { log("rpc ${e.code}"); null }
                    if (reply != null) {
                        val out = JsonObject(linkedMapOf("type" to JsonPrimitive("task.reply"), "envelope" to reply.toJson())).toString()
                        if (out.length <= MAX_FRAME) ws.send(out) else log("reply too large")
                    }
                }
            }
            else -> {} // hello 与不认识的 type 一律忽略(向前兼容)
        }
    }

    private fun revoked() {
        // 清除凭据与桌面公钥,回到未配对;不要再重连
        try { store.clearPairing() } catch (e: LomException) { log("clear ${e.code}") }
        _state.value = LomRelayState.Revoked
    }

    internal fun backoffMs(failures: Int): Long {
        val base = when {
            failures <= 1 -> 1000L
            failures == 2 -> 2000L
            failures == 3 -> 5000L
            failures == 4 -> 10_000L
            else -> 30_000L
        }
        return (base * (1 + 0.2 * (random.nextDouble() * 2 - 1))).toLong()
    }

    companion object {
        const val MAX_FRAME = 512 * 1024
    }
}
