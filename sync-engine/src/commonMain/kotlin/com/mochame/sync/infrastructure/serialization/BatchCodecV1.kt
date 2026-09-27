package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.spi.models.SyncIntent
import com.mochame.sync.spi.infrastructure.serialization.BatchCodec
import com.mochame.sync.spi.infrastructure.serialization.IntentCodecRouter
import io.ktor.utils.io.CancellationException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.koin.core.annotation.Single

/**
    Mixed intent versioning inside a single payload should be impossible on the outbound path.
    Codecs always use the latest available version.
 **/
@ExperimentalSerializationApi
@Serializable
internal data class SyncBatchPayloadV1(
    @ProtoNumber(1) val envelopes: List<ByteArray> = emptyList(),
    @ProtoNumber(2) val intentSchemaVersion: Int
)

@OptIn(ExperimentalSerializationApi::class)
@Single(binds = [BatchCodec::class])
internal class BatchCodecV1(
    private val intentCodecRouter: IntentCodecRouter,
    logger: Logger
) : BatchCodec {

    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.SYNC, "BaCdc1")


    /**
     * The idea is that using protobuf for serialization should never really throw runtime
     * errors in production, and any single intent can only be a valid [SyncIntent] model on persistence.
     */
    override fun encode(intents: List<SyncIntent>): ByteArray {
        require(intents.isNotEmpty()) { "Cannot serialise an empty payload" }

        return try {
            val serializedEnvelopes = intents.map { intent ->
                intentCodecRouter.routedEncode(intent)
            }

            val batchPayload = SyncBatchPayloadV1(
                envelopes = serializedEnvelopes,
                intentSchemaVersion = intentCodecRouter.latestVersion
            )

            val bytes = ProtoBuf.encodeToByteArray(SyncBatchPayloadV1.serializer(), batchPayload)

            logger.v {
                "Encoded ${intents.size} intents -> ${bytes.size}B " +
                        "(schema v${intentCodecRouter.latestVersion})"
            }

            bytes
        } catch (e: Exception) {
            logger.e(e) { "Failed encoding payload containing ${intents.size} intents" }
            throw e
        }
    }

    /**
     * Handles the processing of what is expected to be a payload purely comprising a list of
     * [SyncIntent] models.
     */
    override fun decode(bytes: ByteArray): List<SyncIntent> {
        val batchPayload = try {
            ProtoBuf.decodeFromByteArray(SyncBatchPayloadV1.serializer(), bytes)
        } catch (e: Exception) {
            logger.e(e) { "Failed decoding batch (${bytes.size} bytes)" }
            throw e
        }

        val totalEnvelopes = batchPayload.envelopes.size
        logger.v {
            "Decoding payload: ${bytes.size}B container, " +
                    "$totalEnvelopes envelopes, intentSchemaVersion=v${batchPayload.intentSchemaVersion}..."
        }

        val decodedIntents = ArrayList<SyncIntent>(totalEnvelopes)
        var corruptedCount = 0

        for ((index, envelopeBytes) in batchPayload.envelopes.withIndex()) {
            try {
                val intent = intentCodecRouter.routedDecode(
                    envelopeBytes,
                    batchPayload.intentSchemaVersion
                )
                decodedIntents.add(intent)
            } catch (e: Exception) {
                if (e is CancellationException){ throw e }
                if (e is MochaException.Persistent.UnknownProtocolVersion) {
                    logger.e { "Aborting Batch Process. Batch Envelope holds invalid version: ${batchPayload.intentSchemaVersion}" }
                    return decodedIntents
                }
                corruptedCount++
                logger.e(e) {
                    "Corrupted intent envelope at index $index/$totalEnvelopes " +
                            "in v${batchPayload.intentSchemaVersion} (${envelopeBytes.size} bytes)"
                }
            }
        }

        if (corruptedCount > 0) {
            logger.w {
                "Batch decoding degraded: recovered ${decodedIntents.size}/$totalEnvelopes intents " +
                        "($corruptedCount skipped due to corruption)"
            }
        } else {
            logger.d { "Successfully decoded ${decodedIntents.size} intents" }
        }

        return decodedIntents
    }

}