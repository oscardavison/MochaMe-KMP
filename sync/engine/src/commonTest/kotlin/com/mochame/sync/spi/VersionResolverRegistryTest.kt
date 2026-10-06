@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.spi

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.codec.CodecRouterTestModule
import com.mochame.sync.di.codec.CodecFixtureTestEnv
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.deriveContext
import com.mochame.sync.api.codec.BaseCodecResolver
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private inline fun runEnv(crossinline block: suspend CodecFixtureTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<CodecFixtureTestEnv>(
        koinSetup = { modules(CodecRouterTestModule::class) },
        block = block
    )

class VersionResolverRegistryTest : MochaPlatformTest() {

    // -------------------------------------------------------------------
    //  VERSION REGISTRY & PROTOCOL EXCEPTIONS - USING FEATURE
    // -------------------------------------------------------------------

    @Test
    fun should_throwUnknownProtocolVersion_when_schemaVersionIsIndexZero() = runEnv {
        val entity = FeatureEntity()
        val context = entity.deriveContext(schemaVersion = 0)

        val exception =
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                featureRouter.versionDecode(
                    data = FakeFeatureCodec.BYTES_PRESET,
                    context = context,
                    existing = null
                )
            }

        assertEquals(
            0,
            exception.version,
            "Exception payload must report requested version 0"
        )
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_schemaVersionIsOutOfBounds() = runEnv {
        val entity = FeatureEntity()
        val context = entity.deriveContext(schemaVersion = 3)

        val exception =
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                featureRouter.versionDecode(
                    data = FakeFeatureCodec.BYTES_PRESET,
                    context = context,
                    existing = null
                )
            }

        assertEquals(
            3,
            exception.version,
            "Exception payload must report requested out-of-bounds version 3"
        )
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_schemaVersionIsNegative() = runEnv {
        val entity = FeatureEntity()
        val context = entity.deriveContext(schemaVersion = -1)

        val exception =
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                featureRouter.versionDecode(
                    data = FakeFeatureCodec.BYTES_PRESET,
                    context = context,
                    existing = null
                )
            }

        assertEquals(
            -1,
            exception.version,
            "Negative versions must throw UnknownProtocolVersion with version -1"
        )
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_registryHasGappedNullVersion() = runEnv {
        val gappedRouter = object : BaseCodecResolver<FeatureEntity>(
            latestVersion = 2,
            versionRegistry = arrayOf(null, null, null),
            logger = logger
        ) {}

        val entity = FeatureEntity()
        val context = entity.deriveContext()

        val exception =
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                gappedRouter.versionDecode(
                    data = FakeFeatureCodec.BYTES_PRESET,
                    context = context,
                    existing = null
                )
            }

        assertEquals(
            1,
            exception.version,
            "Null registry slots must fail gracefully with version 1"
        )
    }
}