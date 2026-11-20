package com.advault.feature.common

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Pure formatting helpers (no Android dependencies, unit-testable). */
object Format {

    /** 1284 -> "1,284" (locale grouping). */
    fun count(value: Long, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)

    fun count(value: Int, locale: Locale = Locale.getDefault()): String = count(value.toLong(), locale)

    /** Compact count for tight chips: 950 -> "950", 12_400 -> "12.4K", 3_200_000 -> "3.2M". */
    fun compactCount(value: Long, locale: Locale = Locale.getDefault()): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(locale, "%.1fK", value / 1_000.0).replace(".0K", "K")
        value < 1_000_000_000 -> String.format(locale, "%.1fM", value / 1_000_000.0).replace(".0M", "M")
        else -> String.format(locale, "%.1fB", value / 1_000_000_000.0).replace(".0B", "B")
    }

    /** Decimal (SI) byte formatting: 38_500_000 -> "38.5 MB". */
    fun bytes(value: Long, locale: Locale = Locale.getDefault()): String {
        if (value < 1000) return "$value B"
        val units = listOf("kB", "MB", "GB", "TB")
        var v = value.toDouble()
        var unit = -1
        while (v >= 1000 && unit < units.lastIndex) { v /= 1000; unit++ }
        val pattern = if (v >= 100) "%.0f %s" else "%.1f %s"
        return String.format(locale, pattern, v, units[unit])
    }

    /** "≈ 38.5 MB" — the approximation sign is part of every estimate we display. */
    fun estimateBytes(value: Long, locale: Locale = Locale.getDefault()): String = "≈ " + bytes(value, locale)

    /**
     * Human relative time. Returns null for [then] <= 0 so callers can show "Never".
     * Future timestamps (clock skew) are treated as "just now".
     */
    fun relativeTime(then: Long, now: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String? {
        if (then <= 0) return null
        val diff = (now - then).coerceAtLeast(0)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        val hours = TimeUnit.MILLISECONDS.toHours(diff)
        val days = TimeUnit.MILLISECONDS.toDays(diff)
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            hours < 24 -> "$hours h ago"
            days < 7 -> if (days == 1L) "yesterday" else "$days days ago"
            else -> SimpleDateFormat("d MMM yyyy", locale).format(Date(then))
        }
    }

    /** Duration since [since], e.g. "2 h 14 min". */
    fun duration(since: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - since).coerceAtLeast(0)
        val h = TimeUnit.MILLISECONDS.toHours(diff)
        val m = TimeUnit.MILLISECONDS.toMinutes(diff) % 60
        val d = h / 24
        return when {
            d > 0 -> "$d d ${h % 24} h"
            h > 0 -> "$h h $m min"
            m > 0 -> "$m min"
            else -> "<1 min"
        }
    }

    fun time(ts: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("HH:mm:ss", locale).format(Date(ts))

    fun dateTime(ts: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("d MMM yyyy, HH:mm", locale).format(Date(ts))

    /** Day header for activity grouping, e.g. "Mon 18 Nov". */
    fun day(ts: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("EEE d MMM", locale).format(Date(ts))

    fun percent(fraction: Double, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.0f%%", (fraction * 100).coerceIn(0.0, 100.0))

    /** Short weekday label ("Mon") for a UTC day number (days since epoch). */
    fun weekday(dayEpoch: Long, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat("EEE", locale).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(Date(dayEpoch * 86_400_000L))

    /** DNS QTYPE number to mnemonic. */
    fun queryType(qtype: Int): String = when (qtype) {
        1 -> "A"; 2 -> "NS"; 5 -> "CNAME"; 6 -> "SOA"; 12 -> "PTR"; 15 -> "MX"; 16 -> "TXT"
        28 -> "AAAA"; 33 -> "SRV"; 64 -> "SVCB"; 65 -> "HTTPS"
        else -> "TYPE$qtype"
    }
}
