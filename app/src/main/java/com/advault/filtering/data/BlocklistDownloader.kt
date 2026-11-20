package com.advault.filtering.data

import com.advault.BuildConfig
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

sealed interface FetchResult<out T> {
    data object NotModified : FetchResult<Nothing>
    data class Fetched<T>(val value: T, val etag: String?, val lastModified: String?) : FetchResult<T>
}

/** Downloads blocklists. Errors are thrown as [IOException]. */
interface BlocklistDownloader {
    /**
     * Conditional GET of [url]. On HTTP 200 the body is passed to [consume] (on the calling thread,
     * which is an IO thread) and its result returned; HTTP 304 yields [FetchResult.NotModified].
     */
    fun <T> fetch(url: String, etag: String?, lastModified: String?, consume: (InputStream) -> T): FetchResult<T>
}

object UrlRules {
    /** Returns null if [url] is an acceptable HTTPS list URL, else a reason. */
    fun httpsProblem(url: String): String? {
        val uri = try { URI(url.trim()) } catch (e: Exception) { return "Not a valid URL" }
        if (!"https".equals(uri.scheme, ignoreCase = true)) return "Only https:// URLs are allowed"
        val host = uri.host ?: return "URL has no host"
        if (uri.rawUserInfo != null) return "URLs with credentials are not allowed"
        if (host.isBlank()) return "URL has no host"
        return null
    }
}

/** [HttpsURLConnection] implementation: HTTPS only (including every redirect hop), timeouts, size limit. */
class HttpsBlocklistDownloader @Inject constructor() : BlocklistDownloader {

    override fun <T> fetch(url: String, etag: String?, lastModified: String?, consume: (InputStream) -> T): FetchResult<T> {
        var current = url
        repeat(MAX_REDIRECTS + 1) {
            UrlRules.httpsProblem(current)?.let { throw IOException(it) }
            val conn = URL(current).openConnection() as? HttpsURLConnection ?: throw IOException("Only HTTPS is allowed")
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.useCaches = false
                conn.setRequestProperty("User-Agent", "AdVault/${BuildConfig.VERSION_NAME} (Android)")
                conn.setRequestProperty("Accept", "text/plain, */*;q=0.5")
                if (etag != null) conn.setRequestProperty("If-None-Match", etag)
                if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified)
                when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val len = conn.contentLengthLong
                        if (len > MAX_DOWNLOAD_BYTES) throw IOException("List is too large (${len / (1024 * 1024)} MB)")
                        val value = conn.inputStream.use(consume)
                        return FetchResult.Fetched(value, conn.getHeaderField("ETag"), conn.getHeaderField("Last-Modified"))
                    }
                    HttpURLConnection.HTTP_NOT_MODIFIED -> return FetchResult.NotModified
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP,
                    HttpURLConnection.HTTP_SEE_OTHER, 307, 308 -> {
                        val loc = conn.getHeaderField("Location") ?: throw IOException("Redirect without location")
                        current = URL(URL(current), loc).toString()
                    }
                    else -> throw IOException("HTTP $code")
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val MAX_REDIRECTS = 5
        const val MAX_DOWNLOAD_BYTES = 40L * 1024 * 1024
    }
}
