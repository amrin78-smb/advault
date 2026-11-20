package com.advault.vpn.core.upstream

import com.advault.core.preferences.UpstreamPreset
import com.advault.core.preferences.UpstreamProtocol
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class UpstreamResult(val response: ByteArray, val server: String, val latencyMs: Int)

/** Receives per-attempt health signals (for diagnostics). */
interface UpstreamHealthListener {
    fun onSuccess(server: String, latencyMs: Int)
    fun onFailure(server: String, error: Throwable)

    object None : UpstreamHealthListener {
        override fun onSuccess(server: String, latencyMs: Int) {}
        override fun onFailure(server: String, error: Throwable) {}
    }
}

/** What the handler needs from the upstream side. */
interface Upstream {
    val description: String
    /** Blocking. Throws [UpstreamException] when every attempted server failed. */
    fun resolve(query: ByteArray, timeoutMs: Int): UpstreamResult
}

/**
 * Tries transports in order starting at the last one that worked; each attempt gets the full timeout.
 * At most [maxAttempts] servers are tried per query so a client is not kept waiting indefinitely.
 */
class UpstreamResolver(
    private val transports: List<DnsTransport>,
    override val description: String,
    private val health: UpstreamHealthListener = UpstreamHealthListener.None,
    private val maxAttempts: Int = 3,
) : Upstream, Closeable {
    private val preferred = AtomicInteger(0)

    override fun resolve(query: ByteArray, timeoutMs: Int): UpstreamResult {
        if (transports.isEmpty()) throw UpstreamException("No upstream servers configured")
        val start = preferred.get()
        var last: Throwable? = null
        val attempts = minOf(transports.size, maxAttempts)
        for (i in 0 until attempts) {
            val idx = (start + i) % transports.size
            val t = transports[idx]
            val began = System.nanoTime()
            try {
                val resp = t.exchange(query, timeoutMs)
                val ms = ((System.nanoTime() - began) / 1_000_000L).toInt()
                preferred.set(idx)
                health.onSuccess(t.description, ms)
                return UpstreamResult(resp, t.description, ms)
            } catch (e: Exception) {
                health.onFailure(t.description, e)
                last = e
            }
        }
        throw UpstreamException("All upstream servers failed (${last?.javaClass?.simpleName}: ${last?.message})", last)
    }

    override fun close() { transports.forEach { runCatching { it.close() } } }
}

/** Resolved, validated upstream configuration. */
data class UpstreamSpec(
    val protocol: UpstreamProtocol,
    val description: String,
    /** PLAIN: server endpoints. */
    val servers: List<InetSocketAddress> = emptyList(),
    /** DOT: TLS hostname; DOH: URL. */
    val endpoint: String? = null,
    /** DOT/DOH bootstrap IPs. */
    val bootstrap: List<InetAddress> = emptyList(),
    val notes: List<String> = emptyList(),
)

object UpstreamConfig {

    /**
     * Builds the upstream spec from settings. [systemDns] are the underlying (non-VPN) network's DNS servers.
     * Invalid custom input or an empty SYSTEM list fall back to Cloudflare with a diagnostic note.
     */
    fun resolve(
        preset: UpstreamPreset,
        customProtocol: UpstreamProtocol,
        customValue: String,
        systemDns: List<InetAddress>,
    ): UpstreamSpec = when (preset) {
        UpstreamPreset.SYSTEM -> {
            val servers = systemDns.filterNot { it.isAnyLocalAddress || it.isLoopbackAddress }
            if (servers.isEmpty()) fallback("Network DNS servers unknown (no network yet?)")
            else UpstreamSpec(
                UpstreamProtocol.PLAIN, "Network DNS (${servers.joinToString { it.hostAddress ?: "?" }})",
                servers = servers.map { InetSocketAddress(it, 53) },
            )
        }
        UpstreamPreset.CUSTOM -> custom(customProtocol, customValue)
        else -> fromPreset(preset)
    }

    fun fromPreset(preset: UpstreamPreset): UpstreamSpec {
        val ips = preset.addresses.mapNotNull { parseIpLiteral(it) }
        return when (preset.protocol) {
            UpstreamProtocol.PLAIN -> UpstreamSpec(UpstreamProtocol.PLAIN, preset.displayName, servers = ips.map { InetSocketAddress(it, 53) })
            UpstreamProtocol.DOT -> UpstreamSpec(UpstreamProtocol.DOT, preset.displayName, endpoint = preset.endpoint, bootstrap = ips)
            UpstreamProtocol.DOH -> UpstreamSpec(UpstreamProtocol.DOH, preset.displayName, endpoint = preset.endpoint, bootstrap = ips)
        }
    }

    private fun fallback(reason: String): UpstreamSpec =
        fromPreset(UpstreamPreset.CLOUDFLARE).let { it.copy(notes = it.notes + "$reason; using Cloudflare 1.1.1.1 instead.") }

