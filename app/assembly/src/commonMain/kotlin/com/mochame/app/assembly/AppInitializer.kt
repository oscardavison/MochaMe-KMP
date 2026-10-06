package com.mochame.app.assembly

import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.boot.BootStatusUpdater
import com.mochame.sync.api.network.NetworkConfig
import com.mochame.sync.api.network.SyncTransport
import com.mochame.sync.api.coordination.SyncCoordinator
import com.mochame.sync.api.coordination.SyncJanitor
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds

interface AppInitializer {
    fun startBootSequence(): Job
}

@Single(binds = [AppInitializer::class], createdAtStart = true)
internal class DefaultAppInitializer(
    private val janitor: SyncJanitor,
    private val coordinator: SyncCoordinator,
    private val bootUpdater: BootStatusUpdater,
    private val transport: SyncTransport,
    private val connectionEndPoint: NetworkConfig,
    private val database: MochaMeDatabase,
    @AppBackgroundScope private val appBackgroundScope: CoroutineScope,
    logger: Logger
) : AppInitializer, AutoCloseable {

    private val logger =
        logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.BOOT, "AppIni")

    private val initializerJob = SupervisorJob(appBackgroundScope.coroutineContext[Job])
    private val initializerScope = CoroutineScope(
        appBackgroundScope.coroutineContext + initializerJob + CoroutineName("AppInitializer")
    )

    private val activeBootJob = atomic<Job?>(null)
    private val isDatabaseClosed = atomic(false)

    init {
        startBootSequence()
        registerShutdownHook()
    }

    override fun startBootSequence(): Job {
        activeBootJob.value?.let { if (it.isActive) return it }

        val newJob = initializerScope.launch(
            context = CoroutineName("BootSequence"),
            start = CoroutineStart.LAZY
        ) {
            executeBootSequence()
        }

        return if (activeBootJob.compareAndSet(null, newJob)) {
            newJob.invokeOnCompletion { activeBootJob.value = null }
            newJob.start()
            newJob
        } else {
            newJob.cancel()
            activeBootJob.value ?: newJob
        }
    }

    private suspend fun executeBootSequence() {
        bootUpdater.updateState(BootState.Init)
        try {
            logger.i { "Initializing application..." }

            janitor.startupChecks().join()
            delay(1.5.seconds) // so i get to see wheel spin working

            bootUpdater.updateState(BootState.Ready)
            logger.i { "Application initialized successfully..." }

            configureTransport()

            transport.connect(connectionEndPoint)

            coordinator.startOutboundListener()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Encountered a critical boot failure: ${e.message}" }
            bootUpdater.updateState(BootState.LockOut(e.message ?: "Unknown error", e))
        }
    }

    private fun configureTransport() {
        transport.registerInboundAckHandler { batchId, watermark ->
            coordinator.handleInboundAck(batchId, watermark)
        }

        transport.registerInboundDeltaHandler { watermark, bytes ->
            coordinator.handleInboundBytes(watermark, bytes)
        }

        transport.setOnConnectedListener {
            coordinator.processQueueUntilExhausted()
        }

        transport.setOnDisconnectedListener {
            coordinator.abortInFlightBatch(
                MochaException.Transient.NetworkDisconnect("Transport disconnected")
            )
        }
    }

    private fun registerShutdownHook() {
        appBackgroundScope.coroutineContext[Job]?.invokeOnCompletion { cause ->
            logger.d { "Closing database..." }
            closeDatabase()
        }
    }

    private fun closeDatabase() {
        if (isDatabaseClosed.compareAndSet(expect = false, update = true)) {
            try {
                database.close()
                logger.d { "Database closed." }
            } catch (e: Exception) {
                logger.e(e) { "Failed to close database during scope shutdown." }
            }
        }
    }

    override fun close() {
        initializerJob.cancel()
        closeDatabase()
    }
}