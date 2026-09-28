package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.Alert
import com.hydra.panel.data.model.ForecastResult
import com.hydra.panel.data.model.Server
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(onOpenServer: (String) -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    var servers by remember { mutableStateOf<List<Server>>(emptyList()) }
    var alerts by remember { mutableStateOf<List<Alert>>(emptyList()) }
    var forecasts by remember { mutableStateOf<Map<String, ForecastResult>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh) {
        loading = true; error = null
        runCatching {
            Triple(repo.listServers(), try { repo.activeAlerts() } catch (_: Exception) { emptyList() },
                try { repo.allForecasts().associateBy { it.serverId ?: "" } } catch (_: Exception) { emptyMap() })
        }.onSuccess { (s, a, f) -> servers = s; alerts = a; forecasts = f; loading = false }
         .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Серверы") }, actions = {
            IconButton(onClick = { refresh++ }) { Text("⟳") }
        }) },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            if (loading) LoadingBlock()
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (alerts.isNotEmpty()) {
                    item { SectionTitle("Активные алерты (${alerts.size})") }
                    items(alerts.take(6)) { al ->
                        VCard(Modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.errorContainer) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${al.serverId}: ${al.level}", style = MaterialTheme.typography.titleSmall)
                                Text(al.message ?: "", fontSize = 12.sp)
                            }
                        }
                    }
                }
                item { SectionTitle("Список серверов") }
                if (servers.isEmpty()) {
                    item { Text("Серверов пока нет — добавьте первый кнопкой ниже.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp)) }
                }
                items(servers) { s ->
                        ClickableCard(onClick = { onOpenServer(s.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(s.status)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.id, style = MaterialTheme.typography.titleSmall)
                                        Text("${s.ip}:${s.sshPort ?: 22} · ${s.location ?: "—"}", fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = { pendingDelete = s.id }) { Text("Удалить") }
                                }
                                forecasts[s.id]?.let { f ->
                                    Spacer(Modifier.height(6.dp))
                                    Text(
                                        when (f.status) {
                                            "ok" -> "Прогноз: запас по CPU, тренд ${Format.num(f.trendCpuPerDay)}%/день"
                                            "critical" -> "⚠ Прогноз: критическая нагрузка через ~${f.daysUntilCritical} дн."
                                            else -> "Прогноз: ${f.message ?: f.status ?: "—"}"
                                        },
                                        fontSize = 12.sp,
                                        color = statusColor(if (f.status == "critical") "critical" else if (f.status == "warning") "warning" else "active"),
                                    )
                                }
                            }
                        }
                }
            }
        }
    }

    if (showAdd) AddServerDialog(onDismiss = { showAdd = false }, onSubmit = { id, ip, loc, city, bw, port, pass ->
        showAdd = false
        runner.run { repo.createServer(id, ip, loc, city, bw, port, pass).also { refresh++ } }
    })

    pendingDelete?.let { id ->
        ConfirmDialog("Удалить сервер $id?", "Каскадно удалятся протоколы, метрики, алерты, токены агента и привязки ключей. Действие необратимо.", "Удалить",
            onConfirm = { runner.run { repo.deleteServer(id).also { refresh++ } } },
            onDismiss = { pendingDelete = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddServerDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, String, Int, Int, String) -> Unit) {
    var id by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var loc by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var bw by remember { mutableStateOf("1000") }
    var port by remember { mutableStateOf("22") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить сервер") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = id, onValueChange = { id = it }, label = { Text("ID сервера (напр. de-frankfurt-1)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = ip, onValueChange = { ip = it }, label = { Text("IP адрес") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { OutlinedTextField(value = loc, onValueChange = { loc = it }, label = { Text("Страна") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                    Box(Modifier.weight(1f)) { OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("Город") }, singleLine = true, modifier = Modifier.fillMaxWidth()) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { OutlinedTextField(value = bw, onValueChange = { bw = it.filter(Char::isDigit) }, label = { Text("Канал, Мбит/с") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                    Box(Modifier.weight(1f)) { OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("SSH порт") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) }
                }
                OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Временный SSH-пароль (для установки ключа; необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text("Пароль используется один раз для bootstrap публичного ключа панели, дальше — только ключ.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { if (id.isNotBlank() && ip.isNotBlank()) onSubmit(id.trim(), ip.trim(), loc.trim(), city.trim(), bw.toIntOrNull() ?: 1000, port.toIntOrNull() ?: 22, pass) }) { Text("Добавить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
