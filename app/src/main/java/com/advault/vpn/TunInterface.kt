package com.advault.vpn

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import com.advault.vpn.core.PacketWriter
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Owns one established TUN file descriptor plus its reader thread.
 *
 * The fd is blocking (Builder.setBlocking(true)); the reader thread poll()s on the TUN fd and on a private
 * wake-up pipe so [close] can stop it promptly with zero idle wake-ups (no polling timer -> no battery cost).
 * All writes are serialized through [writer].
 */
class TunInterface(
    private val pfd: ParcelFileDescriptor,
    private val mtu: Int,
    private val onPacket: (ByteArray, Int) -> Unit,
    /** Called once if the reader stops for any reason other than [close]. */
    private val onFailure: (Throwable?) -> Unit,
) {
    private val input = FileInputStream(pfd.fileDescriptor)
    private val output = FileOutputStream(pfd.fileDescriptor)
    private val writeLock = Any()
    @Volatile private var closed = false
    private val wakeRead: FileDescriptor
    private val wakeWrite: FileDescriptor
    private val thread: Thread

    init {
        val pipe = Os.pipe()
        wakeRead = pipe[0]
        wakeWrite = pipe[1]
        thread = Thread(::readLoop, "AdVault-tun-reader").apply { isDaemon = true }
    }

    val writer = PacketWriter { packet ->
        synchronized(writeLock) {
            if (!closed) {
                try { output.write(packet) } catch (e: IOException) {
                    if (!closed) Log.w(TAG, "TUN write failed: ${e.message}")
                }
            }
        }
    }

    fun start() = thread.start()

    private fun readLoop() {
        val buf = ByteArray(mtu.coerceAtLeast(1500) + 64)
        val tunPoll = StructPollfd().apply { fd = pfd.fileDescriptor; events = OsConstants.POLLIN.toShort() }
        val wakePoll = StructPollfd().apply { fd = wakeRead; events = OsConstants.POLLIN.toShort() }
        val fds = arrayOf(tunPoll, wakePoll)
        var failure: Throwable? = null
        try {
            while (!closed) {
                tunPoll.revents = 0; wakePoll.revents = 0
                try {
                    Os.poll(fds, -1)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.EINTR) continue
                    throw e
                }
                if (closed || wakePoll.revents.toInt() != 0) break
                val rev = tunPoll.revents.toInt()
                if (rev and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) {
                    throw IOException("TUN poll error (revents=$rev)")
                }
                if (rev and OsConstants.POLLIN != 0) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("TUN closed")
                    if (n > 0) onPacket(buf, n)
                }
            }
        } catch (t: Throwable) {
            failure = t
        }
        if (!closed) {
            Log.w(TAG, "TUN reader stopped unexpectedly", failure)
            onFailure(failure)
        }
    }

    /** Stops the reader thread and closes the TUN. Safe to call more than once. */
    fun close() {
        if (closed) return
        closed = true
        try { Os.write(wakeWrite, byteArrayOf(1), 0, 1) } catch (_: Exception) {}
        if (Thread.currentThread() !== thread) {
            try { thread.join(2_000) } catch (_: InterruptedException) {}
        }
        synchronized(writeLock) { runCatching { pfd.close() } }
        runCatching { Os.close(wakeRead) }
        runCatching { Os.close(wakeWrite) }
    }

    companion object { private const val TAG = "AdVaultTun" }
}
