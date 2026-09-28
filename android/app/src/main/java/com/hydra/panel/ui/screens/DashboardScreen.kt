package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.Server
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format
import kotlinx.coroutines.launch

data class DashData(
    val servers: List<Server>,
    val totalConnections: Int,
    val activeKeys: Int,
    val alertsCount: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpenServer: (String) -> Unit, onRefreshSignal: Int = 0) {
    val repo = PanelRepository.get(LocalContext.current)
    var data by remember { mutableStateOf<DashData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var refresh by remember { mutableStateOf(0) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)
    val scope = rememberCoroutineScope()

    LaunchedEffect(refresh, onRefreshSignal) {
        loading = true; error = null
        runCatching {
            val servers = repo.listServers()
            // метрики последнего замера по каждому серверу — через metrics (последняя точка)
            var conns = 0
            for (s in servers.take(20)) {
                try {
                    val m = repo.serverMetrics(s.id, limit = 1)
                    conns += m.firstOrNull()?.totalConnections ?: 0
                } catch (_: Exception) {}
            }
            val keys = try { repo.listKeys().count { it.revokedAt == null && (Format.parseIso(it.expiresAt) ?: Long.MAX_VALUE) >= System.currentTimeMillis() } } catch (_: Exception) { 0 }
            val alerts = try { repo.activeAlerts().size } catch (_: Exception) { 0 }
            DashData(servers, conns, keys, alerts)
        }.onSuccess { data = it; loading = false }
         .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Дашборд") },
                actions = {
                    IconButton(onClick = { refresh++ }) { Text("⟳", fontSize = MaterialTheme.typography.titleLarge.fontSize) }
                },
            )
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, isError = true, onDismiss = { error = null })
            if (loading) LoadingBlock("Сводка панели…")
            val d = data
            if (d != null) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { StatCard("Серверы", d.servers.size.toString()) }
                            Box(Modifier.weight(1f)) { StatCard("Клиенты (conn.)", d.totalConnections.toString()) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { StatCard("Активные ключи", d.activeKeys.toString()) }
                            Box(Modifier.weight(1f)) { StatCard("Алерты", d.alertsCount.toString(), color = if (d.alertsCount > 0) statusColor("critical") else statusColor("active")) }
                        }
                    }
                    item { SectionTitle("Серверы") }
                    items(d.servers) { s ->
                        ClickableCard(onClick = { onOpenServer(s.id) }, modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(s.status)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.id, style = MaterialTheme.typography.titleSmall)
                                    Text("${s.ip} · ${s.location ?: "—"}${if (s.city != null) ", ${s.city}" else ""}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(if (s.agentInstalled == true) "агент ✓" else "без агента", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    item {
                        ActionButton("Бэкап БД сейчас", modifier = Modifier.fillMaxWidth()) {
                            runner.run { repo.backupNow() }
                        }
                    }
                }
            }
        }
    }
}
