package com.mochame.sync.api.codec

import co.touchlab.kermit.Logger
import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.utils.readProtobufVarint
import com.mochame.sync.api.utils.skipProtobufValue
import com.mochame.sync.spi.BufferProvider
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf

/**
 * Base template for feature serializers.
 *
 * Defines the wire serialization contract and coordinates domain diffing. All delta
 * schemas require tags 1 through 3 reserved for primary keys, deletion state, and creation time.
 *
 * @param T Main domain entity representation.
 * @param D Serializable Protobuf delta object.
 */
abstract class BaseFeatureCodec<T : LocalFirstEntity<T>, D : LocalFirstDelta>(
    override val bufferProvider: BufferProvider,
    private val deltaSerializer: KSerializer<D>,
    protected val logger: Logger
) : FeatureCodec<T> {

    init {
        val descriptor = deltaSerializer.descriptor
        try {
            require(descriptor.elementsCount >= 3) { "Schema Error: ${descriptor.serialName} needs at least 3 properties." }
            require(descriptor.getElementName(0) == "id") { "Schema Error: Tag 1 in ${descriptor.serialName} must be 'id'." }
            require(descriptor.getElementName(1) == "isDeleted") { "Schema Error: Tag 2 in ${descriptor.serialName} must be 'isDeleted'." }
            require(descriptor.getElementName(2) == "createdAt") { "Schema Error: Tag 3 in ${descriptor.serialName} must be 'createdAt'." }
        } catch (e: IllegalArgumentException) {
            logger.e(e) { "Invalid Feature Delta Schema Contract: ${descriptor.serialName}" }
            throw e
        }
    }

    companion object {
        const val TAG_PRIMARY_KEY = 1
        const val TAG_IS_DELETED = 2
        const val TAG_CREATED_AT = 3
        const val FIRST_DOMAIN_TAG = 4
    }

    @OptIn(ExperimentalSerializationApi::class)
    fun serializeDelta(delta: D): ByteArray = ProtoBuf.encodeToByteArray(deltaSerializer, delta)

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserializeDelta(bytes: ByteArray): D =
        ProtoBuf.decodeFromByteArray(deltaSerializer, bytes)

    /**
     * Serializes a domain transition into raw Protobuf bytes.
     */
    override fun encode(new: T, old: T?): ByteArray {
        val delta = when {
            new.isDeleted -> buildDeleteDelta(new)
            old == null -> buildInsertDelta(new)
            else -> buildUpdateDelta(new, old, isRestored = old.isDeleted)
        }
        return serializeDelta(delta)
    }

    /**
     * Computes all changed tag numbers between two states, automatically accounting
     * for header tags (1, 2, 3) and delegating domain tags to [computeDomainChangedTags].
     */
    override fun computeChangedTags(new: T, old: T?): List<Int> = buildList {
        val deleteStateChange = new.isDeleted != (old?.isDeleted ?: false)
        if (deleteStateChange && new.isDeleted) add(TAG_IS_DELETED)

        if (!new.isDeleted) {
            if (old == null) {
                add(TAG_PRIMARY_KEY)
                add(TAG_CREATED_AT)
            }
            addAll(computeDomainChangedTags(new, old))
        }
    }

    /**
     * Extracts tag numbers from raw Protobuf bytes using stream inspection without full object deserialization.
     * Mostly used to learn how protobuf bytes are structured.
     */
    override fun reconstructSummary(bytes: ByteArray): String {
        if (bytes.isEmpty()) {
            logger.w { "Summary Reconstruction Failed. Received empty ByteArray." }
            return "OP:INVALID_EMPTY_BYTES"
        }

        val buffer = bufferProvider.get().apply {
            this.clear()
            this.write(bytes)
        }

        return try {
            val peekSource = buffer.peek()

            var isDeleted = false
            val tags = buildList {
                while (!peekSource.exhausted()) {
                    val key = peekSource.readProtobufVarint(logger)
                    val tag = (key ushr 3).toInt()
                    val wireType = (key and 0x07L).toInt()

                    if (tag == TAG_IS_DELETED) add(tag).also { isDeleted = true }
                    if (tag == TAG_CREATED_AT) add(tag)
                    if (tag >= FIRST_DOMAIN_TAG) add(tag)

                    peekSource.skipProtobufValue(wireType, logger)
                }
            }

            val opCode = if (isDeleted) "DELETE" else "UPSERT"
            with("OP:$opCode [${tags.distinct().sorted().joinToString(",")}]") {
                logger.d { "Reconstructed Summary: $this" }
                return this
            }
        } catch (e: Exception) {
            logger.e(e) { "Packet summary reconstruction failed on payload size=${bytes.size}B" }
            "OP:CORRUPT_PACKET"
        }
    }

    // --- FEATURE REQUIREMENTS ---
    /**
     * Define [LocalFirstEntity.isDeleted] for [LocalFirstEntity.id]. Example:
     * ```kotlin
     *     override fun buildDeleteDelta(entity: DailyContext) = DailyContextDeltaV1(
     *         id = entity.id,
     *         isDeleted = true
     *     )
     * ```
     */
    protected abstract fun buildDeleteDelta(entity: T): D
    /**
     * Define the domain fields alongside [LocalFirstEntity.id] and [LocalFirstEntity.createdAt]. Example:
     * ```kotlin
 *         override fun buildInsertDelta(entity: DailyContext) = DailyContextDeltaV1(
     *         id = entity.id,
     *         createdAt = entity.createdAt.toEpochMilliseconds(),
     *         ...
     *     )
     * ```
     */
    protected abstract fun buildInsertDelta(entity: T): D

    /**
     * Define the [diff] of the domain fields, alongside [LocalFirstEntity.id] and [LocalFirstEntity.isDeleted] based on
     * the provided restore value. Example
     * ```kotlin
     *  override fun buildUpdateDelta(
     *      new: DailyContext,
     *      old: DailyContext,
     *      isRestored: Boolean
     *  ): DailyContextDeltaV1 = DailyContextDeltaV1(
     *      id = new.id,
     *      isDeleted = false.takeIf { isRestored },
     *      notes = new.notes diff old.notes
     *
     * ```
     */
    protected abstract fun buildUpdateDelta(new: T, old: T, isRestored: Boolean): D

    /**
     * Compute changed tag IDs strictly for domain fields.
     * Features only work from [FIRST_DOMAIN_TAG]+. Example:
     *
     * ```kotlin
     *     override fun computeDomainChangedTags(new: DailyContext, old: DailyContext?): List<Int> =
     *         buildList {
     *             if (new.notes != old?.notes) add(TAG_NOTES)
     *         }
     * ```
     */
    protected abstract fun computeDomainChangedTags(new: T, old: T?): List<Int>

    /**
     * Maps the unpacked Protobuf delta onto the domain entity using the provided [FieldResolver].
     */
    override fun mergeDomain(resolver: FieldResolver, delta: LocalFirstDelta, candidateKey: Long, existing: T?): T {
        @Suppress("UNCHECKED_CAST")
        val typedDelta = delta as D

        return with(resolver) {
            mergeDomainDelta(typedDelta, candidateKey, existing)
        }
    }

    /**
     * Field level resolution for domain properties at [FIRST_DOMAIN_TAG]+
     */
    abstract fun FieldResolver.mergeDomainDelta(
        delta: D,
        candidateKey: Long,
        existing: T?
    ): T
}

/**
 * Returns this value if it differs from [old], or null if unchanged.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun <T> T.diff(old: T): T? = if (this != old) this else null