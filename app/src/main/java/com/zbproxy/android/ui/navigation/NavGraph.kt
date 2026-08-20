package com.zbproxy.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val label: String) {
    object Home : Screen("home", "Home")
    object Services : Screen("services", "Services")
    object Logs : Screen("logs", "Logs")
    object About : Screen("about", "About")
}

data class BottomNavItem(
    val screen: Screen,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem(Screen.Services, Icons.Filled.Dns, Icons.Outlined.Dns),
    BottomNavItem(Screen.Logs, Icons.Filled.Terminal, Icons.Outlined.Terminal),
    BottomNavItem(Screen.About, Icons.Filled.Info, Icons.Outlined.Info)
)