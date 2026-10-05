package com.mochame.sync.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.internal.withTag
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecResolverFixture
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.deriveContext
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.SerializationException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull


// -------------------------------------------------------------------
// SUT ENVIRONMENT
// -------------------------------------------------------------------
private inline fun runEnv(crossinline block: suspend FeatureCodecResolverFixture.(TestScope) -> Unit) =
    runUnitEnvironment<FeatureCodecResolverFixture>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )

class FeatureCodecResolverTest : MochaPlatformTest() {

    // -------------------------------------------------------------------
    // DISPATCH & ROUTING
    // -------------------------------------------------------------------

    @Test
    fun should_delegateToLatestVersion_on_routedEncode() = runEnv {
        val entity = FeatureEntity()

        // versionEncode must always use latestCodec (V2)
        val encodedBytes = versionEncode(new = entity, old = null)

        assertNotNull(encodedBytes)
        assertContentEquals(
            FakeFeatureCodec.BYTES_PRESET,
            encodedBytes,
            "versionEncode must delegate strictly to V2 codec bytes"
        )
    }

    @Test
    fun should_delegateToV1Codec_on_routedDecode_when_schemaVersionIs1() = runEnv {
        val legacyEntity = FeatureEntity()
        val contextV1 = legacyEntity.deriveContext(changedMask = 0L.withTag(4))
        val v1Bytes = v1.encode(new = legacyEntity, old = null)

        val decoded = versionDecode(data = v1Bytes, context = contextV1, existing = null)

        assertEquals(legacyEntity.id, decoded.id)
        assertEquals(legacyEntity.textValue, decoded.textValue)
        assertEquals(legacyEntity.hlc, decoded.hlc)
        assertFalse(decoded.isDeleted)
    }

    @Test
    fun should_delegateToV2Codec_on_routedDecode_when_schemaVersionIs2() = runEnv {
        val entity = FakeFeatureCodec.MODEL_PRESET
        val contextV2 = entity.deriveContext(schemaVersion = 2)

        val decoded = versionDecode(
            data = FakeFeatureCodec.BYTES_PRESET,
            context = contextV2,
            existing = null
        )

        // If v1 was routed, Serialization exception would be thrown
        assertEquals(entity.hlc, decoded.hlc)
    }

    @Test
    fun should_throwSerializationException_on_routedDecode_when_schemaVersionIs1AndBytesAre2() =
        runEnv {
            val contextV1 = FeatureEntity().deriveContext()

            assertFailsWith<SerializationException> {
                versionDecode(
                    data = FakeFeatureCodec.BYTES_PRESET,
                    context = contextV1,
                    existing = null
                )
            }
        }

    @Test
    fun should_routeReconstructSummary_according_to_schemaVersion() = runEnv {
        // Given
        val v1Entity = FeatureEntity()
        val v1Context = v1Entity.deriveContext()
        val v1Bytes = v1.encode(new = v1Entity, old = null)

        val v2Entity = FakeFeatureCodec.MODEL_PRESET
        val v2Context = v2Entity.deriveContext(schemaVersion = 2)

        // When
        val summaryV1 = versionReconstructSummary(v1Bytes, v1Context)
        val summaryV2 = versionReconstructSummary(FakeFeatureCodec.BYTES_PRESET, v2Context)

        assertEquals(
            "OP:UPSERT [3,4,5]",
            summaryV1,
            "versionReconstructSummary must dispatch to V1 peeking logic"
        )
        assertEquals(
            FakeFeatureCodec.RECONSTRUCT_PRESET,
            summaryV2,
            "versionReconstructSummary must dispatch to V2 peeking logic"
        )
    }
}