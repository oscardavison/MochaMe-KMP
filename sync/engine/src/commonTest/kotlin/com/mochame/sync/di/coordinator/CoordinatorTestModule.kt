@file:OptIn(InternalTestApi::class)

package com.mochame.sync.di.coordinator

import com.mochame.logger.test.TestLoggerModule
import com.mochame.node.di.StaggeredDbRetryPolicyModule
import com.mochame.node.fixtures.FakeNodeContextManager
import com.mochame.node.fixtures.SpyBootStatusManager
import com.mochame.node.fixtures.di.FixturesNodeModule
import com.mochame.platform.fixtures.FakeTransactionProvider
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.common.InternalTestApi
import com.mochame.sync.di.SyncConcurrencyModule
import com.mochame.sync.di.SyncOrchestrationModule
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.internal.fixtures.FakeQuarantinedPayloadStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncTransport
import com.mochame.sync.internal.fixtures.api.FakeSyncReceiver
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import com.mochame.sync.internal.fixtures.serialization.FakeIntentCodec
import com.mochame.sync.internal.fixtures.serialization.FakePayloadCodec
import com.mochame.sync.orchestration.DefaultSyncCoordinator
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.domain.serialization.IntentCodec
import com.mochame.sync.domain.serialization.PayloadCodec
import com.mochame.sync.api.network.SyncTransport
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Module(
    includes = [
        SyncOrchestrationModule::class,
        SyncInternalFixturesModule::class,
        SyncConcurrencyModule::class,
        FixturesPlatformModule::class,
        FixturesNodeModule::class,
        StaggeredDbRetryPolicyModule::class,
        TestLoggerModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.coordinator")
class CoordinatorTestModule {

    @Single(binds = [PayloadCodec::class])
    fun provideFakePayloadCodec(): FakePayloadCodec = FakePayloadCodec()

    @Single(binds = [IntentCodec::class])
    fun provideFakeIntentCodec(): FakeIntentCodec = FakeIntentCodec()

    @Single(binds = [SyncTransport::class])
    fun provideFakeSyncTransport(): FakeSyncTransport = FakeSyncTransport()

    @Named("stubA")
    @Single(binds = [SyncReceiver::class, FakeSyncReceiver::class])
    fun provideFakeSyncReceiverA(): FakeSyncReceiver = FakeSyncReceiver(FeatureContext.TEST_STUB_A)

    @Named("stubB")
    @Single(binds = [SyncReceiver::class, FakeSyncReceiver::class])
    fun provideFakeSyncReceiverB(): FakeSyncReceiver = FakeSyncReceiver(FeatureContext.TEST_STUB_B)
}

@Factory
internal class SyncCoordinatorTestEnv(
    val coordinator: DefaultSyncCoordinator,
    @Named("stubA") val stubA: FakeSyncReceiver,
    @Named("stubB") val stubB: FakeSyncReceiver,
    val payloadCodec: FakePayloadCodec,
    val intentCodec: FakeIntentCodec,
    val hlcFactory: SpyHlcFactory,
    val transactor: FakeTransactionProvider,
    val workerHook: SpySyncWorkerHook,
    val bootManager: SpyBootStatusManager,
    val nodeManager: FakeNodeContextManager,
    val syncTransport: FakeSyncTransport,
    val intentStore: FakeSyncIntentStore,
    val quarantineStore: FakeQuarantinedPayloadStore
) : AutoCloseable {
    fun assertIntentsProperlyBatched(expectedKeys: Set<Long>) {
        val storedIntents = intentStore.intents
        val encodedIntents = payloadCodec.encodedInvocations.flatten()

        val storedKeys = storedIntents.map { it.candidateKey }.toSet()
        val encodedKeys = encodedIntents.map { it.candidateKey }.toSet()

        assertEquals(
            expectedKeys,
            storedKeys,
            "All seeded candidateKeys must exist in the intent store"
        )
        assertEquals(
            expectedKeys,
            encodedKeys,
            "All seeded candidateKeys must have been encoded across client sweeps"
        )
        assertEquals(
            expectedKeys.size,
            encodedIntents.size,
            "Total encoded intents count must match seeded count exactly"
        )

        storedIntents.forEach { intent ->
            assertEquals(
                SyncStatus.SUCCESS,
                intent.syncStatus,
                "Intent for key ${intent.candidateKey} must be in SYNCING status"
            )
            assertNotNull(
                intent.batchId,
                "Intent for key ${intent.candidateKey} must hold a non-null batchId (syncId)"
            )
            assertNotNull(
                intent.leasedAt,
                "Intent for key ${intent.candidateKey} must hold a valid leasedAt timestamp"
            )
        }
    }

    override fun close() {
        coordinator.close()
    }
}