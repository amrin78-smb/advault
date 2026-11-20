package com.advault.vpn

import com.advault.core.preferences.UpstreamPreset
import com.advault.core.preferences.UpstreamProtocol
import com.advault.vpn.core.dns.DnsParser
import com.advault.vpn.core.upstream.HttpsTransport
import com.advault.vpn.core.upstream.TcpTransport
import com.advault.vpn.core.upstream.UdpTransport
import com.advault.vpn.core.upstream.UpstreamConfig
import com.advault.vpn.core.upstream.UpstreamException
import com.advault.vpn.core.upstream.UpstreamHealthListener
import com.advault.vpn.core.upstream.UpstreamResolver
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

class UpstreamTest {
    private val loopback = InetAddress.getLoopbackAddress()
    private val ip = byteArrayOf(1, 1, 1, 1)

    @Test fun udpForwardingToFakeServer() {
        FakeUdpDnsServer { q -> TestDns.answer(q, listOf(ip)) }.use { server ->
            val t = UdpTransport(InetSocketAddress(loopback, server.port))
            val q = TestDns.query("example.com", id = 4242)
            val r = t.exchange(q, 2000)
            val info = DnsParser.parseResponse(r)!!
            assertEquals(4242, info.id); assertEquals(1, info.answerCount)
        }
    }

    @Test fun udpIgnoresMismatchedIdsAndWaitsForTheRealAnswer() {
        // Server that sends a spoofed (wrong ID) answer first, then the real one.
        val sock = java.net.DatagramSocket(0, loopback)
        val th = Thread {
            val buf = ByteArray(1500)
            val p = java.net.DatagramPacket(buf, buf.size)
            sock.receive(p)
            val q = buf.copyOf(p.length)
            val spoof = TestDns.answer(q, listOf(byteArrayOf(6, 6, 6, 6))).also { it[0] = (it[0] + 1).toByte() }
            sock.send(java.net.DatagramPacket(spoof, spoof.size, p.socketAddress))
            val real = TestDns.answer(q, listOf(ip))
            sock.send(java.net.DatagramPacket(real, real.size, p.socketAddress))
        }.apply { start() }
        val r = UdpTransport(InetSocketAddress(loopback, sock.localPort)).exchange(TestDns.query("example.com"), 2000)
        assertArrayEquals(ip, r.copyOfRange(r.size - 4, r.size))
        th.join(); sock.close()
    }

    @Test fun timeoutThenNextServerThenServfailPath() {
        val failures = CopyOnWriteArrayList<String>()
        val health = object : UpstreamHealthListener {
            override fun onSuccess(server: String, latencyMs: Int) {}
            override fun onFailure(server: String, error: Throwable) { failures += server }
        }
        FakeUdpDnsServer { null }.use { silent ->
            FakeUdpDnsServer { q -> TestDns.answer(q, listOf(ip)) }.use { good ->
                val resolver = UpstreamResolver(
                    listOf(UdpTransport(InetSocketAddress(loopback, silent.port)), UdpTransport(InetSocketAddress(loopback, good.port))),
                    "test", health,
                )
                val began = System.currentTimeMillis()
                val r = resolver.resolve(TestDns.query("example.com"), 300)
                assertTrue(System.currentTimeMillis() - began >= 280)
                assertEquals(1, DnsParser.parseResponse(r.response)!!.answerCount)
                assertEquals(1, failures.size)
                // Preferred server is now the good one: no further timeout.
                val again = System.currentTimeMillis()
                resolver.resolve(TestDns.query("example.net"), 300)
                assertTrue(System.currentTimeMillis() - again < 250)
                assertEquals(1, silent.received.get())
            }
            val allDown = UpstreamResolver(listOf(UdpTransport(InetSocketAddress(loopback, silent.port))), "down")
            try { allDown.resolve(TestDns.query("example.com"), 200); fail("expected failure") } catch (_: UpstreamException) {}
        }
    }

    @Test fun truncatedUdpAnswerFallsBackToTcp() {
        val fullAnswer = { q: ByteArray -> TestDns.answer(q, List(200) { byteArrayOf(10, 1, (it / 250).toByte(), it.toByte()) }) }
        FakeTcpDnsServer(fullAnswer).use { tcp ->
            FakeUdpDnsServer { q -> TestDns.answer(q, emptyList(), tc = true) }.use { udp ->
                // UDP and TCP on different ports in the test, so inject the TCP transport explicitly.
                val t = UdpTransport(InetSocketAddress(loopback, udp.port), tcpFallback = TcpTransport(InetSocketAddress(loopback, tcp.port)))
                val r = t.exchange(TestDns.query("big.example.com"), 2000)
                val info = DnsParser.parseResponse(r)!!
                assertFalse(info.truncated)
                assertEquals(200, info.answerCount)
                assertEquals(1, tcp.received.get())
            }
        }
    }

    @Test fun tcpTransportFraming() {
        FakeTcpDnsServer { q -> TestDns.answer(q, listOf(ip)) }.use { tcp ->
            val r = TcpTransport(InetSocketAddress(loopback, tcp.port)).exchange(TestDns.query("x.example", id = 99), 2000)
            assertEquals(99, DnsParser.u16(r, 0))
        }
    }

