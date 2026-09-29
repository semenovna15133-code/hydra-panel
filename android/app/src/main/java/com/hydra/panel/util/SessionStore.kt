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
    }
}
