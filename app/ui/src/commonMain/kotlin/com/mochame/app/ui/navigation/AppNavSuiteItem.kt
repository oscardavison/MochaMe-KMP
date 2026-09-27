package com.mochame.app.ui.navigation

import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import kotlinx.serialization.Serializable

sealed interface Destination {
    @Serializable
    data object Dashboard : Destination

    @Serializable
    data class DailyContext(val epochDay: Long? = null) : Destination
}

sealed class AppNavSuiteItem(
    val label: String,
    val iconText: String,
    val destination: Destination
) {
    abstract fun isSelected(currentDestination: NavDestination?): Boolean

    data object Dashboard : AppNavSuiteItem(
        label = "Dashboard",
        iconText = "H",
        destination = Destination.Dashboard
    ) {
        override fun isSelected(currentDestination: NavDestination?): Boolean =
            currentDestination?.hasRoute<Destination.Dashboard>() == true
    }

    data object Bio : AppNavSuiteItem(
        label = "Daily Context",
        iconText = "D",
        destination = Destination.DailyContext()
    ) {
        override fun isSelected(currentDestination: NavDestination?): Boolean =
            currentDestination?.hasRoute<Destination.DailyContext>() == true
    }

    companion object {
        val entries: List<AppNavSuiteItem> = listOf(Dashboard, Bio)
    }
}