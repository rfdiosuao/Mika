package com.wangzan101.mika.core.lom

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class LomRelayTest {
    private lateinit var server: MockWebServer
    private val desktop = LomIdentity.generate()
    private val phone = LomIdentity.generate()
    private val paired = LomPaired("lom.entermodetwo.com", "dev_" + "a".repeat(32), "DEVTOK", 1, listOf(LomDesktop(desktop.keys, "书房电脑", 1)))
    private lateinit var store: FakeLomStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val replies = LinkedBlockingQueue<String>()
    private val sockets = LinkedBlockingQueue<WebSocket>()
    private var tokenResponse: () -> MockResponse = { MockResponse().setBody("""{"success":true,"data":{"relay_token":"RELAYTOK","expires_at":1}}""") }
    private var upgradeStatus = 101
    private lateinit var relay: LomRelay

    @Before fun setUp() {
        store = FakeLomStore(phone, paired)
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                if (request.path == "/v1/relay/token") return tokenResponse()
                if (request.path == "/v1/relay") {
                    if (upgradeStatus != 101) return MockResponse().setResponseCode(upgradeStatus).setBody("""{"success":false,"code":"relay.device_revoked","error":"x"}""")
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            sockets += webSocket
                            webSocket.send("""{"v":1,"kind":"ctrl","type":"hello","device_id":"${paired.deviceId}","server_time":1}""")
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) { replies += text }
                    })
                }
                return MockResponse().setResponseCode(404)
            }
        }
        server.start()
        val processor = LomRpcProcessor({ phone }, { store.pairing()?.trustedKeys.orEmpty() }, MemoryReplayStore(), LomBasicMethods())
        relay = LomRelay(LomCloud(endpoint = { server.url("/") }), store, processor, scope, Random(1))
    }

    @After fun tearDown() { relay.stop(); scope.cancel(); server.shutdown() }

    private fun rpc(method: String, id: String = hex32('1')): String {
        val plain = JsonObject(linkedMapOf("method" to JsonPrimitive(method), "payload" to JsonPrimitive("{}"), "issued_at" to JsonPrimitive(System.currentTimeMillis().toString())))
        val env = LomEnvelopes.seal(plain.toString().toByteArray(), id, desktop, phone.keys)
        return JsonObject(linkedMapOf("type" to JsonPrimitive("task.rpc"), "envelope" to env.toJson())).toString()
    }

    private fun awaitState(s: LomRelayState) = runBlocking { withTimeout(8000) { relay.state.first { it == s } } }

    @Test fun `连上中转 收到请求并回不支持错误`() {
        relay.start()
        awaitState(LomRelayState.Online)
        val socket = sockets.poll(5, TimeUnit.SECONDS)!!
        socket.send(rpc("chat.send"))
        val out = Json.parseToJsonElement(replies.poll(5, TimeUnit.SECONDS)!!).jsonObject
        assertEquals("task.reply", out.getValue("type").jsonPrimitive.content)
        val env = LomEnvelope.fromJson(out["envelope"])!!
        assertEquals(hex32('1'), env.id)
        val opened = LomEnvelopes.open(env, desktop, setOf(phone.keys)) as LomOpen.Ok
        val resp = Json.parseToJsonElement(String(opened.plaintext)).jsonObject
        assertEquals("error", resp.getValue("status").jsonPrimitive.content)
        assertEquals("remote.method_unknown", resp.getValue("error").jsonPrimitive.content)
        // 令牌换取与握手用对了头:设备凭据换令牌,Bearer 握手
        assertEquals("Device ${paired.deviceId}.DEVTOK", requests.first { it.path == "/v1/relay/token" }.getHeader("Authorization"))
        assertEquals("Bearer RELAYTOK", requests.first { it.path == "/v1/relay" }.getHeader("Authorization"))
    }

    @Test fun `不可信或损坏的消息不回复 回复信封只含六个字段`() {
        relay.start(); awaitState(LomRelayState.Online)
        val socket = sockets.poll(5, TimeUnit.SECONDS)!!
        socket.send("not json"); socket.send("""{"type":"task.rpc","envelope":{"id":"x"}}""")
        socket.send("""{"type":"something.new","x":1}""")
        val stranger = LomIdentity.generate()
        val plain = """{"method":"chat.list","payload":"{}","issued_at":"${System.currentTimeMillis()}"}""".toByteArray()
        socket.send(JsonObject(linkedMapOf("type" to JsonPrimitive("task.rpc"), "envelope" to LomEnvelopes.seal(plain, hex32('2'), stranger, phone.keys).toJson())).toString())
        socket.send(rpc("document.list", hex32('3')))
        val only = replies.poll(5, TimeUnit.SECONDS)!!
        assertEquals(hex32('3'), Json.parseToJsonElement(only).jsonObject.getValue("envelope").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(6, Json.parseToJsonElement(only).jsonObject.getValue("envelope").jsonObject.size)
        assertNull(replies.poll(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `在线时收到 device_revoked 清除配对且不再重连`() {
        relay.start(); awaitState(LomRelayState.Online)
        sockets.poll(5, TimeUnit.SECONDS)!!.send("""{"v":1,"kind":"ctrl","type":"device.revoked"}""")
        awaitState(LomRelayState.Revoked)
        assertNull(store.pairing())
        val before = requests.size
        Thread.sleep(1500)
        assertEquals(before, requests.size)
    }

    @Test fun `换令牌得到 device_revoked 同样清除配对`() {
        tokenResponse = { MockResponse().setResponseCode(401).setBody("""{"success":false,"code":"relay.device_revoked","error":"x"}""") }
        relay.start(); awaitState(LomRelayState.Revoked)
        assertNull(store.pairing())
        assertEquals(0, requests.count { it.path == "/v1/relay" })
    }

    @Test fun `握手 401 device_revoked 也走撤销流程`() {
        upgradeStatus = 401
        relay.start(); awaitState(LomRelayState.Revoked)
        assertNull(store.pairing())
    }

    @Test fun `握手 401 unauthorized 只是重连的理由 不清除配对`() {
        upgradeStatus = 401
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return if (request.path == "/v1/relay/token") tokenResponse()
                else MockResponse().setResponseCode(401).setBody("""{"success":false,"code":"relay.unauthorized","error":"x"}""")
            }
        }
        relay.start()
        Thread.sleep(1500)
        assertTrue(store.pairing() != null)
        assertTrue(requests.count { it.path == "/v1/relay/token" } >= 1)
        relay.stop()
    }

    @Test fun `连接被服务端关闭后会重新换令牌再连`() {
        relay.start(); awaitState(LomRelayState.Online)
        sockets.poll(5, TimeUnit.SECONDS)!!.close(1001, "bye")
        awaitState(LomRelayState.Offline)
        relay.reconnectNow()
        awaitState(LomRelayState.Online)
        assertEquals(2, requests.count { it.path == "/v1/relay/token" })
        assertTrue(store.pairing() != null)
    }

    @Test fun `未配对不启动 停止后回到 Stopped`() {
        store.clearPairing()
        relay.start()
        assertEquals(LomRelayState.Stopped, relay.state.value)
        store.savePairing(paired)
        relay.start(); awaitState(LomRelayState.Online)
        relay.stop()
        assertEquals(LomRelayState.Stopped, relay.state.value)
    }

    @Test fun `退避序列是 1 2 5 10 30 秒 加减 20 抖动`() {
        val expected = listOf(1 to 1000, 2 to 2000, 3 to 5000, 4 to 10_000, 5 to 30_000, 9 to 30_000)
        for ((n, base) in expected) repeat(50) {
            val ms = relay.backoffMs(n)
            assertTrue("$n: $ms", ms >= base * 0.8 - 1 && ms <= base * 1.2 + 1)
        }
    }
}
