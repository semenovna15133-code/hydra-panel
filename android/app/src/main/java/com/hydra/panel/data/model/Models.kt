package com.hydra.panel.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DTO, зеркалящие схему БД панели (hydra/core/db.py + auth.py) и JSON-ответы /api/v1.
 * Все поля опциональны — панель отдаёт SQLite-строки как есть.
 */

@Serializable
data class Server(
    val id: String,
    val location: String? = null,
    val city: String? = null,
    val ip: String,
    @SerialName("ssh_port") val sshPort: Int? = 22,
    @SerialName("bandwidth_mbps") val bandwidthMbps: Int? = null,
    val status: String? = "unknown",
    @SerialName("agent_installed") val agentInstalled: Boolean? = false,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ServersResponse(val servers: List<Server> = emptyList())

@Serializable
data class MetricPoint(
    @SerialName("server_id") val serverId: String? = null,
    val timestamp: String? = null,
    @SerialName("cpu_percent") val cpuPercent: Double? = null,
    @SerialName("memory_percent") val memoryPercent: Double? = null,
    @SerialName("network_rx_mbps") val networkRxMbps: Double? = null,
    @SerialName("network_tx_mbps") val networkTxMbps: Double? = null,
    @SerialName("connections_wdtt") val connectionsWdtt: Int? = null,
    @SerialName("connections_aivpn") val connectionsAivpn: Int? = null,
    @SerialName("connections_awg") val connectionsAwg: Int? = null,
    @SerialName("status_wdtt") val statusWdtt: String? = null,
    @SerialName("status_aivpn") val statusAivpn: String? = null,
    @SerialName("status_awg") val statusAwg: String? = null,
    @SerialName("latency_ms") val latencyMs: Double? = null,
) {
    val totalConnections: Int
        get() = (connectionsWdtt ?: 0) + (connectionsAivpn ?: 0) + (connectionsAwg ?: 0)
}

@Serializable
data class ProtocolInstance(
    val id: Int? = null,
    @SerialName("server_id") val serverId: String? = null,
    val protocol: String,
    val port: Int? = null,
    @SerialName("max_connections") val maxConnections: Int? = null,
    val status: String? = null,
)

@Serializable
data class AccessKey(
    @SerialName("key_id") val keyId: String,
    @SerialName("user_id") val userId: Long? = null,
    @SerialName("client_id") val clientId: Long? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("max_devices") val maxDevices: Int? = 3,
    @SerialName("revoked_at") val revokedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("device_count") val deviceCount: Int? = null,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("client_tg") val clientTg: Long? = null,
    @SerialName("client_pk") val clientPk: Long? = null,
)

@Serializable
data class ClientPerson(
    val id: Long,
    @SerialName("telegram_id") val telegramId: Long? = null,
    @SerialName("tg_username") val tgUsername: String? = null,
    @SerialName("display_name") val displayName: String,
    val status: String = "active",
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("keys_total") val keysTotal: Int? = null,
    @SerialName("keys_active") val keysActive: Int? = null,
)

@Serializable
data class DeviceRegistration(
    val id: Long? = null,
    @SerialName("key_id") val keyId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("last_ip") val lastIp: String? = null,
    @SerialName("registered_at") val registeredAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class WeeklyReport(
    val id: Long? = null,
    @SerialName("week_start") val weekStart: String,
    @SerialName("week_end") val weekEnd: String,
    @SerialName("total_keys") val totalKeys: Int? = null,
    @SerialName("total_devices") val totalDevices: Int? = null,
    @SerialName("avg_load") val avgLoad: Double? = null,
    @SerialName("peak_load") val peakLoad: Double? = null,
    @SerialName("protocol_distribution") val protocolDistribution: String? = null,
    val recommendation: String? = null,
    @SerialName("generated_at") val generatedAt: String? = null,
)

@Serializable
data class Alert(
    val id: Long,
    @SerialName("server_id") val serverId: String? = null,
    val level: String,
    val message: String? = null,
    @SerialName("triggered_at") val triggeredAt: String? = null,
    @SerialName("resolved_at") val resolvedAt: String? = null,
)

@Serializable
data class AgentTokenInfo(
    @SerialName("token_hash_prefix") val tokenHashPrefix: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("rotated_at") val rotatedAt: String? = null,
    val active: Boolean = false,
)

@Serializable
data class TokenListResponse(
    @SerialName("server_id") val serverId: String,
    @SerialName("active_count") val activeCount: Int = 0,
    val tokens: List<AgentTokenInfo> = emptyList(),
)

@Serializable
data class ForecastResult(
    @SerialName("server_id") val serverId: String? = null,
    val status: String? = null,
    val message: String? = null,
    @SerialName("current_cpu") val currentCpu: Double? = null,
    @SerialName("current_memory") val currentMemory: Double? = null,
    @SerialName("trend_cpu_per_day") val trendCpuPerDay: Double? = null,
    @SerialName("days_until_critical") val daysUntilCritical: Int? = null,
    val recommendation: String? = null,
)

@Serializable
data class AllForecasts(val forecasts: List<ForecastResult> = emptyList())

@Serializable
data class SessionInfo(
    @SerialName("token_hash") val tokenHash: String,
    @SerialName("user_agent") val userAgent: String? = null,
    val ip: String? = null,
    val remember: Int? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
)

@Serializable
data class BackupInfo(
    val name: String,
    @SerialName("size_kb") val sizeKb: Double = 0.0,
    val mtime: String? = null,
    val auto: Boolean = false,
)



@Serializable
data class PanelLogResponse(
    val version: String? = null,
    @SerialName("log_file") val logFile: String? = null,
    val exists: Boolean = false,
    val lines: List<String> = emptyList(),
    val journal: String = "",
    val note: String = "",
)
