package com.wangzan101.mika.core.lom

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class LomPairingFlowTest {
    private lateinit var server: MockWebServer
    private val routes = HashMap<String, () -> MockResponse>()
    private val seen = mutableListOf<RecordedRequest>()
    private val pauses = mutableListOf<Long>()
    private lateinit var store: FakeLomStore
    private lateinit var flow: LomPairingFlow
    private val case0 = Vectors.pairing["cases"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject }
    private val qr = case0.getValue("qr").jsonPrimitive.content
    private val desktopKeys = case0.getValue("desktop").jsonObject.getValue("keys").jsonPrimitive.content
    private val pid = case0.getValue("pairing_id").jsonPrimitive.content
    private val token = case0.getValue("claim_token").jsonPrimitive.content
    private val psk = Base64Url.decode(case0.getValue("psk").jsonPrimitive.content)!!
    private var now = 1_800_000_000_000L

    private fun ok(data: String) = MockResponse().setBody("""{"success":true,"data":$data}""")
    private fun fail(code: String, status: Int) = MockResponse().setResponseCode(status).setBody("""{"success":false,"code":"$code","error":"x"}""")

    @Before fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return routes["${request.method} ${request.path}"]?.invoke() ?: MockResponse().setResponseCode(404).setBody("nope")
            }
        }
        server.start()
        store = FakeLomStore()
        flow = flowOf(store)
    }

    private fun flowOf(s: LomStore) = LomPairingFlow(
        LomCloud(endpoint = { server.url("/") }), s,
        { LomDeviceInfo("我的 Pixel", "Pixel 8", "Android 15", "1.2.3") }, { now }, { pauses += it },
    )

    @After fun tearDown() = server.shutdown()

    private fun expires() = (now / 1000 + 300)

    private fun scanRoutes(confirm: () -> MockResponse) {
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { ok("""{"pairing_id":"$pid","desktop_name":"书房电脑","desktop_keys":"$desktopKeys","masked_account":"a***@example.com","expires_at":${expires()}}""") }
        routes["POST /v1/pairing/sessions/$pid/claim"] = { ok("""{"claim_secret":"secret-1","expires_at":${expires()}}""") }
        routes["POST /v1/pairing/sessions/$pid/confirm"] = confirm
    }

    private val confirmOk = ok("""{"device_id":"dev_${"0123456789abcdef".repeat(2)}","device_token":"${Base64Url.encode(ByteArray(32) { 5 })}","token_expires_at":${1_900_000_000},"desktop_name":"书房电脑"}""")

    private fun code(block: () -> Unit): String = try { block(); "" } catch (e: LomException) { e.code }

    @Test fun `扫码路径 认领带正确的 key_proof 确认后保存凭据`() = runBlocking {
        scanRoutes { confirmOk }
        val pending = flow.beginWithLink(qr)
        assertEquals("书房电脑", pending.desktopName)
        assertEquals("a***@example.com", pending.maskedAccount)
        assertNull("扫码路径不显示确认码", pending.sas)
        val claim = seen.first { it.path!!.endsWith("/claim") }
        val body = Json.parseToJsonElement(claim.body.readUtf8()).jsonObject
        assertEquals(store.identity().keys, body.getValue("device_keys").jsonPrimitive.content)
        assertEquals(LomKeys.keyProof(psk, pid, desktopKeys, store.identity().keys), body.getValue("key_proof").jsonPrimitive.content)
        assertEquals(token, body.getValue("claim_token").jsonPrimitive.content)
        assertEquals("Pixel 8", body.getValue("device_info").jsonObject.getValue("model").jsonPrimitive.content)
        assertTrue("确认之前不保存", store.pairing() == null)

        val paired = flow.confirm(pending)
        assertEquals(paired.deviceId, store.pairing()!!.deviceId)
        assertEquals(setOf(desktopKeys), store.pairing()!!.trustedKeys)
        assertEquals("书房电脑", store.pairing()!!.desktops.single().name)
        // 令牌/口令不进 URL
        assertTrue(seen.none { it.path!!.contains(token) || it.path!!.contains("secret-1") })
    }

    @Test fun `awaiting_desktop 每秒重试 最终成功`() = runBlocking {
        var n = 0
        scanRoutes { if (++n <= 3) fail("pairing.awaiting_desktop", 409) else confirmOk }
        flow.confirm(flow.beginWithLink(qr))
        assertEquals(listOf(1000L, 1000L, 1000L), pauses)
        assertEquals(4, seen.count { it.path!!.endsWith("/confirm") })
        assertTrue(store.pairing() != null)
    }

    @Test fun `awaiting_desktop 重试 15 次后超时`() = runBlocking {
        scanRoutes { fail("pairing.awaiting_desktop", 409) }
        val pending = flow.beginWithLink(qr)
        val c = try { flow.confirm(pending); "" } catch (e: LomException) { e.code }
        assertEquals("pairing.desktop_timeout", c)
        assertEquals(16, seen.count { it.path!!.endsWith("/confirm") })
        assertEquals(15, pauses.size)
        assertNull(store.pairing())
    }

    @Test fun `指纹不一致时在 claim 之前中止`() = runBlocking {
        scanRoutes { confirmOk }
        val other = case0.getValue("phone").jsonObject.getValue("keys").jsonPrimitive.content
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { ok("""{"pairing_id":"$pid","desktop_name":"x","desktop_keys":"$other","masked_account":"","expires_at":${expires()}}""") }
        val c = try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code }
        assertEquals("pairing.key_mismatch", c)
        assertTrue(seen.none { it.path!!.endsWith("/claim") })
    }

    @Test fun `inspect 回显的 pairing_id 不一致时中止`() = runBlocking {
        scanRoutes { confirmOk }
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { ok("""{"pairing_id":"${"A".repeat(22)}","desktop_name":"x","desktop_keys":"$desktopKeys","masked_account":"","expires_at":${expires()}}""") }
        assertEquals("pairing.key_mismatch", try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code })
    }

    @Test fun `无效二维码不发任何网络请求`() = runBlocking {
        assertEquals("pairing.qr_invalid", try { flow.beginWithLink(qr.replace("v=1", "v=2")); "" } catch (e: LomException) { e.code })
        assertEquals("pairing.qr_invalid", try { flow.beginWithLink("hello"); "" } catch (e: LomException) { e.code })
        assertEquals(0, server.requestCount)
    }

    @Test fun `已配对时拒绝再次配对且不发请求`() = runBlocking {
        val paired = LomPaired("lom.entermodetwo.com", "dev_x", "t", 1, listOf(LomDesktop(desktopKeys, "n", 1)))
        val f = flowOf(FakeLomStore(initial = paired))
        assertEquals("pairing.already_paired", try { f.beginWithLink(qr); "" } catch (e: LomException) { e.code })
        assertEquals("pairing.already_paired", try { f.beginWithCode("AB3D-9XK2"); "" } catch (e: LomException) { e.code })
        assertEquals(0, server.requestCount)
    }

    @Test fun `配对码路径 本地算出确认码 用户确认才 confirm`() = runBlocking {
        routes["POST /v1/pairing/claim-by-code"] = { ok("""{"pairing_id":"$pid","desktop_name":"书房电脑","desktop_keys":"$desktopKeys","masked_account":"a***@example.com","expires_at":${expires()},"claim_secret":"s2"}""") }
        routes["POST /v1/pairing/sessions/$pid/confirm"] = { confirmOk }
        val pending = flow.beginWithCode("ab3d9xk2")
        assertEquals(LomKeys.sas(pid, desktopKeys, store.identity().keys), pending.sas)
        assertTrue(Regex("\\d{6}").matches(pending.sas!!))
        val body = Json.parseToJsonElement(seen.single().body.readUtf8()).jsonObject
        assertEquals("AB3D-9XK2", body.getValue("manual_code").jsonPrimitive.content)
        assertFalse("还没确认就不该调 confirm", seen.any { it.path!!.endsWith("/confirm") })
        flow.confirm(pending)
        assertTrue(store.pairing() != null)
    }

    @Test fun `配对码格式不对本地中止不发请求`() = runBlocking {
        assertEquals("pairing.code_invalid", try { flow.beginWithCode("AB3D-9XK0"); "" } catch (e: LomException) { e.code })
        assertEquals(0, server.requestCount)
    }

    @Test fun `落盘失败报 store_failed 可以再次确认`() = runBlocking {
        scanRoutes { confirmOk }
        val pending = flow.beginWithLink(qr)
        store.failSave = true
        assertEquals("pairing.store_failed", try { flow.confirm(pending); "" } catch (e: LomException) { e.code })
        assertNull(store.pairing())
        store.failSave = false
        flow.confirm(pending)
        assertTrue(store.pairing() != null)
    }

    @Test fun `会话过期后确认按钮失效`() = runBlocking {
        scanRoutes { confirmOk }
        val pending = flow.beginWithLink(qr)
        now += 301_000
        assertEquals("pairing.expired", try { flow.confirm(pending); "" } catch (e: LomException) { e.code })
        assertEquals(0, seen.count { it.path!!.endsWith("/confirm") })
    }

    @Test fun `云端错误码原样透传 非 JSON 和断网各有本地码`() = runBlocking {
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { fail("pairing.expired", 410) }
        assertEquals("pairing.expired", try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code })
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>") }
        assertEquals("pairing.response_invalid", try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code })
        routes["POST /v1/pairing/sessions/$pid/inspect"] = { ok("""{"pairing_id":"$pid"}""") }
        assertEquals("pairing.response_invalid", try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code })
        server.shutdown()
        assertEquals("net.unreachable", try { flow.beginWithLink(qr); "" } catch (e: LomException) { e.code })
    }

    @Test fun `confirm 网络失败最多额外重试 3 次`() = runBlocking {
        scanRoutes { confirmOk }
        val pending = flow.beginWithLink(qr)
        server.shutdown()
        assertEquals("net.unreachable", try { flow.confirm(pending); "" } catch (e: LomException) { e.code })
        assertEquals(3, pauses.size)
    }

    @Test fun `解除配对 通知云端并清除本地`() = runBlocking {
        val paired = LomPaired("lom.entermodetwo.com", "dev_abc", "tok", 1, listOf(LomDesktop(desktopKeys, "n", 1)))
        val s = FakeLomStore(initial = paired)
        routes["DELETE /v1/devices/me"] = { MockResponse().setBody("""{"success":true}""") }
        assertTrue(flowOf(s).unpair())
        assertEquals("Device dev_abc.tok", seen.single().getHeader("Authorization"))
        assertNull(s.pairing())
    }

    @Test fun `解除配对时云端不可达 仍清除本地并告知没通知上`() = runBlocking {
        val s = FakeLomStore(initial = LomPaired("lom.entermodetwo.com", "dev_abc", "tok", 1, listOf(LomDesktop(desktopKeys, "n", 1))))
        server.shutdown()
        assertFalse(flowOf(s).unpair())
        assertNull(s.pairing())
    }
}
