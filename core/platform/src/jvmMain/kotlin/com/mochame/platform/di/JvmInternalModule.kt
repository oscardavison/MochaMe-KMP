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
import java.util.Locale

actual class PlatformContext

@Module
actual class InternalPlatformModule : KoinComponent {

    @Single
    fun providePlatformContext(): PlatformContext = PlatformContext()

    @Single
    fun provideFileSystem(): FileSystem = SystemFileSystem

    @Single
    fun provideAppPaths(): AppPathsProvider {
        val dataDir = System.getenv("MOCHAME_DATA_DIR")
            ?: getDefaultDataDirectory()

        return object : AppPathsProvider {
            override val blobPending = "$dataDir/blobs/pending"
            override val blobCommitted = "$dataDir/blobs/committed"
            override val databasePath = "$dataDir/jvm_mochame.db"
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


fun getDefaultDataDirectory(): Path {
    val os = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: error("No OS detected.")
    val homePath = System.getProperty("user.home") ?: error("No home directory detected.")

    val home = Path(homePath)

    return when {
        "win" in os -> {
            val appData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            if (appData != null) {
                Path(Path(appData), "MochaMe")
            } else {
                Path(home, "AppData", "Local", "MochaMe")
            }
        }

        "mac" in os -> { // Not verified
            Path(home, "Library", "Application Support", "MochaMe")
        }

        else -> { // Linux, BSD, POSIX (XDG Spec)
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            if (xdg != null) {
                Path(Path(xdg), "mochame")
            } else {
                Path(home, ".local", "share", "mochame")
            }
        }
    }
}