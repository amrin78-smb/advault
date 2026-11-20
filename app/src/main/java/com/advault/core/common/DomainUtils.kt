package com.advault.core.common

/** Domain normalisation and validation shared by every module. */
object DomainUtils {
    private val LABEL = Regex("^[a-z0-9_]([a-z0-9_-]{0,61}[a-z0-9_])?$")

    /** Lower-cases, trims and strips a trailing dot. Returns null if the result is not a valid hostname. */
    fun normalize(raw: String): String? {
        var d = raw.trim().lowercase()
        if (d.endsWith('.')) d = d.dropLast(1)
        return if (isValidDomain(d)) d else null
    }

    fun isValidDomain(d: String): Boolean {
        if (d.isEmpty() || d.length > 253) return false
        val labels = d.split('.')
        if (labels.size < 2) return false
        if (labels.all { it.all(Char::isDigit) }) return false // bare IPv4
        return labels.all { LABEL.matches(it) }
    }
}
