package com.mochame.sync.di.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.logger.test.TestLoggerModule
import com.mochame.node.fixtures.FakeNodeContextManager
import com.mochame.node.fixtures.di.FixturesNodeModule
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.infrastructure.ClientWebSocketTransport
import com.mochame.sync.internal.fixtures.network.FakeWebSocketEngine
import com.mochame.sync.internal.fixtures.network.FakeWebSocketSession
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.request.HttpRequestData
import io.ktor.websocket.CloseReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.ClosedSendChannelException
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        SyncInfraModule::class,
        FixturesNodeModule::class,
        TestLoggerModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class TransportTestModule {

    @Single(binds = [HttpClientEngine::class, FakeWebSocketEngine::class])
    fun provideFakeWebSocketEngine(
        @AppBackgroundScope backgroundScope: CoroutineScope
    ): FakeWebSocketEngine = FakeWebSocketEngine(
        onConnect = { _ ->
            FakeWebSocketSession(backgroundScope.coroutineContext)
        },
        parentContext = backgroundScope.coroutineContext,
        config = HttpClientEngineConfig()
    )
}

@Factory
internal class ClientWebSocketTransportTestEnv(
    val transport: ClientWebSocketTransport,
    val engine: FakeWebSocketEngine,
    val nodeManager: FakeNodeContextManager,
    val logger: Logger
) : AutoCloseable {
    var currentSession: FakeWebSocketSession? = null
        private set

    suspend fun awaitHandshake(): HttpRequestData =
        engine.handshakeRequests.receive()

    suspend fun awaitSession(): FakeWebSocketSession =
        engine.sessionChannel.receive().also { currentSession = it }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun teardown(
        reason: CloseReason = CloseReason(CloseReason.Codes.NORMAL, "Remote disconnect")
    ) {
        try {
            currentSession?.close(reason)
        } catch (_: ClosedSendChannelException) {
        }

        while (!engine.sessionChannel.isEmpty) {
            try {
                engine.sessionChannel.receive().close(reason)
            } catch (_: ClosedSendChannelException) {
            }
        }
        transport.pause() // now implemented structured concurrency (tests not refactored)
    }

    override fun close() {
        transport.close()
    }
}