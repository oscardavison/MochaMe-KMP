package com.mochame.sync.di.codec

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.test.TestLoggerModule
import com.mochame.sync.di.node.FixturesNodeModule
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.sync.di.SyncConcurrencyModule
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.SyncSerializationModule
import com.mochame.sync.infrastructure.serialization.BatchCodecV1
import com.mochame.sync.infrastructure.serialization.DefaultPayloadCodec
import com.mochame.sync.infrastructure.serialization.IntentCodecV1
import com.mochame.sync.internal.fixtures.serialization.FakeBatchCodec
import com.mochame.sync.internal.fixtures.serialization.FakeIntentCodec
import com.mochame.sync.internal.fixtures.serialization.CodecResolverFixture
import com.mochame.sync.domain.serialization.BatchCodecResolver
import com.mochame.sync.domain.serialization.IntentCodecResolver
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

/**
 * Adds both real and fake options to the DI registry. It is up to the implementing
 * test class whether a real or fake instance is to be instantiated. If simply declaring
 * a real type, that instance and its routers will all attach to real codecs, and the test
 * would be a full integration test.
 * Fake Codec implementations do not bind to the standard interfaces, they
 * are pulled in purely through a component scan as separate instances, which
 * can then be accessed via [CodecFixtureTestEnv] to wire into any different routers.
 * If using the fixture environment, explicitly use the real vs fake options.
 *
 * Routers to any intermediary serialization layer are provided as real components.
 *
 * Real type example:
 * ```kotlin
 * private inline fun runEnv(crossinline block: suspend IntentCodecV1.(TestScope) -> Unit) =
 *     runUnitEnvironment<IntentCodecV1>(
 *         koinSetup = { modules(CodecTestModule::class) },
 *         block = block
 *     )
 * ```
 *
 * Real type with access to insert fakes within the serialization pipeline (to test
 * various pipeline behaviors):
 * ```kotlin
 * private inline fun runEnv(crossinline block: CodecFixtureTestEnv.(TestScope) -> Unit) =
 *     runUnitEnvironment<CodecFixtureTestEnv>(
 *         koinSetup = { modules(CodecTestModule::class) },
 *         block = block
 *     )
 *
 * ...
 * val multiVersionIntentRouter = realIntentCodec.toRouterWithVersion(fakeIntentCodec, logger)
 *
 * assertFailsWith<SerializationException> {
 *     realIntentCodec.decode(FakeIntentCodec.BYTES_PRESET)
 * }
 * ```
 */
@Module(
    includes = [
        TestLoggerModule::class,
        SyncSerializationModule::class,
        FixturesPlatformModule::class
    ]
)
@ComponentScan(
    "com.mochame.sync.internal.fixtures.serialization",
    "com.mochame.sync.di.codec"
)
internal class CodecTestModule

/**
 * Routers themselves to be real components, implementing v1 of a real codec.
 * Environments can declare to use an extension method for creating a multiversioned router,
 * with access to any fakes for each layer, declared in [CodecFixtureTestEnv].
 *
 * ```kotlin
 * val multiVersionIntentRouter = realIntentCodec.toRouterWithVersion(fakeIntentCodec, logger)
 * ```
 */
@Module(
    includes = [
        TestLoggerModule::class,
        FixturesPlatformModule::class,
        FixturesNodeModule::class,
        FakeTimeProviderModule::class,
        SyncInfraModule::class,
        SyncConcurrencyModule::class
    ]
)
@ComponentScan("com.mochame.sync.internal.fixtures.serialization", "com.mochame.sync.di.codec")
internal class CodecRouterTestModule


@ExperimentalKermitApi
@Factory
internal class CodecFixtureTestEnv(
    val featureRouter: CodecResolverFixture,
    val intentRouter: IntentCodecResolver,
    val realIntentCodec: IntentCodecV1,
    val fakeIntentCodec: FakeIntentCodec,
    val realBatchCodec: BatchCodecV1,
    val fakeBatchCodec: FakeBatchCodec,
    val batchRouter: BatchCodecResolver,
    val realPayloadCodec: DefaultPayloadCodec,
    val writer: TestLogWriter,
    val logger: Logger
)