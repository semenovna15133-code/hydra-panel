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
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.WeeklyReport
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen() {
    val repo = PanelRepository.get(LocalContext.current)
    var reports by remember { mutableStateOf<List<WeeklyReport>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val host = remember { SnackbarHostState() }
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh) {
        loading = true; error = null
        runCatching { repo.weeklyReports() }
            .onSuccess { reports = it; loading = false }
            .onFailure { error = it.message; loading = false }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Отчёты") }, actions = {
            IconButton(onClick = { refresh++ }) { Text("⟳") }
        }) },
        snackbarHost = { SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            if (loading) LoadingBlock()
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            ActionButton("Сгенерировать за прошлую неделю", Modifier.fillMaxWidth()) {
                                runner.run { repo.generateWeeklyReport().also { refresh++ } }
                            }
                        }
                    }
                }
                item {
                    ActionButton("Catch-up: дозаполнить пропущенные недели", Modifier.fillMaxWidth()) {
                        runner.run { repo.catchUpReports().also { refresh++ } }
                    }
                }
                item { SectionTitle("Недельные отчёты (${reports.size})") }
                items(reports) { r ->
                    WeeklyReportCard(r)
                }
            }
        }
    }
}

@Composable
private fun WeeklyReportCard(r: WeeklyReport) {
    var expanded by remember { mutableStateOf(false) }
    VCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${Format.shortDate(r.weekStart)} → ${Format.shortDate(r.weekEnd)}",
                        style = MaterialTheme.typography.titleSmall)
                    Text("ключей ${r.totalKeys ?: "—"} · устройств ${r.totalDevices ?: "—"} · нагрузка ${Format.num(r.avgLoad)}% (пик ${Format.num(r.peakLoad)}%)",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "свернуть" else "подробно") }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                if (!r.protocolDistribution.isNullOrBlank()) {
                    MonoText(" протоколы: ${r.protocolDistribution}")
                }
                if (!r.recommendation.isNullOrBlank()) {
                    Text("→ ${r.recommendation}", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                Text("сгенерирован ${Format.dateTime(r.generatedAt)}", fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
