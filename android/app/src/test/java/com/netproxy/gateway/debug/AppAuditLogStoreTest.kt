package com.netproxy.gateway.debug

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppAuditLogStoreTest {

    @Before
    fun setUp() {
        AppAuditLogStore.clear()
    }

    @After
    fun tearDown() {
        AppAuditLogStore.clear()
    }

    @Test
    fun info_addsEntryWithExpectedLevel() {
        AppAuditLogStore.info("UI", "Open settings")

        val entries = AppAuditLogStore.entries.value
        assertEquals(1, entries.size)
        assertTrue(entries[0].id > 0)
        assertEquals(AuditLogLevel.INFO, entries[0].level)
        assertEquals("UI", entries[0].tag)
        assertEquals("Open settings", entries[0].message)
    }

    @Test
    fun clear_removesAllEntries() {
        AppAuditLogStore.warn("Settings", "Changed switch")

        AppAuditLogStore.clear()

        assertTrue(AppAuditLogStore.entries.value.isEmpty())
    }

    @Test
    fun append_overCapacity_keepsMostRecentEntries() {
        repeat(321) { index ->
            AppAuditLogStore.info("Load", "log-$index")
        }

        val entries = AppAuditLogStore.entries.value
        assertEquals(300, entries.size)
        assertEquals("log-21", entries.first().message)
        assertEquals("log-320", entries.last().message)
    }

    @Test
    fun append_assignsUniqueIncreasingIds() {
        AppAuditLogStore.info("UI", "first")
        AppAuditLogStore.info("UI", "second")

        val entries = AppAuditLogStore.entries.value
        assertEquals(2, entries.size)
        assertTrue(entries[0].id < entries[1].id)
    }

    @Test
    fun append_sanitizesLineBreaks() {
        AppAuditLogStore.error("MQTT", "line1\nline2\rline3")

        val message = AppAuditLogStore.entries.value.last().message
        assertFalse(message.contains('\n'))
        assertFalse(message.contains('\r'))
    }
}
