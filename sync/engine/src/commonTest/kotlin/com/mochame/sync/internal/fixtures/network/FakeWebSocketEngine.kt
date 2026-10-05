package com.mochame.sync.internal.fixtures.network

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.websocket.WebSocketCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.util.date.GMTDate
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext

/**
 * In-memory client engine for WebSocket testing in KMP.
 *
 * Intercepts outbound HTTP requests to mimic the server-side HTTP 101 Switching Protocols
 * handshake, supplying an in-memory [FakeWebSocketSession].
 */
class FakeWebSocketEngine(
    private val onConnect: (HttpRequestData) -> FakeWebSocketSession,
    parentContext: CoroutineContext,
    override val config: HttpClientEngineConfig = HttpClientEngineConfig()
) : HttpClientEngineBase("FakeWebSocketEngine") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = setOf(WebSocketCapability)

    override val dispatcher: CoroutineDispatcher =
        parentContext[ContinuationInterceptor] as CoroutineDispatcher

    /**
     * Captures outbound request handshakes, to view query parameters and paths for verification.
     */
    val handshakeRequests = Channel<HttpRequestData>(Channel.UNLIMITED)
    /**
     * Emits newly created [FakeWebSocketSession] instances as connections are established.
     */
    val sessionChannel = Channel<FakeWebSocketSession>(Channel.UNLIMITED)
    /**
     * When populated, forces the subsequent HTTP handshake to fail with the provided exception.
     */
    var failureOnConnect: Throwable? = null
    var connectGate: CompletableDeferred<Unit>? = null

    /**
     * Intercepts Ktor's client request pipeline, simulating a successful protocol switch.
     */
    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        handshakeRequests.send(data)
        failureOnConnect?.let { throw it }
        connectGate?.await()

        val session = onConnect(data)
        sessionChannel.send(session)

        return HttpResponseData(
            statusCode = HttpStatusCode.SwitchingProtocols,
            requestTime = GMTDate(),
            headers = headers {
                append(HttpHeaders.Upgrade, "websocket")
                append(HttpHeaders.Connection, "Upgrade")
            },
            version = HttpProtocolVersion.HTTP_1_1,
            body = session,
            callContext = session.coroutineContext
        )
    }
}