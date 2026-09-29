package com.mochame.platform.fixtures

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.files.Path
import kotlin.time.Clock

data class TestWorkspace(
    val root: Path,
    val pending: Path,
    val committed: Path
)

expect fun testWorkspaceBaseDir(): String

private val workspaceLock = reentrantLock()
private var workspaceCounter = 0

fun createTestWorkspace(): TestWorkspace {
    val currentCount = workspaceLock.withLock { workspaceCounter++ }
    val baseDir = Path(testWorkspaceBaseDir())

    val timestamp = Clock.System.now()
        .toLocalDateTime(TimeZone.currentSystemDefault())
        .let { "${it.date}_${it.hour}-${it.minute}" }
    val uniqueDir = Path(baseDir, "mocha_test_${timestamp}_${currentCount}")

    return TestWorkspace(
        root = uniqueDir,
        pending = Path(uniqueDir, "pending"),
        committed = Path(uniqueDir, "committed")
    )
}