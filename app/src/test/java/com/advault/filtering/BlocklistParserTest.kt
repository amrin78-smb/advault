package com.advault.filtering

import com.advault.core.common.BlocklistFormat
import com.advault.filtering.parser.BlocklistParseException
import com.advault.filtering.parser.BlocklistParser
import com.advault.filtering.parser.ParseLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream

class BlocklistParserTest {

    @Test fun hostsFormat() {
        val text = """
            # Title: test
            127.0.0.1 localhost
            127.0.0.1 localhost.localdomain
            255.255.255.255 broadcasthost
            ::1 localhost
            fe80::1%lo0 localhost
            0.0.0.0 0.0.0.0
            0.0.0.0 ads.example.com
            0.0.0.0 Tracker.Example.COM.   # inline comment
            127.0.0.1 a.test b.test
            0.0.0.0 ads.example.com
            0.0.0.0 bad_domain-.com
            0.0.0.0 1.2.3.4
        """.trimIndent()
        val (rules, s) = BlocklistParser.parseToList(text)
        assertEquals(listOf("ads.example.com", "tracker.example.com", "a.test", "b.test"), rules)
        assertEquals(4, s.validRules)
        assertEquals(1, s.duplicateRules)
        assertEquals(2, s.invalidRules)
        assertEquals(6, s.skippedRules)
        assertEquals(BlocklistFormat.HOSTS, s.detectedFormat)
    }

    @Test fun domainsFormat() {
        val text = "example.com\n*.wild.example\n\n# c\nexample.com\nEXAMPLE.com\nnot a domain\nsingle\n-bad.com\nx.y.z\r\n"
        val (rules, s) = BlocklistParser.parseToList(text)
        assertEquals(listOf("example.com", "*.wild.example", "x.y.z"), rules)
        assertEquals(2, s.duplicateRules)
        assertEquals(3, s.invalidRules)
        assertEquals(BlocklistFormat.DOMAINS, s.detectedFormat)
    }

    @Test fun adblockFormat() {
        val text = """
            [Adblock Plus 2.0]
            ! Title: test
            ||ads.example.com^
            ||cdn.example.com^|
            ||important.example^${'$'}important
            ||third.example^${'$'}third-party
            ||scoped.example^${'$'}domain=foo.com
            @@||allowed.example^
            ||path.example/ads.js
            ||wild*.example^
            /banner\d+/
            example.com##.ad-banner
            ##.sponsored
            |https://x.example/
            ||ads.example.com^
        """.trimIndent()
        val (rules, s) = BlocklistParser.parseToList(text)
        assertEquals(listOf("ads.example.com", "cdn.example.com", "important.example"), rules)
        assertEquals(1, s.duplicateRules)
        assertEquals(9, s.unsupportedRules)
        assertEquals(BlocklistFormat.ADBLOCK, s.detectedFormat)
    }

    @Test fun rejectsHtml() {
        val html = "<!DOCTYPE HTML>\n<html><head><title>410 Gone</title></head></html>\n"
        assertThrows(BlocklistParseException::class.java) { BlocklistParser.parseToList(html) }
    }

    @Test fun rejectsContentWithoutRules() {
        assertThrows(BlocklistParseException::class.java) { BlocklistParser.parseToList("# only comments\n\n!x\n") }
        assertThrows(BlocklistParseException::class.java) { BlocklistParser.parseToList("") }
        assertThrows(BlocklistParseException::class.java) { BlocklistParser.parseToList("\u0000\u0001\u0002binaryÿ") }
    }

    @Test fun enforcesSizeLimit() {
        val big = (1..10_000).joinToString("\n") { "host$it.example.com" }
        assertThrows(BlocklistParseException::class.java) {
            BlocklistParser.parseToList(big, ParseLimits(maxBytes = 10_000))
        }
    }

    @Test fun enforcesRuleLimit() {
        val text = (1..100).joinToString("\n") { "host$it.example.com" }
        assertThrows(BlocklistParseException::class.java) { BlocklistParser.parseToList(text, ParseLimits(maxRules = 50)) }
    }

    @Test fun overlongLinesAreSkippedWithoutBuffering() {
        val text = "ok.example.com\n" + "a".repeat(100_000) + ".com\nfine.example.com"
        val (rules, s) = BlocklistParser.parseToList(text, ParseLimits(maxLineLength = 300))
        assertEquals(listOf("ok.example.com", "fine.example.com"), rules)
        assertEquals(1, s.invalidRules)
    }

    @Test fun gzipIsDetected() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write("0.0.0.0 gz.example.com\n".toByteArray()) }
        val out = ArrayList<String>()
        BlocklistParser.parse(bos.toByteArray().inputStream() as InputStream) { d, _ -> out += d }
        assertEquals(listOf("gz.example.com"), out)
    }

    @Test fun gzipBombIsBoundedByDecompressedSize() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz -> val line = "0.0.0.0 a.example.com\n".toByteArray(); repeat(200_000) { gz.write(line) } }
        assertTrue(bos.size() < 200_000)
        assertThrows(BlocklistParseException::class.java) {
            BlocklistParser.parse(bos.toByteArray().inputStream(), ParseLimits(maxBytes = 1_000_000)) { _, _ -> }
        }
    }
}
