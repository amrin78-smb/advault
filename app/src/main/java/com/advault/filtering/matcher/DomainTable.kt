package com.advault.filtering.matcher

/**
 * Immutable open-addressing hash table: 64-bit domain hash -> 64-bit value (OR-merged bit set).
 *
 * One interleaved primitive array, linear probing, power-of-two capacity, load factor <= 0.6.
 * Memory: 16 bytes per slot (no per-entry objects, no strings retained). A value of 0 means "absent",
 * so every stored value must be non-zero.
 *
 * Reads are lock-free and safe from any thread once the table has been published (all fields final).
 */
class DomainTable private constructor(
    /** Interleaved slots: [2i] = key (0 = empty), [2i + 1] = value. One cache line serves both. */
    private val slots: LongArray,
    /** Number of distinct keys. */
    val size: Int,
) {
    private val mask = (slots.size ushr 1) - 1

    val capacity: Int get() = slots.size ushr 1

    /** Bytes held by the backing array (excluding the small object header). */
    val memoryBytes: Long get() = slots.size.toLong() * 8L

    /** Returns the value stored for [key], or 0 if absent. */
    fun get(key: Long): Long {
        if (size == 0) return 0L
        val s = slots
        var i = (key xor (key ushr 31)).toInt() and mask
        while (true) {
            val k = s[i shl 1]
            if (k == key) return s[(i shl 1) + 1]
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

    @PublishedApi internal fun valueAt(i: Int): Long = if (slots[i shl 1] != 0L) slots[(i shl 1) + 1] else 0L

    class Builder(expected: Int = 16) {
        private var slots: LongArray
        private var size = 0
        private var threshold: Int

        init {
            val cap = capacityFor(expected)
            slots = LongArray(cap * 2)
            threshold = (cap * LOAD_FACTOR).toInt()
        }

        val count: Int get() = size

        /** ORs [bits] into the value for [key]. Returns true if the key was new. */
        fun put(key: Long, bits: Long): Boolean {
            require(key != 0L && bits != 0L)
            if (size >= threshold) grow()
            val isNew = insert(slots, key, bits)
            if (isNew) size++
            return isNew
        }

        private fun grow() {
            val old = slots
            val cap = old.size // new capacity = 2x old capacity (old.size == 2 * oldCap)
            check(cap > 0 && cap < (1 shl 29)) { "DomainTable capacity overflow" }
            slots = LongArray(cap * 2)
            threshold = (cap * LOAD_FACTOR).toInt()
            var i = 0
            while (i < old.size) {
                if (old[i] != 0L) insert(slots, old[i], old[i + 1])
                i += 2
            }
        }

        fun build(): DomainTable = if (size == 0) EMPTY else DomainTable(slots, size)

        private fun insert(s: LongArray, key: Long, bits: Long): Boolean {
            val mask = (s.size ushr 1) - 1
            var i = (key xor (key ushr 31)).toInt() and mask
            while (true) {
                val k = s[i shl 1]
                if (k == 0L) {
                    s[i shl 1] = key; s[(i shl 1) + 1] = bits; return true
                }
                if (k == key) {
                    s[(i shl 1) + 1] = s[(i shl 1) + 1] or bits; return false
                }
                i = (i + 1) and mask
            }
        }
    }

    companion object {
        const val LOAD_FACTOR = 0.6
        val EMPTY = DomainTable(LongArray(2), 0)

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
