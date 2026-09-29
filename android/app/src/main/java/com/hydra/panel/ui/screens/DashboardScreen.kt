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
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item { NeonHeader("Обзор инфраструктуры", "${d.servers.size} серверов · обновление вручную ⟳") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { HudStat("Серверы", d.servers.size.toString(), accent = MaterialTheme.colorScheme.primary) }
                            Box(Modifier.weight(1f)) { HudStat("Онлайн", d.servers.count { it.status?.lowercase() in setOf("active","ok","up","running","healthy") }.toString(), accent = com.hydra.panel.ui.theme.StatusGreen) }
                        }
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) { HudStat("Активные ключи", d.activeKeys.toString(), accent = MaterialTheme.colorScheme.secondary) }
                            Box(Modifier.weight(1f)) { HudStat("Подключения", d.totalConnections.toString(), accent = MaterialTheme.colorScheme.tertiary) }
                        }
                    }
                    item { HudStat("Алерты", d.alertsCount.toString(), sub = if (d.alertsCount > 0) "требуют внимания" else "всё спокойно", accent = if (d.alertsCount > 0) com.hydra.panel.ui.theme.StatusRed else com.hydra.panel.ui.theme.StatusGreen) }
                    item { NeonDivider() }
                    item { SectionTitle("Серверы") }
                    items(d.servers) { s ->
                        GlassCard(onClick = { onOpenServer(s.id) }, glow = statusTint(s.status, 0.45f), modifier = Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GlowStatusDot(s.status)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(s.id, style = MaterialTheme.typography.titleMedium)
                                    Text("${s.ip} · ${s.location ?: "—"}${if (s.city != null) ", ${s.city}" else ""}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ChipBadge(if (s.agentInstalled == true) "AGENT" else "NO AGENT",
                                    color = if (s.agentInstalled == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
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
