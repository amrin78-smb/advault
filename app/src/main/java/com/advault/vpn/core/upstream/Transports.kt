package com.advault.vpn.core.upstream

import com.advault.vpn.core.dns.DnsMessageBuilder
import com.advault.vpn.core.dns.DnsParser
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Excludes upstream sockets from the VPN (VpnService.protect). Our own package is also added with
 * addDisallowedApplication, so this is defence in depth against routing loops.
 */
interface SocketProtector {
    fun protect(socket: DatagramSocket): Boolean
    fun protect(socket: Socket): Boolean

    object None : SocketProtector {
        override fun protect(socket: DatagramSocket) = true
        override fun protect(socket: Socket) = true
    }
}

class UpstreamException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** One upstream server reachable over one protocol. Implementations must be thread-safe. */
interface DnsTransport : Closeable {
    val description: String
    /** Sends [query] (raw DNS message) and returns the full response. Blocking; throws on failure/timeout. */
    fun exchange(query: ByteArray, timeoutMs: Int): ByteArray
    override fun close() {}
}

/** Response validation shared by all transports. */
internal object ResponseCheck {
    /** True if [response] answers [query]: same ID, QR set, and identical question section (case-insensitive). */
    fun matches(query: ByteArray, response: ByteArray): Boolean {
        if (response.size < 12 || query.size < 12) return false
        if (DnsParser.u16(query, 0) != DnsParser.u16(response, 0)) return false
        if (response[2].toInt() and 0x80 == 0) return false
        val q = DnsParser.parseQuery(query) ?: return false
        val r = DnsParser.parseResponse(response) ?: return false
        // Some servers answer FORMERR/NOTIMP with an empty question section; accept those.
        if (r.questions.isEmpty()) return true
        if (r.questions.size != q.questions.size) return false
        return q.questions.indices.all { i ->
            val a = q.questions[i]; val b = r.questions[i]
            a.type == b.type && a.qclass == b.qclass && a.name.equals(b.name, ignoreCase = true)
        }
    }

    fun requireMatch(query: ByteArray, response: ByteArray, from: String) {
        if (!matches(query, response)) throw UpstreamException("Mismatched response from $from")
    }
}

/** RFC 1035 4.2.2 / RFC 7766 two-byte length framing used by DNS-over-TCP and DNS-over-TLS. */
object DnsStreamFraming {
    fun write(out: OutputStream, message: ByteArray) {
        require(message.size <= 0xFFFF) { "DNS message too large" }
        val framed = ByteArray(2 + message.size)
        framed[0] = (message.size ushr 8).toByte(); framed[1] = message.size.toByte()
        System.arraycopy(message, 0, framed, 2, message.size)
        out.write(framed)
        out.flush()
    }

    fun read(input: InputStream): ByteArray {
        val din = DataInputStream(input)
        val len = din.readUnsignedShort()
        if (len < 12) throw UpstreamException("Short framed DNS message ($len bytes)")
        val buf = ByteArray(len)
        din.readFully(buf)
        return buf
    }
}

/** Plain DNS over TCP, one connection per query (used for TC-bit fallback). */
class TcpTransport(
    private val server: InetSocketAddress,
    private val protector: SocketProtector = SocketProtector.None,
) : DnsTransport {
    override val description: String = "tcp://${hostLiteral(server)}"

    override fun exchange(query: ByteArray, timeoutMs: Int): ByteArray {
        Socket().use { s ->
            protector.protect(s)
            s.tcpNoDelay = true
            s.connect(server, timeoutMs)
            s.soTimeout = timeoutMs
            DnsStreamFraming.write(s.getOutputStream(), query)
            val resp = DnsStreamFraming.read(s.getInputStream())
            ResponseCheck.requireMatch(query, resp, description)
            return resp
        }
    }
}

/**
 * Plain DNS over UDP with a fresh, randomly-ported socket per query (spoofing resistance).
 * Truncated (TC) answers are retried over TCP so the client receives the complete response.
 */
class UdpTransport(
    private val server: InetSocketAddress,
    private val protector: SocketProtector = SocketProtector.None,
    private val tcpFallback: DnsTransport? = TcpTransport(server, protector),
) : DnsTransport {
    override val description: String = "udp://${hostLiteral(server)}"

    override fun exchange(query: ByteArray, timeoutMs: Int): ByteArray {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        val response = DatagramSocket().use { s -> receiveMatching(s, query, deadline) }
        if (response[2].toInt() and 0x02 != 0 && tcpFallback != null) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt().coerceAtLeast(timeoutMs / 2)
            return tcpFallback.exchange(query, remainingMs)
        }
        return response
    }

    private fun receiveMatching(s: DatagramSocket, query: ByteArray, deadline: Long): ByteArray {
        protector.protect(s)
        s.connect(server) // kernel filters datagrams from other sources
        s.send(DatagramPacket(query, query.size))
        val buf = ByteArray(65_535)
        while (true) {
            val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (remainingMs <= 0) throw SocketTimeoutException("Timed out waiting for $description")
            s.soTimeout = remainingMs
            val p = DatagramPacket(buf, buf.size)
            s.receive(p)
            val resp = buf.copyOf(p.length)
            if (ResponseCheck.matches(query, resp)) return resp
            // Ignore stray/mismatched datagrams and keep waiting until the deadline.
        }
    }
}

/**
 * DNS-over-TLS (RFC 7858) on port 853. Validates the certificate chain with the platform trust store and
 * verifies the server hostname. Idle connections are reused (small pool) and dropped after [idleTimeoutMs].
 */
