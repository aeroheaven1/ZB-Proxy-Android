package com.zbproxy.android

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zbproxy.android.proxy.ProxyServer
import com.zbproxy.android.proxy.ProxyStatus
import com.zbproxy.android.service.ProxyForegroundService
import com.zbproxy.android.ui.components.PrivacyDialog
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

    // First-launch privacy consent state
    private fun isPrivacyHandled(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return prefs.getBoolean(PREFS_PRIVACY_HANDLED, false)
    }

    private fun markPrivacyHandled() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREFS_PRIVACY_HANDLED, true)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "zbproxy_settings"
        private const val PREFS_PRIVACY_HANDLED = "privacy_handled"
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do NOT call proxyServer.destroy() here — the singleton is shared
        // with the foreground service; destroying it would kill a running proxy.
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun ZBProxyMainScreen(proxyServer: ProxyServer) {
        val scope = rememberCoroutineScope()
        val config by App.instance.configManager.config.collectAsStateWithLifecycle()
        var proxyStatus by remember { mutableStateOf(ProxyStatus()) }
        val snackbarHostState = remember { SnackbarHostState() }
        var showPrivacyDialog by remember { mutableStateOf(!isPrivacyHandled()) }
        var showDeclineMessage by remember { mutableStateOf(false) }

        // Horizontal pager for swipeable pages (Home / Services / Logs / About)
        val pagerState = rememberPagerState(initialPage = 0) { bottomNavItems.size }

        // Refresh proxy status periodically
        LaunchedEffect(proxyServer) {
            while (true) {
                proxyStatus = proxyServer.status
                kotlinx.coroutines.delay(1000)
            }
        }

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                NavigationBar(
                    tonalElevation = 3.dp
                ) {
                    bottomNavItems.forEachIndexed { index, item ->
                        val selected = pagerState.currentPage == index

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
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            }
                        )
                    }
                }
            }
        ) { paddingValues ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.padding(paddingValues),
                beyondViewportPageCount = 1
            ) { page ->
                when (bottomNavItems[page].screen) {
                    Screen.Home -> HomeScreen(
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
                    Screen.Services -> ServicesScreen(
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
                    Screen.Logs -> LogsScreen(logCollector = App.instance.logCollector)
                    Screen.About -> AboutScreen(
                        onShowPrivacy = { showPrivacyDialog = true }
                    )
                }
            }
        }

        // First-launch Privacy + AI notice dialog
        if (showPrivacyDialog) {
            PrivacyDialog(
                onAgree = {
                    markPrivacyHandled()
                    showPrivacyDialog = false
                },
                onDecline = {
                    markPrivacyHandled()
                    showPrivacyDialog = false
                    showDeclineMessage = true
                    scope.launch {
                        snackbarHostState.showSnackbar(getString(R.string.privacy_declined))
                    }
                }
            )
        }

        // Decline hint (shown once via snackbar when user declines)
        if (showDeclineMessage) {
            LaunchedEffect(Unit) {
                snackbarHostState.showSnackbar(getString(R.string.privacy_decline_hint))
                showDeclineMessage = false
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