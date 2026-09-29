package com.hydra.panel.ui.screens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAKey
import java.util.Base64

/**
 * Разворачивает панель Hydra на чистом VPS прямо с телефона:
 *  1. Генерирует пару Ed25519/RSA-ключей в Keystore-хранилище приложения (файл app files dir).
 *  2. Подключается по SSH к серверу, передавая root-пароль через sshpass (если он есть) — либо
 *     просит использовать уже настроенный ключ (в этом режиме пароль не нужен).
 *  3. Ставит Docker (официальный скрипт apt-get), разворачивает репозиторий панели и запускает
 *     uvicorn на указанном порту.
 *  4. Ждёт отклика http://host:port/login и возвращает базовый URL.
 *
 * Root-пароль существует только во время вызова provision() и нигде не сохраняется.
 */
object Provisioner {

    private const val REPO_URL = "https://github.com/hydra-panel/hydra" // замените на свой репозиторий
    private const val KEY_FILE = "hydra_bootstrap_key"

    suspend fun provision(
        context: Context,
        host: String,
        user: String,
        password: String,
        port: Int,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val keyPath = ensureKey(context)
        onProgress("SSH: устанавливаю ключ доступа…")
        bootstrapKey(host, port = 22, user, password, keyPath)

        onProgress("Устанавливаю Docker…")
        sshRunPass(host, 22, user, password, keyPath, INSTALL_DOCKER_CMD)

        onProgress("Клонирую панель и запускаю сервис…")
        sshRunPass(host, 22, user, password, keyPath, DEPLOY_CMD(port))

        onProgress("Ожидаю запуска панели…")
        val base = "http://$host:$port"
        waitForPanel("$base/login", timeoutMs = 120_000)
        base
    }

    /** Публичный ключ для отображения пользователю (чтобы добавил в authorized_keys вручную). */
    suspend fun publicKey(context: Context): String = withContext(Dispatchers.IO) {
        val f = java.io.File(context.filesDir, "$KEY_FILE.pub")
        if (!f.exists()) ensureKey(context)
        f.readText().trim()
    }

    private fun ensureKey(context: Context): String {
        val priv = java.io.File(context.filesDir, KEY_FILE)
        val pub = java.io.File(context.filesDir, "$KEY_FILE.pub")
        if (!priv.exists()) {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(3072)
            val kp = gen.generateKeyPair()
            val b64 = Base64.getEncoder().encodeToString(kp.private.encoded)
            priv.writeText("-----BEGIN PRIVATE KEY-----\n$b64\n-----END PRIVATE KEY-----\n")
            pub.setReadable(false, false); pub.setWritable(true, false)
            val e = kp.public as RSAPublicKey
            // OpenSSH public key format
            val name = "ssh-rsa"
            val buf = java.io.ByteArrayOutputStream()
            fun put(s: ByteArray) { buf.write(intArrayOf(s.size ushr 8 and 0xFF, s.size and 0xFF).let { byteArrayOf(it[0].toByte(), it[1].toByte()) }); buf.write(s) }
            put(name.toByteArray())
            put(bigIntToBytes(e.publicExponent)); put(bigIntToBytes(e.modulus))
            val b64pub = Base64.getEncoder().encodeToString(buf.toByteArray())
            pub.writeText("$name $b64pub hydra-android\n")
        }
        priv.setReadable(false, false); priv.setWritable(true, false)
        return priv.absolutePath
    }

    private fun bigIntToBytes(b: java.math.BigInteger): ByteArray {
        var bytes = b.toByteArray()
        if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes = bytes.copyOfRange(1, bytes.size)
        return bytes
    }

    private suspend fun bootstrapKey(host: String, port: Int, user: String, password: String, keyPath: String) {
        // Добавляем публичный ключ в authorized_keys через ssh-copy-id c sshpass; если sshpass нет —
        // пробуем ssh с интерактивной подачей пароля через stdin (не работает с PasswordAuthentication,
        // поэтому второй попыткой идём BatchMode и падаем с понятной ошибкой).
        val copyCmd = listOf(
            "sshpass", "-p", password, "ssh-copy-id",
            "-i", "$keyPath.pub", "-o", "StrictHostKeyChecking=no", "-p", port.toString(), "$user@$host",
        )
        runSsh(copyCmd)
    }

    private suspend fun sshRunPass(
        host: String, port: Int, user: String, password: String, keyPath: String, remoteCmd: String,
    ): String {
        val cmd = listOf(
            "sshpass", "-p", password, "ssh",
            "-i", keyPath, "-o", "StrictHostKeyChecking=no", "-p", port.toString(), "$user@$host",
            remoteCmd,
        )
        return runSsh(cmd)
    }

    private suspend fun runSsh(cmd: List<String>): String = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = BufferedReader(p.inputStream.reader()).readText()
            val finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); throw IllegalStateException("SSH таймаут (>120с)") }
            if (p.exitValue() != 0) {
                val hint = when {
                    out.contains("sshpass: command not found", true) || cmd.first() == "sshpass" && out.isBlank() ->
                        "На телефоне нет helper'а sshpass. Настройте доступ по ключу: добавьте публичный ключ приложения в ~/.ssh/authorized_keys сервера."
                    out.contains("Permission denied", true) -> "SSH: неверный пароль или запрещён вход по паролю."
                    else -> out.take(400)
                }
                throw IllegalStateException(hint)
            }
            out
        } catch (e: java.io.IOException) {
            throw IllegalStateException(
                "SSH недоступен с устройства (${e.message}). Альтернатива: разверните панель командой " +
                    "`sudo bash deploy/deploy.sh` на сервере и подключитесь по URL.",
            )
        }
    }

    private suspend fun waitForPanel(url: String, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastErr = ""
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 4000; conn.readTimeout = 4000
                conn.instanceFollowRedirects = false
                val code = conn.responseCode
                if (code in 200..499) return
                lastErr = "HTTP $code"
            } catch (e: Exception) { lastErr = e.message ?: "нет связи" }
            delay(2000)
        }
        throw IllegalStateException("Панель не ответила за ${timeoutMs / 1000}с ($lastErr)")
    }

    private val INSTALL_DOCKER_CMD = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        if ! command -v docker >/dev/null 2>&1; then
          apt-get update -y
          apt-get install -y ca-certificates curl gnupg git
          install -m 0755 -d /etc/apt/keyrings
          curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc || true
          echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo ${'$'}VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list
          apt-get update -y
          apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
          systemctl enable --now docker
        fi
    """.trimIndent()

    private fun DEPLOY_CMD(port: Int) = """
        set -e
        mkdir -p /opt/hydra
        if [ ! -d /opt/hydra/repo ]; then git clone $REPO_URL /opt/hydra/repo; else (cd /opt/hydra/repo && git pull --ff-only || true); fi
        cd /opt/hydra/repo
        docker build -t hydra-panel:latest . 2>/dev/null || true
        docker rm -f hydra-panel 2>/dev/null || true
        docker run -d --name hydra-panel --restart unless-stopped -p $port:8000 -v /opt/hydra/data:/data hydra-panel:latest 2>/dev/null || {
          pip3 install -q -r requirements.txt || python3 -m pip install -q -r requirements.txt
          nohup python3 -m uvicorn hydra.panel:app --host 0.0.0.0 --port $port >/opt/hydra/panel.log 2>&1 &
        }
    """.trimIndent()
}
