@file:OptIn(ExperimentalForeignApi::class)

package com.mochame.app.entry.linux

import com.mochame.app.assembly.di.backgroundScope
import com.mochame.utils.ui.CliScreenNavigator
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.coroutines.runBlocking
import org.koin.core.context.stopKoin
import platform.posix.fflush
import platform.posix.fprintf
import platform.posix.fputs
import platform.posix.stderr
import kotlin.coroutines.cancellation.CancellationException

fun main() {
    val koinApp = initKoinCli()

    try {
        runBlocking {
            val rootMenu = koinApp.koin.get<MainMenuCliScreen>()
            val navigator = CliScreenNavigator(rootScreen = rootMenu)
            navigator.start()
        }
    } catch (_: CancellationException) {

    } catch (t: Throwable) {
        try {
            t.printRunTimeException()
        } catch (_: Throwable) {
            fputs("Application runtime error: Out of memory or critical fault.\n", stderr)
            fflush(stderr)
        }
    } finally {
        try {
            koinApp.backgroundScope?.close()
            stopKoin()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
    }
}


private fun Throwable.printRunTimeException() {
    val fullError = buildString {
        appendLine("=== RUNTIME CRASH ===")
        appendLine(stackTraceToString())
        var cause = cause
        while (cause != null) {
            appendLine("Caused by: ${cause::class.simpleName}: ${cause.message}")
            cause = cause.cause
        }
    }
    fprintf(stderr, "%s", fullError.cstr)
    fflush(stderr)
}