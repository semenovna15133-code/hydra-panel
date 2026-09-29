package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.*
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(
    serverId: String,
    onBack: () -> Unit,
    onOpenLogs: (String) -> Unit,
    onOpenConfigs: (String) -> Unit,
) {
    val repo = PanelRepository.get(LocalContext.current)
    var server by remember { mutableStateOf<Server?>(null) }
    var metrics by remember { mutableStateOf<List<MetricPoint>>(emptyList()) }
    var protocols by remember { mutableStateOf<List<ProtocolInstance>>(emptyList()) }
    var tokens by remember { mutableStateOf<TokenListResponse?>(null) }
    var forecast by remember { mutableStateOf<ForecastResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirmReboot by remember { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh, serverId) {
        loading = true; error = null
        runCatching {
            val s = repo.getServer(serverId)
            val m = try { repo.serverMetrics(serverId, 60).reversed() } catch (_: Exception) { emptyList() }
            val p = try { repo.protocols(serverId) } catch (_: Exception) { emptyList() }
            val t = try { repo.listTokens(serverId) } catch (_: Exception) { null }
            val f = try { repo.forecast(serverId) } catch (_: Exception) { null }
            SenDetails(s, m, p, t, f)
        }.onSuccess { d ->
            server = d.server; metrics = d.metrics; protocols = d.protocols; tokens = d.tokens; forecast = d.forecast
            loading = false
        }.onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(serverId) }, navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
            actions = { IconButton(onClick = { refresh++ }) { Text("⟳") } }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            if (loading) LoadingBlock("Метрики и статус…")
            else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                server?.let { s ->
                    item {
                        VCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(s.status)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${s.ip}:${s.sshPort ?: 22}", style = MaterialTheme.typography.titleSmall)
                                    Text("${s.location ?: "—"} · ${s.city ?: "—"} · канал ${Format.mbps(s.bandwidthMbps?.toDouble())}",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                if (metrics.isNotEmpty()) {
                    item { SectionTitle("CPU за последние ~5 часов") }
                    item {
                        VCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                val last = metrics.last()
                                SparkLine(metrics.map { it.cpuPercent ?: 0.0 }, statusColor(if ((last.cpuPercent ?: 0.0) > 80) "critical" else "active"))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("сейчас CPU ${Format.num(last.cpuPercent)}% · RAM ${Format.num(last.memoryPercent)}%", fontSize = 12.sp)
                                    Text("${last.timestamp?.take(16) ?: ""}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    item { SectionTitle("Сеть и подключения") }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { StatCard("RX", Format.mbps(metrics.last().networkRxMbps)) }
                            Box(Modifier.weight(1f)) { StatCard("TX", Format.mbps(metrics.last().networkTxMbps)) }
                            Box(Modifier.weight(1f)) { StatCard("Conn", metrics.last().totalConnections.toString()) }
                        }
                    }
                }
                if (protocols.isNotEmpty()) {
                    item { SectionTitle("Протоколы") }
                    items(protocols) { pr ->
                        VCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(pr.status)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(pr.protocol.uppercase(), style = MaterialTheme.typography.titleSmall)
                                    Text("порт ${pr.port ?: "—"} · лимит ${pr.maxConnections ?: "—"}", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = { runner.run { repo.restartService(serverId, pr.protocol).also { refresh++ } } }) {
                                    Text("restart")
                                }
                            }
                        }
                    }
                }
                tokens?.let { tk ->
                    item { SectionTitle("Токены агента (активных: ${tk.activeCount})") }
                    items(tk.tokens.take(5)) { t ->
                        VCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                MonoText("prefix: ${t.tokenHashPrefix}…")
                                Text((if (t.active) "активен" else "отозван") + " · до ${t.expiresAt?.take(16) ?: "∞"} · создан ${t.createdAt?.take(16) ?: "—"}",
                                    fontSize = 12.sp, color = statusColor(if (t.active) "active" else "blocked"))
                            }
                        }
                    }
                }
                forecast?.let { f ->
                    item { SectionTitle("ML-прогноз нагрузки") }
                    item {
                        VCard(Modifier.fillMaxWidth(), containerColor = statusTint(f.status, 0.12f)) {
                            Column(Modifier.padding(12.dp)) {
                                Text("Статус: ${f.status ?: "—"}", style = MaterialTheme.typography.titleSmall)
                                f.message?.let { Text(it, fontSize = 13.sp) }
                                if (f.currentCpu != null) Text("CPU сейчас ${Format.num(f.currentCpu)}% · тренд ${Format.num(f.trendCpuPerDay)}%/день · критический уровень через ${f.daysUntilCritical ?: "—"} дн.", fontSize = 12.sp)
                                f.recommendation?.let { Text("→ $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                }
                item { SectionTitle("Действия") }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { ActionButton("Установить протоколы", Modifier.fillMaxWidth()) { runner.run { repo.installProtocols(serverId).also { refresh++ } } } }
                            Box(Modifier.weight(1f)) { ActionButton("Установить агент", Modifier.fillMaxWidth()) { runner.run { repo.installAgent(serverId).also { refresh++ } } } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { ActionButton("Test SSH", Modifier.fillMaxWidth()) { runner.run { repo.testSsh(serverId) } } }
                            Box(Modifier.weight(1f)) { ActionButton("Sync time", Modifier.fillMaxWidth()) { runner.run { repo.syncTime(serverId) } } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { ActionButton("Rotate agent token", Modifier.fillMaxWidth()) { runner.run { repo.rotateAgentToken(serverId).also { refresh++ } } } }
                            Box(Modifier.weight(1f)) { ActionButton("Отзыв токенов", Modifier.fillMaxWidth()) { runner.run { repo.revokeTokens(serverId).also { refresh++ } } } }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { Button(onClick = { onOpenLogs(serverId) }, Modifier.fillMaxWidth()) { Text("Логи") } }
                            Box(Modifier.weight(1f)) { Button(onClick = { onOpenConfigs(serverId) }, Modifier.fillMaxWidth()) { Text("Конфиги") } }
                        }
                        ActionButton("Перезагрузить сервер", danger = true, modifier = Modifier.fillMaxWidth()) { confirmReboot = true }
                    }
                }
            }
        }
    }

    if (confirmReboot) {
        ConfirmDialog("Перезагрузить $serverId?", "Все VPN-сессии на сервере упадут. Перезагрузка асинхронная: результат придёт через 2–3 минуты в логи панели.", "Перезагрузить",
            onConfirm = { runner.run { repo.reboot(serverId) } }, onDismiss = { confirmReboot = false })
    }
}

private data class SenDetails(
    val server: Server,
    val metrics: List<MetricPoint>,
    val protocols: List<ProtocolInstance>,
    val tokens: TokenListResponse?,
    val forecast: ForecastResult?,
)
