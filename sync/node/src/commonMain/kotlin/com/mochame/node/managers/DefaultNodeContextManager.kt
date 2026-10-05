package com.mochame.node.managers

import co.touchlab.kermit.Logger
import com.mochame.annotations.IoContext
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.node.data.NodeContextDao
import com.mochame.node.data.toDomain
import com.mochame.node.data.toEntity
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.node.IdGenerator
import com.mochame.sync.spi.node.NodeContext
import com.mochame.sync.spi.node.NodeContextManager
import com.mochame.sync.spi.node.NodeId
import com.mochame.utils.toDateTime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.Instant

@Single(binds = [NodeContextManager::class])
class DefaultNodeContextManager(
    private val dao: NodeContextDao,
    private val idGenerator: IdGenerator,
    @IoContext private val ioContext: CoroutineContext,
    private val mutex: Mutex = Mutex(),
    logger: Logger
) : NodeContextManager {

    private val logger = logger.withTags(
        layer = LogTags.Layer.ORCH,
        domain = LogTags.Domain.NODE,
        className = "SrNode"
    )

    @Volatile
    private var cachedContext: NodeContext? = null

    /**
     * Guarantees a node identity exists in the database and applies the provided app version if a new
     * node context is triggered.
     *
     * @param baseVersion Defaults to 0.
     */
    override suspend fun getOrEstablishContext(baseVersion: Int): NodeContext =
        withContext(ioContext) {
            cachedContext?.let { return@withContext it }

            mutex.withLock {
                cachedContext?.let { return@withLock it }

                val entity = dao.getOrEstablish(
                    fallbackId = idGenerator.nextId(),
                    baseVersion = baseVersion,
                    createdAt = Clock.System.now().toEpochMilliseconds()
                ).also {
                    logger.i {
                        "Node Fetched. Id: ${it.nodeId} | V: ${it.appVersion} " +
                                "| Watermark: ${it.lastInboundWatermark} | Est: ${it.createdAt.toDateTime()}"
                    }
                }

                entity.toDomain().also { domainContext ->
                    cachedContext = domainContext
                }
            }
        }

    override suspend fun setAppVersion(targetVersion: Int) =
        withContext(ioContext) {
            mutex.withLock {
                dao.setVersion(targetVersion)
                cachedContext = cachedContext?.copy(appVersion = targetVersion)
            }
        }

    override suspend fun updateHlcFloor(hlc: HLC) =
        withContext(ioContext) {
            mutex.withLock {
                val rowsUpdated = dao.setMaxHlc(hlc.toString())
                if (rowsUpdated == 0) {
                    logger.d { "HLC floor update ignored. Stored value is already newer than $hlc." }
                } else {
                    cachedContext = cachedContext?.copy(maxHlc = hlc)
                }
            }
        }

    override suspend fun commitInboundWatermark(
        watermark: Long,
        timestamp: Instant
    ) = withContext(ioContext) {
        if ((cachedContext?.lastInboundWatermark ?: 0L) >= watermark) return@withContext

        mutex.withLock {
            if ((cachedContext?.lastInboundWatermark ?: 0L) >= watermark) return@withLock
            dao.setInboundWatermark(watermark, timestamp.toEpochMilliseconds())
            cachedContext = cachedContext?.copy(
                lastInboundWatermark = watermark,
                lastServerResponseTime = timestamp
            )
        }
    }

    override suspend fun commitOutboundWatermark(
        watermark: Long,
        timestamp: Instant
    ) = withContext(ioContext) {
        if ((cachedContext?.lastOutboundWatermark ?: 0L) >= watermark) return@withContext

        mutex.withLock {
            if ((cachedContext?.lastOutboundWatermark ?: 0L) >= watermark) return@withContext
            dao.setOutboundWatermark(watermark, timestamp.toEpochMilliseconds())
            cachedContext = cachedContext?.copy(
                lastOutboundWatermark = watermark,
                lastServerResponseTime = timestamp
            )
        }
    }

    override suspend fun getLastBootedAppVersion(): Int? =
        cachedContext?.appVersion ?: withContext(ioContext) {
            dao.getLastBootedVersion()
        }

    override suspend fun getLastServerResponseTime(): Instant? {
        cachedContext?.let { return it.lastServerResponseTime }

        return withContext(ioContext) {
            dao.getLastServerResponseTime()?.let { Instant.fromEpochMilliseconds(it) }
        }
    }

    override suspend fun getLastInboundWatermark(): Long? =
        cachedContext?.lastInboundWatermark ?: withContext(ioContext) {
            dao.getLastInboundWatermark()
        }

    override suspend fun getNodeId(): NodeId? =
        cachedContext?.nodeId ?: withContext(ioContext) {
            dao.getNodeId()?.let { NodeId.parse(it) }
        }

    override suspend fun getMaxHlc(): HLC? =
        cachedContext?.maxHlc ?: withContext(ioContext) {
            dao.getMaxHlc()?.let { HLC.parse(it) }
        }

    override suspend fun overwriteNodeContext(nodeContext: NodeContext) =
        withContext(ioContext) {
            mutex.withLock {
                dao.insertOrReplaceContext(nodeContext.toEntity())
                cachedContext = nodeContext
            }
        }
}