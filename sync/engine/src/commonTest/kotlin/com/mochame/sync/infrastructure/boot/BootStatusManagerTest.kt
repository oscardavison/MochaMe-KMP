package com.mochame.sync.infrastructure.boot

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.exceptions.MochaException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@Module
class BootStatusTestModule {
    @Single
    fun provideDefaultBootStatusManager(): DefaultBootStatusManager = DefaultBootStatusManager()
}

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend DefaultBootStatusManager.(TestScope) -> Unit) =
    runUnitEnvironment<DefaultBootStatusManager>(
        koinSetup = { modules(BootStatusTestModule::class) },
        block = block
    )

@ExperimentalCoroutinesApi
class BootStatusManagerTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // STATE TRANSITIONS
    // -----------------------------------------------------------

    @Test
    fun should_defaultToIdleStatus_when_nodeFirstBoots() = runEnv {
        assertEquals(BootState.Idle, bootState.value)
    }

    @Test
    fun should_updateStateCorrectly_when_passedState() = runEnv {
        updateState(BootState.Init)
        assertEquals(BootState.Init, bootState.value)

        updateState(BootState.Ready)
        assertEquals(BootState.Ready, bootState.value)
    }

    @Test
    fun should_acceptFailureDetails_when_updateBootStatePassedAnException() = runEnv {
        val errorException = MochaException.Transient.BootTimeout("Connection timed out")
        val failureState = BootState.TransientFailure("Network error", errorException)

        updateState(failureState)

        assertEquals(failureState, bootState.value)
        assertEquals(errorException, failureState.cause)
    }

    @Test
    fun should_conflateBootState_when_rapidSequentialUpdatesOccur() =
        runEnv { testScope ->
            // Given - queued asynchronous collector
            val emittedStates = mutableListOf<BootState>()
            val collectionJob = testScope.launch {
                bootState.collect { emittedStates.add(it) }
            }
            testScope.runCurrent()

            // When - rapid, distinct updates
            updateState(BootState.Init)
            updateState(BootState.Ready)
            testScope.runCurrent()

            // Then - the collector collected conflation of initialized and ready status
            val expectedStates = listOf(
                BootState.Idle,
                BootState.Ready
            )
            assertEquals(expectedStates, emittedStates)

            collectionJob.cancel()
        }

    @Test
    fun should_notifyActiveCollectorsInstantly_when_bootStateIsUpdated() =
        runEnv { testScope ->
            // Given - eager asynchronous collector
            val emittedStates = mutableListOf<BootState>()
            testScope.backgroundScope.launch(UnconfinedTestDispatcher(testScope.testScheduler)) {
                bootState.collect { emittedStates.add(it) }
            }

            // When
            updateState(BootState.Init)
            updateState(BootState.Ready)

            // Then
            val expectedStates = listOf(
                BootState.Idle,
                BootState.Init,
                BootState.Ready
            )
            assertEquals(expectedStates, emittedStates)
        }

    @Test
    fun awaitReady_whenBootTakesTime_suspendsNaturallyUntilWorkerEmitsReady() =
        runEnv { scope ->
            updateState(BootState.Init)

            scope.launch {
                delay(350.milliseconds)
                updateState(BootState.Ready)
            }

            var completed = false
            val awaitJob = scope.launch {
                awaitReady()
                completed = true
            }

            scope.runCurrent()
            assertFalse(completed)
            assertEquals(0L, scope.testScheduler.currentTime)

            scope.advanceUntilIdle()

            assertTrue(completed)
            assertTrue(awaitJob.isCompleted)
            assertEquals(350L, scope.testScheduler.currentTime)
        }

    // -----------------------------------------------------------
    // PROVIDER ACCESS
    // -----------------------------------------------------------

    @Test
    fun should_provideBootState_when_accessedViaProviderInterface() = runEnv {
        // Given
        val provider = this as BootStatusProvider
        // When
        updateState(BootState.Ready)
        // Then
        assertEquals(BootState.Ready, provider.bootState.value)
    }
}
