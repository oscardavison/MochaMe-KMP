package com.mochame.sync.api.internal


@Suppress("NOTHING_TO_INLINE")
inline fun Long.hasTag(tag: Int): Boolean = (this and (1L shl tag)) != 0L

@Suppress("NOTHING_TO_INLINE")
inline fun Long.withTag(tag: Int): Long = this or (1L shl tag)

/**
 * Converts a list of changed tag indices into a 64-bit mask.
 */
fun List<Int>.toBitmask(): Long {
    var mask = 0L
    for (i in indices) {
        val tag = this[i]
        if (tag in 0..63) {
            mask = mask or (1L shl tag)
        }
    }
    return mask
}

fun bitmaskOf(vararg tags: Int): Long {
    var mask = 0L
    for (i in tags.indices) {
        val tag = tags[i]
        if (tag in 0..63) {
            mask = mask or (1L shl tag)
        }
    }
    return mask
}

