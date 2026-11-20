package com.advault.vpn.core.packet

/**
 * Minimal, allocation-light IPv4/IPv6 + UDP/TCP parsing and building for the DNS-redirect tunnel.
 *
 * Pure Kotlin (no android.*) so it can be unit tested on the JVM. Only what the tunnel needs is
 * implemented: the TUN only ever receives packets addressed to our virtual DNS servers.
 */
object IpProtocol {
    const val TCP = 6
    const val UDP = 17
}

/** A parsed transport-layer packet (UDP or TCP) carried in IPv4 or IPv6. */
class TransportPacket(
    /** 4 or 6. */
    val ipVersion: Int,
    /** [IpProtocol.UDP] or [IpProtocol.TCP]. */
    val protocol: Int,
    /** 4 or 16 bytes. */
    val srcAddress: ByteArray,
    val dstAddress: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    /** The backing buffer; the payload is `buffer[payloadOffset until payloadOffset + payloadLength]`. */
    val buffer: ByteArray,
    val payloadOffset: Int,
    val payloadLength: Int,
    /** Total IP packet length (header + payload), used for measured DNS byte counters. */
    val packetLength: Int,
    /** TCP only (0 for UDP). */
    val tcpSeq: Long = 0,
    val tcpAck: Long = 0,
    val tcpFlags: Int = 0,
) {
    fun payload(): ByteArray = buffer.copyOfRange(payloadOffset, payloadOffset + payloadLength)
}

object TcpFlags {
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10
}

/** Why a packet could not be handled; used for diagnostics counters. */
enum class PacketDropReason { TOO_SHORT, BAD_VERSION, BAD_LENGTH, FRAGMENT, UNSUPPORTED_PROTOCOL }

sealed interface ParseResult {
    class Ok(val packet: TransportPacket) : ParseResult
    class Dropped(val reason: PacketDropReason) : ParseResult
}

object IpPacketParser {

    fun parse(buf: ByteArray, length: Int): ParseResult {
        if (length < 1) return ParseResult.Dropped(PacketDropReason.TOO_SHORT)
        return when ((buf[0].toInt() ushr 4) and 0xF) {
            4 -> parseV4(buf, length)
            6 -> parseV6(buf, length)
            else -> ParseResult.Dropped(PacketDropReason.BAD_VERSION)
        }
    }

    private fun parseV4(buf: ByteArray, length: Int): ParseResult {
        if (length < 20) return ParseResult.Dropped(PacketDropReason.TOO_SHORT)
        val ihl = (buf[0].toInt() and 0xF) * 4
        val totalLength = u16(buf, 2)
        if (ihl < 20 || totalLength < ihl || totalLength > length) return ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
        val flagsFrag = u16(buf, 6)
        val moreFragments = flagsFrag and 0x2000 != 0
        val fragOffset = flagsFrag and 0x1FFF
        if (moreFragments || fragOffset != 0) return ParseResult.Dropped(PacketDropReason.FRAGMENT)
        val proto = buf[9].toInt() and 0xFF
        val src = buf.copyOfRange(12, 16)
        val dst = buf.copyOfRange(16, 20)
        return parseTransport(4, proto, src, dst, buf, ihl, totalLength)
    }

    private fun parseV6(buf: ByteArray, length: Int): ParseResult {
        if (length < 40) return ParseResult.Dropped(PacketDropReason.TOO_SHORT)
        val payloadLength = u16(buf, 4)
        val end = 40 + payloadLength
        if (end > length) return ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
        var next = buf[6].toInt() and 0xFF
        var off = 40
        // Skip the extension headers that may legitimately precede UDP/TCP.
        var guard = 0
        while (guard++ < 8) {
            when (next) {
                0, 43, 60 -> { // hop-by-hop, routing, destination options
                    if (off + 8 > end) return ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
                    val hdrLen = ((buf[off + 1].toInt() and 0xFF) + 1) * 8
                    next = buf[off].toInt() and 0xFF
                    off += hdrLen
                    if (off > end) return ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
                }
                44 -> return ParseResult.Dropped(PacketDropReason.FRAGMENT)
                else -> break
            }
        }
        val src = buf.copyOfRange(8, 24)
        val dst = buf.copyOfRange(24, 40)
        return parseTransport(6, next, src, dst, buf, off, end)
    }

