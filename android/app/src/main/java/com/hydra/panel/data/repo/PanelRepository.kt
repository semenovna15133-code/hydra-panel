package com.hydra.panel.data.repo

import android.content.Context
import com.hydra.panel.data.api.ApiClient
import com.hydra.panel.data.api.PanelApi
import com.hydra.panel.data.model.*
import com.hydra.panel.util.HtmlParser
import com.hydra.panel.util.SessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response

/** Единая точка доступа к панели. Скрывает JSON vs HTML/form dual-transport. */
class PanelRepository private constructor(
    private val api: PanelApi,
    private val store: SessionStore,
) {

    companion object {
        @Volatile private var instance: PanelRepository? = null

        fun get(context: Context): PanelRepository =
            instance ?: synchronized(this) {
                instance ?: PanelRepository(ApiClient.create(context), SessionStore(context))
                    .also {
                        instance = it
                        it.storeContext = context.applicationContext // worker-контекст для пушей
                    }
            }

        fun reset() { instance = null } // после смены baseUrl/логина пересоздать клиент

        /** Пересоздать репозиторий (новый OkHttp-клиент) и вернуть актуальный экземпляр. */
        fun recreate(context: Context): PanelRepository {
            instance = null
            return get(context)
        }
    }

    val sessionStore: SessionStore get() = store

    class ApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private suspend fun <T> unwrap(resp: Response<T>): T {
        // Веб-страницы при неавторизованности отвечают 303 -> /login; API — 401.
        val loc = resp.headers()["Location"] ?: ""
        if (resp.code() == 401 || (resp.code() in 300..399 && loc.contains("/login"))) {
            throw ApiException("Сессия истекла — войди заново")
        }
        if (resp.isSuccessful && resp.body() != null) return resp.body()!!
        val err = runCatching { resp.errorBody()?.string() }.getOrNull()
        val detail = err?.let { Regex("\"detail\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        throw ApiException(detail ?: "HTTP ${resp.code()}")
    }

    /** Form/HTML действия отвечают 303 с Location (?msg=...). Извлекаем msg. */
    private fun resultFromRedirect(resp: Response<*>): String {
        if (resp.code() == 401) throw ApiException("Сессия истекла — войди заново")
        val loc = resp.headers()["Location"] ?: ""
        val msg = Regex("msg=([^&]+)").find(loc)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        val known = mapOf(
            "server_added" to "Сервер добавлен",
            "bootstrap_ok" to "Сервер добавлен, ключ панели установлен ✓",
            "protocols_ok" to "Протоколы установлены ✓",
            "protocols_partial" to "Протоколы: частичная установка",
            "agent_ok" to "Агент установлен ✓",
            "agent_exists" to "Агент уже был установлен",
            "reboot_started" to "Перезагрузка запущена",
            "token_rotated" to "Токен агента повёрнут",
            "time_synced" to "Часовой пояс синхронизирован",
            "ssh_ok" to "SSH доступен ✓",
            "key_created" to "Ключ создан",
            "key_extended" to "Срок ключа продлён",
            "key_revoked" to "Ключ приостановлен",
            "key_restored" to "Ключ возобновлён",
            "key_deleted" to "Ключ удалён",
            "client_created" to "Клиент создан",
            "client_updated" to "Клиент обновлён",
            "client_blocked" to "Клиент заблокирован",
            "client_active" to "Клиент разблокирован",
            "client_deleted" to "Клиент удалён",
            "client_added" to "Устройство добавлено",
            "client_exists" to "Такое устройство уже есть",
            "client_nokey" to "Сначала создай клиента",
            "settings_saved" to "panel.yaml сохранён",
        )
        msg?.let { known[it] }?.let { return it }
        if (msg?.startsWith("service_restarted_") == true)
            return "Сервис перезапущен (${msg.removePrefix("service_restarted_")})"
        if (msg?.startsWith("config_applied_") == true)
            return "Конфиг применён (${msg.removePrefix("config_applied_")})"
        return when {
            msg != null -> msg
            resp.code() in 200..299 -> "ok"
            resp.code() == 303 -> "Выполнено"
            else -> "Ошибка HTTP ${resp.code()}"
        }
    }

    // ───────── Auth ─────────
    /** Проба: health доступен без авторизации — проверяем, что URL правильный. */
    suspend fun probe(): Boolean =
        runCatching { api.health().isSuccessful }.getOrDefault(false)

    /** Проверка через GET /setup (публичный): страница без формы первичной настройки
     *  означает, что админ уже создан (панель редиректит /setup -> /login). */
    suspend fun hasAdminViaSetupCheck(baseUrl: String): Boolean {
        store.baseUrl = baseUrl
        val html = try { (api.setupPage().body()?.string() ?: "") } catch (_: Exception) { return true }
        return !html.contains("name=\"password\"") || html.contains("Уже настроена") ||
            Regex("/setup_done|has_admin").containsMatchIn(html)
    }

    /** Есть ли на панели админ. При неавторизованном доступе SQL-консоль редиректит
     *  на /login (без result=) → кидаем ApiException → считаем, что админ есть (обычный случай). */
    suspend fun hasAdmin(): Boolean {
        val rows = try { sqlQuery("SELECT COUNT(*) AS n FROM admin_credentials") } catch (_: Exception) { return true }
        val n = rows.firstOrNull()?.values?.firstOrNull()?.toIntOrNull()
        return n == null || n > 0
    }

    /** Первичная настройка новой панели: создаёт админа + CLI-токен, выдаёт сессию. */
    suspend fun firstSetup(baseUrl: String, password: String): Boolean {
        store.baseUrl = baseUrl
        store.sessionToken = null
        val resp = api.setup(password, password)
        return !store.sessionToken.isNullOrEmpty() && resp.code() in listOf(200, 303)
    }

    suspend fun login(baseUrl: String, password: String, remember: Boolean): Result<Unit> {
        store.baseUrl = baseUrl
        store.sessionToken = null
        if (!probe()) {
            return Result.failure(ApiException("Панель недоступна по этому URL. Проверь адрес и сеть."))
        }
        val resp = api.login(password, if (remember) "on" else "")
        if ((resp.code() == 303 || resp.code() == 200) && !store.sessionToken.isNullOrEmpty()) {
            startPushMonitoring()
            return Result.success(Unit)
        }
        // Неверный пароль или rate-limit: панель редиректит /login?error=...
        val loc = resp.headers()["Location"] ?: ""
        val errParam = Regex("error=([^&]+)").find(loc)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        if (errParam != null) return Result.failure(ApiException(errParam))
        if (resp.code() == 303 && !loc.contains("error")) {
            return Result.failure(ApiException("Логин не завершился (нет cookie сессии). Попробуй CLI-токен."))
        }
        return Result.failure(ApiException("Логин не удался (HTTP ${resp.code()}). Проверь URL и пароль."))
    }

    suspend fun loginWithCliToken(cliToken: String) {
        store.cliToken = cliToken
    }

    suspend fun logout() {
        runCatching { api.logout() }
        storeContext?.let { com.hydra.panel.alerts.AlertPollWorker.cancel(it) }
        store.clearAuth()
        reset()
    }

    /** Включить фоновый опрос алертов (нужен Context — прокидывается через [bindContext]). */
    fun startPushMonitoring() {
        storeContext?.let { com.hydra.panel.alerts.AlertPollWorker.schedule(it, store.pollIntervalMin) }
    }

    private var storeContext: Context? = null

    fun bindContext(context: Context) {
        storeContext = context.applicationContext
        com.hydra.panel.alerts.AlertNotifier.ensureChannels(context)
    }

    // ───────── Servers ─────────
    suspend fun listServers(): List<Server> = unwrap(api.listServers()).servers

    suspend fun getServer(id: String): Server = unwrap(api.getServer(id))

    suspend fun createServer(
        id: String, ip: String, location: String, city: String,
        bandwidth: Int, sshPort: Int, sshPassword: String,
    ): String = resultFromRedirect(
        api.createServerForm(id, ip, location, city, bandwidth, sshPort, sshPassword)
    )

    suspend fun deleteServer(id: String): String = resultFromRedirect(api.deleteServer(id))

    suspend fun installProtocols(id: String): String = resultFromRedirect(api.installProtocols(id))

    suspend fun installAgent(id: String): String = resultFromRedirect(api.installAgent(id))

    suspend fun reboot(id: String): String = resultFromRedirect(api.rebootServer(id))

    suspend fun restartService(id: String, protocol: String): String =
        resultFromRedirect(api.restartService(id, protocol))

    suspend fun rotateAgentToken(id: String): String = resultFromRedirect(api.rotateToken(id))

    suspend fun syncTime(id: String): String = resultFromRedirect(api.syncTime(id))

    suspend fun testSsh(id: String): String = resultFromRedirect(api.testSsh(id))

    // ───────── Metrics / detail page ─────────
    /** Метрики берутся со страницы деталей сервера: панель рендерит Jinja-контекст;
     *  для надёжности используем SQL-консоль (/database/query) — она отдаёт SELECT в base64-JSON. */
    suspend fun serverMetrics(id: String, limit: Int = 100): List<MetricPoint> {
        val rows = sqlQuery("SELECT timestamp, cpu_percent, memory_percent, network_rx_mbps, network_tx_mbps, connections_wdtt, connections_aivpn, connections_awg, status_wdtt, status_aivpn, status_awg, latency_ms FROM metrics WHERE server_id='$id' ORDER BY timestamp DESC LIMIT $limit")
        return rows.map { r ->
            MetricPoint(
                serverId = id,
                timestamp = r["timestamp"],
                cpuPercent = r["cpu_percent"]?.toDoubleOrNull(),
                memoryPercent = r["memory_percent"]?.toDoubleOrNull(),
                networkRxMbps = r["network_rx_mbps"]?.toDoubleOrNull(),
                networkTxMbps = r["network_tx_mbps"]?.toDoubleOrNull(),
                connectionsWdtt = r["connections_wdtt"]?.toIntOrNull(),
                connectionsAivpn = r["connections_aivpn"]?.toIntOrNull(),
                connectionsAwg = r["connections_awg"]?.toIntOrNull(),
                statusWdtt = r["status_wdtt"],
                statusAivpn = r["status_aivpn"],
                statusAwg = r["status_awg"],
                latencyMs = r["latency_ms"]?.toDoubleOrNull(),
            )
        }
    }

    suspend fun protocols(id: String): List<ProtocolInstance> {
        val rows = sqlQuery("SELECT protocol, port, max_connections, status FROM protocol_instances WHERE server_id='$id'")
        return rows.map { ProtocolInstance(
            serverId = id,
            protocol = it["protocol"] ?: "?",
            port = it["port"]?.toIntOrNull(),
            maxConnections = it["max_connections"]?.toIntOrNull(),
            status = it["status"],
        ) }
    }

    suspend fun activeAlerts(): List<Alert> {
        val rows = try {
            sqlQuery("SELECT id, server_id, level, message, triggered_at FROM alerts WHERE resolved_at IS NULL ORDER BY triggered_at DESC LIMIT 50")
        } catch (_: Exception) { emptyList() }
        return rows.map { Alert(
            id = it["id"]?.toLongOrNull() ?: 0,
            serverId = it["server_id"],
            level = it["level"] ?: "info",
            message = it["message"],
            triggeredAt = it["triggered_at"],
        ) }
    }

    // ───────── Tokens ─────────
    suspend fun listTokens(id: String): TokenListResponse = unwrap(api.listTokens(id))
    suspend fun revokeTokens(id: String): String = resultFromRedirect(api.revokeTokensApi(id))

    // ───────── Forecast & reports ─────────
    suspend fun forecast(id: String): ForecastResult = unwrap(api.forecast(id))
    suspend fun allForecasts(): List<ForecastResult> = unwrap(api.allForecasts()).forecasts

    suspend fun weeklyReports(): List<WeeklyReport> = unwrap(api.weeklyReports()).reports

    suspend fun generateWeeklyReport(): String {
        val body = unwrap(api.generateWeeklyReport()).string()
        return if (body.contains("week_start")) "Отчёт за последнюю неделю сгенерирован" else body.take(200)
    }

    suspend fun catchUpReports(): String {
        val body = unwrap(api.catchUpReports()).string()
        val n = Regex("\"reports_generated\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)
        return if (n != null) "До GENERIRОВАНО отчётов: $n" else body.take(200)
    }

    // ───────── Clients (people) via SQL ─────────
    suspend fun listClients(): List<ClientPerson> {
        val rows = sqlQuery(
            """SELECT cl.id, cl.telegram_id, cl.tg_username, cl.display_name, cl.status, cl.notes, cl.created_at,
               (SELECT COUNT(*) FROM access_keys ak WHERE ak.client_id = cl.id) AS keys_total,
               (SELECT COUNT(*) FROM access_keys ak WHERE ak.client_id = cl.id AND ak.revoked_at IS NULL
                  AND (ak.expires_at IS NULL OR ak.expires_at > datetime('now'))) AS keys_active
               FROM clients cl ORDER BY cl.created_at DESC"""
        )
        return rows.map { ClientPerson(
            id = it["id"]?.toLongOrNull() ?: 0,
            telegramId = it["telegram_id"]?.toLongOrNull(),
            tgUsername = it["tg_username"],
            displayName = it["display_name"] ?: "—",
            status = it["status"] ?: "active",
            notes = it["notes"],
            createdAt = it["created_at"],
            keysTotal = it["keys_total"]?.toIntOrNull(),
            keysActive = it["keys_active"]?.toIntOrNull(),
        ) }
    }

    suspend fun clientKeys(clientId: Long): List<AccessKey> {
        val rows = sqlQuery(
            """SELECT key_id, expires_at, max_devices, revoked_at, created_at,
               (SELECT COUNT(*) FROM device_registrations dr WHERE dr.key_id = access_keys.key_id) AS device_count
               FROM access_keys WHERE client_id=$clientId ORDER BY created_at DESC"""
        )
        return rows.map { AccessKey(
            keyId = it["key_id"] ?: "?",
            clientId = clientId,
            expiresAt = it["expires_at"],
            maxDevices = it["max_devices"]?.toIntOrNull(),
            revokedAt = it["revoked_at"],
            createdAt = it["created_at"],
            deviceCount = it["device_count"]?.toIntOrNull(),
        ) }
    }

    suspend fun clientDevices(clientId: Long): List<DeviceRegistration> {
        val rows = sqlQuery(
            """SELECT dr.key_id, dr.device_id, dr.device_name, dr.last_ip, dr.registered_at, dr.last_seen_at
               FROM device_registrations dr JOIN access_keys ak ON ak.key_id = dr.key_id
               WHERE ak.client_id=$clientId ORDER BY dr.registered_at DESC"""
        )
        return rows.map { DeviceRegistration(
            keyId = it["key_id"] ?: "?",
            deviceId = it["device_id"] ?: "?",
            deviceName = it["device_name"],
            lastIp = it["last_ip"],
            registeredAt = it["registered_at"],
            lastSeenAt = it["last_seen_at"],
        ) }
    }

    suspend fun createClient(name: String, tgId: String, tgUser: String, notes: String): String =
        resultFromRedirect(api.createClient(name, tgId, tgUser, notes))

    suspend fun updateClient(id: Long, name: String, tgId: String, tgUser: String, notes: String): String =
        resultFromRedirect(api.updateClient(id, name, tgId, tgUser, notes))

    suspend fun toggleBlockClient(id: Long): String = resultFromRedirect(api.toggleBlockClient(id))

    suspend fun deleteClient(id: Long): String = resultFromRedirect(api.deleteClient(id))

    // ───────── Keys ─────────
    suspend fun listKeys(): List<AccessKey> {
        val rows = sqlQuery(
            """SELECT ak.key_id, ak.client_id, ak.expires_at, ak.max_devices, ak.revoked_at, ak.created_at,
               (SELECT COUNT(*) FROM device_registrations dr WHERE dr.key_id = ak.key_id) AS device_count,
               cl.display_name AS client_name, cl.telegram_id AS client_tg, cl.id AS client_pk
               FROM access_keys ak LEFT JOIN clients cl ON cl.id = ak.client_id
               ORDER BY ak.created_at DESC"""
        )
        return rows.map { AccessKey(
            keyId = it["key_id"] ?: "?",
            clientId = it["client_id"]?.toLongOrNull(),
            expiresAt = it["expires_at"],
            maxDevices = it["max_devices"]?.toIntOrNull(),
            revokedAt = it["revoked_at"],
            createdAt = it["created_at"],
            deviceCount = it["device_count"]?.toIntOrNull(),
            clientName = it["client_name"],
            clientTg = it["client_tg"]?.toLongOrNull(),
            clientPk = it["client_pk"]?.toLongOrNull(),
        ) }
    }

    suspend fun createKey(clientId: Long, days: Int, maxDevices: Int, customDate: String = ""): String =
        resultFromRedirect(api.createKeyForClient(clientId, days, maxDevices, customDate))

    suspend fun extendKey(keyId: String, days: Int): String = resultFromRedirect(api.extendKey(keyId, days))
    suspend fun revokeKey(keyId: String): String = resultFromRedirect(api.revokeKey(keyId))
    suspend fun restoreKey(keyId: String): String = resultFromRedirect(api.restoreKey(keyId))
    suspend fun deleteKey(keyId: String): String = resultFromRedirect(api.deleteKey(keyId))

    suspend fun downloadKeyConf(keyId: String): String =
        unwrap(api.downloadKeyConf(keyId)).string()

    // ───────── Devices ─────────
    suspend fun addDevice(keyId: String, deviceId: String, name: String, ip: String): String =
        resultFromRedirect(api.createDevice(keyId, deviceId, name, ip))

    suspend fun deleteDevice(keyId: String, deviceId: String): String =
        resultFromRedirect(api.deleteDevice(keyId, deviceId))

    // ───────── Logs & configs (HTML → text) ─────────
    suspend fun serverLogs(id: String, source: String): Pair<String, List<String>> {
        val html = unwrap(api.serverLogsPage(id, source)).string()
        val logs = HtmlParser.extractByClassPre(html) ?: HtmlParser.textContent(html)
        val sources = listOf("agent", "wdtt", "aivpn", "awg")
        return logs to sources
    }

    /** Лог ошибок самой панели (v0.7.1): файл HYDRA_LOG_FILE + journalctl сервиса. */
    suspend fun panelErrorLog(lines: Int = 300): PanelLogResponse = unwrap(api.panelLog(lines))

    data class ConfigPage(
        val listing: String,
        val files: Map<String, String>,
        val awgParams: Map<String, String>,
        val rawContent: String,
        val editPath: String,
        val error: String?,
    )

    suspend fun serverConfigs(id: String, source: String): ConfigPage {
        val html = unwrap(api.serverConfigsPage(id, source)).string()
        val text = HtmlParser.textContent(html)
        val fields = HtmlParser.formFields(html)
        val raw = fields["raw_config"] ?: ""
        val listing = text
        return ConfigPage(
            listing = listing,
            files = emptyMap(),
            awgParams = fields.filter { (k, _) -> k.length <= 3 && k.first().isUpperCase() },
            rawContent = raw,
            editPath = fields["edit_path"] ?: "",
            error = null,
        )
    }

    suspend fun applyConfigRaw(id: String, source: String, rawConfig: String): String {
        val parts = listOf(
            MultipartBody.Part.createFormData("source", source),
            MultipartBody.Part.createFormData("raw_config", rawConfig),
        )
        return resultFromRedirect(api.applyConfig(id, parts))
    }

    suspend fun applyConfigAwg(id: String, params: Map<String, String>): String {
        val parts = mutableListOf(MultipartBody.Part.createFormData("source", "awg"))
        params.forEach { (k, v) ->
            if (v.isNotBlank()) parts.add(MultipartBody.Part.createFormData(k, v))
        }
        return resultFromRedirect(api.applyConfig(id, parts))
    }

    // ───────── Database console ─────────
    /** Выполняет read-only запрос через веб-консоль и возвращает строки. */
    suspend fun sqlQuery(query: String): List<Map<String, String?>> {
        val resp = api.databaseQuery(query)
        if (resp.code() == 401) throw ApiException("Сессия истекла — войди заново")
        val loc = resp.headers()["Location"] ?: ""
        if ("error=" in loc) {
            val e = Regex("error=([^&]+)").find(loc)!!.groupValues[1]
            throw ApiException(java.net.URLDecoder.decode(e, "UTF-8"))
        }
        val b64 = Regex("result=([^&]+)").find(loc)?.groupValues?.get(1)
            ?: throw ApiException("Нет результата")
        val jsonStr = String(
            java.util.Base64.getDecoder().decode(java.net.URLDecoder.decode(b64, "UTF-8"))
        )
        val arr = Json.parseToJsonElement(jsonStr).jsonArray
        return arr.map { el ->
            el.jsonObject.mapValues { (_, v) -> v.jsonPrimitive.contentOrNullSafe() }
        }
    }

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    suspend fun listTables(): List<String> =
        sqlQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .mapNotNull { it["name"] }

    suspend fun backupNow(): String {
        val msg = resultFromRedirect(api.backupNow())
        return msg
    }

    suspend fun listBackups(): List<BackupInfo> {
        val html = unwrap(api.databasePage()).string()
        // Бекапы рендерятся таблицей: имя, размер, дата, auto/manual
        val rowRe = Regex("(?is)<tr[^>]*>(.*?)</tr>")
        val cellRe = Regex("(?is)<t[dh][^>]*>(.*?)</t[dh]>")
        val out = mutableListOf<BackupInfo>()
        for (row in rowRe.findAll(html)) {
            val cells = cellRe.findAll(row.value).map {
                HtmlParser.decodeEntities(it.groupValues[1].replace(Regex("(?s)<[^>]+>"), "")).trim()
            }.toList()
            if (cells.size >= 3 && cells.any { it.endsWith(".db") }) {
                val name = cells.first { it.endsWith(".db") }
                val size = cells.getOrNull(1)?.replace(",", ".")?.toDoubleOrNull() ?: 0.0
                out.add(BackupInfo(
                    name = name, sizeKb = size, mtime = cells.getOrNull(2),
                    auto = name.startsWith("hydra-backup-"),
                ))
            }
        }
        return out
    }

    suspend fun restoreBackup(name: String): String = resultFromRedirect(api.restoreBackup(name))
    suspend fun deleteBackup(name: String): String = resultFromRedirect(api.deleteBackup(name))

    suspend fun downloadBackup(name: String): ByteArray = unwrap(api.backupFile(name)).bytes()

    suspend fun uploadBackup(fileName: String, bytes: ByteArray): String {
        val body = bytes.toRequestBody("application/octet-stream".toMediaType())
        val part = MultipartBody.Part.createFormData("file", fileName, body)
        return resultFromRedirect(api.uploadBackup(part))
    }

    // ───────── Settings panel.yaml ─────────
    suspend fun loadSettingsYaml(): String {
        val html = unwrap(api.settingsPage()).string()
        return HtmlParser.formFields(html)["content"] ?: ""
    }

    suspend fun saveSettingsYaml(content: String): String = resultFromRedirect(api.saveSettings(content))

    // ───────── Backup settings ─────────
    suspend fun backupSettingsSummary(): String {
        val html = unwrap(api.backupSettingsPage()).string()
        return HtmlParser.textContent(html).lineSequence()
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .take(4000)
    }

    suspend fun testGithub(repo: String, token: String): String =
        resultFromRedirect(api.testGithubBackup(repo, token))

    suspend fun saveGithub(repo: String, token: String, keep: Int): String =
        resultFromRedirect(api.saveGithubBackup(repo, token, keep))

    suspend fun pushBackupNow(): String = resultFromRedirect(api.pushBackupNow())
    suspend fun deleteGithubCreds(): String = resultFromRedirect(api.deleteGithubCreds())
    suspend fun setRecoveryPassphrase(pass: String): String =
        resultFromRedirect(api.setRecoveryPassphrase(pass))

    // ───────── Sessions & CLI token ─────────
    suspend fun listSessions(): List<SessionInfo> {
        val rows = sqlQuery("SELECT token_hash, user_agent, ip, remember, created_at, expires_at, last_seen_at FROM sessions ORDER BY last_seen_at DESC")
        return rows.map { SessionInfo(
            tokenHash = it["token_hash"] ?: "?",
            userAgent = it["user_agent"],
            ip = it["ip"],
            remember = it["remember"]?.toIntOrNull(),
            createdAt = it["created_at"],
            expiresAt = it["expires_at"],
            lastSeenAt = it["last_seen_at"],
        ) }
    }

    suspend fun revokeSession(hash: String): String {
        val r = api.revokeSession(hash)
        // отзыв текущей сессии редиректит на /login и чистит cookie — выходим и тут
        if ((r.headers()["Location"] ?: "").contains("/login")) {
            store.clearAuth(); reset()
            return "Текущая сессия отозвана (приложение разлогинено)"
        }
        return resultFromRedirect(r)
    }

    suspend fun revokeAllSessions(): String {
        val r = api.revokeAllSessions()
        store.clearAuth(); reset()
        return "Все сессии отозваны — нужно войти заново"
    }

    /** Ротация CLI-токена: ответ — HTML setup_done с новым токеном внутри. */
    suspend fun rotateCliToken(): String {
        val html = unwrap(api.rotateCliToken()).string()
        val token = Regex("[A-Za-z0-9_-]{40,}").find(HtmlParser.textContent(html))?.value
        return token ?: "Токен повёрнут, но не удалось считать его из ответа — проверь веб-панель"
    }

}
