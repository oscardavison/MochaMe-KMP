package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.sync.api.internal.toBitmask
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagSummary
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecResolver
import com.mochame.sync.spi.models.DecodeContext
import org.koin.core.annotation.Single

internal data class OutboundCommitPackage<T : LocalFirstEntity<T>>(
    val stampedState: T,
    val payload: ByteArray,
    val changedMask: Long,
    val hlc: HLC,
    val diagnosticSummary: String
)

internal interface CrdtIntentResolver {
    fun <T : LocalFirstEntity<T>> prepareOutbound(
        candidateState: T,
        existingState: T?,
        hlc: HLC,
        op: MutationOp,
        codec: FeatureCodecResolver<T, FeatureCodec<T>>
    ): OutboundCommitPackage<T>?

    fun <T : LocalFirstEntity<T>> resolveInbound(
        payload: ByteArray,
        context: DecodeContext,
        existingState: T?,
        codec: FeatureCodecResolver<T, FeatureCodec<T>>
    ): T
}

@Single(binds = [CrdtIntentResolver::class])
internal class DefaultCrdtIntentResolver(private val logger: Logger) : CrdtIntentResolver {

    override fun <T : LocalFirstEntity<T>> prepareOutbound(
        candidateState: T,
        existingState: T?,
        hlc: HLC,
        op: MutationOp,
        codec: FeatureCodecResolver<T, FeatureCodec<T>>
    ): OutboundCommitPackage<T>? {
        val changedTags = codec.routedComputeChangedTags(candidateState, existingState)
        val changedMask = changedTags.toBitmask()
        if (changedMask == 0L) return null

        val currentBytes = existingState?.fieldHlcs ?: ByteArray(0)
        val updatedBytes = FieldHlcMap(currentBytes).updateTags(changedTags, hlc).bytes
        val stampedState = candidateState.withHlcMetadata(hlc, updatedBytes)
        val payload = codec.versionEncode(stampedState, existingState)

        return OutboundCommitPackage(
            stampedState = stampedState,
            payload = payload,
            changedMask = changedMask,
            hlc = hlc,
            diagnosticSummary = changedMask.toTagSummary(op)
        )
    }

    override fun <T : LocalFirstEntity<T>> resolveInbound(
        payload: ByteArray,
        context: DecodeContext,
        existingState: T?,
        codec: FeatureCodecResolver<T, FeatureCodec<T>>
    ): T {
        val delta = codec.versionDecode(payload, context, existingState)
        val scope = FieldMergeScope(
            existingBytes = existingState?.fieldHlcs ?: ByteArray(0),
            incomingHlc = context.hlc,
            changedMask = context.changedMask,
            isDelete = delta.isDeleted,
            logger = logger
        )

        val mergedDomain = with(codec) {
            scope.mergeDomainDelta(delta, context.candidateKey, existingState)
        }

        val deleteState = scope.resolveDeleteState(
                delta.isDeleted,
                existingState?.isDeleted,
                context.candidateKey
            )
        val createdAt = scope.resolveCreatedAt(delta.createdAt, existingState?.createdAt)
        val headerHlc = existingState?.hlc?.takeIf { it > context.hlc } ?: context.hlc

        return mergedDomain.withSyncHeader(
            hlc = headerHlc,
            lastModified = context.hlc.ts,
            createdAt = createdAt,
            isDeleted = deleteState,
            fieldHlcs = scope.buildResultBlob()
        )
    }
}