package com.advault.filtering.matcher

/**
 * Immutable open-addressing hash table: 64-bit domain hash -> 64-bit value (OR-merged bit set).
 *
 * Two parallel primitive arrays, linear probing, power-of-two capacity, load factor <= 0.6.
 * Memory: 16 bytes per slot (no per-entry objects, no strings retained). A value of 0 means "absent",
 * so every stored value must be non-zero.
 *
 * Reads are lock-free and safe from any thread once the table has been published (all fields final).
 */
class DomainTable private constructor(
    private val keys: LongArray,
    private val values: LongArray,
    /** Number of distinct keys. */
    val size: Int,
) {
    private val mask = keys.size - 1

    val capacity: Int get() = keys.size

    /** Bytes held by the backing arrays (excluding the small object headers). */
    val memoryBytes: Long get() = keys.size.toLong() * 16L

    /** Returns the value stored for [key], or 0 if absent. */
    fun get(key: Long): Long {
        if (size == 0) return 0L
        var i = (key xor (key ushr 31)).toInt() and mask
        while (true) {
            val k = keys[i]
            if (k == key) return values[i]
            if (k == 0L) return 0L
            i = (i + 1) and mask
        }
    }

    inline fun forEachValue(block: (Long) -> Unit) {
        for (i in 0 until capacity) {
            val v = valueAt(i)
            if (v != 0L) block(v)
        }
    }

    @PublishedApi internal fun valueAt(i: Int): Long = if (keys[i] != 0L) values[i] else 0L

    class Builder(expected: Int = 16) {
        private var keys: LongArray
        private var values: LongArray
        private var size = 0
        private var threshold: Int

        init {
            val cap = capacityFor(expected)
            keys = LongArray(cap)
            values = LongArray(cap)
            threshold = (cap * LOAD_FACTOR).toInt()
        }

        val count: Int get() = size

        /** ORs [bits] into the value for [key]. Returns true if the key was new. */
        fun put(key: Long, bits: Long): Boolean {
            require(key != 0L && bits != 0L)
            if (size >= threshold) grow()
            val isNew = insert(keys, values, key, bits)
            if (isNew) size++
            return isNew
        }

        fun get(key: Long): Long {
            val mask = keys.size - 1
            var i = (key xor (key ushr 31)).toInt() and mask
            while (true) {
                val k = keys[i]
                if (k == key) return values[i]
                if (k == 0L) return 0L
                i = (i + 1) and mask
            }
        }

        private fun grow() {
            val oldK = keys
            val oldV = values
            val cap = oldK.size * 2
            check(cap > 0) { "DomainTable capacity overflow" }
            keys = LongArray(cap)
            values = LongArray(cap)
            threshold = (cap * LOAD_FACTOR).toInt()
            for (i in oldK.indices) if (oldK[i] != 0L) insert(keys, values, oldK[i], oldV[i])
        }

        fun build(): DomainTable = if (size == 0) EMPTY else DomainTable(keys, values, size)

        private fun insert(keys: LongArray, values: LongArray, key: Long, bits: Long): Boolean {
            val mask = keys.size - 1
            var i = (key xor (key ushr 31)).toInt() and mask
            while (true) {
                val k = keys[i]
                if (k == 0L) {
                    keys[i] = key; values[i] = bits; return true
                }
                if (k == key) {
                    values[i] = values[i] or bits; return false
                }
                i = (i + 1) and mask
            }
        }
    }

    companion object {
        const val LOAD_FACTOR = 0.6
        val EMPTY = DomainTable(LongArray(1), LongArray(1), 0)

        fun capacityFor(expected: Int): Int {
            val needed = (expected / LOAD_FACTOR).toLong() + 1
            var cap = 16
            while (cap < needed) cap = cap shl 1
            return cap
        }
    }
}

/** Minimal open-addressing set of non-zero longs, used for de-duplication while parsing. */
class LongHashSet(expected: Int = 1024) {
    private var keys = LongArray(DomainTable.capacityFor(expected))
    var size = 0
        private set

    /** Returns true if [key] was added (not already present). */
    fun add(key: Long): Boolean {
        require(key != 0L)
        if (size >= keys.size * DomainTable.LOAD_FACTOR) grow()
        return insert(keys, key).also { if (it) size++ }
    }

    private fun grow() {
        val old = keys
        keys = LongArray(old.size * 2)
        for (k in old) if (k != 0L) insert(keys, k)
    }

    private fun insert(arr: LongArray, key: Long): Boolean {
        val mask = arr.size - 1
        var i = (key xor (key ushr 31)).toInt() and mask
        while (true) {
            val k = arr[i]
            if (k == 0L) { arr[i] = key; return true }
            if (k == key) return false
            i = (i + 1) and mask
        }
    }
}
