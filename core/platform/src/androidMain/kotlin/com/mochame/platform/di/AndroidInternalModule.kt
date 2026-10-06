package com.mochame.platform.di

import android.content.Context
import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.logger.LogTags
import com.mochame.platform.providers.AndroidBufferProvider
import com.mochame.platform.providers.AppPathsProvider
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.sync.spi.BufferProvider
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf

actual class PlatformContext(val androidContext: Context)

@Module
actual class InternalPlatformModule : KoinComponent {

    @Single
    fun provideContext(androidContext: Context): PlatformContext = PlatformContext(androidContext)

    @Single
    fun provideFileSystem(): FileSystem = SystemFileSystem

    @Single
    fun provideAppPaths(context: Context): AppPathsProvider {
        val baseDir = context.filesDir.absolutePath
        return object : AppPathsProvider {
            override val blobPending = "$baseDir/blobs/pending"
            override val blobCommitted = "$baseDir/blobs/committed"
            override val databasePath = "$baseDir/mocha_me.db"
        }
    }

    @Single(binds = [DatabaseLocation::class])
    fun provideDatabasePath(
        path: AppPathsProvider
    ): DatabaseLocation = DatabaseLocation.OnDisk(path.databasePath)

    @Single
    @PendingDir
    fun providePendingPath(paths: AppPathsProvider): Path = Path(paths.blobPending)

    @Single
    @CommittedDir
    fun provideCommittedPath(paths: AppPathsProvider): Path = Path(paths.blobCommitted)

    @Single
    fun provideBufferProvider(): BufferProvider {
        return AndroidBufferProvider(
            logger = get { parametersOf(LogTags.Domain.SYNC, LogTags.Layer.INFRA) }
        )
    }
}