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
import com.hydra.panel.data.model.AccessKey
import com.hydra.panel.data.model.ClientPerson
import com.hydra.panel.data.model.DeviceRegistration
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(onOpenClient: (Long) -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    var clients by remember { mutableStateOf<List<ClientPerson>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh) {
        loading = true; error = null
        runCatching { repo.listClients() }
            .onSuccess { clients = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Клиенты") }, actions = {
                IconButton(onClick = { refresh++ }) { Text("⟳") }
            })
        },
        floatingActionButton = { FloatingActionButton(onClick = { showAdd = true }) { Text("+") } },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            OutlinedTextField(value = query, onValueChange = { query = it },
                label = { Text("Поиск по имени / @username / Telegram ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
            if (loading) LoadingBlock()
            val filtered = remember(clients, query) {
                if (query.isBlank()) clients else clients.filter {
                    it.displayName.contains(query, true) ||
                        (it.tgUsername ?: "").contains(query, true) ||
                        (it.telegramId?.toString() ?: "").contains(query)
                }
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filtered) { c ->
                    ClickableCard(onClick = { onOpenClient(c.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (c.status == "blocked") "blocked" else "active")
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    buildString {
                                        if (c.tgUsername != null) append("@${c.tgUsername} · ")
                                        if (c.telegramId != null) append("${c.telegramId} · ")
                                        append("ключи ${c.keysActive ?: 0}/${c.keysTotal ?: 0}")
                                    },
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (c.status == "blocked") {
                                AssistChip(onClick = {}, label = { Text("блок", fontSize = 11.sp) })
                            }
                        }
                    }
                }
                if (!loading && filtered.isEmpty()) {
                    item { Text("Нет клиентов", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp)) }
                }
            }
        }
    }

    if (showAdd) {
        ClientEditDialog(
            title = "Новый клиент",
            initial = null,
            onDismiss = { showAdd = false },
            onSave = { name, tg, user, notes ->
                showAdd = false
                runner.run { repo.createClient(name, tg, user, notes).also { refresh++ } }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientDetailScreen(clientId: Long, onBack: () -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    var client by remember { mutableStateOf<ClientPerson?>(null) }
    var keys by remember { mutableStateOf<List<AccessKey>>(emptyList()) }
    var devices by remember { mutableStateOf<List<DeviceRegistration>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var showEdit by remember { mutableStateOf(false) }
    var showKeyAdd by remember { mutableStateOf(false) }
    var pendingDeleteClient by remember { mutableStateOf(false) }
    var confForKey by remember { mutableStateOf<String?>(null) }
    var confText by remember { mutableStateOf<String?>(null) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh, clientId) {
        loading = true; error = null
        runCatching {
            val cs = repo.listClients().firstOrNull { it.id == clientId }
            Triple(cs, repo.clientKeys(clientId), repo.clientDevices(clientId))
        }.onSuccess { (c, k, d) -> client = c; keys = k; devices = d; loading = false }
         .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(client?.displayName ?: "Клиент #$clientId") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = {
                    IconButton(onClick = { refresh++ }) { Text("⟳") }
                    IconButton(onClick = { showEdit = true }) { Text("✎") }
                },
            )
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            if (loading) LoadingBlock()
            else LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                client?.let { c ->
                    item {
                        VCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(if (c.status == "blocked") "blocked" else "active")
                                    Spacer(Modifier.width(10.dp))
                                    Text(c.displayName, style = MaterialTheme.typography.titleMedium)
                                }
                                if (c.tgUsername != null || c.telegramId != null) {
                                    Text(
                                        listOfNotNull(c.tgUsername?.let { "@$it" }, c.telegramId?.toString())
                                            .joinToString(" · "),
                                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!c.notes.isNullOrBlank()) {
                                    Spacer(Modifier.height(6.dp)); Text(c.notes, fontSize = 13.sp)
                                }
                                Text("Создан ${Format.dateTime(c.createdAt)}", fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ActionButton(if (c.status == "blocked") "Разблокировать" else "Заблокировать",
                                        danger = c.status != "blocked", modifier = Modifier.weight(1f)) {
                                        runner.run { repo.toggleBlockClient(c.id).also { refresh++ } }
                                    }
                                    ActionButton("Удалить клиента", danger = true, modifier = Modifier.weight(1f)) {
                                        pendingDeleteClient = true
                                    }
                                }
                            }
                        }
                    }
                }
                item { SectionTitle("Ключи доступа (${keys.size})") }
                item {
                    ActionButton("+ Новый ключ", modifier = Modifier.fillMaxWidth()) { showKeyAdd = true }
                }
                items(keys) { k ->
                    KeyCard(k,
                        onExtend = { days -> runner.run { repo.extendKey(k.keyId, days).also { refresh++ } } },
                        onRevoke = { runner.run { repo.revokeKey(k.keyId).also { refresh++ } } },
                        onRestore = { runner.run { repo.restoreKey(k.keyId).also { refresh++ } } },
                        onDelete = { runner.run { repo.deleteKey(k.keyId).also { refresh++ } } },
                        onDownload = {
                            runner.scope.launch {
                                runCatching { confText = repo.downloadKeyConf(k.keyId); confForKey = k.keyId }
                                    .onFailure { host.showSnackbar("Ошибка: ${it.message}".take(160)) }
                            }
                        },
                    )
                }
                item { SectionTitle("Устройства (${devices.size})") }
                items(devices) { d ->
                    VCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.deviceName ?: d.deviceId, style = MaterialTheme.typography.titleSmall)
                                    Text("key ${d.keyId.take(8)}… · ip ${d.lastIp ?: "—"} · seen ${Format.relative(d.lastSeenAt)}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(onClick = {
                                    runner.run { repo.deleteDevice(d.keyId, d.deviceId).also { refresh++ } }
                                }) { Text("удалить") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEdit && client != null) {
        val c = client!!
        ClientEditDialog("Редактировать клиента", c, onDismiss = { showEdit = false },
            onSave = { name, tg, user, notes ->
                showEdit = false
                runner.run { repo.updateClient(c.id, name, tg, user, notes).also { refresh++ } }
            })
    }

    if (showKeyAdd) {
        NewKeyDialog(onDismiss = { showKeyAdd = false },
            onCreate = { days, devs ->
                showKeyAdd = false
                runner.run { repo.createKey(clientId, days, devs).also { refresh++ } }
            })
    }

    if (pendingDeleteClient) {
        ConfirmDialog("Удалить клиента?", "Каскадно удалятся все ключи и отвязки устройств. Действие необратимо.", "Удалить",
            onConfirm = { runner.run { repo.deleteClient(clientId).also { onBack() } } },
            onDismiss = { pendingDeleteClient = false })
    }

    if (confForKey != null && confText != null) {
        AlertDialog(
            onDismissRequest = { confForKey = null },
            title = { Text("Конфиг ключа ${confForKey!!.take(8)}…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) { MonoText(confText!!) }
            },
            confirmButton = { TextButton(onClick = { confForKey = null }) { Text("Закрыть") } },
        )
    }
}

@Composable
private fun KeyCard(
    k: AccessKey,
    onExtend: (Int) -> Unit,
    onRevoke: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
) {
    val expired = Format.daysLeft(k.expiresAt)?.let { it < 0 } == true
    val revoked = k.revokedAt != null
    val statusLabel = when {
        revoked -> "приостановлен"
        expired -> "истёк"
        else -> "активен"
    }
    VCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (revoked) "blocked" else if (expired) "expired" else "active")
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("${k.keyId.take(12)}… · $statusLabel", style = MaterialTheme.typography.titleSmall)
                    val left = Format.daysLeft(k.expiresAt)
                    Text(
                        buildString {
                            append("до ${Format.dateTime(k.expiresAt)}")
                            if (left != null && !revoked && !expired) {
                                append(if (left >= 0) " (осталось $left дн.)" else " (просрочен на ${-left} дн.)")
                            }
                            append(" · устр. ${k.deviceCount ?: 0}/${k.maxDevices ?: 3}")
                        },
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { onExtend(30) }) { Text("+30д") }
                OutlinedButton(onClick = { onExtend(90) }) { Text("+90д") }
                if (revoked) ActionButton("Возобновить") { onRestore() }
                else ActionButton("Пауза", danger = true) { onRevoke() }
                OutlinedButton(onClick = onDownload) { Text("конф") }
                OutlinedButton(onClick = onDelete) { Text("удал.") }
            }
        }
    }
}

@Composable
private fun ClientEditDialog(
    title: String,
    initial: ClientPerson?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.displayName ?: "") }
    var tg by remember { mutableStateOf(initial?.telegramId?.toString() ?: "") }
    var user by remember { mutableStateOf(initial?.tgUsername ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = tg, onValueChange = { tg = it.filter(Char::isDigit) }, label = { Text("Telegram ID (число)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("@username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Заметки") }, modifier = Modifier.fillMaxWidth().height(96.dp))
            }
        },
        confirmButton = { Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), tg.trim(), user.trim(), notes.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun NewKeyDialog(onDismiss: () -> Unit, onCreate: (Int, Int) -> Unit) {
    var days by remember { mutableStateOf("30") }
    var devices by remember { mutableStateOf("3") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый ключ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = days, onValueChange = { days = it.filter(Char::isDigit) }, label = { Text("Срок, дней") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = devices, onValueChange = { devices = it.filter(Char::isDigit) }, label = { Text("Макс. устройств") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(days.toIntOrNull() ?: 30, devices.toIntOrNull() ?: 3) }) { Text("Создать") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}
