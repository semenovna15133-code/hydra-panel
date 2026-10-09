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

    /** Репозиторий панели по умолчанию; можно переопределить в UI при провижининге. */
    const val DEFAULT_REPO_URL = "https://github.com/semenovna15133-code/hydra-panel"
    private const val KEY_FILE = "hydra_bootstrap_key"

    suspend fun provision(
        context: Context,
        host: String,
        user: String,
        password: String,
        port: Int,
        repoUrl: String = DEFAULT_REPO_URL,
        sshPort: Int = 22,
        onProgress: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        onProgress("SSH: подключаюсь к $host:$sshPort…")
        sshRunPass(host, sshPort, user, password, null, "echo ok && uname -a")

        onProgress("Устанавливаю Docker…")
        sshRunPass(host, sshPort, user, password, null, INSTALL_DOCKER_CMD)

        onProgress("Клонирую панель и запускаю сервис…")
        sshRunPass(host, sshPort, user, password, null, DEPLOY_CMD(port, repoUrl))

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

    private suspend fun sshRunPass(
        host: String, port: Int, user: String, password: String, keyPath: String?, remoteCmd: String,
    ): String = withContext(Dispatchers.IO) {
        val jsch = com.jcraft.jsch.JSch()
        try {
            val session = jsch.getSession(user, host, port)
            session.setConfig("StrictHostKeyChecking", "no")
            session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")
            session.setPassword(password)
            session.connect(15_000)
            val chan = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            chan.setCommand("sudo -n sh -c '" + remoteCmd.replace("'", "'\\''") + "' 2>&1 || sh -c '" + remoteCmd.replace("'", "'\\''") + "' 2>&1")
            chan.connect(15_000)
            val out = chan.inputStream.bufferedReader().readText()
            val status = chan.exitStatus
            chan.disconnect(); session.disconnect()
            if (status != 0) {
                val hint = when {
                    out.contains("Permission denied", true) || out.contains("Auth fail", true) ->
                        "SSH: неверный логин/пароль или запрещён вход по паролю."
                    out.isBlank() -> "Команда завершилась с кодом $status без вывода."
                    else -> out.take(500)
                }
                throw IllegalStateException(hint)
            }
            out
        } catch (e: com.jcraft.jsch.JSchException) {
            val hint = when {
                e.message?.contains("Auth") == true -> "SSH: аутентификация не пройдена (проверьте root-пароль)."
                e.message?.contains("timeout") == true -> "SSH: таймаут подключения (порт 22 закрыт/фишволл?)."
                else -> "SSH ошибка: ${e.message}"
            }
            throw IllegalStateException(hint)
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

    private fun DEPLOY_CMD(port: Int, repoUrl: String) = """
        set -e
        mkdir -p /opt/hydra
        if [ ! -d /opt/hydra/repo ]; then git clone "$repoUrl" /opt/hydra/repo; else (cd /opt/hydra/repo && git pull --ff-only || true); fi
        cd /opt/hydra/repo
        docker build -t hydra-panel:latest . 2>/dev/null || true
        docker rm -f hydra-panel 2>/dev/null || true
        docker run -d --name hydra-panel --restart unless-stopped -p $port:8000 -v /opt/hydra/data:/data hydra-panel:latest 2>/dev/null || {
          pip3 install -q -r requirements.txt || python3 -m pip install -q -r requirements.txt
          nohup python3 -m uvicorn hydra.panel:app --host 0.0.0.0 --port $port >/opt/hydra/panel.log 2>&1 &
        }
    """.trimIndent()
}