    private fun parseTransport(
        version: Int, proto: Int, src: ByteArray, dst: ByteArray, buf: ByteArray, off: Int, end: Int,
    ): ParseResult = when (proto) {
        IpProtocol.UDP -> {
            if (off + 8 > end) ParseResult.Dropped(PacketDropReason.TOO_SHORT)
            else {
                val udpLen = u16(buf, off + 4)
                if (udpLen < 8 || off + udpLen > end) ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
                else ParseResult.Ok(
                    TransportPacket(
                        version, proto, src, dst, u16(buf, off), u16(buf, off + 2),
                        buf, off + 8, udpLen - 8, end,
                    )
                )
            }
        }
        IpProtocol.TCP -> {
            if (off + 20 > end) ParseResult.Dropped(PacketDropReason.TOO_SHORT)
            else {
                val dataOff = ((buf[off + 12].toInt() ushr 4) and 0xF) * 4
                if (dataOff < 20 || off + dataOff > end) ParseResult.Dropped(PacketDropReason.BAD_LENGTH)
                else ParseResult.Ok(
                    TransportPacket(
                        version, proto, src, dst, u16(buf, off), u16(buf, off + 2),
                        buf, off + dataOff, end - off - dataOff, end,
                        tcpSeq = u32(buf, off + 4), tcpAck = u32(buf, off + 8),
                        tcpFlags = buf[off + 13].toInt() and 0x3F,
                    )
                )
            }
        }
        else -> ParseResult.Dropped(PacketDropReason.UNSUPPORTED_PROTOCOL)
    }

    internal fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
    internal fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}

object IpPacketBuilder {
    private var ipv4Id = 0

    /** Builds a UDP datagram from [src]:[srcPort] to [dst]:[dstPort] with correct IP and UDP checksums. */
    fun udp(src: ByteArray, srcPort: Int, dst: ByteArray, dstPort: Int, payload: ByteArray): ByteArray {
        require(src.size == dst.size && (src.size == 4 || src.size == 16)) { "address family mismatch" }
        val udpLen = 8 + payload.size
        val ipHeaderLen = if (src.size == 4) 20 else 40
        val out = ByteArray(ipHeaderLen + udpLen)
        writeIpHeader(out, src, dst, IpProtocol.UDP, udpLen)
        val o = ipHeaderLen
        put16(out, o, srcPort); put16(out, o + 2, dstPort); put16(out, o + 4, udpLen); put16(out, o + 6, 0)
        System.arraycopy(payload, 0, out, o + 8, payload.size)
        var c = transportChecksum(src, dst, IpProtocol.UDP, out, o, udpLen)
        if (c == 0) c = 0xFFFF // RFC 768 / RFC 8200: transmitted zero means "no checksum"
        put16(out, o + 6, c)
        return out
    }

