package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.hlc.instant
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.common.readProtobufVarint
import com.mochame.sync.common.skipProtobufValue
import com.mochame.sync.spi.infrastructure.BufferProvider
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.FIRST_DOMAIN_TAG
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_IS_DELETED
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_PRIMARY_KEY
import com.mochame.sync.spi.models.DecodeContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.time.Instant

/**
 * Centralizes standard domain delta logic.
 * All implementing [FeatureCodec]s and their Serializable deltas must define:
 * * Primary Key (id) : [TAG_PRIMARY_KEY]
 * * isDeleted: [TAG_IS_DELETED]
 * * Domain Fields: [FIRST_DOMAIN_TAG]+
 * ```kotlin
 * internal data class FeatureEntityDeltaV1(
 *     @ProtoNumber(TAG_PRIMARY_KEY) val id: Long,
 *     @ProtoNumber(TAG_IS_DELETED) val isDeleted: Boolean? = null,
 *     @ProtoNumber(TAG_CREATED_AT) val createdAt: Long? = null
 * ```
 *
 * * [T] = Main Domain Entity (e.g. DailyContext)
 * * [D] = Serializable Protobuf Delta Schema (e.g. DailyContextDeltaV1)
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

    /**
     * Encodes a delta between [new] and optional [old] entity states into Protobuf bytes.
     *
     * Dispatches to [buildDeleteDelta] when [new] is deleted, [buildInsertDelta] when [old] is null,
     * or [buildUpdateDelta] for sparse field updates and restorations.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun encode(new: T, old: T?): ByteArray {
        val delta = when {
            new.isDeleted -> buildDeleteDelta(new)
            old == null -> buildInsertDelta(new)
            else -> buildUpdateDelta(new, old, isRestored = old.isDeleted)
        }

        return try {
            val bytes = ProtoBuf.encodeToByteArray(deltaSerializer, delta)
            logger.v { "Encoded $deltaName [${bytes.size}B] key=${new.id}" }
            bytes
        } catch (e: Exception) {
            logger.e(e) { "Failed to encode delta payload for entity key=${new.id}" }
            throw e
        }
    }

    /**
     * Decodes incoming Protobuf delta bytes and merges them against optional [existing] state.
     *
     * Instantiates a [FieldMergeScope] to resolve field-level LWW values, evaluates deletion
     * status via [resolveDeleteState], and stamps the final sync header metadata.
     */
    @OptIn(ExperimentalSerializationApi::class)
    override fun decode(
        bytes: ByteArray,
        context: DecodeContext,
        existing: T?
    ): T {
        logger.v { "Decoding $deltaName [${bytes.size}B] key=${context.candidateKey} hlc=${context.hlc}..." }

        val delta = try {
            ProtoBuf.decodeFromByteArray(deltaSerializer, bytes)
        } catch (e: Exception) {
            logger.e(e) { "Protobuf decoding failed: key=${context.candidateKey} hlc=${context.hlc} schema=${context.featureSchemaVersion} (${bytes.size} bytes)" }
            throw e
        }

        val isDelete = delta.isDeleted == true

        val scope = FieldMergeScope(
            existingBytes = existing?.fieldHlcs ?: ByteArray(0),
            incomingHlc = context.hlc,
            changedMask = context.changedMask,
            logger = logger,
            isDelete = isDelete
        )

        val createdAt = resolveCreatedAt(delta.createdAt, existing?.createdAt, context)
        val mergedDomain = scope.mergeDomainDelta(delta, context, existing)
        val deleteState = scope.resolveDeleteState(delta.isDeleted, existing?.isDeleted, delta.id)

        val headerHlc = existing?.hlc?.takeIf { it > context.hlc } ?: context.hlc
        return mergedDomain.withSyncHeader(
            hlc = headerHlc,
            lastModified = context.hlc.ts,
            createdAt = createdAt,
            isDeleted = deleteState,
            fieldHlcs = scope.buildResultBlob()
        ).also { logger.v { "Decoding finalized. key=${it.id}" } }
    }

    /**
     * Resolves the entity deletion state using [TAG_IS_DELETED] as an LWW register:
     * - Explicit delete (`deltaIsDeleted == true`): Marks deleted if [incomingHlc] is newer than the recorded delete HLC.
     * - Explicit un-delete (`deltaIsDeleted == false`): Restores active status if [incomingHlc] is newer than the recorded delete HLC.
     * - Implicit revival (`existingIsDeleted == true`): Restores active status when an incoming upsert arrives with
     *   an [incomingHlc] newer than the tombstone's recorded delete HLC.
     */
    private fun FieldMergeScope.resolveDeleteState(
        deltaIsDeleted: Boolean?,
        existingIsDeleted: Boolean?,
        candidateKey: Long
    ): Boolean {
        val lastDeleteHlc = getTagHlc(TAG_IS_DELETED)
        val isNewer = lastDeleteHlc == null || incomingHlc > lastDeleteHlc

        return when {
            // Explicit delete intent
            deltaIsDeleted == true -> {
                if (isNewer) {
                    updateTag(TAG_IS_DELETED, incomingHlc)
                    val hasSurvivingField = hasTagNewerThan(incomingHlc, TAG_IS_DELETED)
                    !hasSurvivingField
                } else {
                    existingIsDeleted ?: true
                }
            }

            // Explicit un-delete intent (e.g., CLI or UI toggling isDeleted back to false)
            deltaIsDeleted == false -> {
                if (isNewer) {
                    updateTag(TAG_IS_DELETED, incomingHlc)
                    logger.i { "Restored [key=$candidateKey]: explicit restore (HLC=$incomingHlc) overrides delete (HLC=$lastDeleteHlc)" }
                    false
                } else {
                    existingIsDeleted ?: false
                }
            }

            // Implicit revival: Incoming upsert arrives against an existing tombstone
            existingIsDeleted == true -> {
                if (isNewer) {
                    updateTag(TAG_IS_DELETED, incomingHlc)
                    logger.i { "Restored [key=$candidateKey]: incoming edit (HLC=$incomingHlc) overrides delete (HLC=$lastDeleteHlc)" }
                    false
                } else {
                    true
                }
            }

            else -> false
        }
    }

    /**
     * Resolves entity creation timestamp:
     * - Retains existing timestamp if already present locally.
     * - Adopts incoming timestamp on initial insert.
     * - Uses `minOf(existing, incoming)` if both exist.
     * - Falls back to [context] HLC timestamp if an out-of-order field delta arrives without an existing record.
     */
    protected fun resolveCreatedAt(
        deltaCreatedAt: Long?,
        existingCreatedAt: Instant?,
        context: DecodeContext
    ): Instant {

        return when {
            existingCreatedAt == null && deltaCreatedAt != null -> {
                Instant.fromEpochMilliseconds(deltaCreatedAt)
            }

            existingCreatedAt != null && deltaCreatedAt == null -> {
                existingCreatedAt
            }

            existingCreatedAt != null && deltaCreatedAt != null -> {
                val incoming = Instant.fromEpochMilliseconds(deltaCreatedAt)
                logger.d { "Conflict [key=${context.candidateKey}, tag=$TAG_CREATED_AT]: using min(in=$incoming, local=$existingCreatedAt)" }
                minOf(existingCreatedAt, incoming)
            }

            else -> {
                logger.w {
                    "Out-of-order intent detected [key=${context.candidateKey}]: " +
                            "Received field upsert intent with no local existing record and missing createdAt. " +
                            "Falling back to HLC timestamp (${context.hlc.ts}); expecting override when origin insert arrives."
                }
                context.hlc.instant
            }
        }
    }

    /**
     * Extracts tag numbers from raw Protobuf bytes using stream inspection without full object deserialization.
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

    /**
     * Computes changed tag IDs between [new] and optional [old] states.
     *
     * Automatically includes [TAG_IS_DELETED] on deletion state transitions, tags 1 and 3 on initial
     * insertions, and delegates domain field diffing to [computeDomainChangedTags].
     */
    override fun computeChangedTags(new: T, old: T?): List<Int> = buildList {
        val deleteStateChange = new.isDeleted != (old?.isDeleted ?: false)

        if (deleteStateChange) add(TAG_IS_DELETED)

        if (!new.isDeleted) {
            if (old == null) {
                add(TAG_PRIMARY_KEY)
                add(TAG_CREATED_AT)
            }
            addAll(computeDomainChangedTags(new, old))
        }
    }

    // --- FEATURE REQUIREMENTS ---
    protected abstract fun buildDeleteDelta(entity: T): D
    protected abstract fun buildInsertDelta(entity: T): D

    /**
     * /** Builds a sparse update delta containing only modified fields. */
     * Feature implementation example:
     *
     * ```kotlin
     *     override fun buildUpdateDelta(
     *         new: FeatureEntity,
     *         old: FeatureEntity,
     *         isRestored: Boolean
     *     ): FeatureEntityDeltaV1 = FeatureEntityDeltaV1(
     *         id = new.id,
     *         isDeleted = false.takeIf { isRestored },
     *
     *         // Domain fields follow
     *         textValue = new.textValue diff old.textValue,
     *         countValue = new.countValue diff old.countValue
     * ```
     */
    protected abstract fun buildUpdateDelta(new: T, old: T, isRestored: Boolean): D

    /**
     * Compute changed tag IDs strictly for domain fields.
     * Features only work with their [TAG_PRIMARY_KEY], and from [FIRST_DOMAIN_TAG]+
     */
    protected abstract fun computeDomainChangedTags(new: T, old: T?): List<Int>

    /**
     * Maps the decoded protobuf delta [D] onto domain entity [T] using [DecodeContext] and optional [existing] state.
     * Features only work from [FIRST_DOMAIN_TAG]+
     *
     * ```kotlin
     *     override fun FieldMergeScope.mergeDomainDelta(
     *         delta: FeatureEntityDeltaV1,
     *         context: DecodeContext,
     *         existing: FeatureEntity?
     *     ): FeatureEntity = FeatureEntity(
     *         id = context.candidateKey,
     *
     *         textValue = eval(TAG_TEXT_VALUE, delta.textValue, existing?.textValue),
     *         countValue = eval(TAG_COUNT_VALUE, delta.countValue, existing?.countValue)
     *     )
     * ```
     *
     * Implementation note: When adding new fields in future schema revisions, older client versions
     * only evaluate tags known at compile time; unknown tags will not participate in LWW evaluation.
     * This is solvable.
     */
    protected abstract fun FieldMergeScope.mergeDomainDelta(
        delta: D,
        context: DecodeContext,
        existing: T?
    ): T


    private val BaseFeatureCodec<T, D>.deltaName
        get() = this.deltaSerializer.descriptor.serialName.substringAfterLast(".")
}

/**
 * Returns this value if it differs from [old], or null if unchanged.
 */
@Suppress("NOTHING_TO_INLINE")
inline infix fun <T> T.diff(old: T): T? = if (this != old) this else null