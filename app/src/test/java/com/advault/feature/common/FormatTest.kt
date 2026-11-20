package com.advault.feature.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormatTest {
    private val us = Locale.US

    @Test fun bytes() {
        assertEquals("0 B", Format.bytes(0, us))
        assertEquals("999 B", Format.bytes(999, us))
        assertEquals("1.5 kB", Format.bytes(1_500, us))
        assertEquals("38.5 MB", Format.bytes(38_500_000, us))
        assertEquals("250 MB", Format.bytes(250_000_000, us))
        assertEquals("≈ 1.2 GB", Format.estimateBytes(1_200_000_000, us))
    }

    @Test fun counts() {
        assertEquals("1,284", Format.count(1284L, us))
        assertEquals("950", Format.compactCount(950, us))
        assertEquals("12.4K", Format.compactCount(12_400, us))
        assertEquals("3M", Format.compactCount(3_000_000, us))
    }

    @Test fun relativeTime() {
        val now = 1_000_000_000_000L
        assertNull(Format.relativeTime(0, now, us))
        assertEquals("just now", Format.relativeTime(now - 10_000, now, us))
        assertEquals("5 min ago", Format.relativeTime(now - 5 * 60_000, now, us))
        assertEquals("3 h ago", Format.relativeTime(now - 3 * 3_600_000, now, us))
        assertEquals("yesterday", Format.relativeTime(now - 26 * 3_600_000, now, us))
        assertEquals("just now", Format.relativeTime(now + 60_000, now, us))
    }

    @Test fun queryTypes() {
        assertEquals("AAAA", Format.queryType(28))
        assertEquals("TYPE99", Format.queryType(99))
    }
}