    /**
     * Builds a TCP RST answering [incoming] (RFC 793 reset generation), so clients attempting DNS-over-TCP
     * (or opportunistic DoT on 853) against the virtual DNS address fail fast instead of timing out.
     * Returns null if [incoming] is itself a RST.
     */
    fun tcpReset(incoming: TransportPacket): ByteArray? {
        if (incoming.protocol != IpProtocol.TCP || incoming.tcpFlags and TcpFlags.RST != 0) return null
        val seq: Long
        val ack: Long
        val flags: Int
        if (incoming.tcpFlags and TcpFlags.ACK != 0) {
            seq = incoming.tcpAck; ack = 0; flags = TcpFlags.RST
        } else {
            var segLen = incoming.payloadLength.toLong()
            if (incoming.tcpFlags and TcpFlags.SYN != 0) segLen++
            if (incoming.tcpFlags and TcpFlags.FIN != 0) segLen++
            seq = 0; ack = (incoming.tcpSeq + segLen) and 0xFFFFFFFFL; flags = TcpFlags.RST or TcpFlags.ACK
        }
        val src = incoming.dstAddress
        val dst = incoming.srcAddress
        val ipHeaderLen = if (src.size == 4) 20 else 40
        val tcpLen = 20
        val out = ByteArray(ipHeaderLen + tcpLen)
        writeIpHeader(out, src, dst, IpProtocol.TCP, tcpLen)
        val o = ipHeaderLen
        put16(out, o, incoming.dstPort); put16(out, o + 2, incoming.srcPort)
        put32(out, o + 4, seq); put32(out, o + 8, ack)
        out[o + 12] = (5 shl 4).toByte()
        out[o + 13] = flags.toByte()
        put16(out, o + 14, 0) // window
        put16(out, o + 16, 0)
        put16(out, o + 18, 0)
        put16(out, o + 16, transportChecksum(src, dst, IpProtocol.TCP, out, o, tcpLen))
        return out
    }

    private fun writeIpHeader(out: ByteArray, src: ByteArray, dst: ByteArray, proto: Int, payloadLen: Int) {
        if (src.size == 4) {
            out[0] = 0x45
            out[1] = 0
            put16(out, 2, 20 + payloadLen)
            put16(out, 4, nextId())
            put16(out, 6, 0x4000) // DF
            out[8] = 64
            out[9] = proto.toByte()
            put16(out, 10, 0)
            System.arraycopy(src, 0, out, 12, 4)
            System.arraycopy(dst, 0, out, 16, 4)
            put16(out, 10, Checksum.finish(Checksum.sum(out, 0, 20, 0)))
        } else {
            out[0] = 0x60; out[1] = 0; out[2] = 0; out[3] = 0
            put16(out, 4, payloadLen)
            out[6] = proto.toByte()
            out[7] = 64
            System.arraycopy(src, 0, out, 8, 16)
            System.arraycopy(dst, 0, out, 24, 16)
        }
    }

    @Synchronized private fun nextId(): Int { ipv4Id = (ipv4Id + 1) and 0xFFFF; return ipv4Id }

    /** Checksum over the IPv4/IPv6 pseudo-header plus the transport segment at [off]..[off]+[len]. */
    fun transportChecksum(src: ByteArray, dst: ByteArray, proto: Int, seg: ByteArray, off: Int, len: Int): Int {
        var s = Checksum.sum(src, 0, src.size, 0)
        s = Checksum.sum(dst, 0, dst.size, s)
        if (src.size == 4) {
            s += proto + len
        } else {
            s += (len ushr 16) + (len and 0xFFFF) + proto
        }
        s = Checksum.sum(seg, off, len, s)
        return Checksum.finish(s)
    }

    internal fun put16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    internal fun put32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

/** RFC 1071 Internet checksum. */
object Checksum {
    fun sum(b: ByteArray, off: Int, len: Int, initial: Long): Long {
        var s = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) s += (b[i].toInt() and 0xFF) shl 8
        return s
    }

    fun sum(b: ByteArray, off: Int, len: Int, initial: Int): Long = sum(b, off, len, initial.toLong())

    fun finish(sum: Long): Int {
        var s = sum
        while (s ushr 16 != 0L) s = (s and 0xFFFF) + (s ushr 16)
        return (s.inv() and 0xFFFF).toInt()
    }

    /** True if the ones-complement sum including the stored checksum folds to 0xFFFF. */
    fun verifyIpv4Header(packet: ByteArray): Boolean {
        val ihl = (packet[0].toInt() and 0xF) * 4
        return finish(sum(packet, 0, ihl, 0L)) == 0
    }
}
