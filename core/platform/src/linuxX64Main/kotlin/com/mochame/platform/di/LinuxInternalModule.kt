package com.mochame.platform.di

import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.logger.LogTags
import com.mochame.platform.providers.AppPathsProvider
import com.mochame.platform.providers.DatabaseLocation
import com.mochame.platform.providers.LinuxBufferProvider
import com.mochame.sync.spi.BufferProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import platform.posix.EEXIST
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.errno
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.stat
import platform.posix.strerror

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
        val baseDir = getLinuxDataDirectory("mochame")

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

@OptIn(ExperimentalForeignApi::class)
fun getLinuxDataDirectory(appName: String = "mochame"): String {
    // Enforce XDG absolute path requirement
    val xdgDataHome = getenv("XDG_DATA_HOME")
        ?.toKString()
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.startsWith("/") }

    val baseDir = if (xdgDataHome != null) {
        "${xdgDataHome.trimEnd('/')}/$appName"
    } else {
        val home = getenv("HOME")
            ?.toKString()
            ?.trim()
            ?.takeIf { it.isNotBlank() && it.startsWith("/") }
            ?: error("Neither XDG_DATA_HOME nor HOME is set to a valid absolute path.")

        "${home.trimEnd('/')}/.local/share/$appName"
    }

    createDirectories(baseDir)
    verifyIsDirectory(baseDir)

    return baseDir
}

@OptIn(ExperimentalForeignApi::class)
fun createDirectories(path: String) {
    val segments = path.split("/").filter { it.isNotEmpty() }
    var current = if (path.startsWith("/")) "" else "."

    for (segment in segments) {
        current += "/$segment"

        // 0b111_101_101u equals octal 0755 (rwxr-xr-x)
        val result = mkdir(current, 0b111_101_101u)

        // Non-zero return indicates error; EEXIST is fine only if it's already a dir
        if (result != 0 && errno != EEXIST) {
            val errStr = strerror(errno)?.toKString() ?: "errno $errno"
            error("Failed to create directory at '$current': $errStr")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun verifyIsDirectory(path: String) {
    memScoped {
        val statBuf = alloc<stat>()
        if (stat(path, statBuf.ptr) != 0) {
            val errStr = strerror(errno)?.toKString() ?: "errno $errno"
            error("Data directory '$path' could not be accessed: $errStr")
        }

        val isDirectory = (statBuf.st_mode.toInt() and S_IFMT) == S_IFDIR
        check(isDirectory) { "Path '$path' exists but is a file, not a directory." }
    }
}