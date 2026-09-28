package com.hydra.panel.data.api

import android.content.Context
import com.hydra.panel.util.SessionStore
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.X509TrustManager

/**
 * Диод-настройка OkHttp:
 *  - AuthInterceptor: подставляет cookie hydra_session и/или заголовок x-hydra-token;
 *  - не следует редиректам (панель отвечает 303 на form-POST — клиент читает Location сам);
 *  - trust-all включается ТОЛЬКО если пользователь явно доверил self-signed сертификат.
 */
object ApiClient {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        coerceInputValues = true
    }

    fun create(context: Context): PanelApi {
        val store = SessionStore(context)

        val builder = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS) // SSH-действия (установка протоколов) долгие
            .addInterceptor(AuthInterceptor(store))
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })

        if (store.trustSelfSigned) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslCtx = javax.net.ssl.SSLContext.getInstance("TLS")
            sslCtx.init(null, arrayOf(trustAll), SecureRandom())
            builder.sslSocketFactory(sslCtx.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }

        val client = builder.build()
        val base = store.baseUrl.ifBlank { "http://localhost/" }.let {
            if (it.endsWith("/")) it else "$it/"
        }

        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(json.asConverterFactory(JSON))
            .build()
            .create(PanelApi::class.java)
    }
}
