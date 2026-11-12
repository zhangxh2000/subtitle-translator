package com.zhangxh.subtitletranslator.domain.entitlement

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestQuotaPolicyTest {

    @Test
    fun `guest word quota blocks the thirty first word`() {
        assertTrue(GuestQuotaPolicy.canAdd(Plan.GUEST, QuotaKind.WORD, 29))
        assertFalse(GuestQuotaPolicy.canAdd(Plan.GUEST, QuotaKind.WORD, 30))
        assertEquals(0, GuestQuotaPolicy.remaining(Plan.GUEST, QuotaKind.WORD, 30))
    }

    @Test
    fun `guest phrase quota blocks the twenty first phrase`() {
        assertTrue(GuestQuotaPolicy.canAdd(Plan.GUEST, QuotaKind.PHRASE, 19))
        assertFalse(GuestQuotaPolicy.canAdd(Plan.GUEST, QuotaKind.PHRASE, 20))
        assertEquals(1, GuestQuotaPolicy.remaining(Plan.GUEST, QuotaKind.PHRASE, 19))
    }

    @Test
    fun `existing over quota data does not become negative`() {
        assertEquals(0, GuestQuotaPolicy.remaining(Plan.GUEST, QuotaKind.WORD, 35))
        assertFalse(GuestQuotaPolicy.canAdd(Plan.GUEST, QuotaKind.WORD, 35))
    }

    @Test
    fun `free and member accounts are unlimited`() {
        assertTrue(GuestQuotaPolicy.canAdd(Plan.FREE, QuotaKind.WORD, 1_000))
        assertTrue(GuestQuotaPolicy.canAdd(Plan.MEMBER, QuotaKind.PHRASE, 1_000))
        assertNull(GuestQuotaPolicy.remaining(Plan.FREE, QuotaKind.WORD, 1_000))
        assertNull(GuestQuotaPolicy.remaining(Plan.MEMBER, QuotaKind.PHRASE, 1_000))
    }
}
