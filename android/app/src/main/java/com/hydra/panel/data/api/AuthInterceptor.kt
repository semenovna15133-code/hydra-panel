package com.hydra.panel.data.api

import com.hydra.panel.util.SessionStore
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Подставляет авторизацию в каждый запрос:
 *  - Cookie: hydra_session=<token>  (основной путь, как в браузере)
 *  - x-hydra-token: <cli_token>     (запасной путь — CLI-токен панели)
 * Также ловит Set-Cookie при успешном /login и сохраняет токен в SessionStore.
 */
class AuthInterceptor(private val store: SessionStore) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val builder = original.newBuilder()
        store.sessionToken?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("Cookie", "hydra_session=$it")
        }
        store.cliToken?.takeIf { it.isNotBlank() }?.let {
            builder.addHeader("x-hydra-token", it)
        }
        // User-Agent, чтобы панель различала мобильные сессии в списке sessions
        builder.addHeader("User-Agent", "HydraPanelAndroid/0.1 (Compose)")

        val response = chain.proceed(builder.build())

        // Сохраняем hydra_session из Set-Cookie (ответ /login или /setup)
        val setCookies = response.headers("Set-Cookie")
        if (setCookies.isNotEmpty()) {
            store.ingestSetCookie(setCookies)
        }
        return response
    }
}
