package com.wangzan101.mika.core.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RelayQrTest {
    private val code = "AbCdEfGh12345678"

    @Test fun parsesGatewayAndCodeFromPairingQr() {
        val parsed = RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=$code")
        assertEquals("https://relay.example.com/", parsed?.gateway)
        assertEquals(code, parsed?.code)
        assertEquals(RelayQr.parse("mika://pair?url=https%3A%2F%2Frelay.example.com&code=$code")?.gateway, parsed?.gateway)
    }

    @Test fun acceptsOnlyTheSameGatewayAddressAsManualEntry() {
        assertNull(RelayQr.parse("mika://relay?url=http%3A%2F%2Frelay.example.com&code=$code"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Fuser%3Apass%40relay.example.com&code=$code"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com%2Fadmin&code=$code"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com%3Fkey%3Dsecret&code=$code"))
        assertEquals("http://127.0.0.1:8787/", RelayQr.parse("mika://relay?url=http%3A%2F%2F127.0.0.1%3A8787&code=$code", allowLoopbackHttp = true)?.gateway)
    }

    @Test fun rejectsWrongShapeOrIncompleteCodes() {
        assertNull(RelayQr.parse("https://relay.example.com/?code=$code"))
        assertNull(RelayQr.parse("mika://other?url=https%3A%2F%2Frelay.example.com&code=$code"))
        assertNull(RelayQr.parse("mika://relay/path?url=https%3A%2F%2Frelay.example.com&code=$code"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=short"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=${"a".repeat(65)}"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=$code&code=$code"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=${code.substring(0, 4)}%20${code.substring(4)}"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=$code#extra"))
        assertNull(RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=$code".padEnd(4096, 'a')))
    }

    @Test fun pairingCodeIsNotExposedByToString() {
        val parsed = RelayQr.parse("mika://relay?url=https%3A%2F%2Frelay.example.com&code=$code")!!
        assertFalse(parsed.toString().contains(code))
        assertFalse(parsed.toString().contains("AbCdEf"))
    }
}
