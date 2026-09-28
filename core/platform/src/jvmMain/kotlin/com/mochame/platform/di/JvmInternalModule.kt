package com.mochame.platform.di


import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.logger.LogTags
import com.mochame.platform.providers.AppPathsProvider
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.platform.providers.JvmBufferProvider
import com.mochame.sync.spi.infrastructure.BufferProvider
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

actual class PlatformContext

@Module
actual class InternalPlatformModule : KoinComponent {

    @Single
    fun providePlatformContext(): PlatformContext = PlatformContext()

    @Single
    fun provideFileSystem(): FileSystem = SystemFileSystem

    @Single
    fun provideAppPaths(): AppPathsProvider {
        val userHome = System.getProperty("user.home")
        val baseDir = "$userHome/.mochame"

        return object : AppPathsProvider {
            override val blobPending = "$baseDir/blobs/pending"
            override val blobCommitted = "$baseDir/blobs/committed"
            override val databasePath = "$baseDir/jvm_mochame.db"
        }
    }

    @Single(binds = [DatabaseLocation::class])
    fun provideDatabasePath(path: AppPathsProvider): DatabaseLocation =
        DatabaseLocation.OnDisk(path.databasePath)

    @Single
    @PendingDir
    fun providePendingPath(paths: AppPathsProvider): Path = Path(paths.blobPending)

    @Single
    @CommittedDir
    fun provideCommittedPath(paths: AppPathsProvider): Path = Path(paths.blobCommitted)

    @Single
    fun provideBufferProvider(): BufferProvider {
        return JvmBufferProvider(
            logger = get { parametersOf(LogTags.Domain.SYNC, LogTags.Layer.INFRA) }
        )
    }
}