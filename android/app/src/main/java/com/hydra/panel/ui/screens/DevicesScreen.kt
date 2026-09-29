package com.hydra.panel.ui.screens

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.model.DeviceRegistration
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*
import com.hydra.panel.ui.components.MessageBar
import com.hydra.panel.ui.components.SectionTitle
import com.hydra.panel.ui.components.StatusDot
import com.hydra.panel.ui.components.rememberActionRunner
import com.hydra.panel.util.Format

private data class DeviceRow(
    val keyId: String,
    val clientId: Long?,
    val clientName: String?,
    val device: DeviceRegistration,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen() {
    val repo = PanelRepository.get(LocalContext.current)
    var rows by remember { mutableStateOf<List<DeviceRow>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var filterKey by remember { mutableStateOf<String?>(null) }
    val host = androidx.compose.material3.SnackbarHostState()
    val runner = rememberActionRunner(host)

    LaunchedEffect(refresh) {
        runCatching {
            val keys = repo.listKeys()
            keys.flatMap { k ->
                try {
                    if (k.clientId == null) emptyList()
                    else repo.clientDevices(k.clientId).map { DeviceRow(k.keyId, k.clientId, k.clientName, it) }
                } catch (_: Exception) { emptyList() }
            }
        }.onSuccess { rows = it; error = null }
         .onFailure { error = it.message }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Устройства") }, actions = {
            IconButton(onClick = { refresh++ }) { Text("⟳") }
        }) },
        snackbarHost = { androidx.compose.material3.SnackbarHost(host) },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            MessageBar(error, true) { error = null }
            val all = rows.orEmpty()
            val distinctKeys = all.map { it.keyId to it.clientName }.distinctBy { it.first }
            Row(
                Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(filterKey == null, { filterKey = null }, label = { Text("Все (${all.size})") })
                if (distinctKeys.size in 1..8) {
                    for ((kid, name) in distinctKeys) {
                        FilterChip(selected = filterKey == kid, onClick = { filterKey = kid },
                            label = { Text((name ?: kid.take(6)) + " (${all.count { it.keyId == kid }})") })
                    }
                }
            }
            if (rows == null) LoadingBlock("Собираем устройства по ключам…")
            val shown = if (filterKey == null) all else all.filter { it.keyId == filterKey }
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(shown) { item ->
                        VCard(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(if (isRecent(item.device.lastSeenAt)) "active" else "unknown")
                                androidx.compose.foundation.layout.Spacer(Modifier.padding(end = 8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.device.deviceName ?: item.device.deviceId, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        "${item.clientName ?: "—"} · key ${item.keyId.take(8)}… · ip ${item.device.lastIp ?: "—"} · seen ${Format.relative(item.device.lastSeenAt)}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                OutlinedButton(onClick = {
                                    runner.run { repo.deleteDevice(item.keyId, item.device.deviceId).also { refresh++ } }
                                }) { Text("отвязать") }
                            }
                        }
                }
                if (all.isEmpty() && rows != null) {
                    item { SectionTitle("Устройств пока нет — добавь их на экране клиента") }
                }
            }
        }
    }
}

private fun isRecent(ts: String?): Boolean {
    val d = Format.parseDateTime(ts) ?: return false
    return System.currentTimeMillis() - d.time < 7 * 24 * 3600_000L
}

