package com.advault.vpn

import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.data.models.FilterEngineStatus
import com.advault.data.models.FilterVerdict
import com.advault.data.repository.FilterEngine
import com.advault.vpn.core.AppPolicy
import com.advault.vpn.core.PacketWriter
import com.advault.vpn.core.packet.IpPacketBuilder
import com.advault.vpn.core.packet.IpPacketParser
import com.advault.vpn.core.packet.IpProtocol
import com.advault.vpn.core.packet.ParseResult
import com.advault.vpn.core.packet.TransportPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

object TestDns {
    val CLIENT_V4: ByteArray = InetAddress.getByName("10.111.222.1").address
    val VDNS_V4: ByteArray = InetAddress.getByName("10.111.222.2").address
    val CLIENT_V6: ByteArray = InetAddress.getByName("fd66:6164:7661::1").address
    val VDNS_V6: ByteArray = InetAddress.getByName("fd66:6164:7661::2").address

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        if (name.isNotEmpty()) for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0)
        return out.toByteArray()
    }

    /** A standard query; [questions] as (name, type) pairs. */
    fun query(vararg questions: Pair<String, Int>, id: Int = 0x1234, flags: Int = 0x0100): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id ushr 8); out.write(id and 0xFF)
        out.write(flags ushr 8); out.write(flags and 0xFF)
        out.write(0); out.write(questions.size)
        repeat(6) { out.write(0) }
        for ((n, t) in questions) {
            out.write(encodeName(n)); out.write(t ushr 8); out.write(t and 0xFF); out.write(0); out.write(1)
        }
        return out.toByteArray()
    }

    fun query(name: String, type: Int = 1, id: Int = 0x1234): ByteArray = query(name to type, id = id)

    /** A response to a single-question [query] with one A record (pointer-compressed name) per IP. */
    fun answer(query: ByteArray, ips: List<ByteArray>, ttl: Long = 300, tc: Boolean = false, rcode: Int = 0): ByteArray {
        val qEnd = 12 + (com.advault.vpn.core.dns.DnsParser.readName(query, 12).second - 12) + 4
        val out = ByteArrayOutputStream()
        out.write(query, 0, 2)
        val flags = 0x8180 or (if (tc) 0x0200 else 0) or rcode
        out.write(flags ushr 8); out.write(flags and 0xFF)
        out.write(0); out.write(1); out.write(0); out.write(ips.size); out.write(0); out.write(0); out.write(0); out.write(0)
        out.write(query, 12, qEnd - 12)
        for (ip in ips) {
            out.write(0xC0); out.write(0x0C)
            val type = if (ip.size == 4) 1 else 28
            out.write(0); out.write(type); out.write(0); out.write(1)
            out.write((ttl ushr 24).toInt() and 0xFF); out.write((ttl ushr 16).toInt() and 0xFF)
            out.write((ttl ushr 8).toInt() and 0xFF); out.write(ttl.toInt() and 0xFF)
            out.write(0); out.write(ip.size); out.write(ip)
        }
        return out.toByteArray()
    }

    fun udpV4(payload: ByteArray, srcPort: Int = 40000) = IpPacketBuilder.udp(CLIENT_V4, srcPort, VDNS_V4, 53, payload)
    fun udpV6(payload: ByteArray, srcPort: Int = 40000) = IpPacketBuilder.udp(CLIENT_V6, srcPort, VDNS_V6, 53, payload)

    fun parse(packet: ByteArray): TransportPacket {
        val r = IpPacketParser.parse(packet, packet.size)
        check(r is ParseResult.Ok) { "parse failed: ${(r as ParseResult.Dropped).reason}" }
        return r.packet
    }

    fun assertTransportChecksumValid(packet: ByteArray) {
        val p = parse(packet)
        val hdr = if (p.ipVersion == 4) (packet[0].toInt() and 0xF) * 4 else 40
        val segLen = p.packetLength - hdr
        assertEquals("transport checksum", 0,
            IpPacketBuilder.transportChecksum(p.srcAddress, p.dstAddress, p.protocol, packet, hdr, segLen))
    }
}

class FakeFilter(
    private val blocked: Map<String, FilterCategory> = emptyMap(),
    /** Categories only blocked in AGGRESSIVE mode, mimicking FilterMode.includes. */
) : FilterEngine {
    override val status: StateFlow<FilterEngineStatus> = MutableStateFlow(FilterEngineStatus(loaded = true))
    val calls = ConcurrentHashMap<String, FilterMode>()
    val callCount = AtomicInteger()

    override fun evaluate(domain: String, mode: FilterMode): FilterVerdict {
        calls[domain] = mode
        callCount.incrementAndGet()
        val cat = blocked[domain] ?: return FilterVerdict(false, DecisionReason.NO_MATCH)
        return if (mode.includes(cat)) FilterVerdict(true, DecisionReason.BLOCKLIST, cat, domain, "test-list")
        else FilterVerdict(false, DecisionReason.MODE_EXCLUDES_CATEGORY, cat)
    }

    override suspend fun reload() {}
}

class MapPolicy(private val modes: Map<Int, FilterMode?>, private val default: FilterMode = FilterMode.AGGRESSIVE) : AppPolicy {
    override fun modeForUid(uid: Int): FilterMode? = if (modes.containsKey(uid)) modes[uid] else default
    override fun packageForUid(uid: Int): String? = if (uid >= 0) "pkg.$uid" else null
}

class CapturingWriter : PacketWriter {
    val packets = LinkedBlockingQueue<ByteArray>()
    override fun write(packet: ByteArray) { packets.add(packet) }
    fun take(timeoutMs: Long = 5_000): ByteArray = packets.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: error("no packet written")
}

/** Fake UDP DNS server on 127.0.0.1. [respond] returns null to stay silent (timeout tests). */
class FakeUdpDnsServer(private val respond: (ByteArray) -> ByteArray?) : AutoCloseable {
    val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val port: Int get() = socket.localPort
    val received = AtomicInteger()
    private val t = thread(isDaemon = true) {
        val buf = ByteArray(65535)
        while (!socket.isClosed) {
            try {
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                received.incrementAndGet()
                val r = respond(buf.copyOf(p.length)) ?: continue
                socket.send(DatagramPacket(r, r.size, p.socketAddress))
            } catch (_: Exception) { }
        }
    }
    override fun close() { socket.close() }
}

/** Fake DNS-over-TCP server on 127.0.0.1. */
class FakeTcpDnsServer(private val respond: (ByteArray) -> ByteArray) : AutoCloseable {
    val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    val received = AtomicInteger()
    private val t = thread(isDaemon = true) {
        while (!server.isClosed) {
            try {
                val s = server.accept()
                thread(isDaemon = true) {
                    s.use {
                        val q = com.advault.vpn.core.upstream.DnsStreamFraming.read(it.getInputStream())
                        received.incrementAndGet()
                        com.advault.vpn.core.upstream.DnsStreamFraming.write(it.getOutputStream(), respond(q))
                    }
                }
            } catch (_: Exception) { }
        }
    }
    override fun close() { server.close() }
}

val UDP = IpProtocol.UDP
