package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatabaseScreen() {
    val repo = PanelRepository.get(LocalContext.current)
    var query by remember {
        mutableStateOf("SELECT id, location, ip, status FROM servers ORDER BY id")
    }
    var rows by remember { mutableStateOf<List<Map<String, String?>>?>(null) }
    var tables by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showTables by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        runCatching { tables = repo.listTables() }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("SQL-консоль") }) }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp)) {
            Text("Read-only (SELECT / PRAGMA). Выполняется на сервере панелью.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(140.dp),
                label = { Text("Запрос") },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && query.isNotBlank(),
                    onClick = {
                        busy = true; error = null; rows = null
                        scope.launch {
                            runCatching { repo.sqlQuery(query.trim()) }
                                .onSuccess { rows = it }
                                .onFailure { error = it.message }
                            busy = false
                        }
                    },
                ) { if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Выполнить") }
                OutlinedButton(onClick = { showTables = true }) { Text("Таблицы (${tables.size})") }
            }
            Spacer(Modifier.height(8.dp))
            MessageBar(error, true) { error = null }
            val r = rows
            if (r != null) {
                Text("${r.size} строк", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                VCard(Modifier.fillMaxSize()) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            if (r.isNotEmpty()) {
                                Row {
                                    r.first().keys.forEach { k ->
                                        Text(k, Modifier.width(150.dp), style = MaterialTheme.typography.labelLarge)
                                    }
                                }
                                HorizontalDivider()
                                r.forEach { row ->
                                    Row {
                                        row.values.forEach { v ->
                                            Text(v ?: "NULL", Modifier.width(150.dp), fontSize = 12.sp, maxLines = 1)
                                        }
                                    }
                                }
                            } else {
                                Text("Пусто", Modifier.padding(12.dp))
                            }
                        }
                    }
                }
            } else if (!busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Введи SELECT-запрос", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    if (showTables) {
        AlertDialog(
            onDismissRequest = { showTables = false },
            title = { Text("Таблицы БД") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    tables.forEach { t ->
                        TextButton(onClick = {
                            query = "SELECT * FROM $t LIMIT 50"
                            showTables = false
                        }) { Text(t, modifier = Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTables = false }) { Text("Закрыть") } },
        )
    }
}

