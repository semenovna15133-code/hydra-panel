package com.hydra.panel.ui.screens

import android.content.Context
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.SessionInfo
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onLoggedOut: () -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    val context = LocalContext.current
    val tabStates = listOf("Панель", "БД и бэкапы", "GitHub", "Сессии")
    var tab by remember { mutableStateOf(0) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ещё") }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                for ((i, label) in tabStates.withIndex()) {
                    FilterChip(selected = i == tab, onClick = { tab = i },
                        label = { Text(label) }, modifier = Modifier.padding(horizontal = 3.dp))
                }
            }
            HorizontalDivider()
            when (tab) {
                0 -> { YamlTab(repo, runner) }
                1 -> { BackupsTab(repo, runner, host, context) }
                2 -> { GithubTab(repo, runner) }
                else -> { SessionsTab(repo, runner, host, onLoggedOut) }
            }
        }
    }
}

// ───────── panel.yaml ─────────

@Composable
private fun YamlTab(repo: PanelRepository, runner: ActionRunner) {
    var yaml by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        runCatching { repo.loadSettingsYaml() }
            .onSuccess { yaml = it; dirty = false }
            .onFailure { error = it.message }
    }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MessageBar(error, true) { error = null }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("panel.yaml", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { refresh++ }) { Text("перечитать") }
        }
        Spacer(Modifier.height(6.dp))
        val v = yaml
        if (v == null && !dirty) LoadingBlock("Читаем panel.yaml…")
        else {
            OutlinedTextField(
                draft ?: v ?: "",
                { draft = it; dirty = true },
                Modifier.fillMaxWidth().weight(1f),
            )
            Spacer(Modifier.height(8.dp))
            ActionButton("Сохранить panel.yaml", Modifier.fillMaxWidth(), enabled = dirty) {
                (draft ?: v)?.let { content ->
                    runner.run { repo.saveSettingsYaml(content).also { draft = null; dirty = false; refresh++ } }
                }
            }
        }
    }
}

// ───────── Backup / SQL maintenance ─────────

@Composable
private fun BackupsTab(repo: PanelRepository, runner: ActionRunner, host: SnackbarHostState, context: Context) {
    var backups by remember { mutableStateOf<List<com.hydra.panel.data.model.BackupInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var restoreName by remember { mutableStateOf<String?>(null) }
    var savedMsg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(refresh) {
        runCatching { repo.listBackups() }
            .onSuccess { backups = it }
            .onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MessageBar(error, true) { error = null }
        MessageBar(savedMsg, false) { savedMsg = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ActionButton("Бэкап сейчас", modifier = Modifier.fillMaxWidth()) {
                    runner.run { repo.backupNow().also { refresh++ } }
                }
            }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("Обновить список") }
            }
        }
        Spacer(Modifier.height(8.dp))
        SectionTitle("Файлы бэкапов (${backups?.size ?: "…"})")
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(backups.orEmpty()) { b ->
                VCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall)
                            Text("${Format.bytes(b.sizeKb)} · ${b.mtime ?: "—"} · ${if (b.auto) "авто" else "manual"}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = {
                            runner.scope.launch {
                                runCatching {
                                    val bytes = repo.downloadBackup(b.name)
                                    val dir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
                                    val f = File(dir, b.name)
                                    f.writeBytes(bytes)
                                    savedMsg = "Сохранено: ${f.absolutePath}"
                                }.onFailure { savedMsg = "Ошибка сохранения: ${it.message}" }
                            }
                        }) { Text("скачать") }
                        OutlinedButton(onClick = { restoreName = b.name }) { Text("restore") }
                    }
                }
            }
        }
    }

    restoreName?.let { name ->
        ConfirmDialog("Восстановить из $name?",
            "Текущая база панели будет заменена этим снимком. Все изменения после него пропадут. Сервис перезапустится.",
            "Восстановить",
            onConfirm = { runner.run { repo.restoreBackup(name).also { refresh++ } } },
            onDismiss = { restoreName = null })
    }
}

// ───────── GitHub offsite ─────────

