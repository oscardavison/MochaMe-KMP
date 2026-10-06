package com.mochame.platform.providers

import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.di.PlatformProviderModule
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.spi.BufferProvider
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.io.writeString
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame


private inline fun runEnv(crossinline block: suspend BufferProvider.(TestScope) -> Unit) =
    runUnitEnvironment<BufferProvider>(
        koinSetup = { modules(PlatformProviderModule::class, TestLoggerModule::class) },
        block = block
    )


class BufferProviderTest : MochaPlatformTest() {

    @Test
    fun should_returnIdenticalInstance_when_calledOnSameThread() = runEnv {
        val buffer1 = get()
        val buffer2 = get()

        assertSame(
            expected = buffer1,
            actual = buffer2,
            message = "BufferProvider must reuse the exact same Buffer instance on the same thread."
        )
    }

    private val threadBFinished = atomic(false)

    @Test
    fun should_returnDistinctInstances_when_calledFromDifferentThreads() = runEnv { scope ->
        val threadAClaimed = CompletableDeferred<Unit>()
        threadBFinished.value = false

        val deferredA = scope.async(Dispatchers.Default) {
            val buffer = get()
            threadAClaimed.complete(Unit)

            while (!threadBFinished.value) {
             // Busy-wait prevents thread release
            }
            buffer
        }

        val deferredB = scope.async(Dispatchers.Default) {
            threadAClaimed.await()

            val buffer = get()
            threadBFinished.value = true
            buffer
        }

        val bufferA = deferredA.await()
        val bufferB = deferredB.await()

        assertNotSame(
            illegal = bufferA,
            actual = bufferB,
            message = "Distinct worker threads must receive isolated Buffer instances."
        )
    }

    @Test
    fun should_clearResidualData_when_retrievingExistingThreadBuffer() = runEnv {
        val buffer = get()
        buffer.writeString("residual data that was not consumed")

        assertEquals(35L, buffer.size)

        // Next retrieval on same thread must wipe previous dirty state
        val reacquiredBuffer = get()

        assertEquals(
            expected = 0L,
            actual = reacquiredBuffer.size,
            message = "BufferProvider must clear dirty bytes prior to yielding the buffer."
        )
    }
}