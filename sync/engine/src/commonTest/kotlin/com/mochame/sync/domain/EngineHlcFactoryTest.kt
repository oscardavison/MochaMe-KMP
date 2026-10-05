@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.domain

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.utils.fixtures.TestNodeId
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.instant
import com.mochame.sync.di.hlc.EngineHlcTestModule
import com.mochame.sync.di.hlc.HLCTestEnvironment
import com.mochame.utils.fixtures.TestHlcFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// -----------------------------------------------------------
// SUT Environment
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend HLCTestEnvironment.(TestScope) -> Unit) =
    runUnitEnvironment<HLCTestEnvironment>(
        koinSetup = { modules(EngineHlcTestModule::class) },
        block = block
    )

class EngineHlcFactoryTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // SUCCESS PATH
    // -----------------------------------------------------------
    @Test
    fun should_initialize_valid_hlc_when_new_install() = runEnv {
        // Given: wallClock is March 2026
        fakeClock.setTime(HLC.APP_RELEASE_TIME)

        // When: First hydration with no history
        val result = factory.hydrate(null, TestNodeId.A)

        // Then: TS is exactly wallClock, count is 0
        assertEquals(HLC.APP_RELEASE_TIME.toEpochMilliseconds(), result.ts)
        assertEquals(0, result.count)
    }

    @Test
    fun should_log_warning_but_succeed_when_future_jump_detected() = runEnv {
        // Given: Wall clock is 2 years ahead of history
        val historyTs = HLC.APP_RELEASE_TIME.also { fakeClock.setTime(it) }
        val historicHlc = TestHlcFactory.create(historyTs, 1, TestNodeId.A)
        fakeClock.advanceTime(HLC.ONE_DAY * 60)

        // When
        factory.hydrate(historicHlc, TestNodeId.A)

        // Then
        assertTrue(writer.logs.any { it.message.contains("60 days", true) })
        assertTrue(writer.logs.any { it.message.contains("successfully hydrated", true) })
    }

    @Test
    fun should_persist_new_node_id_for_subsequent_hlcs_after_migration() =
        runEnv {
            // Given: History from "node-old"
            fakeClock.setTime(HLC.APP_RELEASE_TIME)
            val history = TestHlcFactory.create(
                ts = HLC.APP_RELEASE_TIME,
                count = 1,
                nodeId = TestNodeId.A
            )
            factory.hydrate(history, TestNodeId.B)

            // When: Generating the next HLC
            val next = factory.getNextHlc()

            // Then: It must use "node-new"
            assertEquals(
                TestNodeId.B,
                next.nodeId,
                "Factory failed to adopt the new NodeID after hydration."
            )
            assertEquals(
                2,
                next.count,
                "Migration at same ms as hydration didn't increment count."
            )
        }

    // -----------------------------------------------------------
    // COUNTER
    // -----------------------------------------------------------
    @Test
    fun should_increment_logical_counter_when_wall_clock_has_not_advanced() =
        runEnv {
            // Given
            factory.hydrate(null, TestNodeId.A)

            // When
            val first = factory.getNextHlc()
            val second = factory.getNextHlc()

            // Then
            assertEquals(1, first.count)
            assertEquals(2, second.count)
            assertEquals(first.ts, second.ts)
        }

    @Test
    fun should_reset_counter_to_zero_when_wall_clock_moves_forward() = runEnv {
        // Given
        factory.hydrate(null, TestNodeId.A)
        val first = factory.getNextHlc()

        // When
        fakeClock.advanceTime(1.milliseconds)
        val second = factory.getNextHlc()

        // Then
        assertEquals(0, second.count)
        assertTrue(second.ts > first.ts)
    }

    @Test
    fun should_reset_counter_to_zero_during_migration_if_wall_clock_is_ahead() =
        runEnv {
            // Given: History is older than current wall clock
            val olderHistory = TestHlcFactory.create(
                ts = fakeClock.now(),
                count = 1,
                nodeId = TestNodeId.A
            )
            fakeClock.advanceTime(1.seconds)
            // When
            val result = factory.hydrate(olderHistory, TestNodeId.B)

            // Then: TS pins to wall clock, counter resets
            assertEquals(fakeClock.now(), result.instant)
            assertEquals(0, result.count)
            assertEquals(TestNodeId.B, result.nodeId)
        }

    @Test
    fun should_preserve_history_counter_during_migration_if_wall_clock_is_behind() =
        runEnv {
            // Given: History is newer than current wall clock (but within 1 hour)
            val newerHistoryTs = fakeClock.now().plus(1.seconds)
            val newerHistory = TestHlcFactory.create(
                ts = newerHistoryTs,
                count = 99,
                nodeId = TestNodeId.A
            )

            // When
            val result = factory.hydrate(newerHistory, TestNodeId.B)

            // Then: TS pins to history, counter is preserved
            assertEquals(newerHistoryTs, result.instant)
            assertEquals(99, result.count)
            assertEquals(TestNodeId.B, result.nodeId)
        }

    // -----------------------------------------------------------
    // MONOTONICITY / CONCURRENCY / CONTENTION
    // -----------------------------------------------------------
    @Test
    fun should_ignore_second_hydration_and_log_warning() = runEnv {
        // Given
        factory.hydrate(null, TestNodeId.A)

        // When
        val secondResult = factory.hydrate(
            TestHlcFactory.create(
                ts = 12345,
                count = 0,
                nodeId = TestNodeId.A
            ),
            TestNodeId.A
        )

        // Then: Returns the first hydration result
        assertTrue(writer.logs.any { it.message.contains("rehydrate") })
        assertEquals(TestNodeId.A, secondResult.nodeId)
    }

    @Test
    fun should_maintain_monotonicity_when_multi_threaded_push_for_nextHlc() =
        runEnv { scope ->
            // Arrange: Multi-threaded simulation suspended with CompletableDeferred
            val threadCount = 5
            val readySignals = List(threadCount) { CompletableDeferred<Unit>() }
            val iterations = 5
            val gate = CompletableDeferred<Unit>()
            factory.hydrate(null, TestNodeId.A)

            val workerDeferreds = List(threadCount) { workerId ->
                scope.async(Dispatchers.IO) {
                    readySignals[workerId].complete(Unit)
                    gate.await()

                    val localResults = mutableListOf<HLC>()
                    repeat(iterations) {
                        val current = factory.getNextHlc()
                        if (localResults.isNotEmpty()) {
                            assertTrue(
                                current > localResults.last(),
                                "Local Monotonicity Failure: $current <= ${localResults.last()}"
                            )
                        }
                        localResults.add(current)
                    }
                    localResults
                }
            }
            readySignals.awaitAll()

            gate.complete(Unit)
            val allResults = workerDeferreds.awaitAll().flatten()

            // Assert: Distinct results across all threads matching expected total
            val totalExpected = threadCount * iterations
            assertEquals(
                totalExpected, allResults.distinct().size,
                "Causality Violation: Duplicate HLCs detected across threads!"
            )

            // Act II: introduce clock skew and fetchById a new HLC
            fakeClock.reverseTime(1.milliseconds)
            val skewedSentinel = factory.getNextHlc()

            // Assert: the skewed sentinel is still the greatest value
            val maxProduced = allResults.maxOrNull()!!
            assertTrue(
                skewedSentinel > maxProduced,
                "Causality Broken possibly by Clock Skew! Sentinel $skewedSentinel is not > $maxProduced"
            )
            assertEquals(
                maxProduced.ts,
                skewedSentinel.ts,
                "HLC should have pinned to the max known physical time during skew."
            )
            assertTrue(
                skewedSentinel.count > maxProduced.count,
                "Logical counter failed to increment during physical clock regression."
            )
        }


    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_yield_and_retry_when_counter_is_exhausted() = runEnv { scope ->
        // Arrange: Hit the counter limit at a certain time
        fakeClock.setTime(HLC.APP_RELEASE_TIME)
        val initialHlc = TestHlcFactory.create(
            ts = HLC.APP_RELEASE_TIME,
            count = HLC.MAX_COUNTER_INT,
            nodeId = TestNodeId.A
        )
        factory.hydrate(initialHlc, TestNodeId.A)

        // Act: Launch a coroutine to make the 65,536th call
        var capturedHlc: HLC? = null
        val stallingJob = scope.launch {
            capturedHlc = factory.getNextHlc()
        }
        // -- Yield the test coroutine, so the child job runs and hits its own yield
        yield()

        // Assert: The job should be suspended (Case C)
        assertNull(capturedHlc, "Factory should have yielded, but it returned an HLC!")
        assertFalse(stallingJob.isCompleted, "Job should be stuck in the yield loop")

        // Act: Advance the fake clock by 1ms
        fakeClock.advanceTime(1.seconds)
        // -- Wait for the child job to see the new time and hit Case A
        stallingJob.join()

        // Assert:
        val exhaustionLog = writer.logs.any { it.message.contains("counter exhaustion") }
        val logCount = writer.logs.count { it.message.contains("counter exhaustion") }
        assertTrue(exhaustionLog, "Should have logged a warning about exhaustion")
        assertEquals(
            1,
            logCount,
            "Concurrency problem - only one yield should have occurred."
        )

        assertNotNull(capturedHlc)
        assertEquals(
            HLC.APP_RELEASE_TIME.plus(1.seconds),
            capturedHlc!!.instant,
            "New HLC should use the new millisecond"
        )
        assertEquals(0, capturedHlc!!.count, "Counter should have reset to 0")
    }

    @Test
    fun should_only_initialize_one_new_install_when_init_under_contention() =
        runEnv { scope ->
            val threadCount = 8
            val readySignals = List(threadCount) { CompletableDeferred<Unit>() }
            val gate = CompletableDeferred<Unit>()
            val nodeId = TestNodeId.A

            val jobs = List(threadCount) { index ->
                scope.launch(Dispatchers.IO) {
                    readySignals[index].complete(Unit)
                    gate.await() // THE STARTING GUN (gemini)
                    factory.hydrate(null, nodeId)
                }
            }
            readySignals.awaitAll()

            gate.complete(Unit)
            jobs.joinAll()

            // Verification
            val initLogs = writer.logs.filter { it.message.contains("New Install", true) }
            val rehydrateLogs = writer.logs.filter { it.message.contains("rehydrate") }
            assertEquals(
                1,
                initLogs.size,
                "Race Condition. Multiple threads triggered 'New Install' logic."
            )
            assertEquals(
                threadCount - 1,
                rehydrateLogs.size,
                "Race Condition. Multiple threads triggered 'New Install' logic."
            )

        }

    // -----------------------------------------------------------
    // HYDRATION PARSING EXCEPTIONS
    // -----------------------------------------------------------
    @Test
    fun should_throw_HlcParseException_when_string_has_incorrect_number_of_parts() =
        runEnv {
            // Given
            val corruptInput = "1740787200000:0" // Missing NodeID

            // When / Then
            assertFailsWith<MochaException.Persistent.HlcParseException> {
                HLC.parse(corruptInput)
            }
        }

    @Test
    fun should_throw_HlcParseException_when_timestamp_is_not_a_valid_long() {
        // Given
        val corruptInput = "not_a_long:0:device-a"

        // When / Then
        assertFailsWith<MochaException.Persistent.HlcParseException> {
            HLC.parse(corruptInput)
        }
    }

    @Test
    fun should_throw_HlcParseException_when_counter_is_not_a_valid_int() =
        runEnv {
            // Given
            val corruptInput = "1740787200000:abc:device-a"

            // When / Then
            assertFailsWith<MochaException.Persistent.HlcParseException> {
                HLC.parse(corruptInput)
            }
        }

    @Test
    fun should_throw_parse_exception_when_node_id_is_blank() = runEnv {
        // Given
        val corruptInput = "1740787200000:0: " // Blank NodeID

        // When / Then
        assertFailsWith<MochaException.Persistent.HlcParseException> {
            HLC.parse(corruptInput)
        }
    }

    // -----------------------------------------------------------
    // HYDRATION FAILURE
    // -----------------------------------------------------------
    @Test
    fun should_throw_ClockSkew_when_history_is_poisoned_with_future_date() =
        runEnv {
            // Given: System clock is March 2026, but history is Jan 2040
            val futureTs = 2209032000000L
            val poisonedHlc =
                TestHlcFactory.create(ts = futureTs, count = 1, nodeId = TestNodeId.A)

            // When / Then
            assertFailsWith<MochaException.Persistent.ClockSkew> {
                factory.hydrate(poisonedHlc, TestNodeId.A)
            }

            assertTrue(writer.logs.any {
                it.message.contains("future", ignoreCase = true)
            })
        }

    @Test
    fun should_report_clock_skew_when_system_time_is_before_2026_floor() =
        runEnv {
            // Given
            fakeClock.setTime(Instant.fromEpochMilliseconds(1000L)) // Set back to Jan 1st, 1970

            // When & Then
            val exception = assertFailsWith<MochaException.Persistent.ClockSkew> {
                factory.hydrate(null, TestNodeId.A)
            }

            assertTrue(
                exception.drift.inWholeMilliseconds > 0,
                "Drift should be a positive value"
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun should_log_warning_when_counter_exhaustion_triggers_delay() =
        runEnv { scope ->
            // When: The factory at the 16-bit limit, matching the device time
            val maxCounterHlc = TestHlcFactory.create(count = HLC.MAX_COUNTER_INT)
            factory.hydrate(maxCounterHlc, TestNodeId.A)

            // Then: This will trigger a delay on attempting to get a new hlc
            val hlcJob = scope.launch { factory.getNextHlc() }
            yield()
            fakeClock.advanceTime(1.milliseconds)
            scope.advanceTimeBy(1.milliseconds)
            scope.runCurrent()

            // Verify the log recorded the yield event
            val finalHlc = factory.getCurrentHlc()
            val counterWarning = writer.logs.find { it.message.contains("Attempts: 1") }

            assertEquals(0, finalHlc?.count)
            assertEquals(fakeClock.now().toEpochMilliseconds(), finalHlc?.ts)
            assertNotNull(counterWarning, "Missing visibility into counter exhaustion!")
        }

    // -----------------------------------------------------------
    // WITNESS
    // -----------------------------------------------------------

    @Test
    fun should_logWarningAndNotThrow_when_witnessCalledBeforeHydration() = runEnv {
        // factory is not hydrated — state is null
        val remoteHlc = TestHlcFactory.create(ts = 1_000L)

        // Should not throw
        factory.witness(remoteHlc)

        // State must remain null — witness must not initialise state
        val currentHlc = factory.getCurrentHlc()
        assertEquals(
            currentHlc,
            null,
            "witness must not initialise factory state when called before hydration"
        )

        // Warning must have been logged
        val warningLog = writer.logs.find {
            it.message.contains("no internal state", ignoreCase = true)
        }
        assertNotNull(
            warningLog,
            "witness must log a warning when state is uninitialised"
        )
    }

    @Test
    fun should_advanceToRemoteTs_when_remoteTsExceedsBothWallClockAndLocalTs() = runEnv {
        val localTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(TestHlcFactory.create(ts = localTs), TestHlcFactory.DEFAULT_NODE)

        // Remote is ahead of local clock but within drift
        val remoteTs = localTs + 50.seconds.inWholeMilliseconds
        val remoteHlc = TestHlcFactory.create(ts = remoteTs, count = 3)

        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(result)
        // Result ts must equal remote ts (it was the max)
        assertEquals(
            remoteTs,
            result.ts,
            "Factory ts must advance to remote ts when remote wins"
        )
        assertEquals(
            3,
            result.count,
            "Count must be remote.count + 1 when ts matches remote"
        )
    }

    @Test
    fun should_advanceToWallClock_when_wallClockExceedsBothRemoteAndLocalTs() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(
            TestHlcFactory.create(ts = baseTs - 10_000L),
            TestHlcFactory.DEFAULT_NODE
        )
        val remoteHlc = TestHlcFactory.create(ts = baseTs - 5_000L, count = 2)

        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(result)
        assertEquals(
            baseTs,
            result.ts,
            "Factory ts must use wall clock when it is the maximum"
        )
        assertEquals(
            0,
            result.count,
            "Count must reset to 0 when wall clock wins the timestamp"
        )
    }

    @Test
    fun should_notRegress_when_localTsExceedsRemoteAndWallClock() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds().plus(1000L)
        val localTs = baseTs + 10_000L
        val localCount = 7
        val remoteHlc = TestHlcFactory.create(ts = baseTs - 1_000L, count = 0)
        val localHlc = TestHlcFactory.create(ts = localTs, count = localCount)

        factory.hydrate(localHlc, TestHlcFactory.DEFAULT_NODE)
        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(result)
        assertEquals(localTs, result.ts, "Factory ts must not regress below local ts")
        assertEquals(
            localCount,
            result.count,
            "Count must be local.count when local ts wins"
        )
    }

    @Test
    fun should_ignoreWitnessCount_when_localTsEqualsRemoteTsAndBothExceedWallClock() =
        runEnv {
            val baseTs = fakeClock.now().toEpochMilliseconds()
            val sharedTs = baseTs + 20_000L
            factory.hydrate(
                TestHlcFactory.create(ts = sharedTs, count = 9),
                TestHlcFactory.DEFAULT_NODE
            )
            val remoteHlc = TestHlcFactory.create(ts = sharedTs, count = 5)

            factory.witness(remoteHlc)

            val result = factory.getCurrentHlc()
            assertNotNull(result)
            assertEquals(sharedTs, result.ts)
            assertEquals(
                9,
                result.count,
                "Count cannot be reduced by a remote Hlc."
            )
        }

    @Test
    fun should_witnessCount_when_localAndRemoteAndDeviceTsAreEqual() = runEnv {
        val sharedTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(
            TestHlcFactory.create(ts = sharedTs, count = 3),
            TestHlcFactory.DEFAULT_NODE
        )
        val remoteHlc = TestHlcFactory.create(ts = sharedTs, count = 3)

        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(result)
        assertEquals(sharedTs, result.ts)
        assertEquals(3, result.count)
    }

    @Test
    fun should_advanceCountMonotonically_when_sameRemoteHlcWitnessedTwice() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(TestHlcFactory.create(ts = baseTs), TestHlcFactory.DEFAULT_NODE)
        val remoteHlc = TestHlcFactory.create(ts = baseTs, count = 0)

        factory.witness(remoteHlc)
        val afterFirst = factory.getCurrentHlc()
        factory.witness(remoteHlc)
        val afterSecond = factory.getCurrentHlc()
        val finalHlc = factory.getNextHlc()

        assertNotNull(afterFirst)
        assertNotNull(afterSecond)
        assertEquals(afterSecond, afterFirst)
        assertTrue(finalHlc > afterSecond)
        assertEquals(1, finalHlc.count)
    }

    @Test
    fun should_remainMonotonic_when_multipleRemoteHlcsWitnessedInAscendingOrder() =
        runEnv {
            val baseTs = fakeClock.now().toEpochMilliseconds()
            factory.hydrate(
                TestHlcFactory.create(ts = baseTs),
                TestHlcFactory.DEFAULT_NODE
            )
            val sequence = TestHlcFactory.chronologicalSequence(
                size = 10,
                stepMs = 1_000L,
                baseTs = baseTs + 1_000L
            )

            var previous = factory.getCurrentHlc()!!
            sequence.forEach { remoteHlc ->
                factory.witness(remoteHlc)
                val current = factory.getCurrentHlc()!!
                assertTrue(
                    current > previous,
                    "Each witness must produce a strictly greater HLC: $previous -> $current"
                )
                previous = current
            }
        }

    @Test
    fun should_preserveLocalNodeId_when_witnessHlcCarriesDifferentNodeId() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(
            TestHlcFactory.create(ts = baseTs),
            TestHlcFactory.DEFAULT_NODE
        )
        val remoteHlc = TestHlcFactory.create(
            ts = baseTs + 5_000L,
            nodeId = TestNodeId.B
        )

        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(result)
        assertEquals(
            TestHlcFactory.DEFAULT_NODE,
            result.nodeId,
            "witness must preserve local nodeId — remote nodeId must never overwrite it"
        )
    }

    @Test
    fun should_throwClockSkew_when_remoteTsExceedsMaxDriftThreshold() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(TestHlcFactory.create(ts = baseTs), TestHlcFactory.DEFAULT_NODE)

        val drift = HLC.MAX_DRIFT.plus(1.milliseconds)
        val futureTs = baseTs + drift.inWholeMilliseconds
        val remoteHlc = TestHlcFactory.create(ts = futureTs)

        val result = assertFailsWith<MochaException.Persistent.ClockSkew> {
            factory.witness(remoteHlc)
        }

        assertEquals(drift, result.drift)
    }

    @Test
    fun should_notThrow_when_remoteTsIsWithinMaxDriftBoundary() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        factory.hydrate(TestHlcFactory.create(ts = baseTs), TestHlcFactory.DEFAULT_NODE)
        val acceptableTs = baseTs + 30.seconds.inWholeMilliseconds
        val remoteHlc = TestHlcFactory.create(ts = acceptableTs)

        factory.witness(remoteHlc)

        val result = factory.getCurrentHlc()
        assertNotNull(
            result,
            "Factory state must be updated after a valid witness within drift boundary"
        )
    }

    @Test
    fun should_preserveState_when_witnessThrowsDueToClockSkew() = runEnv {
        val baseTs = fakeClock.now().toEpochMilliseconds()
        val originalCount = 4
        factory.hydrate(
            TestHlcFactory.create(ts = baseTs, count = originalCount),
            TestHlcFactory.DEFAULT_NODE
        )
        val stateBeforeRejection = factory.getCurrentHlc()
        val farFutureHlc =
            TestHlcFactory.create(ts = baseTs + 2.minutes.inWholeMilliseconds)

        try {
            factory.witness(farFutureHlc)
        } catch (_: Exception) {
        }

        val stateAfterRejection = factory.getCurrentHlc()
        assertEquals(
            stateBeforeRejection,
            stateAfterRejection,
            "Factory state must be unchanged after a rejected witness — no partial mutation allowed"
        )
    }

    @Test
    fun should_produceMonotonicFinalState_when_multipleWitnessCallsFireConcurrently() =
        runEnv { scope ->
            val baseTs = fakeClock.now().toEpochMilliseconds()
            factory.hydrate(
                TestHlcFactory.create(ts = baseTs),
                TestHlcFactory.DEFAULT_NODE
            )

            val threadCount = 8
            val iterationsPerThread = 5
            val totalOperations = threadCount * iterationsPerThread
            val targetTs = baseTs + 1_000L
            val gate = CompletableDeferred<Unit>()
            val readySignals = List(threadCount) { CompletableDeferred<Unit>() }

            val concurrentHlcs = TestHlcFactory.concurrentSequence(
                size = totalOperations,
                ts = targetTs
            )

            // When - Multi-threaded witness updates at identical timestamps
            val workerJobs = List(threadCount) { threadId ->
                scope.launch(Dispatchers.Default) {
                    readySignals[threadId].complete(Unit)
                    gate.await()

                    val startIndex = threadId * iterationsPerThread
                    repeat(iterationsPerThread) { iteration ->
                        val remoteHlc = concurrentHlcs[startIndex + iteration]
                        factory.witness(remoteHlc)
                    }
                }
            }
            readySignals.awaitAll()

            gate.complete(Unit)
            workerJobs.joinAll()

            // Then
            val result = factory.getCurrentHlc()
            assertNotNull(result)
            assertEquals(targetTs, result.ts, "Timestamp must match witnessed floor")
            assertEquals(totalOperations - 1, result.count, "Total calls: count=${result.count}")
        }

    @Test
    fun should_neverLoseWitnessUpdates_when_witnessCalledFromMultipleCoroutines() =
        runEnv { scope ->
            factory.hydrate(
                TestHlcFactory.create(),
                TestHlcFactory.DEFAULT_NODE
            )
            val threadCount = 8
            val iterationsPerThread = 5
            val totalOperations = threadCount * iterationsPerThread

            val readySignals = List(threadCount) { CompletableDeferred<Unit>() }
            val gate = CompletableDeferred<Unit>()
            val hlcSequence = TestHlcFactory.chronologicalSequence(size = totalOperations)
            val maxExpectedTs = hlcSequence.last().ts

            val workerJobs = List(threadCount) { threadId ->
                scope.launch(Dispatchers.Default) {
                    readySignals[threadId].complete(Unit)
                    gate.await()

                    val startIndex = threadId * iterationsPerThread
                    repeat(iterationsPerThread) { iteration ->
                        val remoteHlc = hlcSequence[startIndex + iteration]
                        factory.witness(remoteHlc)
                    }
                }
            }
            readySignals.awaitAll()

            gate.complete(Unit)
            workerJobs.joinAll()

            // Then
            val result = factory.getCurrentHlc()
            assertNotNull(result)
            assertEquals(
                maxExpectedTs,
                result.ts,
                "Factory state regressed! Expected highest timestamp $maxExpectedTs, got ${result.ts}"
            )
        }

    // -----------------------------------------------------------
    // MISC
    // -----------------------------------------------------------
    // Demo test purely to better understand job hierarchies and yield()
    @Test
    fun testSingleStep() = runTest {
        var count = 0
        val job = launch {
            while (isActive) {
                count++
                yield() // The "infinite" pinger
            }
        }

        // Instead of runCurrent(), which seems to reschedule immediately on the schedular
        yield()
        // Now count is 1. The test yielded, the loop ran once, then it yielded back to the test.
        assertEquals(1, count)

        yield()
        // Now count is 2.
        assertEquals(2, count)

        job.cancel()
    }
}