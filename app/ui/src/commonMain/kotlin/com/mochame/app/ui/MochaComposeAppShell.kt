package com.mochame.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.mochame.app.ui.navigation.AppNavSuiteItem
import com.mochame.app.ui.navigation.Destination
import com.mochame.app.ui.screens.BootLockoutScreen
import com.mochame.app.ui.screens.BootSplashScreen
import com.mochame.app.ui.screens.DashboardScreen
import com.mochame.bio.ui.DailyContextRoute
import com.mochame.core.design.theme.AppTheme
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.utils.interfaces.MochaTimeUtils
import org.koin.compose.koinInject

@Composable
fun MochaComposeAppShell(
    modifier: Modifier = Modifier,
    bootStatusProvider: BootStatusProvider = koinInject(),
    darkTheme: Boolean = isSystemInDarkTheme(),
    navController: NavHostController = rememberNavController()
) {
    val bootState by bootStatusProvider.bootState.collectAsStateWithLifecycle()
    val timeProvider: MochaTimeUtils = koinInject()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    AppTheme(darkTheme = darkTheme) {
        when (val state = bootState) {
            is BootState.Ready -> {
                NavigationSuiteScaffold(
                    navigationSuiteItems = {
                        AppNavSuiteItem.entries.forEach { item ->
                            item(
                                selected = item.isSelected(currentDestination),
                                onClick = {
                                    navController.navigate(item.destination) {
                                        popUpTo(navController.graph.findStartDestination().id)
                                        launchSingleTop = true
                                    }
                                },
                                icon = { item.RenderIcon(item.isSelected(currentDestination)) },
                                label = { Text(item.label) },
                                modifier = modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    },
                    modifier = modifier.fillMaxSize()
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = Destination.Dashboard,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        composable<Destination.Dashboard> {
                            DashboardScreen(
                                onNavigateToBio = { targetDay ->
                                    // Delay until bootstate is ready? So need loading screen before dashboard
                                    navController.navigate(Destination.DailyContext(targetDay))
                                },
                                timeProvider = timeProvider
                            )
                        }

                        composable<Destination.DailyContext> { backStackEntry ->
                            val route = backStackEntry.toRoute<Destination.DailyContext>()
                            val resolvedDay = route.epochDay ?: timeProvider.getMochaDay()

                            DailyContextRoute(epochDay = resolvedDay, timeUtils = timeProvider)
                        }
                    }
                }
            }

            is BootState.Idle, BootState.Init -> {
                BootSplashScreen(modifier = modifier)
            }

            is BootState.Failure -> {
                BootLockoutScreen(modifier = modifier, message = state.message)
            }
        }

    }
}