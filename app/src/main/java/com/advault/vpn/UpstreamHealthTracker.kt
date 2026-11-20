package com.advault.vpn

import com.advault.vpn.core.upstream.UpstreamHealthListener

/** Aggregates upstream health for diagnostics: EWMA latency, consecutive failures, last error. */
class UpstreamHealthTracker : UpstreamHealthListener {
    @Volatile var averageLatencyMs: Int = 0
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile private var consecutiveFailures = 0

    /** Unhealthy after 3 consecutive failed attempts; recovers on the next success. */
    val healthy: Boolean get() = consecutiveFailures < 3

    @Synchronized override fun onSuccess(server: String, latencyMs: Int) {
        consecutiveFailures = 0
        averageLatencyMs = if (averageLatencyMs == 0) latencyMs else (averageLatencyMs * 7 + latencyMs) / 8
    }

    @Synchronized override fun onFailure(server: String, error: Throwable) {
        consecutiveFailures++
        lastError = "$server: ${error.javaClass.simpleName}${error.message?.let { " ($it)" } ?: ""}"
    }

    @Synchronized fun reset() {
        consecutiveFailures = 0; averageLatencyMs = 0; lastError = null
    }
}
