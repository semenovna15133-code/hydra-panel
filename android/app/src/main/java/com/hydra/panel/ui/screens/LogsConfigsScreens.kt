package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(serverId: String, onBack: () -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    val sources = listOf("agent" to "Агент", "wdtt" to "WDTT", "aivpn" to "AIVPN", "awg" to "AmneziaWG")
    var source by remember { mutableStateOf("agent") }
    var text by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh, serverId, source) {
        loading = true; error = null
        runCatching { repo.serverLogs(serverId, source).first }
            .onSuccess { text = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Логи · $serverId") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { IconButton(onClick = { refresh++ }) { Text("⟳") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                for ((i, pair) in sources.withIndex()) {
                    SegmentedButton(
                        selected = source == pair.first,
                        onClick = { source = pair.first },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = sources.size),
                    ) { Text(pair.second, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) LoadingBlock("Читаем журнал с сервера…")
            else Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                ) {
                    MonoText(text?.take(120_000) ?: "Пусто")
                }
            }
        }
    }
}

// ─────────────────────────── Configs ───────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigsScreen(serverId: String, onBack: () -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    val sources = listOf("agent" to "Агент", "wdtt" to "WDTT", "aivpn" to "AIVPN", "awg" to "AmneziaWG")
    var source by remember { mutableStateOf("agent") }
    var page by remember { mutableStateOf<PanelRepository.ConfigPage?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var rawEdit by remember { mutableStateOf<String?>(null) }
    var awgEdit by remember { mutableStateOf<Map<String, String>?>(null) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh, serverId, source) {
        loading = true; error = null; rawEdit = null; awgEdit = null
        runCatching { repo.serverConfigs(serverId, source) }
            .onSuccess { page = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Конфиги · $serverId") },
                navigationIcon = { TextButton(onClick = onBack) { Text("←") } },
                actions = { IconButton(onClick = { refresh++ }) { Text("⟳") } },
            )
        },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                for ((i, pair) in sources.withIndex()) {
                    SegmentedButton(
                        selected = source == pair.first,
                        onClick = { source = pair.first },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = sources.size),
                    ) { Text(pair.second, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (loading) LoadingBlock("Читаем конфиги…")
            else {
                val p = page
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (p != null && p.awgParams.isNotEmpty() && source == "awg") {
                        item { SectionTitle("Параметры AmneziaWG (Jc/S1/I1/H1…)") }
                        item {
                            VCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    p.awgParams.forEach { (k, v) -> MonoText("$k = $v") }
                                    Spacer(Modifier.height(6.dp))
                                    ActionButton("Редактировать AWG-параметры", modifier = Modifier.fillMaxWidth()) {
                                        awgEdit = p.awgParams.toMutableMap()
                                    }
                                }
                            }
                        }
                    }
                    if (p != null && p.rawContent.isNotBlank()) {
                        item { SectionTitle(if (p.editPath.isNotBlank()) "raw · ${p.editPath}" else "Текст конфига") }
                        item {
                            VCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    MonoText(p.rawContent.take(6000))
                                    Spacer(Modifier.height(6.dp))
                                    ActionButton("Редактировать raw", modifier = Modifier.fillMaxWidth()) {
                                        rawEdit = p.rawContent
                                    }
                                }
                            }
                        }
                    }
                    item { SectionTitle("Страница конфигурации (текст)") }
                    item {
                        VCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text((p?.listing ?: "—").take(20_000), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Raw editor dialog
    rawEdit?.let { initial ->
        var value by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { rawEdit = null },
            title = { Text("raw_config · $source") },
            text = {
                OutlinedTextField(value = value, onValueChange = { value = it }, Modifier.fillMaxWidth().height(360.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            },
            confirmButton = {
                Button(onClick = {
                    val target = rawEdit; rawEdit = null
                    if (target != null) runner.run { repo.applyConfigRaw(serverId, source, value) }
                }) { Text("Применить") }
            },
            dismissButton = { TextButton(onClick = { rawEdit = null }) { Text("Отмена") } },
        )
    }

    // AWG params editor dialog
    awgEdit?.let { initial ->
        var fields by remember { mutableStateOf(initial) }
        AlertDialog(
            onDismissRequest = { awgEdit = null },
            title = { Text("AWG-параметры") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Валидация на сервере: uint16, диапазоны, base64. Пустое значение = не менять.",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    for ((idx, item) in fields.keys.sorted().withIndex()) {
                        val k = item
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(k, Modifier.width(56.dp), style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                fields[k] ?: "", { v -> fields = fields.toMutableMap().apply { put(k, v) } },
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val f = fields; awgEdit = null
                    runner.run { repo.applyConfigAwg(serverId, f) }
                }) { Text("Применить") }
            },
            dismissButton = { TextButton(onClick = { awgEdit = null }) { Text("Отмена") } },
        )
    }
}
