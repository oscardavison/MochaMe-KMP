package com.mochame.sync.api.models

/**
 * Feature Implementations are to assign:
 * * [id]: ProtoNumber 1
 * * [isDeleted]: ProtoNumber 2
 * * [createdAt]: ProtoNumber 3
 *
 * Max Tag Count: 63 (Internal sync logic stores the ProtoNumber as a single [Byte]
 * & a mask stores all possible tag changes in a single [Long]).
 *
 * ```kotlin
    @ProtoNumber(TAG_PRIMARY_KEY) override val id: Long,
    @ProtoNumber(TAG_IS_DELETED) override val isDeleted: Boolean? = null,
    @ProtoNumber(TAG_CREATED_AT) override val createdAt: Long? = null,
 * ```
 */
interface LocalFirstDelta {
    val id: Long
    val isDeleted: Boolean?
    val createdAt: Long?
}