package com.mochame.platform.di

import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.logger.LogTags
import com.mochame.platform.providers.AppPathsProvider
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.platform.providers.LinuxBufferProvider
import com.mochame.sync.spi.infrastructure.BufferProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import platform.posix.getenv

actual class PlatformContext

// REVIEW THIS
@Module
actual class InternalPlatformModule : KoinComponent {

    @Single
    fun providePlatformContext(): PlatformContext = PlatformContext()

    @Single
    fun provideFileSystem(): FileSystem = SystemFileSystem

    @OptIn(ExperimentalForeignApi::class)
    @Single
    fun provideAppPaths(): AppPathsProvider {
        // Resolve HOME directory using POSIX
        val home = getenv("HOME")?.toKString() ?: "."
        val baseDir = "$home/.mochame"

        return object : AppPathsProvider {
            override val blobPending = "$baseDir/blobs/pending"
            override val blobCommitted = "$baseDir/blobs/committed"
            override val databasePath = "$baseDir/native_mochame.db"
        }
    }

    @Single(binds = [DatabaseLocation::class])
    fun provideDatabasePath(
        path: AppPathsProvider
    ): DatabaseLocation = DatabaseLocation.OnDisk(path.databasePath)

    @Single
    @PendingDir
    fun providePendingPath(paths: AppPathsProvider): Path =
        Path(paths.blobPending)

    @Single
    @CommittedDir
    fun provideCommittedPath(paths: AppPathsProvider): Path =
        Path(paths.blobCommitted)

    @Single
    fun provideBufferProvider(): BufferProvider {
        return LinuxBufferProvider(
            logger = get { parametersOf(LogTags.Domain.SYNC, LogTags.Layer.INFRA) }
        )
    }
}