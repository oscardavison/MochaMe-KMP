package com.mochame.platform.providers

import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.di.PlatformProviderModule
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.spi.DigestFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.io.Buffer
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals

private inline fun runEnv(crossinline block: suspend DigestFactory.(TestScope) -> Unit) =
    runUnitEnvironment(
        koinSetup = { modules(TestLoggerModule::class, PlatformProviderModule::class) },
        block = block
    )

class HasherProviderTest : MochaPlatformTest() {

    @Test
    fun should_produceExactSha256_when_inputIsStandardSingleBlock() = runEnv {
        val digestState = this()
        val buffer = Buffer().apply { write("abc".encodeToByteArray()) }

        digestState.update(buffer)
        val result = digestState.digest()

        assertEquals(
            expected = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            actual = result.toHexString(),
        )
        assertEquals(expected = 32, actual = result.size)
    }

    @Test
    fun should_produceExactSha256_when_inputIsEmpty() = runEnv {
        val digestState = this()
        val emptyBuffer = Buffer()

        digestState.update(emptyBuffer)
        val result = digestState.digest()

        assertEquals(
            expected = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            actual = result.toHexString(),
        )
        assertEquals(expected = 32, actual = result.size)
    }

    @Test
    fun should_produceExactSha256_when_inputSpansMultipleBlocks() = runEnv {
        val digestState = this()
        val buffer = Buffer().apply {
            write("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        }

        digestState.update(buffer)
        val result = digestState.digest()

        assertEquals(
            expected = "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            actual = result.toHexString(),
            message = "Multi-block SHA-256 digest diverged across internal block boundaries."
        )
        assertEquals(expected = 32, actual = result.size)
    }

    @Test
    fun should_produceConsistentDigest_when_payloadExceedsChunkBufferWindow() = runEnv {
        val digestState = this()
        // 16,384 bytes to cross the 8,192-byte temp buffer boundary in LinuxOpenSSLDigest
        val payload = ByteArray(16384) { (it % 128).toByte() }
        val buffer = Buffer().apply { write(payload) }

        digestState.update(buffer)
        val result = digestState.digest()

        assertEquals(
            expected = 32,
            actual = result.size,
            message = "Final digest output size must strictly be 32 bytes (256 bits)."
        )
    }
}