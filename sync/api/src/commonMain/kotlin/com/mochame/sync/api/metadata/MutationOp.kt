package com.mochame.sync.api.metadata

import kotlinx.serialization.Serializable

/**
 * New entries must append to the existing structure. Never adjust existing ordinal state.
 */
@Serializable
enum class MutationOp(val id: Int) {
    UPSERT(0),
    DELETE(1),
    UNKNOWN(2);

    companion object {
        fun fromId(id: Int) = entries.find { it.id == id } ?: UNKNOWN

        fun safeValueOf(value: String): MutationOp {
            return entries.firstOrNull { it.name == value } ?: UNKNOWN
        }
    }
}

// --- DIAGNOSTICS ---

/**
 * Traverses active bits.
 */
fun Long.toTagList(): List<Int> = buildList {
    var temp = this@toTagList
    while (temp != 0L) {
        add(temp.countTrailingZeroBits())
        temp = temp and (temp - 1L)
    }
}


fun Long.toTagSummary(op: MutationOp): String {
    val opStr = if (op == MutationOp.DELETE) "DELETE" else "UPSERT"
    if (this == 0L) return "OP:$opStr []"
    return "OP:$opStr ${toTagList().joinToString(prefix = "[", postfix = "]", separator = ",")}"
}