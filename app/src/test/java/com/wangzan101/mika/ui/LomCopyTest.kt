package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LomCopyTest {
    /** 公开文档 08 表 A、表 C 里手机会遇到的错误码,每个都要有专门文案,而不是落到通用提示。 */
    private val documented = listOf(
        "pairing.qr_invalid", "pairing.not_found", "pairing.expired", "pairing.cancelled", "pairing.failed",
        "pairing.already_claimed", "pairing.awaiting_desktop", "pairing.secret_invalid", "pairing.code_invalid",
        "pairing.request_invalid", "pairing.rate_limited", "device.owned_by_other_account", "sub.phone_limit",
        "pairing.key_mismatch", "pairing.already_paired", "pairing.desktop_timeout", "pairing.store_failed",
        "pairing.response_invalid", "net.unreachable", "server.internal",
    )

    @Test
    fun everyDocumentedCodeHasItsOwnText() {
        val generic = LomCopy.error("no.such_code")
        for (code in documented) assertNotEquals(code, generic, LomCopy.error(code))
    }

    @Test
    fun unknownCodeNeverEchoesServerText() {
        assertEquals(LomCopy.error("x.y"), LomCopy.error("sk-live_secret.token"))
    }

    @Test
    fun noticeNamesTheDesktopOnlyWhenKnown() {
        assertTrue(LomCopy.notifBody("书房电脑").contains("书房电脑"))
        assertTrue(!LomCopy.notifBody("").contains("「"))
        assertTrue(LomCopy.doneBody("").contains("麓鸣电脑"))
    }

    @Test
    fun drawerLabelShowsDesktopNameOrGenericName() {
        assertEquals("麓鸣电脑", LomCopy.drawerLabel(null))
        assertEquals("麓鸣电脑", LomCopy.drawerLabel(""))
        assertEquals("麓鸣电脑", LomCopy.drawerLabel("  "))
        assertEquals("书房电脑", LomCopy.drawerLabel("书房电脑"))
    }
}
