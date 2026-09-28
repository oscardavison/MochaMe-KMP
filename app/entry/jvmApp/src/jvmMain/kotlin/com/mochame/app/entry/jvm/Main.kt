package com.mochame.app.entry.jvm

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.mochame.app.assembly.di.backgroundScope
import com.mochame.app.ui.MochaComposeAppShell
import com.mochame.app.ui.di.initKoinCompose
import com.mochame.core.design.generated.resources.Res
import com.mochame.core.design.generated.resources.app_logo
import org.koin.core.context.GlobalContext.stopKoin
import org.jetbrains.compose.resources.painterResource
import java.awt.Dimension

fun main() {
    val koinApp = initKoinCompose()

    application {
        val windowState = rememberWindowState(
            width = 1024.dp,
            height = 768.dp
        )

        val isDark = System.getProperty("dark.theme")?.toBooleanStrictOrNull() ?: true

        Window(
            onCloseRequest = {
                try {
                    koinApp.backgroundScope?.close()
                    stopKoin()
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
                    exitApplication()
                }
            },
            icon = painterResource(Res.drawable.app_logo),
            state = windowState,
            title = "MochaMe"
        ) {
            SideEffect {
                window.minimumSize = Dimension(480, 560)
            }

            MochaComposeAppShell(darkTheme = isDark)
        }
    }
}