@Composable
private fun GithubTab(repo: PanelRepository, runner: ActionRunner) {
    var summary by remember { mutableStateOf<String?>(null) }
    var repo_ by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var keep by remember { mutableStateOf("14") }
    var pass by remember { mutableStateOf("") }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(refresh) {
        try { summary = repo.backupSettingsSummary() } catch (e: Exception) {
            summary = "Не удалось прочитать страницу: ${e.message}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Резервное копирование в GitHub + recovery-пароль", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = repo_, onValueChange = { repo_ = it }, label = { Text("repo: owner/name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Personal access token") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = keep, onValueChange = { keep = it.filter(Char::isDigit) }, label = { Text("Хранить копий") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ActionButton("Тест", Modifier.fillMaxWidth(), enabled = repo_.isNotBlank() && token.isNotBlank()) {
                    runner.run { repo.testGithub(repo_.trim(), token.trim()) }
                }
            }
            Box(Modifier.weight(1f)) {
                ActionButton("Сохранить", Modifier.fillMaxWidth(), enabled = repo_.isNotBlank() && token.isNotBlank()) {
                    runner.run { repo.saveGithub(repo_.trim(), token.trim(), keep.toIntOrNull() ?: 14).also { refresh++ } }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                ActionButton("Push сейчас", modifier = Modifier.fillMaxWidth()) { runner.run { repo.pushBackupNow() } }
            }
            Box(Modifier.weight(1f)) {
                ActionButton("Удалить креды", danger = true, modifier = Modifier.fillMaxWidth()) {
                    runner.run { repo.deleteGithubCreds().also { refresh++ } }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        Text("Recovery passphrase (для дешифровки offsite-бэкапов)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Passphrase (min 12 символов)") }, singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        ActionButton("Задать passphrase", Modifier.fillMaxWidth(), enabled = pass.length >= 12) {
            runner.run { repo.setRecoveryPassphrase(pass).also { pass = ""; refresh++ } }
        }
        Spacer(Modifier.height(12.dp))
        SectionTitle("Текущее состояние")
        VCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) { MonoText(summary ?: "…") }
        }
    }
}

// ───────── Sessions ─────────

@Composable
private fun SessionsTab(repo: PanelRepository, runner: ActionRunner, host: SnackbarHostState, onLoggedOut: () -> Unit) {
    var sessions by remember { mutableStateOf<List<SessionInfo>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var confirmAll by remember { mutableStateOf(false) }

    LaunchedEffect(refresh) {
        runCatching { repo.listSessions() }
            .onSuccess { sessions = it }
            .onFailure { error = it.message }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        MessageBar(error, true) { error = null }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Активные сессии панели", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { refresh++ }) { Text("обновить") }
            Button(onClick = { confirmAll = true }) { Text("отозвать все") }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sessions.orEmpty()) { s ->
                VCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.userAgent?.take(60) ?: "unknown UA", style = MaterialTheme.typography.titleSmall)
                            Text("ip ${s.ip ?: "—"} · вход ${Format.relative(s.createdAt)} · seen ${Format.relative(s.lastSeenAt)} · до ${Format.shortDate(s.expiresAt)}${if (s.remember == 1) " · remember" else ""}",
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MonoText(s.tokenHash.take(12) + "…")
                        }
                        OutlinedButton(onClick = {
                            runner.scope.launch {
                                runCatching { repo.revokeSession(s.tokenHash) }
                                    .onSuccess { msg ->
                                        host.showSnackbar(msg.take(160))
                                        if (msg.contains("разлогинено")) onLoggedOut() else refresh++
                                    }
                            }
                        }) { Text("отозвать") }
                    }
                }
            }
        }
    }

    if (confirmAll) {
        ConfirmDialog("Отозвать ВСЕ сессии?", "Веб-панель и это приложение будут разлогинены. Придётся войти заново.", "Отозвать все", onConfirm = { runner.run { repo.revokeAllSessions().also { onLoggedOut() } } }, onDismiss = { confirmAll = false })
    }
}
