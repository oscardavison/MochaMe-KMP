@file:OptIn(InternalTestApi::class)

package com.mochame.sync.infrastructure.adaptor

import co.touchlab.kermit.Logger
import co.touchlab.kermit.StaticConfig
import com.mochame.annotations.InternalTestApi
import com.mochame.support.MochaPlatformTest
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
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SyncAdaptorBridgeTest : MochaPlatformTest() {

    private val logger = Logger(StaticConfig())

    @Test
    fun should_forwardToEngineWithUpsertOp_when_upsertCalled() = runTest {
        // Given
        val engine = FakeLocalFirstEngine(resultToReturn = 101L)
        val reconciler = FakeCrdtReconciler()
        val codec = FakeCodecResolver()
        var fetchCalledWith: Long? = null
        var saveCalledWith: FeatureEntity? = null
        val fetchById: suspend (Long) -> FeatureEntity? = { id ->
            fetchCalledWith = id
            FeatureEntity(id = id, textValue = "initial")
        }
        val save: suspend (FeatureEntity) -> Long = { entity ->
            saveCalledWith = entity
            entity.id
        }

        val bridge = SyncAdaptorBridge(
            featureContext = FeatureContext.TEST_STUB_A,
            codec = codec,
            engine = engine,
            reconciler = reconciler,
            fetchById = fetchById,
            save = save,
            logger = logger
        )
        val candidateKey = 101L

        // When
        val result = bridge.upsert(candidateKey) { existing ->
            existing!!.copy(textValue = "updated")
        }

        // Then
        assertEquals(101L, result)
        assertEquals(1, engine.localInvocations.size)
        val invocation = engine.localInvocations.first()
        assertEquals(FeatureContext.TEST_STUB_A, invocation.featureContext)
        assertSame(codec, invocation.codecResolver)
        assertSame(reconciler, invocation.reconciler)
        assertEquals(candidateKey, invocation.candidateKey)
        assertEquals(MutationOp.UPSERT, invocation.op)

        val fetched = invocation.fetchExistingState(candidateKey) as FeatureEntity?
        assertEquals(candidateKey, fetchCalledWith)
        assertEquals("initial", fetched?.textValue)

        val computed = invocation.computeChange(fetched) as FeatureEntity
        assertEquals("updated", computed.textValue)

        invocation.persist(computed)
        assertEquals(computed, saveCalledWith)

        val skipResult = invocation.onSkip(null)
        assertEquals(0L, skipResult)
    }

    @Test
    fun should_forwardToEngineWithDeleteOp_when_deleteCalledWithNullComputeChange() = runTest {
        // Given
        val engine = FakeLocalFirstEngine(resultToReturn = 202L)
        val reconciler = FakeCrdtReconciler()
        val codec = FakeCodecResolver()

        val bridge = SyncAdaptorBridge(
            featureContext = FeatureContext.TEST_STUB_A,
            codec = codec,
            engine = engine,
            reconciler = reconciler,
            fetchById = { null },
            save = { 1L },
            logger = logger
        )
        val candidateKey = 202L

        // When
        val result = bridge.delete(candidateKey)

        // Then
        assertEquals(202L, result)
        assertEquals(1, engine.localInvocations.size)
        val invocation = engine.localInvocations.first()
        assertEquals(FeatureContext.TEST_STUB_A, invocation.featureContext)
        assertSame(codec, invocation.codecResolver)
        assertSame(reconciler, invocation.reconciler)
        assertEquals(candidateKey, invocation.candidateKey)
        assertEquals(MutationOp.DELETE, invocation.op)

        val existing = FeatureEntity(id = candidateKey, isDeleted = false, textValue = "active")
        val computed = invocation.computeChange(existing) as FeatureEntity
        assertTrue(computed.isDeleted)

        val skipResult = invocation.onSkip(null)
        assertEquals(0L, skipResult)
    }

    @Test
    fun should_forwardToEngineWithDeleteOpAndCustomComputeChange_when_deleteCalledWithExplicitComputeChange() =
        runTest {
            // Given
            val engine = FakeLocalFirstEngine(resultToReturn = 303L)
            val reconciler = FakeCrdtReconciler()
            val codec = FakeCodecResolver()

            val bridge = SyncAdaptorBridge(
                featureContext = FeatureContext.TEST_STUB_A,
                codec = codec,
                engine = engine,
                reconciler = reconciler,
                fetchById = { null },
                save = { 1L },
                logger = logger
            )
            val candidateKey = 303L

            // When
            val result = bridge.delete(candidateKey) { existing ->
                existing!!.copy(isDeleted = true, textValue = "custom-tombstone")
            }

            // Then
            assertEquals(303L, result)
            assertEquals(1, engine.localInvocations.size)
            val invocation = engine.localInvocations.first()
            assertEquals(MutationOp.DELETE, invocation.op)

            val existing = FeatureEntity(id = candidateKey, textValue = "prior")
            val computed = invocation.computeChange(existing) as FeatureEntity
            assertEquals("custom-tombstone", computed.textValue)
            assertTrue(computed.isDeleted)
        }

    @Test
    fun should_forwardToEngineProcessRemoteIntent_when_processRemoteIntentCalled() = runTest {
        // Given
        val engine = FakeLocalFirstEngine()
        val reconciler = FakeCrdtReconciler()
        val codec = FakeCodecResolver()
        var fetchCalledWith: Long? = null
        var saveCalledWith: FeatureEntity? = null
        val fetchById: suspend (Long) -> FeatureEntity? = { id ->
            fetchCalledWith = id
            FeatureEntity(id = id)
        }
        val save: suspend (FeatureEntity) -> Long = { entity ->
            saveCalledWith = entity
            entity.id
        }

        val bridge = SyncAdaptorBridge(
            featureContext = FeatureContext.TEST_STUB_A,
            codec = codec,
            engine = engine,
            reconciler = reconciler,
            fetchById = fetchById,
            save = save,
            logger = logger
        )
        val receiver: SyncReceiver = bridge
        val decodeContext = DecodeContext(
            featureSchemaVersion = 1,
            primaryKey = 404L,
            hlc = HLC.EMPTY,
            op = MutationOp.UPSERT,
            changedMask = 1L
        )
        val payload = byteArrayOf(10, 20, 30)

        // When
        receiver.processRemoteIntent(decodeContext, payload)

        // Then
        assertEquals(1, engine.remoteInvocations.size)
        val invocation = engine.remoteInvocations.first()
        assertEquals(FeatureContext.TEST_STUB_A, invocation.featureContext)
        assertSame(codec, invocation.codecResolver)
        assertSame(reconciler, invocation.reconciler)
        assertEquals(decodeContext, invocation.decodeContext)
        assertEquals(payload, invocation.payload)

        invocation.fetchExistingState(404L)
        assertEquals(404L, fetchCalledWith)

        val entityToSave = FeatureEntity(id = 404L, textValue = "remote")
        invocation.save(entityToSave)
        assertEquals(entityToSave, saveCalledWith)
    }

    // -----------------------------------------------------------
    // FAKE PROVIDERS
    // -----------------------------------------------------------

    private class FakeLocalFirstEngine(
        val resultToReturn: Long = 1L
    ) : LocalFirstEngine {
        data class LocalInvocation(
            val featureContext: FeatureContext,
            val codecResolver: CodecResolver<*, *>,
            val reconciler: CrdtReconciler,
            val candidateKey: Long,
            val op: MutationOp,
            val fetchExistingState: suspend (Long) -> Any?,
            val computeChange: suspend (Any?) -> Any,
            val persist: suspend (Any) -> Long,
            val onSkip: (Any?) -> Long
        )

        data class RemoteInvocation(
            val featureContext: FeatureContext,
            val codecResolver: CodecResolver<*, *>,
            val reconciler: CrdtReconciler,
            val decodeContext: DecodeContext,
            val payload: ByteArray?,
            val fetchExistingState: suspend (Long) -> Any?,
            val save: suspend (Any) -> Long
        )

        val localInvocations = mutableListOf<LocalInvocation>()
        val remoteInvocations = mutableListOf<RemoteInvocation>()

        @Suppress("UNCHECKED_CAST")
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
        ): Long {
            localInvocations.add(
                LocalInvocation(
                    featureContext = featureContext,
                    codecResolver = codecResolver,
                    reconciler = reconciler,
                    candidateKey = candidateKey,
                    op = op,
                    fetchExistingState = fetchExistingState as suspend (Long) -> Any?,
                    computeChange = computeChange as suspend (Any?) -> Any,
                    persist = persist as suspend (Any) -> Long,
                    onSkip = onSkip as (Any?) -> Long
                )
            )
            return resultToReturn
        }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : LocalFirstEntity<T>> processRemoteIntent(
            featureContext: FeatureContext,
            codecResolver: CodecResolver<T, FeatureCodec<T>>,
            reconciler: CrdtReconciler,
            decodeContext: DecodeContext,
            payload: ByteArray?,
            fetchExistingState: suspend (id: Long) -> T?,
            save: suspend (entity: T) -> Long
        ) {
            remoteInvocations.add(
                RemoteInvocation(
                    featureContext = featureContext,
                    codecResolver = codecResolver,
                    reconciler = reconciler,
                    decodeContext = decodeContext,
                    payload = payload,
                    fetchExistingState = fetchExistingState as suspend (Long) -> Any?,
                    save = save as suspend (Any) -> Long
                )
            )
        }
    }

    private class FakeCrdtReconciler : CrdtReconciler {
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

    private class FakeCodecResolver : CodecResolver<FeatureEntity, FeatureCodec<FeatureEntity>> {
        override val latestVersion: Int = 0
        override val versionRegistry: Array<FeatureCodec<FeatureEntity>?> = emptyArray()

        override fun versionEncode(new: FeatureEntity, old: FeatureEntity?): ByteArray =
            ByteArray(0)

        override fun versionDecode(
            version: Int,
            data: ByteArray,
            logger: Logger,
            primaryKey: Long
        ): LocalFirstDelta = throw UnsupportedOperationException()

        override fun versionComputeChangedTags(new: FeatureEntity, old: FeatureEntity?): List<Int> =
            emptyList()

        override fun versionReconstructSummary(data: ByteArray, featureSchemaVersion: Int): String =
            ""
    }
}
