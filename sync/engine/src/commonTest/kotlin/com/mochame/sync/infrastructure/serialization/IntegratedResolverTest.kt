package com.mochame.sync.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.serialization.CodecResolverFixture
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.FeatureEntityDeltaV1
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun runEnv(crossinline block: suspend CodecResolverFixture.(TestScope) -> Unit) =
    runUnitEnvironment<CodecResolverFixture>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )

@ExperimentalSerializationApi
class IntegratedResolverTest : MochaPlatformTest() {

    @Test
    fun should_delegateToLatestVersion_on_versionEncode() = runEnv {
        // Given
        val entity = FeatureEntity()

        // When
        val encodedBytes = versionEncode(new = entity, old = null)

        // Then
        assertNotNull(encodedBytes)
        assertContentEquals(FakeFeatureCodec.BYTES_PRESET, encodedBytes)
    }

    @Test
    fun should_delegateToLatestCodec_on_versionComputeChangedTags() = runEnv {
        // Given
        val entity = FeatureEntity()

        // When
        val changedTags = versionComputeChangedTags(new = entity, old = null)

        // Then
        assertEquals(listOf(4, 5), changedTags)
    }

    @Test
    fun should_delegateToV1Codec_on_versionDecode_when_schemaVersionIs1() = runEnv {
        // Given
        val legacyEntity = FeatureEntity(id = 101L, textValue = "legacy-val", countValue = 42)
        val v1Bytes = v1.encode(new = legacyEntity, old = null)

        // When
        val delta = versionDecode(
            version = 1,
            data = v1Bytes,
            logger = testLogger,
            primaryKey = legacyEntity.id
        )

        // Then
        assertTrue(delta is FeatureEntityDeltaV1)
        assertEquals(legacyEntity.id, delta.id)
        assertEquals(legacyEntity.textValue, delta.textValue)
        assertEquals(legacyEntity.countValue, delta.countValue)
        assertNull(delta.isDeleted)
    }

    @Test
    fun should_delegateToV2Codec_on_versionDecode_when_schemaVersionIs2() = runEnv {
        // Given
        val primaryKey = 5L

        // When
        val delta = versionDecode(
            version = 2,
            data = FakeFeatureCodec.BYTES_PRESET,
            logger = testLogger,
            primaryKey = primaryKey
        )

        // Then
        assertEquals(FakeFeatureCodec.DELTA_PRESET, delta)
    }

    @Test
    fun should_throwSerializationException_on_versionDecode_when_schemaVersionIs1AndBytesAre2() = runEnv {
        // Given
        val primaryKey = 100L

        // When & Then
        assertFailsWith<SerializationException> {
            versionDecode(
                version = 1,
                data = FakeFeatureCodec.BYTES_PRESET,
                logger = testLogger,
                primaryKey = primaryKey
            )
        }
    }

    @Test
    fun should_versionReconstructSummary_accordingToSchemaVersion() = runEnv {
        // Given
        val v1Entity = FeatureEntity()
        val v1Bytes = v1.encode(new = v1Entity, old = null)

        // When
        val summaryV1 = versionReconstructSummary(data = v1Bytes, featureSchemaVersion = 1)
        val summaryV2 = versionReconstructSummary(data = FakeFeatureCodec.BYTES_PRESET, featureSchemaVersion = 2)

        // Then
        assertEquals("OP:UPSERT [3,4,5]", summaryV1)
        assertEquals(FakeFeatureCodec.RECONSTRUCT_PRESET, summaryV2)
    }
}