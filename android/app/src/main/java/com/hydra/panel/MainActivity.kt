package com.hydra.panel

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
    val repo = remember { PanelRepository.get(context) }
    var loggedIn by remember { mutableStateOf(repo.sessionStore.isLoggedIn) }

    if (!loggedIn) {
        LoginScreen(onLoggedIn = { loggedIn = true })
        return
    }
    MainNav(onLoggedOut = {
        PanelRepository.reset()
        loggedIn = false
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNav(onLoggedOut: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val topLevel = listOf(TopDest.Dashboard, TopDest.Servers, TopDest.Clients, TopDest.Keys, TopDest.More)
    val showBar = topLevel.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                topLevel.forEach { d ->
                    NavigationBarItem(
                        selected = currentRoute == d.route,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(TopDest.Dashboard.route) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
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
    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Ещё") }) }) { p ->
        Column(Modifier.padding(p).fillMaxSize()) {
            items.forEach { (label, route) ->
                ListItem(
                    headlineContent = { Text(label) },
                    leadingContent = { Icon(Icons.Filled.ChevronRight, null) },
                    modifier = Modifier.fillMaxWidth().clickableItem { nav.navigate(route) },
                )
                HorizontalDivider()
            }
            ListItem(
                headlineContent = { Text("Выйти", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.fillMaxWidth().clickableItem(onLoggedOut),
            )
        }
    }
}

private fun Modifier.clickableItem(onClick: () -> Unit): Modifier =
    this.then(clickable(onClick = onClick))
