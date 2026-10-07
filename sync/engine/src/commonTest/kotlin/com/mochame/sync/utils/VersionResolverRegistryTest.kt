@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.utils

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.codec.BaseCodecResolver
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.utils.getCodec
import com.mochame.sync.di.codec.CodecFixtureTestEnv
import com.mochame.sync.di.codec.CodecRouterTestModule
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
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
        // Given
        val version = 0
        val primaryKey = 1000L

        // When & Then
        val exception = assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
            featureCodecResolver.getCodec(
                version = version,
                logger = logger,
                contextKey = primaryKey
            )
        }

        assertEquals(0, exception.version)
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_schemaVersionIsOutOfBounds() = runEnv {
        // Given
        val version = 3
        val primaryKey = 1000L

        // When & Then
        val exception = assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
            featureCodecResolver.getCodec(
                version = version,
                logger = logger,
                contextKey = primaryKey
            )
        }

        assertEquals(3, exception.version)
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_schemaVersionIsNegative() = runEnv {
        // Given
        val version = -1
        val primaryKey = 1000L

        // When & Then
        val exception = assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
            featureCodecResolver.getCodec(
                version = version,
                logger = logger,
                contextKey = primaryKey
            )
        }

        assertEquals(-1, exception.version)
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_registryHasGappedNullVersion() = runEnv {
        // Given
        val gappedRouter = object : BaseCodecResolver<FeatureEntity>(
            latestVersion = 2,
            versionRegistry = arrayOf(null, null, null),
            logger = logger
        ) {}
        val version = 1
        val primaryKey = 1000L

        // When & Then
        val exception = assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
            gappedRouter.getCodec(
                version = version,
                logger = logger,
                contextKey = primaryKey
            )
        }

        assertEquals(1, exception.version)
    }
}