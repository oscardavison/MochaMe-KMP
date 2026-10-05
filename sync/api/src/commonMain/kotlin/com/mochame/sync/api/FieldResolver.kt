package com.mochame.sync.api

/**
 * Resolves field-level Last-Write-Wins (LWW) conflicts between incoming deltas and local records.
 */
interface FieldResolver {
    /**
     * Evaluates whether [incoming] supersedes [existing] for [tagId].
     *
     * @param tagId Protobuf field tag identifying the domain property.
     * @param incoming Candidate value from the incoming delta payload.
     * @param existing Current value from the local database record.
     * @return The winning value determined by field-level timestamps.
     */
    fun <V> resolve(tagId: Int, incoming: V?, existing: V?): V?
}