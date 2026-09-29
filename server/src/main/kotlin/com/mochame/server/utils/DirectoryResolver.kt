package com.mochame.server.utils

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

fun resolveBackupDirectory(subDirectory: String = "backups"): Path {
    val envBackup = System.getenv("SQLITE_DB_BACKUP_DIR")?.takeIf { it.isNotBlank() }
    if (envBackup != null) {
        val target = Path.of(envBackup)
        Files.createDirectories(target)
        return target
    }

    val os = System.getProperty("os.name")?.lowercase(Locale.ROOT).orEmpty()
    val home = System.getProperty("user.home")?.takeIf { it.isNotBlank() }
        ?: error("No home directory detected.")

    val appDataBase = when {
        "win" in os -> {
            val appData = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            if (appData != null) {
                Path.of(appData, "MochaMe")
            } else {
                Path.of(home, "AppData", "Local", "MochaMe")
            }
        }

        "mac" in os -> {
            Path.of(home, "Library", "Application Support", "MochaMe")
        }

        else -> { // Linux, BSD, POSIX (XDG Spec)
            val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            if (xdg != null) {
                Path.of(xdg, "mochame")
            } else {
                Path.of(home, ".local", "share", "mochame")
            }
        }
    }

    val targetDir = appDataBase.resolve(subDirectory)
    Files.createDirectories(targetDir)
    return targetDir
}