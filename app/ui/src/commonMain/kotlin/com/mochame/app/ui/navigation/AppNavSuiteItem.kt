package com.mochame.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
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
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val destination: Destination
) {
    abstract fun isSelected(currentDestination: NavDestination?): Boolean

    @Composable
    fun RenderIcon(isSelected: Boolean) {
        Icon(
            imageVector = if (isSelected) selectedIcon else unselectedIcon,
            contentDescription = label
        )
    }

    data object Dashboard : AppNavSuiteItem(
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home,
        destination = Destination.Dashboard
    ) {
        override fun isSelected(currentDestination: NavDestination?): Boolean =
            currentDestination?.hasRoute<Destination.Dashboard>() == true
    }

    data object Bio : AppNavSuiteItem(
        label = "Daily Context",
        selectedIcon = Icons.Filled.DateRange,
        unselectedIcon = Icons.Outlined.DateRange,
        destination = Destination.DailyContext()
    ) {
        override fun isSelected(currentDestination: NavDestination?): Boolean =
            currentDestination?.hasRoute<Destination.DailyContext>() == true
    }

    companion object {
        val entries: List<AppNavSuiteItem> = listOf(Dashboard, Bio)
    }
}