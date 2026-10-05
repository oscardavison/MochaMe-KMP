package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.domain.model.ClaimedBatch
import com.mochame.sync.spi.models.QuarantinedFeatureSummary
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.utils.fixtures.FakeTimeUtils
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeSyncIntentStore(private val fakeClock: FakeTimeUtils) :
    SyncIntentStore, SyncIntentMaintenanceStore {

    private val lock = reentrantLock()

    // --- State ---
    private val _intents = linkedMapOf<HLC, SyncIntent>()
    private val _quarantinedFlow =
        MutableStateFlow<List<QuarantinedFeatureSummary>>(emptyList())

    // --- Hooks & Telemetry ---
    private val _batchCounter = atomic(-1L)
    private var _failWith: Exception? = null
    private var _onClaimHook: (suspend () -> Unit)? = null
    private val _failingBlobIds = mutableSetOf<String>()
    private var _claimedBatchCallCount: Int = 0

    var failWith: Exception?
        set(value) = lock.withLock { _failWith = value }
        get() = lock.withLock { _failWith }

    var claimedBatchCallCount: Int
        get() = lock.withLock { _claimedBatchCallCount }
        set(value) = lock.withLock { _claimedBatchCallCount = value }

    var onClaimHook: (suspend () -> Unit)?
        get() = lock.withLock { _onClaimHook }
        set(value) = lock.withLock { _onClaimHook = value }

    val intents: List<SyncIntent>
        get() = lock.withLock { _intents.values.toList() }

    val batchCounter = _batchCounter.value


    fun seedIntents(vararg entries: SyncIntent) {
        seedIntents(entries.asList())
    }

    private fun ensureInitialized() {
        if (_batchCounter.value >= 0) return
        lock.withLock {
            if (_batchCounter.value < 0) {
                val maxExisting = getMaxBatchId() ?: 0L
                _batchCounter.value = maxExisting
            }
        }
    }

    fun getMaxBatchId(): Long? = lock.withLock {
        var max: Long? = null
        for (intent in _intents.values) {
            val id = intent.batchId ?: continue
            if (max == null || id > max) {
                max = id
            }
        }
        max
    }

    fun seedIntents(entries: Collection<SyncIntent>) {
        val summaries = lock.withLock {
            entries.forEach { _intents[it.hlc] = it }
            calculateQuarantinedSummaries()
        }
        _quarantinedFlow.value = summaries
    }

    fun failOnBlobCheck(blobId: String) = lock.withLock {
        _failingBlobIds.add(blobId)
    }

    fun reset() =
        lock.withLock {
            _intents.clear()
            _failingBlobIds.clear()
            _claimedBatchCallCount = 0
            _failWith = null
            _quarantinedFlow.value = emptyList()
            _onClaimHook = null
        }

    // --- SyncIntentStore Implementations ---

    override suspend fun getPendingByCandidateKey(candidateKey: Long): SyncIntent? = lock.withLock {
        _intents.values.find { it.candidateKey == candidateKey && it.syncStatus == SyncStatus.PENDING }
    }

    override suspend fun getPendingByFeature(feature: FeatureContext): List<SyncIntent?> =
        lock.withLock {
            _intents.values.filter {
                it.featureContext.featureName == feature.featureName && it.syncStatus == SyncStatus.PENDING
            }
        }

    override suspend fun recordIntent(entry: SyncIntent) {
        val updatedSummaries = lock.withLock {
            _intents[entry.hlc] = entry
            calculateQuarantinedSummaries()
        }
        _quarantinedFlow.value = updatedSummaries
    }

    override suspend fun claimNextBatch(limit: Int): ClaimedBatch? {
        val (error, hook, claimed) = lock.withLock {
            ensureInitialized()
            val nextBatchId = _batchCounter.incrementAndGet()
            _claimedBatchCallCount++

            val err = _failWith
            if (err != null) {
                _failWith = null
                return@withLock Triple(err, null, ClaimedBatch(nextBatchId, emptyList()))
            }

            if (limit <= 0) return@withLock Triple(
                null,
                _onClaimHook,
                ClaimedBatch(nextBatchId, emptyList())
            )

            val allIntents = _intents.values

            val quarantinedKeys = allIntents
                .filter { it.syncStatus == SyncStatus.QUARANTINED }
                .map { it.candidateKey }
                .toSet()

            val pendingCandidates = allIntents
                .filter { it.batchId == null && it.syncStatus == SyncStatus.PENDING }
                .sortedBy { it.hlc }

            val claimed = mutableListOf<SyncIntent>()
            val now = fakeClock.now().toEpochMilliseconds()

            for (candidate in pendingCandidates) {
                if (claimed.size == limit) break

                if (candidate.candidateKey in quarantinedKeys) {
                    continue
                }

                val hasUnfinishedPrior = allIntents.any { prior ->
                    prior.candidateKey == candidate.candidateKey &&
                            prior.hlc < candidate.hlc &&
                            prior.syncStatus != SyncStatus.SUCCESS
                }
                if (hasUnfinishedPrior) {
                    continue
                }

                val updated = candidate.copy(
                    syncStatus = SyncStatus.SYNCING,
                    batchId = nextBatchId,
                    leasedAt = now
                )
                _intents[candidate.hlc] = updated
                claimed.add(updated)
            }

            Triple(null, _onClaimHook, ClaimedBatch(nextBatchId, claimed))
        }

        error?.let { throw it }
        hook?.invoke()
        return if (claimed.intents.isEmpty()) null else claimed
    }

    override suspend fun acknowledgeSuccess(batchId: Long): Int = lock.withLock {
        var updatedCount = 0
        _intents.values
            .filter { it.batchId == batchId && it.syncStatus == SyncStatus.SYNCING }
            .forEach { intent ->
                _intents[intent.hlc] = intent.copy(syncStatus = SyncStatus.SUCCESS)
                updatedCount++
            }
        updatedCount
    }

    override suspend fun stampLastError(batchId: Long, message: String) = lock.withLock {
        _intents.values
            .filter { it.batchId == batchId }
            .forEach { intent ->
                _intents[intent.hlc] = intent.copy(lastErrorMessage = message)
            }
    }

    // --- SyncIntentMaintenanceStore Implementations ---

    override suspend fun resetStaleLeases(
        cutOff: Long,
        retryThreshold: Int,
        shouldIncrementRetry: Boolean
    ): Int = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        val increment = if (shouldIncrementRetry) 1 else 0

        var resetCount = 0
        _intents.values
            .filter {
                it.batchId != null &&
                        it.syncStatus == SyncStatus.SYNCING &&
                        it.leasedAt != null && it.leasedAt!! <= cutOff &&
                        (it.retryCount + increment) < retryThreshold
            }
            .forEach { intent ->
                _intents[intent.hlc] = intent.copy(
                    retryCount = intent.retryCount + increment,
                    syncStatus = SyncStatus.PENDING,
                    batchId = null,
                    leasedAt = null
                )
                resetCount++
            }
        resetCount
    }

    override suspend fun quarantineStaleLeases(cutOff: Long, retryThreshold: Int): Int {
        val (error, summaries, count) = lock.withLock {
            val err = _failWith
            if (err != null) {
                _failWith = null
                return@withLock Triple(err, null, 0)
            }

            var quarantinedCount = 0
            _intents.values
                .filter {
                    it.batchId != null &&
                            it.syncStatus == SyncStatus.SYNCING &&
                            it.leasedAt != null && it.leasedAt!! < cutOff &&
                            (it.retryCount + 1) >= retryThreshold
                }
                .forEach { intent ->
                    _intents[intent.hlc] = intent.copy(
                        retryCount = intent.retryCount + 1,
                        syncStatus = SyncStatus.QUARANTINED,
                        batchId = null,
                        leasedAt = null
                    )
                    quarantinedCount++
                }

            val updated = if (quarantinedCount > 0) calculateQuarantinedSummaries() else null
            Triple(null, updated, quarantinedCount)
        }

        error?.let { throw it }

        if (summaries != null) {
            _quarantinedFlow.value = summaries
        }
        return count
    }

    override suspend fun cascadeQuarantine(): Int {
        val (error, summaries, count) = lock.withLock {
            val err = _failWith
            if (err != null) {
                _failWith = null
                return@withLock Triple(err, null, 0)
            }

            val quarantinedKeys = _intents.values
                .filter { it.syncStatus == SyncStatus.QUARANTINED }
                .map { it.candidateKey }
                .toSet()

            var cascadedCount = 0
            _intents.values
                .filter { it.syncStatus == SyncStatus.PENDING && it.candidateKey in quarantinedKeys }
                .forEach { intent ->
                    _intents[intent.hlc] = intent.copy(
                        syncStatus = SyncStatus.QUARANTINED,
                        lastErrorMessage = "Cascaded quarantine: causal predecessor failed on candidateKey"
                    )
                    cascadedCount++
                }

            val updated = if (cascadedCount > 0) calculateQuarantinedSummaries() else null
            Triple(null, updated, cascadedCount)
        }

        error?.let { throw it }

        if (summaries != null) {
            _quarantinedFlow.value = summaries
        }
        return count
    }

    override suspend fun quarantineIntent(
        hlc: HLC,
        candidateKey: Long,
        errorMessage: String
    ) {
        val (error, summaries) = lock.withLock {
            val err = _failWith
            if (err != null) {
                _failWith = null
                return@withLock Pair(err, null)
            }

            val existing = _intents[hlc]
            if (existing != null && existing.candidateKey == candidateKey) {
                _intents[hlc] = existing.copy(
                    syncStatus = SyncStatus.QUARANTINED,
                    lastErrorMessage = errorMessage
                )
            }

            Pair(null, calculateQuarantinedSummaries())
        }

        error?.let { throw it }

        if (summaries != null) {
            _quarantinedFlow.value = summaries
        }
    }

    override suspend fun releaseIntents(hlcs: List<HLC>): Int = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        var releasedCount = 0
        val targetHlcs = hlcs.toSet()

        for (hlc in targetHlcs) {
            val intent = _intents[hlc] ?: continue
            if (intent.syncStatus == SyncStatus.SYNCING) continue
            _intents[hlc] = intent.copy(
                syncStatus = SyncStatus.PENDING,
                batchId = null,
                leasedAt = null
            )
            releasedCount++
        }
        releasedCount
    }

    override suspend fun releaseIntents(batchId: Long): Int = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        var releasedCount = 0

        for (intent in _intents.values) {
            if (intent.batchId == batchId) {
                _intents[intent.hlc] = intent.copy(
                    syncStatus = SyncStatus.PENDING,
                    batchId = null,
                    leasedAt = null
                )
                releasedCount++
            }
        }
        releasedCount
    }

    override suspend fun pruneAgedIntents(pruneAfter: Long, limit: Int): Int = lock.withLock {
        _failWith?.let { throw it }

        var pruned = 0
        val candidates = _intents.values
            .filter { it.syncStatus == SyncStatus.SUCCESS && it.createdAt < pruneAfter }
            .sortedBy { it.hlc.toString() }

        for (intent in candidates) {
            if (pruned >= limit) break
            _intents.remove(intent.hlc)
            pruned++
        }
        pruned
    }

    override suspend fun observeQuarantinedCountByModule(): Flow<List<QuarantinedFeatureSummary>> {
        return _quarantinedFlow.asStateFlow()
    }

    override suspend fun existsForBlob(blobId: String): Boolean {
        if (blobId in _failingBlobIds) {
            throw IllegalStateException("Simulated database read failure for blob $blobId")
        }
        return intents.any { it.overflowBlobId == blobId }
    }

    // --- Test Utils ---
    private fun calculateQuarantinedSummaries(): List<QuarantinedFeatureSummary> {
        return _intents.values
            .filter { it.syncStatus == SyncStatus.QUARANTINED }
            .groupBy { it.featureContext }
            .map { (feature, items) ->
                QuarantinedFeatureSummary(featureContext = feature, count = items.size)
            }
    }
}
