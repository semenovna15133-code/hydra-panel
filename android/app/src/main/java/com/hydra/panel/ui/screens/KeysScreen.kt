package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.AccessKey
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format
import kotlinx.coroutines.launch

private enum class KeyFilter(val label: String) {
    ALL("Все"), ACTIVE("Активные"), EXPIRED("Истёкшие"), REVOKED("Пауза"), ORPHAN("Без клиента")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeysScreen() {
    val repo = PanelRepository.get(LocalContext.current)
    var keys by remember { mutableStateOf<List<AccessKey>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(KeyFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var confKey by remember { mutableStateOf<String?>(null) }
    var confText by remember { mutableStateOf<String?>(null) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh) {
        loading = true; error = null
        runCatching { repo.listKeys() }
            .onSuccess { keys = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ключи") }, actions = {
            IconButton(onClick = { refresh++ }) { Text("⟳") }
        }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text("Поиск: key / клиент") },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp))
            ScrollableTabRow(
                selectedTabIndex = KeyFilter.entries.indexOf(filter),
                edgePadding = 12.dp,
            ) {
                val _entries = KeyFilter.entries
                _entries.forEach { f ->
                    Tab(selected = f == filter, onClick = { filter = f }, text = { Text(f.label, fontSize = 13.sp) })
                }
            }
            if (loading) LoadingBlock()
            val shown = remember(keys, filter, query) {
                keys.filter { k ->
                    when (filter) {
                        KeyFilter.ALL -> true
                        KeyFilter.ACTIVE -> k.revokedAt == null && !isExpired(k)
                        KeyFilter.EXPIRED -> k.revokedAt == null && isExpired(k)
                        KeyFilter.REVOKED -> k.revokedAt != null
                        KeyFilter.ORPHAN -> k.clientId == null
                    } && (query.isBlank() || k.keyId.contains(query, true) ||
                        (k.clientName ?: "").contains(query, true))
                }
            }
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("Показано ${shown.size} из ${keys.size}", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown) { k ->
                        val expired = isExpired(k)
                        VCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    StatusDot(if (k.revokedAt != null) "blocked" else if (expired) "expired" else "active")
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(k.clientName ?: "без клиента · ${k.keyId.take(10)}…",
                                            style = MaterialTheme.typography.titleSmall)
                                        Text("key ${k.keyId.take(14)}… · до ${Format.shortDate(k.expiresAt)} · устр. ${k.deviceCount ?: 0}/${k.maxDevices ?: 3}",
                                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(onClick = { runner.run { repo.extendKey(k.keyId, 30).also { refresh++ } } }) { Text("+30д") }
                                    if (k.revokedAt != null) {
                                        ActionButton("Возобновить") { runner.run { repo.restoreKey(k.keyId).also { refresh++ } } }
                                    } else {
                                        ActionButton("Пауза", danger = true) { runner.run { repo.revokeKey(k.keyId).also { refresh++ } } }
                                    }
                                    OutlinedButton(onClick = {
                                        runner.scope.launch {
                                            runCatching { confText = repo.downloadKeyConf(k.keyId); confKey = k.keyId }
                                                .onFailure { host.showSnackbar("Ошибка: ${it.message}".take(160)) }
                                        }
                                    }) { Text("конф") }
                                }
                            }
                        }
                    }
                }
            }
        }

    if (confKey != null && confText != null) {
        AlertDialog(
            onDismissRequest = { confKey = null },
            title = { Text("Конфиг ключа ${confKey!!.take(8)}…") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    MonoText(confText!!)
                }
            },
            confirmButton = { TextButton(onClick = { confKey = null }) { Text("Закрыть") } },
        )
    }
}

private fun isExpired(k: AccessKey) = Format.daysLeft(k.expiresAt)?.let { it < 0 } == true
