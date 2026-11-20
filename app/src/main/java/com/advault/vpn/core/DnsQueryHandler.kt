package com.advault.vpn.core

import com.advault.core.common.BlockResponse
import com.advault.core.common.DecisionReason
import com.advault.core.common.FilterCategory
import com.advault.core.common.FilterMode
import com.advault.data.models.FilterVerdict
import com.advault.data.repository.FilterEngine
import com.advault.vpn.core.dns.DnsCache
import com.advault.vpn.core.dns.DnsMessageBuilder
import com.advault.vpn.core.dns.DnsParser
import com.advault.vpn.core.dns.DnsQuery
import com.advault.vpn.core.dns.DnsRcode
import com.advault.vpn.core.packet.IpPacketBuilder
import com.advault.vpn.core.packet.IpPacketParser
import com.advault.vpn.core.packet.IpProtocol
import com.advault.vpn.core.packet.ParseResult
import com.advault.vpn.core.packet.TransportPacket
import com.advault.vpn.core.upstream.Upstream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Serialized writer of IP packets back into the TUN. */
fun interface PacketWriter {
    fun write(packet: ByteArray)
}

/** Maps a UDP/TCP 4-tuple to the owning app's uid (ConnectivityManager.getConnectionOwnerUid on device). -1 = unknown. */
fun interface UidResolver {
    fun resolve(protocol: Int, srcAddress: ByteArray, srcPort: Int, dstAddress: ByteArray, dstPort: Int): Int

    companion object { val UNKNOWN = UidResolver { _, _, _, _, _ -> -1 } }
}

/** Per-app policy as seen by the handler. */
interface AppPolicy {
    /** Effective mode; null = excluded (forward unfiltered). Implementations map unknown uids (-1) to the default mode. */
    fun modeForUid(uid: Int): FilterMode?
    fun packageForUid(uid: Int): String?
}

/** Classifies a domain to an ad provider id (ProviderRepository.match). */
fun interface ProviderClassifier {
    fun providerIdFor(domain: String): String?

    companion object { val NONE = ProviderClassifier { null } }
}

/** One observed DNS query, for statistics and the activity log. */
data class QueryEvent(
    val timestamp: Long,
    val uid: Int,
    val packageName: String?,
    val domain: String,
    val queryType: Int,
    val blocked: Boolean,
    val reason: DecisionReason,
    val category: FilterCategory?,
    val providerId: String?,
    val listId: String?,
    val latencyMs: Int,
    val cached: Boolean,
    /** IP bytes of request + response packets. */
    val bytes: Long,
    val upstreamFailed: Boolean = false,
)

fun interface QueryRecorder {
    fun record(event: QueryEvent)

    companion object { val NONE = QueryRecorder { } }
}

/** Settings that change live without re-establishing the tunnel. */
data class HandlerConfig(
    val blockResponse: BlockResponse = BlockResponse.NULL_IP,
    val cacheEnabled: Boolean = true,
    val timeoutMs: Int = 4000,
    /** TTL of synthesized block answers; short so allowlisting takes effect quickly. */
    val blockTtlSeconds: Int = 30,
)

/** Counters exposed to diagnostics. */
class HandlerCounters {
    val packets = AtomicLong()
    val queries = AtomicLong()
    val blocked = AtomicLong()
    val cacheHits = AtomicLong()
    val forwarded = AtomicLong()
    val upstreamFailures = AtomicLong()
    val malformed = AtomicLong()
    val droppedNonDns = AtomicLong()
    val tcpResets = AtomicLong()
    val overloadRejected = AtomicLong()
    val filterErrors = AtomicLong()
}

/**
 * The DNS-redirect core: takes raw IP packets read from the TUN, answers DNS queries addressed to the virtual
 * DNS servers (block / cache / forward) and writes response packets back. Pure Kotlin and thread-safe;
 * [handlePacket] never throws and never blocks on network I/O (upstream exchanges run on [ioScope]).
 */
