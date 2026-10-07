@file:OptIn(InternalTestApi::class)

package com.mochame.sync.infrastructure.adaptor

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import com.mochame.annotations.InternalTestApi
import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.codec.CodecResolver
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.domain.crdt.CrdtReconciler
import com.mochame.sync.domain.crdt.OutboundContext
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.domain.infrastructure.SyncReceiver
import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DefaultSyncAdaptorFactoryTest : MochaPlatformTest() {

    private val logger = Logger(StaticConfig())

    @Test
    fun should_createBridgeAndRegisterInRegistryAndReturnSyncAdaptor_when_invoked() = runTest {
        // Given
        val engine = DummyLocalFirstEngine()
        val registry = SyncReceiverRegistry()
        val reconciler = DummyCrdtReconciler()
        val factory = DefaultSyncAdaptorFactory(
            engine = engine,
            registry = registry,
            resolver = reconciler,
            logger = logger
        )
        val codec = DummyCodecResolver()

        // When
        val adaptor: SyncAdaptor<FeatureEntity> = factory(
            featureContext = FeatureContext.TEST_STUB_A,
            codec = codec,
            fetchById = { null },
            save = { 1L }
        )

        // Then
        assertNotNull(adaptor)
        assertTrue(adaptor is SyncAdaptorBridge<FeatureEntity>)
        val registeredReceiver = registry.receivers[FeatureContext.TEST_STUB_A]
        assertNotNull(registeredReceiver)
        assertSame(adaptor as SyncReceiver, registeredReceiver)
        assertEquals(FeatureContext.TEST_STUB_A, registeredReceiver.featureContext)
    }

    @Test
    fun should_registerMultipleDistinctContexts_when_factoryInvokedForMultipleFeatures() = runTest {
        // Given
        val engine = DummyLocalFirstEngine()
        val registry = SyncReceiverRegistry()
        val reconciler = DummyCrdtReconciler()
        val factory = DefaultSyncAdaptorFactory(
            engine = engine,
            registry = registry,
            resolver = reconciler,
            logger = logger
        )
        val codec = DummyCodecResolver()

        // When
        val adaptorA = factory(
            featureContext = FeatureContext.TEST_STUB_A,
            codec = codec,
            fetchById = { null },
            save = { 1L }
        )
        val adaptorB = factory(
            featureContext = FeatureContext.TEST_STUB_B,
            codec = codec,
            fetchById = { null },
            save = { 2L }
        )

        // Then
        assertEquals(2, registry.receivers.size)
        assertSame(adaptorA as SyncReceiver, registry.receivers[FeatureContext.TEST_STUB_A])
        assertSame(adaptorB as SyncReceiver, registry.receivers[FeatureContext.TEST_STUB_B])
    }

    private class DummyLocalFirstEngine : LocalFirstEngine {
        override suspend fun <T : LocalFirstEntity<T>> processLocalIntent(
            featureContext: FeatureContext,
            codecResolver: CodecResolver<T, FeatureCodec<T>>,
            reconciler: CrdtReconciler,
            candidateKey: Long,
            op: MutationOp,
            fetchExistingState: suspend (id: Long) -> T?,
            computeChange: suspend (existing: T?) -> T,
            persist: suspend (stamped: T) -> Long,
            onSkip: (fallback: T?) -> Long
        ): Long = 0L

        override suspend fun <T : LocalFirstEntity<T>> processRemoteIntent(
            featureContext: FeatureContext,
            codecResolver: CodecResolver<T, FeatureCodec<T>>,
            reconciler: CrdtReconciler,
            decodeContext: DecodeContext,
            payload: ByteArray?,
            fetchExistingState: suspend (id: Long) -> T?,
            save: suspend (entity: T) -> Long
        ) = Unit
    }

    private class DummyCrdtReconciler : CrdtReconciler {
        override fun <T : LocalFirstEntity<T>> prepareOutbound(
            candidateState: T,
            existingState: T?,
            hlc: HLC,
            op: MutationOp,
            codecResolver: CodecResolver<T, FeatureCodec<T>>
        ): OutboundContext<T>? = null

        override fun <T : LocalFirstEntity<T>> resolveInbound(
            payload: ByteArray,
            decodeContext: DecodeContext,
            existingState: T?,
            codecResolver: CodecResolver<T, FeatureCodec<T>>
        ): T = throw UnsupportedOperationException()
    }

    private class DummyCodecResolver : CodecResolver<FeatureEntity, FeatureCodec<FeatureEntity>> {
        override val latestVersion: Int = 0
        override val versionRegistry: Array<FeatureCodec<FeatureEntity>?> = emptyArray()

        override fun versionEncode(new: FeatureEntity, old: FeatureEntity?): ByteArray = ByteArray(0)
        override fun versionDecode(
            version: Int,
            data: ByteArray,
            logger: Logger,
            primaryKey: Long
        ): LocalFirstDelta = throw UnsupportedOperationException()
        override fun versionComputeChangedTags(new: FeatureEntity, old: FeatureEntity?): List<Int> = emptyList()
        override fun versionReconstructSummary(data: ByteArray, featureSchemaVersion: Int): String = ""
    }
}
