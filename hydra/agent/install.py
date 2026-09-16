"""Agent installation logic."""
import hashlib
import secrets
import base64
from datetime import datetime, timedelta
from pathlib import Path
from typing import Optional
from ..ssh import SSHTransport
from ..core.db import Database


# Путь к скрипту агента
AGENT_SCRIPT = Path(__file__).parent / "agent.sh"

# Зависимости агента
AGENT_DEPS = ["iputils-ping", "netcat-openbsd", "jq", "gawk"]

# Пути на сервере
AGENT_DIR = "/var/lib/hydra-agent"
AGENT_LOG = "/var/log/hydra-agent.log"
AGENT_LOCK = "/var/lock/hydra-agent.lock"


async def install_agent(
    server_ip: str,
    ssh_key_path: str,
    panel_url: str,
    server_id: str,
    db: Database,
    rotation_days: int = 90,
) -> dict:
    """Install monitoring agent on remote server.
    
    Steps:
    1. Install dependencies (jq, ping, nc, gawk)
    2. Create agent directory and lock file
    3. Generate AGENT_TOKEN with expiry
    4. Upload agent.sh with SHA256 verification
    5. Create config.env with PANEL_URL, AGENT_TOKEN, SERVER_ID
    6. Setup cron (/etc/cron.d/hydra-agent)
    7. Setup logrotate (/etc/logrotate.d/hydra-agent)
    8. Run initial agent execution to verify
    
    Args:
        server_ip: Server IP
        ssh_key_path: Path to SSH key
        panel_url: Panel URL (https://panel.example)
        server_id: Server ID from database
        db: Database instance
        rotation_days: Token rotation period (default 90)
    
    Returns:
        Dict with installation result
    """
    # Генерация токена
    token = secrets.token_urlsafe(32)
    token_hash = hashlib.sha256(token.encode()).hexdigest()
    expires_at = datetime.utcnow() + timedelta(days=rotation_days)
    
    # Чтение скрипта агента
    if not AGENT_SCRIPT.exists():
        raise FileNotFoundError(f"Agent script not found: {AGENT_SCRIPT}")
    
    script_content = AGENT_SCRIPT.read_text()
    script_sha256 = hashlib.sha256(script_content.encode()).hexdigest()
    
    # Base64 для передачи через SSH
    script_b64 = base64.b64encode(script_content.encode()).decode()
    
    # Команда установки
    cmd = f'''
set -euo pipefail

# Проверка что уже установлен
if [ -f {AGENT_DIR}/config.env ] && [ -f /usr/local/bin/hydra-agent.sh ]; then
    echo "ALREADY_INSTALLED"
    exit 0
fi

echo "Installing Hydra monitoring agent..."

# Установка зависимостей
sudo apt update -qq
sudo apt install -y -qq {' '.join(AGENT_DEPS)}

# Создание директорий
sudo mkdir -p {AGENT_DIR}
sudo touch {AGENT_LOG} {AGENT_LOCK}
sudo chmod 600 {AGENT_LOG}

# Загрузка скрипта с проверкой SHA256
TMP_SCRIPT=$(mktemp)
echo '{script_b64}' | base64 -d > "$TMP_SCRIPT"

actual_sha=$(sha256sum "$TMP_SCRIPT" | awk '{{print $1}}')
if [ "$actual_sha" != "{script_sha256}" ]; then
    echo "ERROR: SHA256 mismatch (possible MITM)"
    rm -f "$TMP_SCRIPT"
    exit 1
fi

sudo install -m 755 "$TMP_SCRIPT" /usr/local/bin/hydra-agent.sh
rm -f "$TMP_SCRIPT"

# Создание config.env
sudo bash -c 'cat > {AGENT_DIR}/config.env <<CONFIG_EOF
PANEL_URL={panel_url}
AGENT_TOKEN={token}
SERVER_ID={server_id}
CONFIG_EOF'
sudo chmod 600 {AGENT_DIR}/config.env
sudo chown -R root:root {AGENT_DIR}

# Создание cron
cat <<CRONEOF | sudo tee /etc/cron.d/hydra-agent > /dev/null
# Hydra monitoring agent
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
* * * * * root /usr/local/bin/hydra-agent.sh >> {AGENT_LOG} 2>&1
CRONEOF
sudo chmod 644 /etc/cron.d/hydra-agent

# Проверка что последний байт - newline (cron требует)
if [ -s /etc/cron.d/hydra-agent ]; then
    last_byte=$(od -An -tx1 /etc/cron.d/hydra-agent | tr -d ' \\n' | tail -c 2)
    if [ "$last_byte" != "0a" ]; then
        echo | sudo tee -a /etc/cron.d/hydra-agent > /dev/null
    fi
fi

# Создание logrotate
cat <<LOGEOF | sudo tee /etc/logrotate.d/hydra-agent > /dev/null
{AGENT_LOG} {{
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 644 root root
}}
LOGEOF

# Перезапуск cron для подхвата нового конфига
sudo systemctl restart cron 2>/dev/null || sudo service cron restart 2>/dev/null || true

echo "INSTALL_COMPLETE"
'''
    
    async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
        result = await ssh.run(cmd)
        output = result.stdout
        
        if "ALREADY_INSTALLED" in output:
            return {
                "status": "already_installed",
                "message": "Agent already installed on this server",
                "token": token,  # Возвращаем для возможной ротации
            }
        
        if not result.success or "ERROR" in output:
            return {
                "status": "failed",
                "message": f"Installation failed: {output}\nStderr: {result.stderr}",
            }
        
        if "INSTALL_COMPLETE" not in output:
            return {
                "status": "unknown",
                "message": f"Unexpected output: {output}",
            }
    
    # Сохранение токена в БД (с поддержкой ротации через composite PK)
    await db.execute(
        """INSERT INTO agent_tokens (server_id, token_hash, expires_at, created_at)
           VALUES (?, ?, ?, datetime('now'))""",
        server_id, token_hash, expires_at.isoformat(),
    )
    await db.commit()
    
    # Первичный запуск агента для проверки
    async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
        test_result = await ssh.run("/usr/local/bin/hydra-agent.sh")
        
        if not test_result.success:
            return {
                "status": "warning",
                "message": f"Agent installed but initial run failed: {test_result.stderr}",
                "token": token,
            }
    
    return {
        "status": "success",
        "message": f"Agent installed and verified on {server_ip}",
        "token": token,
        "token_expires": expires_at.isoformat(),
    }
