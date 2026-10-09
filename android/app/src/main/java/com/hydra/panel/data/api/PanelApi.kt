package com.hydra.panel.data.api

import com.hydra.panel.data.model.*
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

/**
 * Retrofit-интерфейс Hydra Panel.
 *  1) JSON /api/v1 — нативные API-эндпоинты панели (приоритет).
 *  2) form/HTML веб-эндпоинты — те же вызовы, что делает браузер; ответы приходят
 *     как 303 с Location ?msg=... (клиент читает Location сам, редиректы отключены).
 * Авторизация — AuthInterceptor: cookie hydra_session ИЛИ заголовок x-hydra-token.
 */
interface PanelApi {

    // ───────── Health / Auth ─────────
    @GET("health")
    suspend fun health(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("login")
    @Headers("Accept: text/html")
    suspend fun login(
        @Field("password") password: String,
        @Field("remember") remember: String, // "on" | ""
    ): Response<ResponseBody>

    /** Первая настройка: если админа ещё нет, панель создаёт его и выдаёт сессию + CLI-токен. */
    @FormUrlEncoded
    @GET("setup")
    suspend fun setupPage(): Response<ResponseBody>

    @POST("setup")
    @Headers("Accept: text/html")
    suspend fun setup(
        @Field("password") password: String,
        @Field("password2") password2: String,
    ): Response<ResponseBody>

    @POST("logout")
    suspend fun logout(): Response<ResponseBody>

    // ───────── Servers: JSON API ─────────
    @GET("api/v1/servers")
    suspend fun listServers(): Response<ServersResponse>

    @GET("api/v1/servers/{id}")
    suspend fun getServer(@Path("id") serverId: String): Response<Server>

    // ───────── Tokens: JSON API ─────────
    @GET("api/v1/servers/{id}/tokens")
    suspend fun listTokens(@Path("id") serverId: String): Response<TokenListResponse>

    @POST("api/v1/servers/{id}/tokens/revoke")
    suspend fun revokeTokensApi(@Path("id") serverId: String): Response<ResponseBody>

    // ───────── Forecast & reports: JSON API ─────────
    @GET("api/v1/forecast/{id}")
    suspend fun forecast(@Path("id") serverId: String, @Query("days") days: Int = 7): Response<ForecastResult>

    @GET("api/v1/forecast")
    suspend fun allForecasts(): Response<AllForecasts>

    @GET("api/v1/reports/weekly")
    suspend fun weeklyReports(@Query("limit") limit: Int = 20): Response<WeeklyReportsResponse>

    @POST("api/v1/reports/weekly/generate")
    suspend fun generateWeeklyReport(
        @Query("week_start") start: String? = null,
        @Query("week_end") end: String? = null,
    ): Response<ResponseBody>

    @POST("api/v1/reports/weekly/catch-up")
    suspend fun catchUpReports(): Response<ResponseBody>

    // ───────── Keys standalone: JSON API ─────────
    @FormUrlEncoded
    @POST("api/v1/keys/create")
    suspend fun createKeyApi(
        @Field("days_valid") daysValid: Int,
        @Field("max_devices") maxDevices: Int,
    ): Response<ResponseBody>

    @POST("api/v1/keys/{keyId}/revoke")
    suspend fun revokeKeyApi(@Path("keyId") keyId: String): Response<ResponseBody>

    // ───────── Servers (web actions, form-encoded) ─────────
    @FormUrlEncoded
    @POST("servers/create")
    suspend fun createServerForm(
        @Field("server_id") serverId: String,
        @Field("ip") ip: String,
        @Field("location") location: String,
        @Field("city") city: String,
        @Field("bandwidth_mbps") bandwidthMbps: Int,
        @Field("ssh_port") sshPort: Int,
        @Field("ssh_password") sshPassword: String,
    ): Response<ResponseBody>

    @POST("servers/{id}/delete")
    suspend fun deleteServer(@Path("id") serverId: String): Response<ResponseBody>

    @POST("servers/{id}/install-protocols")
    suspend fun installProtocols(@Path("id") serverId: String): Response<ResponseBody>

    @POST("servers/{id}/install-agent")
    suspend fun installAgent(@Path("id") serverId: String): Response<ResponseBody>

    @POST("servers/{id}/reboot")
    suspend fun rebootServer(@Path("id") serverId: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("servers/{id}/restart-service")
    suspend fun restartService(
        @Path("id") serverId: String,
        @Field("protocol") protocol: String,
    ): Response<ResponseBody>

    @POST("servers/{id}/rotate-token")
    suspend fun rotateToken(@Path("id") serverId: String): Response<ResponseBody>

    @POST("servers/{id}/sync-time")
    suspend fun syncTime(@Path("id") serverId: String): Response<ResponseBody>

    @POST("servers/{id}/test-ssh")
    suspend fun testSsh(@Path("id") serverId: String): Response<ResponseBody>

    // ───────── Logs (HTML → текст) ─────────
    @GET("servers/{id}/logs")
    @Headers("Accept: text/html")
    suspend fun serverLogsPage(@Path("id") serverId: String, @Query("source") source: String): Response<ResponseBody>

    // ───────── Панель: собственный лог ошибок (v0.7.1) ─────────
    @GET("api/v1/panel/log")
    suspend fun panelLog(@Query("lines") lines: Int = 300): Response<PanelLogResponse>

    // ───────── Configs (HTML + apply) ─────────
    @GET("servers/{id}/configs")
    @Headers("Accept: text/html")
    suspend fun serverConfigsPage(@Path("id") serverId: String, @Query("source") source: String): Response<ResponseBody>

    @Multipart
    @POST("servers/{id}/config/apply")
    suspend fun applyConfig(
        @Path("id") serverId: String,
        @Part parts: List<MultipartBody.Part>,
    ): Response<ResponseBody>

    // ───────── Clients (people) ─────────
    @FormUrlEncoded
    @POST("clients/create")
    suspend fun createClient(
        @Field("display_name") displayName: String,
        @Field("telegram_id") telegramId: String,
        @Field("tg_username") tgUsername: String,
        @Field("notes") notes: String,
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("clients/{id}/update")
    suspend fun updateClient(
        @Path("id") clientId: Long,
        @Field("display_name") displayName: String,
        @Field("telegram_id") telegramId: String,
        @Field("tg_username") tgUsername: String,
        @Field("notes") notes: String,
    ): Response<ResponseBody>

    @POST("clients/{id}/toggle-block")
    suspend fun toggleBlockClient(@Path("id") clientId: Long): Response<ResponseBody>

    @POST("clients/{id}/delete")
    suspend fun deleteClient(@Path("id") clientId: Long): Response<ResponseBody>

    // ───────── Keys (web) ─────────
    @FormUrlEncoded
    @POST("keys/create")
    suspend fun createKeyForClient(
        @Field("client_id") clientId: Long,
        @Field("expire_days") expireDays: Int,
        @Field("max_devices") maxDevices: Int,
        @Field("custom_date") customDate: String = "",
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("keys/{keyId}/extend")
    suspend fun extendKey(@Path("keyId") keyId: String, @Field("days") days: Int): Response<ResponseBody>

    @POST("keys/{keyId}/revoke")
    suspend fun revokeKey(@Path("keyId") keyId: String): Response<ResponseBody>

    @POST("keys/{keyId}/restore")
    suspend fun restoreKey(@Path("keyId") keyId: String): Response<ResponseBody>

    @POST("keys/{keyId}/delete")
    suspend fun deleteKey(@Path("keyId") keyId: String): Response<ResponseBody>

    @GET("keys/{keyId}/download")
    suspend fun downloadKeyConf(@Path("keyId") keyId: String): Response<ResponseBody>

    // ───────── Devices ─────────
    @FormUrlEncoded
    @POST("devices/create")
    suspend fun createDevice(
        @Field("key_id") keyId: String,
        @Field("device_id") deviceId: String,
        @Field("device_name") deviceName: String,
        @Field("last_ip") lastIp: String,
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("devices/delete")
    suspend fun deleteDevice(
        @Field("key_id") keyId: String,
        @Field("device_id") deviceId: String,
    ): Response<ResponseBody>

    // ───────── Database console ─────────
    @GET("database")
    @Headers("Accept: text/html")
    suspend fun databasePage(@Query("result") result: String? = null): Response<ResponseBody>

    @FormUrlEncoded
    @POST("database/query")
    suspend fun databaseQuery(@Field("query") query: String): Response<ResponseBody>

    @POST("database/backup-now")
    suspend fun backupNow(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("database/restore")
    suspend fun restoreBackup(@Field("name") name: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("database/backup-delete")
    suspend fun deleteBackup(@Field("name") name: String): Response<ResponseBody>

    @GET("database/backup-file")
    suspend fun backupFile(@Query("name") name: String): Response<ResponseBody>

    @Multipart
    @POST("database/backup-upload")
    suspend fun uploadBackup(@Part file: MultipartBody.Part): Response<ResponseBody>

    // ───────── Settings (panel.yaml) ─────────
    @GET("settings")
    @Headers("Accept: text/html")
    suspend fun settingsPage(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("settings")
    suspend fun saveSettings(@Field("content") content: String): Response<ResponseBody>

    // ───────── Backup settings (GitHub offsite) ─────────
    @GET("settings/backup")
    @Headers("Accept: text/html")
    suspend fun backupSettingsPage(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("settings/backup/test")
    suspend fun testGithubBackup(@Field("repo") repo: String, @Field("token") token: String): Response<ResponseBody>

    @FormUrlEncoded
    @POST("settings/backup/save")
    suspend fun saveGithubBackup(
        @Field("repo") repo: String,
        @Field("token") token: String,
        @Field("keep") keep: Int,
    ): Response<ResponseBody>

    @POST("settings/backup/push-now")
    suspend fun pushBackupNow(): Response<ResponseBody>

    @POST("settings/backup/delete")
    suspend fun deleteGithubCreds(): Response<ResponseBody>

    @FormUrlEncoded
    @POST("settings/backup/passphrase")
    suspend fun setRecoveryPassphrase(@Field("passphrase") passphrase: String): Response<ResponseBody>

    // ───────── Sessions & CLI token ─────────
    @FormUrlEncoded
    @POST("sessions/revoke")
    suspend fun revokeSession(@Field("token_hash") tokenHash: String): Response<ResponseBody>

    @POST("sessions/revoke-all")
    suspend fun revokeAllSessions(): Response<ResponseBody>

    @POST("cli/rotate")
    @Headers("Accept: text/html")
    suspend fun rotateCliToken(): Response<ResponseBody>
}

@kotlinx.serialization.Serializable
data class WeeklyReportsResponse(val reports: List<WeeklyReport> = emptyList())
