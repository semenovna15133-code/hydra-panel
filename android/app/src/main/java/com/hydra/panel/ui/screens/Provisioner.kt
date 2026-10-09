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

    // v0.7.2 — полный переписанный деплой без Docker и с внятными ошибками:
    //  * В v0.7.1 здесь был дохлый Docker-путь: в репозитории никогда не было Dockerfile,
    //    `docker build ... || true` всегда молча проваливался, а fallback на pip ставил
    //    кривое окружение (в requirements.txt были только cryptography/pyyaml, без
    //    fastapi/uvicorn) и панель умирала со "No module named uvicorn".
    //  * Теперь: python3-venv -> pip install -r requirements.txt (полный список) ->
    //    systemd unit с WorkingDirectory=репозиторий; если systemd недоступен — nohup.
    //  * Каждая ошибка выводится с понятным текстом и уходит в IllegalStateException
    //    на экран приложения.
    private fun DEPLOY_CMD(port: Int, repoUrl: String) = """
        set -e
        export DEBIAN_FRONTEND=noninteractive
        log(){ echo "[deploy] ${'$'}1"; }
        die(){ echo "[deploy][ERROR] ${'$'}1"; exit 1; }

        command -v git >/dev/null 2>&1 || { apt-get update -y >/dev/null 2>&1 || true; apt-get install -y git >/dev/null 2>&1 || die "не удалось установить git (проверьте доступ к apt-репозиториям)"; }
        mkdir -p /opt/hydra
        if [ ! -d /opt/hydra/repo/.git ]; then
          rm -rf /opt/hydra/repo
          git clone --depth 1 "$repoUrl" /opt/hydra/repo || die "git clone не удался — проверьте URL репозитория и доступ сервера к github.com"
        else
          ( cd /opt/hydra/repo && git fetch --depth 1 origin && git reset --hard origin/HEAD ) || die "git pull не удался"
        fi
        cd /opt/hydra/repo

        # Python >= 3.11 (проверяем реально исполняемым тестом, а не парсингом --version)
        PYBIN=""
        for c in python3.13 python3.12 python3 python3.11; do
          if command -v "${'$'}c" >/dev/null 2>&1 && "${'$'}c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 11) else 1)' >/dev/null 2>&1; then
            PYBIN="${'$'}c"; break
          fi
        done
        [ -n "${'$'}PYBIN" ] || { apt-get update -y >/dev/null 2>&1 || true; apt-get install -y python3 python3-venv >/dev/null 2>&1 || die "на сервере нет Python >= 3.11 и apt не смог его установить"; PYBIN=python3; }
        "${'$'}PYBIN" -m venv --help >/dev/null 2>&1 || { apt-get update -y >/dev/null || true; apt-get install -y python3-venv || die "не удалось установить python3-venv"; }
        log "python: $("${'$'}PYBIN" -V 2>&1)"

        [ -d /opt/hydra/venv ] || "${'$'}PYBIN" -m venv /opt/hydra/venv || die "не удалось создать venv"
        /opt/hydra/venv/bin/pip install -q --upgrade pip setuptools wheel || die "pip upgrade не удался (нет доступа к pypi.org?)"
        /opt/hydra/venv/bin/pip install -q -r requirements.txt || die "установка зависимостей из requirements.txt не удалась (см. вывод pip выше)"
        /opt/hydra/venv/bin/python -c "import uvicorn, fastapi, aiosqlite, asyncssh" || die "питон-окружение собрано, но модулы панели не импортируются"

        mkdir -p /var/log/hydra
        pkill -f "uvicorn hydra.panel:app" 2>/dev/null || true

        cat > /etc/systemd/system/hydra-panel.service <<UNIT
[Unit]
Description=Hydra Control Panel
After=network.target

[Service]
WorkingDirectory=/opt/hydra/repo
ExecStart=/opt/hydra/venv/bin/python -m uvicorn hydra.panel:app --host 0.0.0.0 --port $port
Restart=unless-stopped
Environment=HYDRA_DB_PATH=/opt/hydra/panel.db
Environment=HYDRA_LOG_FILE=/var/log/hydra/panel.log

[Install]
WantedBy=multi-user.target
UNIT
        if command -v systemctl >/dev/null 2>&1 && systemctl daemon-reload 2>/dev/null && systemctl enable --now hydra-panel 2>/dev/null; then
          sleep 4
          systemctl is-active --quiet hydra-panel || { journalctl -u hydra-panel -n 40 --no-pager || true; die "сервис hydra-panel не запустился через systemd (вывод journalctl выше)"; }
          log "запущено через systemd"
        else
          log "systemd недоступен — стартую через nohup"
          cd /opt/hydra/repo
          HYDRA_DB_PATH=/opt/hydra/panel.db HYDRA_LOG_FILE=/var/log/hydra/panel.log nohup /opt/hydra/venv/bin/python -m uvicorn hydra.panel:app --host 0.0.0.0 --port $port >>/var/log/hydra/panel.log 2>&1 &
          sleep 4
          pgrep -f "uvicorn hydra.panel:app" >/dev/null || { tail -n 40 /var/log/hydra/panel.log || true; die "панель не запустилась даже через nohup (последние строки лога выше)"; }
        fi

        if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "Status: active"; then
          ufw allow $port/tcp >/dev/null 2>&1 || log "не смог открыть порт $port в ufw — проверьте файрвол вручную"
        fi
        log "готово: панель должна отвечать на http://127.0.0.1:$port"
    """.trimIndent()
}