class DnsQueryHandler(
    /** Virtual DNS server addresses (4 or 16 bytes each) that live inside the tunnel. */
    virtualDnsAddresses: List<ByteArray>,
    private val filter: FilterEngine,
    private val policy: AppPolicy,
    private val classifier: ProviderClassifier,
    private val uidResolver: UidResolver,
    private val recorder: QueryRecorder,
    private val writer: PacketWriter,
    val cache: DnsCache,
    private val upstream: () -> Upstream?,
    private val ioScope: CoroutineScope,
    private val maxInFlight: Int = 256,
    /** Max DNS payload we put in one UDP datagram; larger answers are returned truncated (TC) - see KNOWN_LIMITATIONS. */
    private val maxUdpPayload: Int = 16_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var config: HandlerConfig = HandlerConfig()

    val counters = HandlerCounters()
    private val inFlight = AtomicInteger()
    private val virtualDns = virtualDnsAddresses.map { it.copyOf() }

    val inFlightCount: Int get() = inFlight.get()

    fun handlePacket(buf: ByteArray, length: Int) {
        counters.packets.incrementAndGet()
        try {
            val parsed = IpPacketParser.parse(buf, length)
            if (parsed !is ParseResult.Ok) { counters.droppedNonDns.incrementAndGet(); return }
            val pkt = parsed.packet
            if (!isVirtualDns(pkt.dstAddress)) { counters.droppedNonDns.incrementAndGet(); return }
            when {
                pkt.protocol == IpProtocol.UDP && pkt.dstPort == 53 -> handleDns(pkt)
                pkt.protocol == IpProtocol.TCP -> {
                    // DNS-over-TCP / opportunistic DoT to the virtual server: reset so clients fail fast.
                    IpPacketBuilder.tcpReset(pkt)?.let { writer.write(it); counters.tcpResets.incrementAndGet() }
                }
                else -> counters.droppedNonDns.incrementAndGet()
            }
        } catch (t: Throwable) {
            // Never let a single bad packet kill the reader thread.
            counters.malformed.incrementAndGet()
        }
    }

    private fun isVirtualDns(addr: ByteArray): Boolean = virtualDns.any { it.contentEquals(addr) }

    private fun handleDns(pkt: TransportPacket) {
        val payload = pkt.payload()
        val query = DnsParser.parseQuery(payload)
        if (query == null) {
            counters.malformed.incrementAndGet()
            DnsMessageBuilder.headerOnlyError(payload, DnsRcode.FORMERR)?.let { reply(pkt, it) }
            return
        }
        if (query.isResponse) { counters.malformed.incrementAndGet(); return }
        if (query.opcode != 0) { reply(pkt, DnsMessageBuilder.emptyResponse(query, DnsRcode.NOTIMP)); return }
        if (query.questions.isEmpty()) { reply(pkt, DnsMessageBuilder.emptyResponse(query, DnsRcode.FORMERR)); return }

        counters.queries.incrementAndGet()
        val started = clock()
        val uid = safe(-1) { uidResolver.resolve(IpProtocol.UDP, pkt.srcAddress, pkt.srcPort, pkt.dstAddress, pkt.dstPort) }
        val mode = safe(FilterMode.AGGRESSIVE) { policy.modeForUid(uid) }
        val pkg = if (uid >= 0) safe(null) { policy.packageForUid(uid) } else null
        val q0 = query.questions[0]
        val domain = normalize(q0.name)

        val verdict = decide(query, mode)
        val config = config

        if (verdict.blocked) {
            counters.blocked.incrementAndGet()
            val resp = blockResponse(query, config)
            val bytes = reply(pkt, resp)
            record(started, uid, pkg, domain, q0.type, verdict, 0, false, pkt.packetLength + bytes)
            return
        }

        if (config.cacheEnabled) {
            cache.get(query)?.let { cached ->
                counters.cacheHits.incrementAndGet()
                val bytes = reply(pkt, cached, query)
                record(started, uid, pkg, domain, q0.type, verdict, 0, true, pkt.packetLength + bytes)
                return
            }
        }

        val up = upstream()
        val admitted = up != null && inFlight.incrementAndGet().let { n ->
            if (n > maxInFlight) { inFlight.decrementAndGet(); false } else true
        }
        if (up == null || !admitted) {
            if (up != null) counters.overloadRejected.incrementAndGet() else counters.upstreamFailures.incrementAndGet()
            val bytes = reply(pkt, DnsMessageBuilder.emptyResponse(query, DnsRcode.SERVFAIL))
            record(started, uid, pkg, domain, q0.type, verdict, 0, false, pkt.packetLength + bytes, upstreamFailed = true)
            return
        }
        counters.forwarded.incrementAndGet()
        try {
            ioScope.launch {
                try {
                    forward(pkt, query, up, config, started, uid, pkg, domain, verdict)
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        } catch (t: Throwable) {
            inFlight.decrementAndGet()
            throw t
        }
    }

    private fun forward(
        pkt: TransportPacket, query: DnsQuery, up: Upstream, config: HandlerConfig, started: Long,
        uid: Int, pkg: String?, domain: String, verdict: FilterVerdict,
    ) {
        val q0 = query.questions[0]
        try {
            val result = up.resolve(query.raw, config.timeoutMs)
            if (config.cacheEnabled) cache.put(query, result.response)
            val bytes = reply(pkt, result.response, query)
            record(started, uid, pkg, domain, q0.type, verdict, result.latencyMs, false, pkt.packetLength + bytes)
        } catch (e: Exception) {
            counters.upstreamFailures.incrementAndGet()
            val bytes = reply(pkt, DnsMessageBuilder.emptyResponse(query, DnsRcode.SERVFAIL))
            record(started, uid, pkg, domain, q0.type, verdict, (clock() - started).toInt(), false,
                pkt.packetLength + bytes, upstreamFailed = true)
        }
    }

    /** Evaluates every question; the first blocking verdict wins. Excluded apps (mode == null) are never filtered. */
    private fun decide(query: DnsQuery, mode: FilterMode?): FilterVerdict {
        if (mode == null) return FilterVerdict(blocked = false, reason = DecisionReason.APP_EXCLUDED)
        var first: FilterVerdict? = null
        for (q in query.questions) {
            val d = normalize(q.name)
            val v = if (isFilterable(d)) {
                try { filter.evaluate(d, mode) } catch (t: Throwable) {
                    counters.filterErrors.incrementAndGet()
                    FilterVerdict(false, DecisionReason.NO_MATCH) // fail open: never break DNS on an engine bug
                }
            } else FilterVerdict(false, DecisionReason.NO_MATCH)
            if (v.blocked) return v
            if (first == null) first = v
        }
        return first ?: FilterVerdict(false, DecisionReason.NO_MATCH)
    }

    private fun blockResponse(query: DnsQuery, config: HandlerConfig): ByteArray = when (config.blockResponse) {
        BlockResponse.NULL_IP -> DnsMessageBuilder.nullIpResponse(query, config.blockTtlSeconds)
        BlockResponse.NXDOMAIN -> DnsMessageBuilder.emptyResponse(query, DnsRcode.NXDOMAIN)
        BlockResponse.REFUSED -> DnsMessageBuilder.emptyResponse(query, DnsRcode.REFUSED)
    }

    /** Writes a DNS response back to the client, swapping addresses/ports. Returns the IP packet size. */
    private fun reply(pkt: TransportPacket, dns: ByteArray, query: DnsQuery? = null): Int {
        var payload = dns
        if (payload.size > maxUdpPayload && query != null) {
            // Cannot fit: tell the client the answer is truncated (it may retry via TCP, which we reset).
            // Note: pkt.buffer may already be reused by the reader thread here, so only [query] is consulted.
            payload = DnsMessageBuilder.emptyResponse(query, DnsRcode.NOERROR).also { it[2] = (it[2].toInt() or 0x02).toByte() }
        }
        val out = IpPacketBuilder.udp(pkt.dstAddress, pkt.dstPort, pkt.srcAddress, pkt.srcPort, payload)
        try { writer.write(out) } catch (_: Exception) { /* TUN closed during shutdown */ }
        return out.size
    }

    private fun record(
        started: Long, uid: Int, pkg: String?, domain: String, qtype: Int, v: FilterVerdict,
        latency: Int, cached: Boolean, bytes: Int, upstreamFailed: Boolean = false,
    ) {
        val providerId = if (isFilterable(domain)) safe(null) { classifier.providerIdFor(domain) } else null
        safe(Unit) {
            recorder.record(
                QueryEvent(
                    timestamp = started, uid = uid, packageName = pkg, domain = domain, queryType = qtype,
                    blocked = v.blocked, reason = v.reason, category = v.category, providerId = providerId,
                    listId = v.listId, latencyMs = latency, cached = cached, bytes = bytes.toLong(),
                    upstreamFailed = upstreamFailed,
                )
            )
        }
    }

    private inline fun <T> safe(default: T, block: () -> T): T = try { block() } catch (_: Throwable) { default }

    companion object {
        fun normalize(name: String): String = name.trim().lowercase().removeSuffix(".")

        /** Only real hostnames go to the filter (skips "", single labels like "localhost", escaped binary labels). */
        fun isFilterable(d: String): Boolean = d.indexOf('.') > 0 && d.indexOf('\\') < 0 && d.length <= 253
    }
}