    fun custom(protocol: UpstreamProtocol, raw: String): UpstreamSpec {
        val value = raw.trim()
        if (value.isEmpty()) return fallback("Custom upstream is empty")
        return when (protocol) {
            UpstreamProtocol.PLAIN -> {
                val parts = value.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
                val servers = parts.map { parseEndpoint(it, 53) }
                if (servers.any { it == null } || servers.isEmpty() || servers.size > 8) {
                    fallback("Custom DNS servers '$value' are not valid IP addresses")
                } else UpstreamSpec(UpstreamProtocol.PLAIN, "Custom DNS (${parts.joinToString()})", servers = servers.filterNotNull())
            }
            UpstreamProtocol.DOT -> {
                val host = value.removePrefix("tls://").substringBefore('/').lowercase()
                val hostOnly = host.substringBefore(':')
                if (!isValidHostname(hostOnly)) fallback("Custom DNS-over-TLS host '$value' is invalid")
                else UpstreamSpec(UpstreamProtocol.DOT, "Custom DoT ($hostOnly)", endpoint = hostOnly)
            }
            UpstreamProtocol.DOH -> {
                val url = runCatching { URI(value).toURL() }.getOrNull()
                if (url == null || url.protocol != "https" || url.host.isNullOrEmpty() ||
                    !(isValidHostname(url.host) || parseIpLiteral(url.host.trim('[', ']')) != null)
                ) fallback("Custom DNS-over-HTTPS URL '$value' is invalid (must be https://)")
                else UpstreamSpec(UpstreamProtocol.DOH, "Custom DoH (${url.host})", endpoint = url.toString())
            }
        }
    }

    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")
    private val HOST_LABEL = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")

    /** Parses an IP literal without ever performing a DNS lookup. */
    fun parseIpLiteral(s: String): InetAddress? {
        val v = s.trim()
        if (IPV4.matches(v)) return runCatching { InetAddress.getByName(v) }.getOrNull()
        if (v.contains(':') && IPV6_CHARS.matches(v) && v.count { it == ':' } in 2..7) {
            return runCatching { InetAddress.getByName(v) }.getOrNull()
        }
        return null
    }

    /** "1.2.3.4", "1.2.3.4:5353", "2001:db8::1", "[2001:db8::1]:5353". */
    fun parseEndpoint(s: String, defaultPort: Int): InetSocketAddress? {
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null
            val ip = parseIpLiteral(s.substring(1, close)) ?: return null
            val rest = s.substring(close + 1)
            val port = if (rest.isEmpty()) defaultPort else rest.removePrefix(":").toIntOrNull() ?: return null
            return if (port in 1..65535) InetSocketAddress(ip, port) else null
        }
        parseIpLiteral(s)?.let { return InetSocketAddress(it, defaultPort) }
        val colon = s.lastIndexOf(':')
        if (colon > 0 && s.count { it == ':' } == 1) {
            val ip = parseIpLiteral(s.substring(0, colon)) as? Inet4Address ?: return null
            val port = s.substring(colon + 1).toIntOrNull() ?: return null
            return if (port in 1..65535) InetSocketAddress(ip, port) else null
        }
        return null
    }

    fun isValidHostname(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        val labels = h.split('.')
        if (labels.size < 2 || labels.all { l -> l.all(Char::isDigit) }) return false
        return labels.all { HOST_LABEL.matches(it) }
    }

    /** For preset DoH endpoints: the same path on each bootstrap IP (certificates of the presets carry IP SANs). */
    fun dohFallbackUrls(spec: UpstreamSpec): List<URL> {
        val base = spec.endpoint?.let { runCatching { URI(it).toURL() }.getOrNull() } ?: return emptyList()
        return spec.bootstrap.mapNotNull { ip ->
            val host = ip.hostAddress?.substringBefore('%') ?: return@mapNotNull null
            // java.net.URI brackets IPv6 literals itself.
            runCatching { URI("https", null, host, base.port, base.path, base.query, null).toURL() }.getOrNull()
        }
    }

    fun buildResolver(
        spec: UpstreamSpec,
        protector: SocketProtector,
        health: UpstreamHealthListener,
        dohOpener: ((URL) -> java.net.HttpURLConnection)? = null,
    ): UpstreamResolver {
        val transports: List<DnsTransport> = when (spec.protocol) {
            UpstreamProtocol.PLAIN -> spec.servers.map { UdpTransport(it, protector) }
            UpstreamProtocol.DOT -> listOf(TlsTransport(spec.endpoint!!, spec.bootstrap, protector = protector))
            UpstreamProtocol.DOH -> {
                val url = URI(spec.endpoint!!).toURL()
                val fallbacks = dohFallbackUrls(spec)
                listOf(
                    if (dohOpener != null) HttpsTransport(url, fallbacks, dohOpener) else HttpsTransport(url, fallbacks)
                )
            }
        }
        return UpstreamResolver(transports, spec.description, health)
    }
}
