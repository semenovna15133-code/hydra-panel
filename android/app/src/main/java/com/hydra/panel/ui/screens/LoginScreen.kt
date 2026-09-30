package com.hydra.panel.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hydra.panel.data.repo.PanelRepository
import com.hydra.panel.ui.components.*

import kotlinx.coroutines.launch

private val fieldColors
    @Composable get() = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        cursorColor = MaterialTheme.colorScheme.primary,
    )

/**
 * Экран входа. Два режима:
 *  • «Действующая панель» — URL + пароль администратора (или CLI-токен).
 *  • «Новый сервер» — IP/домен нового VPS: приложение само поднимет панель
 *    (docker over SSH с root-паролем → http://IP:8000), затем выполнит /setup
 *    и вход. Root-пароль используется один раз для bootstrap и не хранится.
 */
@Composable
fun LoginScreen(onLoggedIn: () -> Unit) {
    val appCtx = LocalContext.current
    // PanelRepository.get() до первого логина может бросить — экран обязан отрисуется.
    val repo = remember { runCatching { PanelRepository.get(appCtx) }.getOrNull() }
    val scope = rememberCoroutineScope()

    var provision by remember { mutableStateOf(false) } // режим «новый сервер»
    var url by remember { mutableStateOf(repo?.sessionStore?.baseUrl ?: "") }
    var password by remember { mutableStateOf("") }
    var cliToken by remember { mutableStateOf(repo?.sessionStore?.cliToken ?: "") }
    var useCli by remember { mutableStateOf(false) }

    // поля провижининга нового сервера
    var host by remember { mutableStateOf("") }
    var sshUser by remember { mutableStateOf("root") }
    var sshPass by remember { mutableStateOf("") }
    var adminPass by remember { mutableStateOf("") }
    var panelPort by remember { mutableStateOf("8000") }
    var repoUrl by remember { mutableStateOf(Provisioner.DEFAULT_REPO_URL) }

    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun normalize(raw: String): String {
        val t = raw.trim().trimEnd('/')
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }

    Scaffold { pad ->
        Box(Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(
                MaterialTheme.colorScheme.background,
                MaterialTheme.colorScheme.surface,
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.18f),
            )))) {
            Column(
                Modifier.padding(pad).padding(horizontal = 24.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start,
            ) {
                Spacer(Modifier.height(40.dp))

                // ── Логотип ──
                Column {
                    Text("⬡", fontSize = 44.sp, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "HYDRA PANEL",
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text("Мобильный контроль VPN-инфраструктуры", style = MaterialTheme.typography.bodySmall)
                    Text("v${com.hydra.panel.BuildConfig.VERSION_NAME} (${com.hydra.panel.BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                }

                Spacer(Modifier.height(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Переключатель режима
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ModeTab("Действующая панель", !provision) { provision = false; error = null; info = null }
                        ModeTab("Новый сервер", provision) { provision = true; error = null; info = null }
                    }

                    if (!provision) {
                        // ═══ Обычный вход ═══
                        OutlinedTextField(value = url, onValueChange = { url = it },
                            label = { Text("URL панели — https://host:8000") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = fieldColors)

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (useCli) "CLI-токен" else "Пароль администратора",
                                style = MaterialTheme.typography.labelLarge)
                            TextButton(onClick = { useCli = !useCli; error = null }) {
                                Text(if (useCli) "по паролю" else "по токену", fontSize = 12.sp)
                            }
                        }

                        if (useCli) {
                            OutlinedTextField(value = cliToken, onValueChange = { cliToken = it },
                                label = { Text("hydra CLI token") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                colors = fieldColors)
                            Text("Токен из ~/.hydra/cli_token на сервере панели (read-only API).",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            OutlinedTextField(value = password, onValueChange = { password = it },
                                label = { Text("Пароль") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                colors = fieldColors)
                        }

                        GradientButton(text = if (busy) "" else "Войти в панель", enabled = !busy, busy = busy,
                            onClick = {
                                if (busy) return@GradientButton
                                if (url.isBlank()) { error = "Укажи URL панели"; return@GradientButton }
                                busy = true; error = null; info = null
                                val trimmed = normalize(url)
                                scope.launch {
                                    repo?.sessionStore?.baseUrl = trimmed
                                    repo?.sessionStore?.cliToken = if (useCli) cliToken.trim() else null
                                    PanelRepository.reset()
                                    val r = PanelRepository.recreate(appCtx)
                                    val outcome = try {
                                        if (useCli) {
                                            r.loginWithCliToken(cliToken.trim())
                                        } else {
                                            if (password.length < 8) throw IllegalStateException("Минимум 8 символов")
                                            if (!r.hasAdminViaSetupCheck(trimmed)) {
                                                info = "Админа нет — выполняю первичную настройку /setup…"
                                                if (!r.firstSetup(trimmed, password))
                                                    throw IllegalStateException("Не удалось выполнить первичную настройку")
                                            } else {
                                                r.login(trimmed, password, true).getOrThrow()
                                            }
                                        }
                                        null
                                    } catch (e: Exception) { e }
                                    if (outcome != null) error = outcome.message ?: "Сетевая ошибка"
                                    else onLoggedIn()
                                    busy = false
                                }
                            })
                        Text(
                            "Если панель новая и админ ещё не создан — приложение само выполнит /setup с этим паролем.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        // ═══ Провижининг нового сервера ═══
                        Text("Приложение установит панель Docker'ом по SSH (нужен root или sudo), дождётся запуска и создаст администратора.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(value = host, onValueChange = { host = it },
                            label = { Text("IP или домен нового сервера") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = fieldColors)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(value = sshUser, onValueChange = { sshUser = it },
                                    label = { Text("SSH пользователь") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(), colors = fieldColors)
                            }
                            Box(Modifier.weight(1f)) {
                                OutlinedTextField(value = panelPort, onValueChange = { panelPort = it.filter(Char::isDigit) },
                                    label = { Text("Порт панели") }, singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    colors = fieldColors)
                            }
                        }
                        OutlinedTextField(value = sshPass, onValueChange = { sshPass = it },
                            label = { Text("Root-пароль SSH (одноразовый bootstrap)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = fieldColors)
                        OutlinedTextField(value = adminPass, onValueChange = { adminPass = it },
                            label = { Text("Пароль администратора панели (≥8)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            colors = fieldColors)
                        OutlinedTextField(value = repoUrl, onValueChange = { repoUrl = it },
                            label = { Text("Git-репозиторий панели") }, singleLine = true,
                            supportingText = { Text("Сюда же можно вставить приватный URL вида https://user:token@github.com/me/repo") },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = fieldColors)

                        GradientButton(text = if (busy) "" else "Развернуть и войти", enabled = !busy, busy = busy,
                            onClick = {
                                if (busy) return@GradientButton
                                if (host.isBlank()) { error = "Укажи адрес сервера"; return@GradientButton }
                                if (sshPass.isBlank()) { error = "Нужен root-пароль для bootstrap"; return@GradientButton }
                                if (adminPass.length < 8) { error = "Пароль администратора минимум 8 символов"; return@GradientButton }
                                busy = true; error = null; info = null
                                val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
                                scope.launch {
                                    val outcome = try {
                                        info = "Подключаюсь по SSH и разворачиваю панель…"
                                        val baseUrl = Provisioner.provision(appCtx, cleanHost,
                                            sshUser.trim().ifBlank { "root" }, sshPass,
                                            panelPort.toIntOrNull()?.coerceIn(1, 65535) ?: 8000,
                                            repoUrl.trim().ifBlank { Provisioner.DEFAULT_REPO_URL }) { msg -> info = msg }
                                        info = "Панель поднялась: $baseUrl — создаю администратора…"
                                        repo?.sessionStore?.baseUrl = baseUrl
                                        repo?.sessionStore?.cliToken = null
                                        PanelRepository.reset()
                                        val r = PanelRepository.recreate(appCtx)
                                        if (!r.hasAdminViaSetupCheck(baseUrl)) {
                                            if (!r.firstSetup(baseUrl, adminPass))
                                                throw IllegalStateException("Не удалось создать администратора")
                                        } else {
                                            r.login(baseUrl, adminPass, true).getOrThrow()
                                        }
                                        null
                                    } catch (e: Exception) { e }
                                    if (outcome != null) error = outcome.message ?: "Ошибка провижининга"
                                    else onLoggedIn()
                                    busy = false
                                }
                            })
                        Text("Root-пароль используется один раз для установки ключа панели и не сохраняется в приложении.",
                            style = MaterialTheme.typography.bodySmall)
                    }

                    if (error != null) ErrorBubble(error!!, onDismiss = { error = null })
                    if (info != null && !busy) {
                        Text(info!!, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                    } else if (info != null && busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text(info!!, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Spacer(Modifier.height(48.dp))
            }
        }
    }
}

@Composable
private fun ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.CircleShape
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent
    val accent = MaterialTheme.colorScheme.primary
    val borderColor = if (selected) accent else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    Box(
        Modifier
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor.copy(alpha = 0.6f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
