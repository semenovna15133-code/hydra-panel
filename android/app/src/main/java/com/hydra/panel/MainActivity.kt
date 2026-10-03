package com.hydra.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.screens.*
import com.hydra.panel.ui.theme.HydraTheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

sealed class TopDest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : TopDest("dashboard", "Дашборд", Icons.Filled.Dashboard)
    data object Servers : TopDest("servers", "Серверы", Icons.Filled.Dns)
    data object Clients : TopDest("clients", "Клиенты", Icons.Filled.Groups)
    data object Keys : TopDest("keys", "Ключи", Icons.Filled.VpnKey)
    data object More : TopDest("more", "Ещё", Icons.Filled.MoreHoriz)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.hydra.panel.alerts.AlertNotifier.ensureChannels(this)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
        runCatching {
            val r = com.hydra.panel.data.repo.PanelRepository.get(applicationContext)
            r.bindContext(applicationContext)
            if (r.sessionStore.isLoggedIn && r.sessionStore.pushEnabled) {
                com.hydra.panel.alerts.AlertPollWorker.schedule(applicationContext, r.sessionStore.pollIntervalMin)
            }
        }
        setContent {
            HydraTheme {
                AppRoot()
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val context = LocalContext.current
    // Инициализация репозитория не должна ронять приложение: при любой ошибке
    // показываем экран входа (он сам всё пересоздаст после ввода URL).
    val repo = remember { runCatching { PanelRepository.get(context) }.getOrNull() }
    var loggedIn by remember { mutableStateOf(repo?.sessionStore?.isLoggedIn == true) }
    var bootTick by remember { mutableStateOf(0) }

    // глобальный тост + бейдж версии
    LaunchedEffect(Unit) {
        com.hydra.panel.ui.components.Toaster.toasterScope = this
        com.hydra.panel.ui.components.Toaster.versionBadge =
            "v${com.hydra.panel.BuildConfig.VERSION_NAME} (${com.hydra.panel.BuildConfig.VERSION_CODE})"
    }

    Box(Modifier.fillMaxSize()) {
        if (!loggedIn) {
            LoginScreen(onLoggedIn = {
                bootTick++
                loggedIn = runCatching { PanelRepository.get(context).sessionStore.isLoggedIn }.getOrDefault(true)
            })
        } else {
            MainNav(key = bootTick, onLoggedOut = {
                kotlinx.coroutines.MainScope().launch {
                    runCatching { PanelRepository.get(context).logout() }
                }
                PanelRepository.reset()
                loggedIn = false
            })
        }
        SnackbarHost(
            hostState = com.hydra.panel.ui.components.Toaster.host,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
            snackbar = { data ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(data.visuals.message, Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium)
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNav(key: Int = 0, onLoggedOut: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = listOf(TopDest.Dashboard, TopDest.Servers, TopDest.Clients, TopDest.Keys, TopDest.More)
    val showBar = topLevel.any { it.route == currentRoute }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 0.dp,
            ) {
                topLevel.forEach { d ->
                    val selected = currentRoute == d.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(TopDest.Dashboard.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Box(contentAlignment = Alignment.Center) {
                                if (selected) {
                                    Box(Modifier.size(44.dp).clip(CircleShape)
                                        .background(Brush.radialGradient(listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), Color.Transparent,
                                        ))))
                                }
                                Icon(d.icon, contentDescription = d.label)
                            }
                        },
                        label = { Text(d.label, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopDest.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(TopDest.Dashboard.route) {
                DashboardScreen(onOpenServer = { id -> nav.navigate("server/$id") })
            }
            composable(TopDest.Servers.route) {
                ServersScreen(onOpenServer = { id -> nav.navigate("server/$id") })
            }
            composable(TopDest.Clients.route) {
                ClientsScreen(onOpenClient = { id -> nav.navigate("client/$id") })
            }
            composable(TopDest.Keys.route) { KeysScreen() }
            composable(TopDest.More.route) { MoreScreen(nav, onLoggedOut) }

            composable("server/{id}") { entry ->
                val id = entry.arguments?.getString("id").orEmpty()
                ServerDetailScreen(
                    serverId = id,
                    onBack = { nav.popBackStack() },
                    onOpenLogs = { nav.navigate("logs/$it") },
                    onOpenConfigs = { nav.navigate("configs/$it") },
                )
            }
            composable("logs/{id}") { entry ->
                LogsScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("configs/{id}") { entry ->
                ConfigsScreen(serverId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("client/{id}") { entry ->
                ClientDetailScreen(
                    clientId = entry.arguments?.getString("id")?.toLongOrNull() ?: 0L,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("devices") { DevicesScreen() }
            composable("reports") { ReportsScreen() }
            composable("database") { DatabaseScreen() }
            composable("settings") { SettingsScreen(onLoggedOut = onLoggedOut) }
        }
    }
}

/** Вкладка «Ещё»: остальные разделы панели. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreScreen(nav: NavHostController, onLoggedOut: () -> Unit) {
    val items = listOf(
        "Устройства" to "devices",
        "Отчёты и прогноз" to "reports",
        "SQL-консоль" to "database",
        "Настройки и бекапы" to "settings",
    )
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Ещё", style = MaterialTheme.typography.titleLarge) },
            actions = {
                com.hydra.panel.ui.components.ChipBadge(
                    com.hydra.panel.ui.components.Toaster.versionBadge.ifBlank {
                        "v" + com.hydra.panel.BuildConfig.VERSION_NAME
                    },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )
            },
        )
    }) { p ->
        Column(Modifier.padding(p).fillMaxSize().padding(horizontal = 12.dp)) {
            com.hydra.panel.ui.components.NeonHeader("Разделы панели", "Отчёты, устройства, консоль и настройки")
            Spacer(Modifier.height(14.dp))
            items.forEach { (label, route) ->
                com.hydra.panel.ui.components.GlassCard(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    onClick = { nav.navigate(route) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    ListItem(
                        headlineContent = { Text(label, style = MaterialTheme.typography.titleMedium) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
            com.hydra.panel.ui.components.GlassCard(
                modifier = Modifier.fillMaxWidth(),
                glow = MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                onClick = onLoggedOut,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            ) {
                ListItem(
                    headlineContent = { Text("Выйти", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium) },
                    leadingContent = { Icon(Icons.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))
