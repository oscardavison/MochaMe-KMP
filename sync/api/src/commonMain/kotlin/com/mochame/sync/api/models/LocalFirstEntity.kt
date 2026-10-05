package com.mochame.sync.api.models

import kotlin.time.Instant


/**
 * When a concrete class implements this contract, it must declare itself as the
 * type parameter T. Can be thought of a compiler contract where T
 * becomes your models type, meaning manual casting by the developer is not required
 * for all generic local first sync manipulations on a given model.
 *
 * The intended application is for all feature models that exist in this
 * distributed system to implement this contract.
 *
 * To understand, look into the bytecode adjustments and background
 * checkcast instructions that must be the result of this compiler token system...
 */
interface LocalFirstEntity<T : LocalFirstEntity<T>> {
    val id: Long
    val isDeleted: Boolean
    val hlc: HLC
    val fieldHlcs: ByteArray
    val createdAt: Instant
    val lastModified: Long

    fun withHlcMetadata(hlc: HLC, fieldBlob: ByteArray): T
    fun withDeleteState(isDeleted: Boolean): T
    fun withSyncHeader(
        hlc: HLC,
        lastModified: Long,
        createdAt: Instant,
        isDeleted: Boolean,
        fieldHlcs: ByteArray
    ): T
}

// would be useful to have a permanent indication that the instance is synced