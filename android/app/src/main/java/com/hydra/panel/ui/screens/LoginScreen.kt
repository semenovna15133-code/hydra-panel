package com.hydra.panel.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hydra.panel.data.repo.PanelRepository
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val repo = PanelRepository.get(LocalContext.current)
    var url by remember { mutableStateOf(repo.sessionStore.baseUrl) }
    var password by remember { mutableStateOf("") }
    var cliToken by remember { mutableStateOf(repo.sessionStore.cliToken ?: "") }
    var rememberMe by remember { mutableStateOf(true) }
    var useCli by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val appCtx = LocalContext.current

    Scaffold { pad ->
        Column(
            Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Hydra Panel", style = MaterialTheme.typography.headlineSmall)
            Text("Вход в панель управления", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL панели (https://host:8000)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (useCli) "CLI-токен" else "Пароль администратора", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { useCli = !useCli; error = null }) {
                    Text(if (useCli) "вход по паролю" else "вход по токену")
                }
            }

            if (useCli) {
                OutlinedTextField(value = cliToken, onValueChange = { cliToken = it }, label = { Text("hydra CLI token") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation())
                Text("Токен из ~/.hydra/cli_token на сервере панели. Доступ только к read-only API.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(), visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                            if (busy) return@Button
                            busy = true; error = null; info = null
                            val ctx = appCtx
                            scope.launch {
                                val trimmed = url.trim().trimEnd('/')
                                if (trimmed.isEmpty()) { error = "Укажи URL панели"; busy = false; return@launch }
                                if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                                    error = "URL должен начинаться с http(s)://"; busy = false; return@launch
                                }
                                if (password.length < 8) { error = "Минимум 8 символов"; busy = false; return@launch }
                                repo.sessionStore.baseUrl = trimmed
                                repo.sessionStore.cliToken = null
                                PanelRepository.reset()
                                val r = PanelRepository.recreate(ctx)
                                val outcome = try {
                                    if (!r.hasAdminViaSetupCheck(trimmed)) {
                                        info = "Админа нет — выполняю первичную настройку /setup…"
                                        val ok = r.firstSetup(trimmed, password)
                                        if (!ok) throw IllegalStateException("Не удалось выполнить первичную настройку")
                                    } else {
                                        r.login(trimmed, password, rememberMe).getOrThrow()
                                    }
                                    null
                                } catch (e: Exception) { e }
                                if (outcome != null) error = outcome.message ?: "Сетевая ошибка"
                                else onLoggedIn()
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Text("Войти")
                    }
                }
                Text(
                    "Если панель новая и админ ещё не создан, приложение само выполнит первичную настройку /setup с этим паролем.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (error != null) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(error!!, Modifier.fillMaxWidth().padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (info != null) {
                Box(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(info!!, Modifier.fillMaxWidth().padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}