    @Test fun dohPostEncoding() {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        val seen = CopyOnWriteArrayList<String>()
        var body = ByteArray(0)
        server.createContext("/dns-query") { ex ->
            seen += ex.requestMethod
            seen += ex.requestHeaders.getFirst("Content-Type")
            seen += ex.requestHeaders.getFirst("Accept")
            body = ex.requestBody.readBytes()
            val resp = TestDns.answer(body, listOf(ip))
            ex.responseHeaders.add("Content-Type", "application/dns-message")
            ex.sendResponseHeaders(200, resp.size.toLong())
            ex.responseBody.use { it.write(resp) }
        }
        server.start()
        try {
            val t = HttpsTransport(URI("http://127.0.0.1:${server.address.port}/dns-query").toURL())
            val q = TestDns.query("example.com", id = 0x7777)
            val r = t.exchange(q, 2000)
            assertEquals(listOf("POST", "application/dns-message", "application/dns-message"), seen.toList())
            assertEquals("RFC 8484: ID is 0 on the wire", 0, DnsParser.u16(body, 0))
            assertArrayEquals(q.copyOfRange(2, q.size), body.copyOfRange(2, body.size))
            assertEquals("ID restored", 0x7777, DnsParser.u16(r, 0))
            assertArrayEquals(HttpsTransport.encodeRequestBody(q), body)
        } finally { server.stop(0) }
    }

    @Test fun dohRejectsWrongContentTypeAndHttpErrors() {
        val server = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        server.createContext("/bad") { ex -> ex.requestBody.readBytes(); ex.sendResponseHeaders(500, -1); ex.close() }
        server.createContext("/html") { ex ->
            ex.requestBody.readBytes(); val b = "<html>".toByteArray()
            ex.responseHeaders.add("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.use { it.write(b) }
        }
        server.start()
        try {
            for (path in listOf("/bad", "/html")) {
                try {
                    HttpsTransport(URI("http://127.0.0.1:${server.address.port}$path").toURL()).exchange(TestDns.query("a.com"), 1000)
                    fail("expected failure for $path")
                } catch (_: UpstreamException) {}
            }
        } finally { server.stop(0) }
    }

    @Test fun upstreamConfigValidation() {
        val cf = UpstreamConfig.resolve(UpstreamPreset.CLOUDFLARE, UpstreamProtocol.PLAIN, "", emptyList())
        assertEquals(3, cf.servers.size); assertTrue(cf.notes.isEmpty())

        val sys = UpstreamConfig.resolve(UpstreamPreset.SYSTEM, UpstreamProtocol.PLAIN, "", listOf(InetAddress.getByName("192.168.1.1")))
        assertEquals(InetSocketAddress(InetAddress.getByName("192.168.1.1"), 53), sys.servers.single())
        val sysEmpty = UpstreamConfig.resolve(UpstreamPreset.SYSTEM, UpstreamProtocol.PLAIN, "", emptyList())
        assertEquals("1.1.1.1", sysEmpty.servers.first().address.hostAddress); assertTrue(sysEmpty.notes.isNotEmpty())

        val custom = UpstreamConfig.custom(UpstreamProtocol.PLAIN, "9.9.9.9, 1.2.3.4:5353, [2620:fe::fe]:53, 2001:db8::1")
        assertEquals(listOf(53, 5353, 53, 53), custom.servers.map { it.port }); assertTrue(custom.notes.isEmpty())

        for (bad in listOf("dns.google", "999.1.1.1", "1.2.3", "", "1.1.1.1:99999")) {
            val s = UpstreamConfig.custom(UpstreamProtocol.PLAIN, bad)
            assertTrue("'$bad' should fall back", s.notes.isNotEmpty()); assertEquals(UpstreamProtocol.PLAIN, s.protocol)
            assertEquals("1.1.1.1", s.servers.first().address.hostAddress)
        }
        assertEquals("dns.quad9.net", UpstreamConfig.custom(UpstreamProtocol.DOT, "dns.quad9.net").endpoint)
        assertTrue(UpstreamConfig.custom(UpstreamProtocol.DOT, "not a host").notes.isNotEmpty())
        assertEquals("https://dns.example/dns-query", UpstreamConfig.custom(UpstreamProtocol.DOH, "https://dns.example/dns-query").endpoint)
        assertTrue(UpstreamConfig.custom(UpstreamProtocol.DOH, "http://dns.example/dns-query").notes.isNotEmpty())
        assertTrue(UpstreamConfig.custom(UpstreamProtocol.DOH, "ftp://x").notes.isNotEmpty())

        val doh = UpstreamConfig.fromPreset(UpstreamPreset.CLOUDFLARE_DOH)
        assertEquals(listOf("https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"), UpstreamConfig.dohFallbackUrls(doh).map { it.toString() })
        val dot = UpstreamConfig.fromPreset(UpstreamPreset.QUAD9_DOT)
        assertEquals("dns.quad9.net", dot.endpoint); assertEquals(2, dot.bootstrap.size)
    }
}
