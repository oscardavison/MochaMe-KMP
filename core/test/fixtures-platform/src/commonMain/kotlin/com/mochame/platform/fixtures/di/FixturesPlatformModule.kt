package com.mochame.platform.fixtures.di

import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.fixtures.FakeDigestFactory
import com.mochame.platform.fixtures.FakeTransactionProvider
import com.mochame.platform.fixtures.TestWorkspace
import com.mochame.platform.fixtures.createTestWorkspace
import com.mochame.support.TestTeardownHook
import com.mochame.sync.spi.BufferProvider
import com.mochame.sync.spi.DigestFactory
import com.mochame.sync.spi.TransactionProvider
import kotlinx.io.Buffer
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

/**
 * Provides a fake platform integration module with no SQLite usage, for Unit testing.
 */
@Module([TestLoggerModule::class])
class FixturesPlatformModule {

    @Single(binds = [DigestFactory::class, FakeDigestFactory::class])
    fun provideFakeDigestFactory(): FakeDigestFactory = FakeDigestFactory()

    @Single
    fun provideFileSystem(): FileSystem = SystemFileSystem

    @Single
    fun provideTestWorkspace(): TestWorkspace = createTestWorkspace()

    @Single(binds = [TestTeardownHook::class])
    fun provideWorkspaceCleanupHook(
        fs: FileSystem,
        workspace: TestWorkspace
    ): TestTeardownHook = TestTeardownHook {
        try {
            if (fs.exists(workspace.root)) {
                fs.deleteRecursively( workspace.root)
            }
        } catch (e: Exception) {
            println("WARNING: Workspace cleanup failed for ${workspace.root}: ${e.message}")
        }
    }

    @Factory
    @PendingDir
    fun providePendingDir(workspace: TestWorkspace): Path = workspace.pending

    @Factory
    @CommittedDir
    fun provideCommittedDir(workspace: TestWorkspace): Path = workspace.committed

    @Single(binds = [TransactionProvider::class, FakeTransactionProvider::class])
    fun provideFakeTransactionProvider() : FakeTransactionProvider =
        FakeTransactionProvider()

    @Single(binds = [BufferProvider::class])
    fun provideFakeBufferProvider(): BufferProvider {
        return object : BufferProvider {
            private val sharedBuffer = Buffer()

            override fun get(): Buffer {
                sharedBuffer.clear()
                return sharedBuffer
            }
        }
    }
}

fun FileSystem.deleteRecursively(path: Path) {
    val metadata = this.metadataOrNull(path) ?: return
    if (metadata.isDirectory) {
        this.list(path).forEach { child -> deleteRecursively(child) }
    }
    this.delete(path)
}


