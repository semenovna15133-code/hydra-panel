package com.hydra.panel.util

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Безопасное хранилище сессии панели.
 * Хранит: базовый URL, session-cookie-токен (hydra_session), CLI-токен (x-hydra-token),
 * флаг «запомнить», флаг доверия self-signed сертификату.
 */
class SessionStore(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context, "hydra_session_store", masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE_URL, v.trimEnd('/')).apply()

    var sessionToken: String?
        get() = prefs.getString(KEY_SESSION, null)
        set(v) = prefs.edit().putString(KEY_SESSION, v).apply()

    var cliToken: String?
        get() = prefs.getString(KEY_CLI, null)
        set(v) = prefs.edit().putString(KEY_CLI, v).apply()

    var trustSelfSigned: Boolean
        get() = prefs.getBoolean(KEY_TRUST, false)
        set(v) = prefs.edit().putBoolean(KEY_TRUST, v).apply()

    val isLoggedIn: Boolean
        get() = !sessionToken.isNullOrEmpty() || !cliToken.isNullOrEmpty()

    // ── Настройки фонового мониторинга / пушей ──
    var pushEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSH_ON, true)
        set(v) = prefs.edit().putBoolean(KEY_PUSH_ON, v).apply()

    /** Порог CPU % (0..100), при превышении — локальный пуш. */
    var alertCpuPct: Int
        get() = prefs.getInt(KEY_ALERT_CPU, 85)
        set(v) = prefs.edit().putInt(KEY_ALERT_CPU, v).apply()

    /** Порог RAM %. */
    var alertRamPct: Int
        get() = prefs.getInt(KEY_ALERT_RAM, 90)
        set(v) = prefs.edit().putInt(KEY_ALERT_RAM, v).apply()

    /** Порог латентности ms. */
    var alertLatencyMs: Int
        get() = prefs.getInt(KEY_ALERT_LAT, 300)
        set(v) = prefs.edit().putInt(KEY_ALERT_LAT, v).apply()

    /** Интервал опроса, минуты. */
    var pollIntervalMin: Int
        get() = prefs.getInt(KEY_POLL_MIN, 15)
        set(v) = prefs.edit().putInt(KEY_POLL_MIN, v).apply()

    /** Сохранённый набор id алертов панели (дедупликация пушей). */
    var seenAlertIds: Set<String>
        get() = prefs.getStringSet(KEY_SEEN_ALERTS, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_SEEN_ALERTS, v).apply()

    fun clearAuth() {
        prefs.edit().remove(KEY_SESSION).remove(KEY_CLI).apply()
    }

    /** Разбор Set-Cookie из ответа /login для ручного сохранения токена. */
    fun ingestSetCookie(setCookieHeaders: List<String>) {
        for (raw in setCookieHeaders) {
            val cookie = runCatching {
                val url = baseUrl.toHttpUrlOrNull() ?: return
                Cookie.parse(url, raw)
            }.getOrNull() ?: continue
            if (cookie.name == "hydra_session") sessionToken = cookie.value
        }
    }

    companion object {
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_SESSION = "session_token"
        private const val KEY_CLI = "cli_token"
        private const val KEY_TRUST = "trust_self_signed"
        private const val KEY_PUSH_ON = "push_enabled"
        private const val KEY_ALERT_CPU = "alert_cpu_pct"
        private const val KEY_ALERT_RAM = "alert_ram_pct"
        private const val KEY_ALERT_LAT = "alert_latency_ms"
        private const val KEY_POLL_MIN = "poll_interval_min"
        private const val KEY_SEEN_ALERTS = "seen_alert_ids"
    }
}
