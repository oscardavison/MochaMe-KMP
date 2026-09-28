@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.models.SyncIntent
import com.mochame.sync.spi.infrastructure.serialization.BatchCodecRouter
import com.mochame.sync.spi.infrastructure.serialization.PayloadCodec
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.koin.core.annotation.Single

@Serializable
internal data class VersionedPayload(
    @ProtoNumber(1) val batchVersion: Int,
    @ProtoNumber(2) val payload: ByteArray
)


@Single(binds = [PayloadCodec::class])
internal class DefaultPayloadCodec(
    private val batchCodecRouter: BatchCodecRouter,
    logger: Logger
) : PayloadCodec {

    private val logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "PayCdc")

    override fun encode(payload: List<SyncIntent>): ByteArray {
        require(payload.isNotEmpty()) { "Cannot encode an empty payload" }

        return try {
            val encodedPayload = batchCodecRouter.routedEncode(payload)
            val version = batchCodecRouter.latestVersion
            val delta = VersionedPayload(version, encodedPayload)

            val bytes = ProtoBuf.encodeToByteArray(VersionedPayload.serializer(), delta)

            logger.d {
                "Encoded complete wire payload: ${payload.size} intents -> ${bytes.size}B " +
                        "(schema v$version, payload blob ${encodedPayload.size}B)"
            }

            bytes
        } catch (e: Exception) {
            logger.e(e) { "Failed to encode finalized payload containing ${payload.size} intents" }
            throw e
        }
    }

    /**
     * Separates the binary payload from the version and initiates the version handling of
     * decoding the payload by calling [BatchCodecRouter], providing it the version.
     */
    override fun decode(bytes: ByteArray): List<SyncIntent> {
        val delta = try {
            ProtoBuf.decodeFromByteArray(VersionedPayload.serializer(), bytes)
        } catch (e: Exception) {
            logger.e(e) { "Failed to decode outer VersionedPayload container (${bytes.size} bytes)" }
            throw e
        }

        logger.v {
            "Unwrapped outer VersionedPayload: batchVersion=v${delta.batchVersion}, " +
                    "inner payload=${delta.payload.size}B (outer container size=${bytes.size}B)"
        }

        return batchCodecRouter.routedDecode(delta.payload, delta.batchVersion)
    }

}