package com.advault.vpn

import com.advault.vpn.core.dns.DnsMessageBuilder
import com.advault.vpn.core.dns.DnsParser
import com.advault.vpn.core.dns.DnsRcode
import com.advault.vpn.core.dns.DnsType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DnsMessageTest {

    @Test fun parsesSimpleQuery() {
        val q = DnsParser.parseQuery(TestDns.query("Ads.Example.COM", DnsType.AAAA, id = 0xBEEF))!!
        assertEquals(0xBEEF, q.id)
        assertEquals(1, q.questions.size)
        assertEquals("Ads.Example.COM", q.questions[0].name)
        assertEquals("ads.example.com", q.questions[0].normalizedName)
        assertEquals(DnsType.AAAA, q.questions[0].type)
        assertEquals(1, q.questions[0].qclass)
        assertEquals(q.raw.size, q.questionSectionEnd)
    }

    @Test fun parsesMultipleQuestions() {
        val q = DnsParser.parseQuery(TestDns.query("a.com" to 1, "b.org" to 28))!!
        assertEquals(listOf("a.com", "b.org"), q.questions.map { it.name })
        assertEquals(listOf(1, 28), q.questions.map { it.type })
    }

    @Test fun followsCompressionPointers() {
        // Question 1: www.example.com ; question 2: "ads" + pointer to "example.com" (offset 16).
        val base = TestDns.query("www.example.com" to 1)
        val second = byteArrayOf(3, 'a'.code.toByte(), 'd'.code.toByte(), 's'.code.toByte(), 0xC0.toByte(), 16, 0, 1, 0, 1)
        val msg = base + second
        msg[5] = 2
        val q = DnsParser.parseQuery(msg)!!
        assertEquals("ads.example.com", q.questions[1].name)
        assertEquals(msg.size, q.questionSectionEnd)
    }

    @Test fun rejectsPointerLoopsTruncationAndGarbage() {
        val loop = TestDns.query("a.com").copyOf(12) + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1)
        loop[5] = 1
        assertNull(DnsParser.parseQuery(loop))
        val full = TestDns.query("example.com")
        for (cut in 0 until full.size) assertNull("cut=$cut", DnsParser.parseQuery(full.copyOf(cut)))
        val badLabel = full.copyOf(); badLabel[12] = 0x80.toByte()
        assertNull(DnsParser.parseQuery(badLabel))
        val longName = (1..10).joinToString(".") { "a".repeat(60) }
        assertNull(DnsParser.parseQuery(TestDns.query(longName)))
        val rnd = java.util.Random(7)
        repeat(20000) { DnsParser.parseQuery(ByteArray(rnd.nextInt(64)).also(rnd::nextBytes)); DnsParser.parseResponse(ByteArray(rnd.nextInt(64)).also(rnd::nextBytes)) }
    }

    @Test fun nullIpResponseForAandAAAA() {
        for ((type, len) in listOf(DnsType.A to 4, DnsType.AAAA to 16)) {
            val q = DnsParser.parseQuery(TestDns.query("ads.example.com", type, id = 77))!!
            val r = DnsMessageBuilder.nullIpResponse(q, 30)
            val info = DnsParser.parseResponse(r)!!
            assertEquals(77, info.id)
            assertEquals(DnsRcode.NOERROR, info.rcode)
            assertEquals(1, info.answerCount)
            assertEquals(30L, info.cacheTtlSeconds)
            // rdata = all zeroes at the end
            assertArrayEquals(ByteArray(len), r.copyOfRange(r.size - len, r.size))
            assertEquals(0x81, r[2].toInt() and 0xFF) // QR + RD
            assertEquals(0x80, r[3].toInt() and 0xF0) // RA
        }
    }

    @Test fun nullIpResponseForHttpsAndOtherTypesIsNodata() {
        for (type in listOf(DnsType.HTTPS, DnsType.SVCB, DnsType.TXT, DnsType.MX, DnsType.ANY)) {
            val q = DnsParser.parseQuery(TestDns.query("ads.example.com", type))!!
            val info = DnsParser.parseResponse(DnsMessageBuilder.nullIpResponse(q, 30))!!
            assertEquals(DnsRcode.NOERROR, info.rcode)
            assertEquals(0, info.answerCount)
            assertEquals(type, info.questions[0].type)
        }
    }

    @Test fun nullIpResponseMultiQuestionPointsAtEachQuestion() {
        val q = DnsParser.parseQuery(TestDns.query("a.com" to 1, "b.org" to 28))!!
        val r = DnsMessageBuilder.nullIpResponse(q, 30)
        val info = DnsParser.parseResponse(r)!!
        assertEquals(2, info.answerCount)
        // second answer's name pointer must resolve to b.org
        val secondAnswerOffset = q.questionSectionEnd + 16
        assertEquals("b.org", DnsParser.readName(r, secondAnswerOffset).first)
    }

    @Test fun rcodeResponsesEchoQuestion() {
        val q = DnsParser.parseQuery(TestDns.query("x.example", 1, id = 9))!!
        for (rc in listOf(DnsRcode.NXDOMAIN, DnsRcode.REFUSED, DnsRcode.SERVFAIL)) {
            val info = DnsParser.parseResponse(DnsMessageBuilder.emptyResponse(q, rc))!!
            assertEquals(rc, info.rcode); assertEquals(9, info.id); assertEquals("x.example", info.questions[0].name)
        }
        assertNotNull(DnsMessageBuilder.headerOnlyError(ByteArray(12), DnsRcode.FORMERR))
        assertNull(DnsMessageBuilder.headerOnlyError(ByteArray(5), DnsRcode.FORMERR))
    }

    @Test fun responseParsingFindsTtlsAndNegativeTtl() {
        val query = TestDns.query("example.com")
        val r = TestDns.answer(query, listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8)), ttl = 120)
        val info = DnsParser.parseResponse(r)!!
        assertEquals(2, info.ttlOffsets.size); assertEquals(120L, info.cacheTtlSeconds)

        // NXDOMAIN with SOA in authority: negative TTL = min(soa ttl 900, minimum 60)
        val soaRdata = TestDns.encodeName("ns.example.com") + TestDns.encodeName("admin.example.com") + ByteArray(16) + byteArrayOf(0, 0, 0, 60)
        val head = TestDns.answer(query, emptyList(), rcode = 3)
        head[9] = 1 // NSCOUNT
        val soa = byteArrayOf(0xC0.toByte(), 12, 0, 6, 0, 1, 0, 0, 0x03, 0x84.toByte(), 0, soaRdata.size.toByte()) + soaRdata
        val nx = DnsParser.parseResponse(head + soa)!!
        assertEquals(3, nx.rcode); assertEquals(60L, nx.cacheTtlSeconds)
    }
}