class TlsTransport(
    private val hostname: String,
    /** Bootstrap addresses; if empty the hostname is resolved via the system resolver (we bypass our own VPN). */
    private val bootstrap: List<InetAddress>,
    private val port: Int = 853,
    private val protector: SocketProtector = SocketProtector.None,
    private val sslFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val maxIdle: Int = 4,
    private val idleTimeoutMs: Long = 20_000,
) : DnsTransport {
    override val description: String = "tls://$hostname:$port"

    private class Conn(val socket: SSLSocket, var lastUsed: Long)

    private val idle = ArrayDeque<Conn>()
    @Volatile private var closed = false

    override fun exchange(query: ByteArray, timeoutMs: Int): ByteArray {
        // A pooled connection may have been closed by the server; retry once on a fresh one.
        val pooled = takeIdle()
        if (pooled != null) {
            try {
                return exchangeOn(pooled, query, timeoutMs)
            } catch (_: IOException) {
                runCatching { pooled.socket.close() }
            }
        }
        return exchangeOn(open(timeoutMs), query, timeoutMs)
    }

    private fun exchangeOn(c: Conn, query: ByteArray, timeoutMs: Int): ByteArray {
        try {
            c.socket.soTimeout = timeoutMs
            DnsStreamFraming.write(c.socket.outputStream, query)
            val resp = DnsStreamFraming.read(c.socket.inputStream)
            ResponseCheck.requireMatch(query, resp, description)
            c.lastUsed = System.currentTimeMillis()
            release(c)
            return resp
        } catch (e: IOException) {
            runCatching { c.socket.close() }
            throw e
        }
    }

    private fun open(timeoutMs: Int): Conn {
        val addrs = bootstrap.ifEmpty { resolver(hostname) }
        var last: Exception? = null
        for (addr in addrs) {
            val plain = Socket()
            try {
                protector.protect(plain)
                plain.tcpNoDelay = true
                plain.connect(InetSocketAddress(addr, port), timeoutMs)
                plain.soTimeout = timeoutMs
                val ssl = sslFactory.createSocket(plain, hostname, port, true) as SSLSocket
                ssl.startHandshake()
                if (!hostnameVerifier.verify(hostname, ssl.session)) {
                    ssl.close()
                    throw SSLPeerUnverifiedException("Certificate does not match $hostname")
                }
                return Conn(ssl, System.currentTimeMillis())
            } catch (e: Exception) {
                runCatching { plain.close() }
                last = e
            }
        }
        throw UpstreamException("Could not connect to $description", last)
    }

    private fun takeIdle(): Conn? = synchronized(idle) {
        val now = System.currentTimeMillis()
        while (idle.isNotEmpty()) {
            val c = idle.removeLast()
            if (!c.socket.isClosed && now - c.lastUsed < idleTimeoutMs) return c
            runCatching { c.socket.close() }
        }
        null
    }

    private fun release(c: Conn) {
        val keep = synchronized(idle) {
            if (!closed && idle.size < maxIdle) { idle.addLast(c); true } else false
        }
        if (!keep) runCatching { c.socket.close() }
    }

    override fun close() {
        closed = true
        synchronized(idle) { idle.forEach { runCatching { it.socket.close() } }; idle.clear() }
    }
}

/**
 * DNS-over-HTTPS (RFC 8484) using POST with `application/dns-message`. The transaction ID is sent as 0
 * (RFC 8484 4.1, cache friendly) and restored on the response. If the hostname cannot be resolved,
 * [fallbackUrls] (IP-literal URLs whose certificates carry IP SANs, e.g. https://1.1.1.1/dns-query) are tried.
 */
class HttpsTransport(
    private val url: URL,
    private val fallbackUrls: List<URL> = emptyList(),
    private val opener: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : DnsTransport {
    override val description: String = url.toString()
    @Volatile private var preferred = 0

    override fun exchange(query: ByteArray, timeoutMs: Int): ByteArray {
        val urls = listOf(url) + fallbackUrls
        var last: Exception? = null
        for (i in urls.indices) {
            val idx = (preferred + i) % urls.size
            try {
                val resp = post(urls[idx], query, timeoutMs)
                preferred = idx
                return resp
            } catch (e: Exception) {
                last = e
            }
        }
        throw UpstreamException("DoH request to $description failed", last)
    }

    private fun post(target: URL, query: ByteArray, timeoutMs: Int): ByteArray {
        val originalId = DnsParser.u16(query, 0)
        val body = encodeRequestBody(query)
        val conn = opener(target)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.doOutput = true
            conn.useCaches = false
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Content-Type", CONTENT_TYPE)
            conn.setRequestProperty("Accept", CONTENT_TYPE)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code != 200) {
                runCatching { conn.errorStream?.use { it.readBytes() } }
                throw UpstreamException("HTTP $code from $target")
            }
            val type = conn.contentType ?: ""
            if (!type.startsWith(CONTENT_TYPE, ignoreCase = true)) throw UpstreamException("Unexpected content type '$type'")
            val bytes = conn.inputStream.use { readLimited(it, 65_535) }
            val resp = DnsMessageBuilder.withId(bytes, originalId)
            ResponseCheck.requireMatch(query, resp, description)
            return resp
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val bytes = input.readBytes()
        if (bytes.size > limit || bytes.size < 12) throw UpstreamException("Invalid DoH body size ${bytes.size}")
        return bytes
    }

    companion object {
        const val CONTENT_TYPE = "application/dns-message"
        /** RFC 8484 POST body: the DNS message with ID 0. */
        fun encodeRequestBody(query: ByteArray): ByteArray = DnsMessageBuilder.withId(query, 0)
    }
}

internal fun hostLiteral(a: InetSocketAddress): String {
    val host = a.address?.hostAddress ?: a.hostString
    return if (host.contains(':')) "[$host]:${a.port}" else "$host:${a.port}"
}
