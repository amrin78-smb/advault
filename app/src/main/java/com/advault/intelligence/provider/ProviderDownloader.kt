package com.advault.intelligence.provider

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

/** Fetches raw provider-database text. Abstracted so tests never touch the network. */
fun interface ProviderDownloader {
    /** Returns the response body as UTF-8 text, or throws. Must enforce [maxBytes]. */
    @Throws(IOException::class)
    fun download(url: String, maxBytes: Int): String
}

/** HTTPS-only downloader with timeouts and a hard size cap. Content is returned as text and never executed. */
class HttpsProviderDownloader @Inject constructor() : ProviderDownloader {
    override fun download(url: String, maxBytes: Int): String {
        require(url.startsWith("https://")) { "Provider updates must use HTTPS" }
        val conn = URL(url).openConnection() as? HttpsURLConnection ?: throw IOException("Not an HTTPS connection")
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json, text/plain")
            conn.setRequestProperty("User-Agent", "AdVault")
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val declared = conn.contentLengthLong
            if (declared > maxBytes) throw IOException("Response too large ($declared bytes)")
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("Response too large (> $maxBytes bytes)")
                    out.write(buf, 0, n)
                }
                return out.toString(Charsets.UTF_8.name())
            }
        } finally {
            conn.disconnect()
        }
    }
}
