package com.zbproxy.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zbproxy.android.proxy.ProxyServer
import com.zbproxy.android.proxy.ProxyStatus
import com.zbproxy.android.service.ProxyForegroundService
import com.zbproxy.android.ui.navigation.Screen
import com.zbproxy.android.ui.navigation.bottomNavItems
import com.zbproxy.android.ui.screens.*
import com.zbproxy.android.ui.theme.ZBProxyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val proxyServer = ProxyServer.getInstance(App.instance.configManager, App.instance.logCollector)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            ZBProxyTheme {
                ZBProxyMainScreen(proxyServer)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT call proxyServer.destroy() here — the singleton is shared
        // with the foreground service; destroying it would kill a running proxy.
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ZBProxyMainScreen(proxyServer: ProxyServer) {
        val navController = rememberNavController()
        val scope = rememberCoroutineScope()
        val config by App.instance.configManager.config.collectAsStateWithLifecycle()
        var proxyStatus by remember { mutableStateOf(ProxyStatus()) }
        val snackbarHostState = remember { SnackbarHostState() }

        // Refresh proxy status periodically
        LaunchedEffect(proxyServer) {
            while (true) {
                proxyStatus = proxyServer.status
                kotlinx.coroutines.delay(1000)
            }
        }

        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentDestination = navBackStackEntry?.destination

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    tonalElevation = 3.dp
                ) {
                    bottomNavItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any {
                            it.route == item.screen.route
                        } == true

                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = stringResource(item.screen.labelRes)
                                )
                            },
                            label = { Text(stringResource(item.screen.labelRes)) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        proxyStatus = proxyStatus,
                        onStartProxy = {
                            startProxyService()
                            scope.launch {
                                snackbarHostState.showSnackbar(getString(R.string.home_starting))
                            }
                        },
                        onStopProxy = {
                            stopProxyService()
                            scope.launch {
                                snackbarHostState.showSnackbar(getString(R.string.home_stopping))
                            }
                        }
                    )
                }
                composable(Screen.Services.route) {
                    ServicesScreen(
                        config = config,
                        onUpdateService = { service ->
                            scope.launch {
                                App.instance.configManager.updateService(service)
                            }
                        },
                        onDeleteService = { name ->
                            scope.launch {
                                App.instance.configManager.deleteService(name)
                            }
                        },
                        onUpdateOutbound = { outbound ->
                            scope.launch {
                                App.instance.configManager.updateOutbound(outbound)
                            }
                        },
                        onDeleteOutbound = { name ->
                            scope.launch {
                                App.instance.configManager.deleteOutbound(name)
                            }
                        }
                    )
                }
                composable(Screen.Logs.route) {
                    LogsScreen(logCollector = App.instance.logCollector)
                }
                composable(Screen.About.route) {
                    AboutScreen()
                }
            }
        }
    }

    private fun startProxyService() {
        val intent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopProxyService() {
        val intent = Intent(this, ProxyForegroundService::class.java).apply {
            action = ProxyForegroundService.ACTION_STOP
        }
        startService(intent)
    }
}