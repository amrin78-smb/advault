package com.advault.vpn

import com.advault.vpn.core.packet.Checksum
import com.advault.vpn.core.packet.IpPacketBuilder
import com.advault.vpn.core.packet.IpPacketParser
import com.advault.vpn.core.packet.IpProtocol
import com.advault.vpn.core.packet.PacketDropReason
import com.advault.vpn.core.packet.ParseResult
import com.advault.vpn.core.packet.TcpFlags
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IpPacketTest {

    @Test fun ipv4UdpRoundTripWithValidChecksums() {
        val payload = TestDns.query("example.com")
        val pkt = IpPacketBuilder.udp(TestDns.CLIENT_V4, 41234, TestDns.VDNS_V4, 53, payload)
        assertEquals(20 + 8 + payload.size, pkt.size)
        assertTrue("IPv4 header checksum", Checksum.verifyIpv4Header(pkt))
        TestDns.assertTransportChecksumValid(pkt)
        val p = TestDns.parse(pkt)
        assertEquals(4, p.ipVersion); assertEquals(IpProtocol.UDP, p.protocol)
        assertArrayEquals(TestDns.CLIENT_V4, p.srcAddress); assertArrayEquals(TestDns.VDNS_V4, p.dstAddress)
        assertEquals(41234, p.srcPort); assertEquals(53, p.dstPort)
        assertArrayEquals(payload, p.payload())
    }

    @Test fun ipv6UdpRoundTripWithValidChecksum() {
        val payload = TestDns.query("example.com", 28)
        val pkt = IpPacketBuilder.udp(TestDns.CLIENT_V6, 5353, TestDns.VDNS_V6, 53, payload)
        assertEquals(40 + 8 + payload.size, pkt.size)
        TestDns.assertTransportChecksumValid(pkt)
        val p = TestDns.parse(pkt)
        assertEquals(6, p.ipVersion)
        assertArrayEquals(TestDns.VDNS_V6, p.dstAddress)
        assertArrayEquals(payload, p.payload())
    }

    @Test fun knownIpv4HeaderChecksum() {
        // Classic RFC 1071 example header (checksum 0xB861).
        val hdr = intArrayOf(0x45,0x00,0x00,0x73,0x00,0x00,0x40,0x00,0x40,0x11,0x00,0x00,0xc0,0xa8,0x00,0x01,0xc0,0xa8,0x00,0xc7)
            .map { it.toByte() }.toByteArray()
        assertEquals(0xB861, Checksum.finish(Checksum.sum(hdr, 0, 20, 0L)))
    }

    @Test fun ipv6HopByHopExtensionIsSkipped() {
        val payload = TestDns.query("example.org")
        val plain = IpPacketBuilder.udp(TestDns.CLIENT_V6, 1000, TestDns.VDNS_V6, 53, payload)
        val udp = plain.copyOfRange(40, plain.size)
        val ext = byteArrayOf(17, 0, 1, 4, 0, 0, 0, 0) // next=UDP, len=0 (8 bytes), PadN
        val pkt = plain.copyOfRange(0, 40) + ext + udp
        pkt[6] = 0 // next header = hop-by-hop
        val len = ext.size + udp.size
        pkt[4] = (len ushr 8).toByte(); pkt[5] = len.toByte()
        val p = TestDns.parse(pkt)
        assertEquals(IpProtocol.UDP, p.protocol)
        assertArrayEquals(payload, p.payload())
    }

    @Test fun malformedAndUnsupportedPacketsAreDroppedNotThrown() {
        fun reason(b: ByteArray, len: Int = b.size) = (IpPacketParser.parse(b, len) as ParseResult.Dropped).reason
        assertEquals(PacketDropReason.TOO_SHORT, reason(ByteArray(0)))
        assertEquals(PacketDropReason.TOO_SHORT, reason(byteArrayOf(0x45, 0)))
        assertEquals(PacketDropReason.BAD_VERSION, reason(ByteArray(40) { 0x20 }))
        val good = TestDns.udpV4(TestDns.query("a.com"))
        assertEquals(PacketDropReason.BAD_LENGTH, reason(good, good.size - 3))
        val frag = good.copyOf(); frag[6] = 0x20 // MF
        assertEquals(PacketDropReason.FRAGMENT, reason(frag))
        val icmp = good.copyOf(); icmp[9] = 1
        assertEquals(PacketDropReason.UNSUPPORTED_PROTOCOL, reason(icmp))
        // Random garbage of many lengths must never throw.
        val rnd = java.util.Random(42)
        repeat(5000) {
            val b = ByteArray(rnd.nextInt(80)).also(rnd::nextBytes)
            if (b.isNotEmpty()) b[0] = (if (rnd.nextBoolean()) 0x45 else 0x60).toByte()
            IpPacketParser.parse(b, b.size)
        }
    }

    private fun tcpSyn(v6: Boolean, flags: Int = TcpFlags.SYN): ByteArray {
        val src = if (v6) TestDns.CLIENT_V6 else TestDns.CLIENT_V4
        val dst = if (v6) TestDns.VDNS_V6 else TestDns.VDNS_V4
        val hl = if (v6) 40 else 20
        val out = ByteArray(hl + 20)
        if (v6) { out[0] = 0x60; out[5] = 20; out[6] = 6; out[7] = 64; System.arraycopy(src, 0, out, 8, 16); System.arraycopy(dst, 0, out, 24, 16) }
        else { out[0] = 0x45; out[3] = (hl + 20).toByte(); out[8] = 64; out[9] = 6; System.arraycopy(src, 0, out, 12, 4); System.arraycopy(dst, 0, out, 16, 4) }
        out[hl] = 0x9C.toByte(); out[hl + 1] = 0x40 // sport 40000
        out[hl + 3] = 53
        out[hl + 4] = 0; out[hl + 5] = 0; out[hl + 6] = 0x03; out[hl + 7] = 0xE8.toByte() // seq 1000
        out[hl + 12] = 0x50; out[hl + 13] = flags.toByte()
        return out
    }

    @Test fun tcpResetForSynV4AndV6() {
        for (v6 in listOf(false, true)) {
            val syn = TestDns.parse(tcpSyn(v6))
            val rst = IpPacketBuilder.tcpReset(syn)!!
            TestDns.assertTransportChecksumValid(rst)
            if (!v6) assertTrue(Checksum.verifyIpv4Header(rst))
            val p = TestDns.parse(rst)
            assertEquals(IpProtocol.TCP, p.protocol)
            assertEquals(53, p.srcPort); assertEquals(40000, p.dstPort)
            assertEquals(TcpFlags.RST or TcpFlags.ACK, p.tcpFlags)
            assertEquals(1001L, p.tcpAck)
        }
    }

    @Test fun noResetForReset() {
        assertNull(IpPacketBuilder.tcpReset(TestDns.parse(tcpSyn(false, TcpFlags.RST))))
    }
}
