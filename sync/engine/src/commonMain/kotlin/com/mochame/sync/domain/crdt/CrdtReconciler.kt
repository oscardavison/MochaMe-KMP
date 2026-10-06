package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.sync.utils.toBitmask
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagSummary
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.codec.CodecResolver
import com.mochame.sync.api.utils.getCodec
import com.mochame.sync.api.utils.latestCodec
import com.mochame.sync.domain.model.DecodeContext
import org.koin.core.annotation.Single

internal data class OutboundContext<T : LocalFirstEntity<T>>(
    val stampedState: T,
    val payload: ByteArray,
    val changedMask: Long,
    val hlc: HLC,
    val diagnosticSummary: String
)

internal interface CrdtReconciler {
    fun <T : LocalFirstEntity<T>> prepareOutbound(
        candidateState: T,
        existingState: T?,
        hlc: HLC,
        op: MutationOp,
        codecResolver: CodecResolver<T, FeatureCodec<T>>
    ): OutboundContext<T>?

    fun <T : LocalFirstEntity<T>> resolveInbound(
        payload: ByteArray,
        decodeContext: DecodeContext,
        existingState: T?,
        codecResolver: CodecResolver<T, FeatureCodec<T>>
    ): T
}

@Single(binds = [CrdtReconciler::class])
internal class DefaultCrdtReconciler(private val logger: Logger) : CrdtReconciler {

    override fun <T : LocalFirstEntity<T>> prepareOutbound(
        candidateState: T,
        existingState: T?,
        hlc: HLC,
        op: MutationOp,
        codecResolver: CodecResolver<T, FeatureCodec<T>>
    ): OutboundContext<T>? {
        val targetCodec = codecResolver.latestCodec
        val changedTags = targetCodec.computeChangedTags(candidateState, existingState)
        val changedMask = changedTags.toBitmask()
        if (changedMask == 0L) return null

        val currentBytes = existingState?.fieldHlcs ?: ByteArray(0)
        val updatedBytes = FieldHlcMap(currentBytes).updateTags(changedTags, hlc).bytes
        val stampedState = candidateState.withHlcMetadata(hlc, updatedBytes)
        val payload = targetCodec.encode(stampedState, existingState)

        return OutboundContext(
            stampedState = stampedState,
            payload = payload,
            changedMask = changedMask,
            hlc = hlc,
            diagnosticSummary = changedMask.toTagSummary(op)
        )
    }

    override fun <T : LocalFirstEntity<T>> resolveInbound(
        payload: ByteArray,
        decodeContext: DecodeContext,
        existingState: T?,
        codecResolver: CodecResolver<T, FeatureCodec<T>>
    ): T {
        val targetCodec = codecResolver.getCodec(decodeContext.featureSchemaVersion, logger)
        val delta = targetCodec.deserializeDelta(payload)
        val isDelete = delta.isDeleted == true

        val scope = FieldMergeScope(
            existingBytes = existingState?.fieldHlcs ?: ByteArray(0),
            incomingHlc = decodeContext.hlc,
            changedMask = decodeContext.changedMask,
            isDelete = isDelete,
            logger = logger
        )

        val mergedDomain = targetCodec.mergeDomain(scope, delta, decodeContext.primaryKey, existingState)

        val deleteState = scope.resolveDeleteState(
            delta.isDeleted,
            existingState?.isDeleted,
            decodeContext.primaryKey
        )
        val createdAt = scope.resolveCreatedAt(delta.createdAt, existingState?.createdAt)
        val headerHlc = existingState?.hlc?.takeIf { it > decodeContext.hlc } ?: decodeContext.hlc

        return mergedDomain.withSyncHeader(
            hlc = headerHlc,
            lastModified = decodeContext.hlc.ts,
            createdAt = createdAt,
            isDeleted = deleteState,
            fieldHlcs = scope.buildResultBlob()
        )
    }
}