package com.mochame.support

import androidx.room.RoomDatabase
import androidx.room.useReaderConnection
import com.mochame.annotations.AppBackgroundScope
import com.mochame.annotations.DefaultContext
import com.mochame.annotations.IoContext
import com.mochame.annotations.MainContext
import com.mochame.logger.test.TestLoggerModule
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.qualifier
import org.koin.dsl.module
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// -----------------------------------------------------------
// MODULES / PLATFORM BRIDGES
// -----------------------------------------------------------

/**
 * Central bridge for TestRunners
 */
expect abstract class MochaPlatformTest()


/**
 * Provides platform test dependencies.
 */
@org.koin.core.annotation.Module
expect class TestTargetsProviderModule()

@org.koin.core.annotation.Module(
    includes = [
        TestLoggerModule::class,
        TestTargetsProviderModule::class
    ]
)
class TestSupportModule

/**
 * Generates the test context bindings dynamically.
 */
fun TestScope.bindAsKoinModule(): Module {
    val testDispatcher = this.coroutineContext[ContinuationInterceptor]
        ?: throw IllegalStateException("Error fetching the dispatcher of an established test scope.")

    return module {
        single<CoroutineContext> { testDispatcher }
        single<CoroutineContext>(qualifier<IoContext>()) { testDispatcher + CoroutineName("Test-IO") }
        single<CoroutineContext>(qualifier<MainContext>()) { testDispatcher + CoroutineName("Test-Main") }
        single<CoroutineContext>(qualifier<DefaultContext>()) { testDispatcher + CoroutineName("Test-Default") }

        single<CoroutineScope>(qualifier<AppBackgroundScope>()) { this@bindAsKoinModule }
    }
}

// -----------------------------------------------------------
// EXTENSION FUNCTIONS / SETUP UTILITIES
// -----------------------------------------------------------

suspend fun RoomDatabase.getPhysicalRowCount(tableName: String): Int =
    useReaderConnection { connection ->
        connection.usePrepared("SELECT COUNT(*) FROM $tableName") { statement ->
            if (statement.step()) statement.getLong(0).toInt() else 0
        }
    }

fun Exception.reportAndThrowFailure(): Nothing {

    println("\n === POSSIBLE DI REGISTRY ISSUE OR GENERAL FAILURE === ")
    println("Crash: ${this.message}")

    var currentCause = this.cause
    while (currentCause != null) {
        println("If Missing Component Or Error: ${currentCause.message}")
        currentCause = currentCause.cause
    }

    println(" ====================================================== \n")
    throw this
}

/**
 * For usage in multithreaded testing where the test scope is not in control of coroutines
 * running on a delegated dispatcher. As a TestScope is connected to a virtual clock,
 * this method provides a way to suspend the StandardTestDispatcher while advancing real time,
 * to await a condition resulting from work done on other Dispatchers with their own task queues.
 *
 * Limited parallelism is used to ensure the Default pool is kept for the test workers. Any
 * further coroutines spawned by the await condition will be scheduled within a limited
 * parallelism of 1.
 */
suspend fun awaitCondition(
    timeout: Duration = 8.seconds,
    pollInterval: Duration = 10.milliseconds,
    message: String = "Condition was not met within $timeout",
    condition: suspend () -> Boolean
) {
    withContext(Dispatchers.Default.limitedParallelism(1)) {
        try {
            withTimeout(timeout) {
                while (!condition()) {
                    delay(pollInterval)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AssertionError("$message (timed out after $timeout)", e)
        }
    }
}

fun interface TestTeardownHook {
    fun onTeardown()
}

@PublishedApi
internal inline fun performTestTeardown(
    environment: Any?,
    koin: Koin,
    onCloseKoin: () -> Unit
) {
    try {
        (environment as? AutoCloseable)?.close()
    } catch (e: Exception) {
        println("WARNING: Environment close failed: ${e.message}")
    }

    try {
        koin.getOrNull<TestTeardownHook>()?.onTeardown()
    } catch (e: Exception) {
        println("WARNING: Teardown hook failed: ${e.message}")
    }

    onCloseKoin()
}