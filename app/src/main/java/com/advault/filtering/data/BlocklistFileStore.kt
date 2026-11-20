package com.advault.filtering.data

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter

/**
 * Compiled list storage: one normalised rule per line in `<dir>/<id>.txt`.
 * Writes go to a temp file in the same directory and are atomically renamed into place, so a failed
 * download or parse never damages the previously working list.
 */
class BlocklistFileStore(val dir: File) {

    fun file(id: String): File {
        require(ID.matches(id)) { "Invalid list id" }
        return File(dir, "$id.txt")
    }

    fun exists(id: String): Boolean = file(id).isFile

    fun open(id: String): InputStream = file(id).inputStream()

    fun delete(id: String) {
        file(id).delete()
    }

    /** Writes via [block] to a temp file, fsyncs and renames over the old file. Returns [block]'s value. */
    fun <T> writeAtomically(id: String, block: (BufferedWriter) -> T): T {
        val target = file(id)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create ${dir.path}")
        val tmp = File(dir, "$id.txt.tmp-${System.nanoTime()}")
        try {
            val fos = FileOutputStream(tmp)
            val result = fos.use { out ->
                val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024)
                val r = block(w)
                w.flush()
                out.fd.sync()
                r
            }
            if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
            return result
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Removes temp files left over from an interrupted write. */
    fun cleanupTemp() {
        dir.listFiles()?.filter { it.name.contains(".txt.tmp-") }?.forEach { it.delete() }
    }

    companion object {
        val ID = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    }
}
