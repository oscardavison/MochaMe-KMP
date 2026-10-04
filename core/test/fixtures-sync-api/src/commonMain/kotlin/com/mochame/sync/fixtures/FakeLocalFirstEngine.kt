package com.mochame.sync.fixtures

import co.touchlab.kermit.Logger
import com.mochame.logger.test.TestLoggerModule
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.repository.LocalFirstEngine
import com.mochame.sync.common.toBitmask
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecRouter
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.spi.node.NodeId
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.math.max
import kotlin.uuid.Uuid

/**
 * Thread-safe fake implementation of [LocalFirstEngine] for unit testing.
 * Provided to satisfy dependencies but is irrelevant to feature testing.
 *
 * Uses an internal, in-memory HLC state store.
 * Uses Coroutine [Mutex] if feature tests implement Daos using Dispatchers other than TestCoroutineDispatcher.
 */
@Single(binds = [LocalFirstEngine::class])
class FakeLocalFirstEngine<T : LocalFirstEntity<T>>(
    private val fakeNodeId: NodeId = NodeId(Uuid.random()),
    private val clock: FakeTimeUtils,
    private val logger: Logger
) : LocalFirstEngine {

    private val mutex = Mutex()

    private var lastHlc: HLC = HLC(
        ts = clock.now().toEpochMilliseconds(),
        count = 0,
        nodeId = fakeNodeId
    )

    private val _processedIntentCount = atomic(0)
    private val _processedRemoteIntentCount = atomic(0)

    val processedIntentCount: Int get() = _processedIntentCount.value
    val processedRemoteIntentCount: Int get() = _processedRemoteIntentCount.value

    fun resetTelemetry() {
        _processedIntentCount.value = 0
        _processedRemoteIntentCount.value = 0
    }

    override suspend fun <T: LocalFirstEntity<T>> processIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        candidateKey: Long,
        incomingHlc: HLC?,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long {
        return mutex.withLock {
            _processedIntentCount.incrementAndGet()
            val existingState = fetchExistingState(candidateKey)
            val candidateState = computeChange(existingState)

            if (existingState != null && candidateState == existingState && incomingHlc == null) {
                return@withLock onSkip(existingState)
            }

            val hlc = if (incomingHlc != null) {
                advanceWithRemoteHlc(incomingHlc)
            } else {
                generateNextLocalHlc()
            }

            if (shouldRejectIntent(existingState, incomingHlc, op, candidateKey)) {
                onSkip(existingState)
            }

            val changedTags = codec.routedComputeChangedTags(candidateState, existingState)
            val changedMask = changedTags.toBitmask()

            if (changedMask == 0L) {
                return onSkip(existingState)
            }

            val stampedState =
                codec.stampHlcMetadata(candidateState, existingState, changedTags, hlc)

            persist(stampedState)
        }
    }

    private fun shouldRejectIntent(
        existing: LocalFirstEntity<*>?,
        incomingHlc: HLC?,
        op: MutationOp,
        candidateKey: Long
    ): Boolean {
        if (existing == null) {
            if (op == MutationOp.UPSERT) return false

            if (incomingHlc != null)
                return reject(candidateKey) { "Non-existent local record (remote HLC: $incomingHlc)" }

            return reject(candidateKey) { "Local Delete attempt against non-existent record: $candidateKey." }
        }

        if (incomingHlc == null && op == MutationOp.DELETE && existing.isDeleted) {
            return reject(candidateKey) { "Local record is already deleted (HLC: ${existing.hlc})" }
        }

        return false
    }

    private inline fun reject(candidateKey: Long, crossinline message: () -> String): Boolean {
        logger.v { "Skipping operation [ID:$candidateKey] -> ${message()}" }
        return true
    }

    override suspend fun <T: LocalFirstEntity<T>> processRemoteIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        context: DecodeContext,
        payload: ByteArray?,
        fetchAny: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ) {
        _processedRemoteIntentCount.incrementAndGet()

        if (payload != null) {
            processIntent(
                featureContext = featureContext,
                codec = codec,
                candidateKey = context.candidateKey,
                incomingHlc = context.hlc,
                op = context.op,
                fetchExistingState = fetchAny,
                computeChange = { existing -> codec.routedDecode(payload, context, existing) },
                persist = save,
                onSkip = { 0L }
            )
        }
    }

    /**
     * Generates a strictly monotonically increasing local HLC relative to the in-memory store.
     * Must be called while holding [mutex].
     */
    private fun generateNextLocalHlc(): HLC {
        val physicalNow = clock.now().toEpochMilliseconds()
        val nextTs = max(lastHlc.ts, physicalNow)
        val nextCount = if (nextTs == lastHlc.ts) lastHlc.count + 1 else 0

        val next = HLC(ts = nextTs, count = nextCount, nodeId = fakeNodeId)
        lastHlc = next
        return next
    }

    /**
     * Advances the internal store using standard Hybrid Logical Clock receive semantics.
     * Must be called while holding [mutex].
     */
    private fun advanceWithRemoteHlc(remote: HLC): HLC {
        val physicalNow = clock.now().toEpochMilliseconds()
        val nextTs = max(max(lastHlc.ts, physicalNow), remote.ts)

        val nextCount = when {
            nextTs == lastHlc.ts && nextTs == remote.ts -> max(lastHlc.count, remote.count) + 1
            nextTs == lastHlc.ts -> lastHlc.count + 1
            nextTs == remote.ts -> remote.count + 1
            else -> 0
        }

        val next = HLC(ts = nextTs, count = nextCount, nodeId = fakeNodeId)
        lastHlc = next
        return remote
    }
}

@Module(includes = [FakeTimeProviderModule::class, TestLoggerModule::class])
class FakeLocalFirstEngineModule {
    @Single(binds = [LocalFirstEngine::class, FakeLocalFirstEngine::class])
    fun <T : LocalFirstEntity<T>> provideFakeLocalFirstEngine(
        clock: FakeTimeUtils,
        logger: Logger
    ): FakeLocalFirstEngine<T> = FakeLocalFirstEngine(clock = clock, logger = logger)
}