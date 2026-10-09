#!/bin/bash
set -euo pipefail

DOMAIN=""
EMAIL=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --domain) DOMAIN="$2"; shift 2 ;;
        --email) EMAIL="$2"; shift 2 ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

if [ -z "$DOMAIN" ]; then
    echo "Error: --domain is required"
    exit 1
fi

if [ -z "$EMAIL" ]; then
    EMAIL="admin@$DOMAIN"
fi

if [ "$EUID" -ne 0 ]; then
    echo "Error: This script must be run as root"
    exit 1
fi

echo "=== Hydra Panel Deployment ==="
echo "Domain: $DOMAIN"
echo "Email: $EMAIL"
echo ""

echo "[1/7] Installing system dependencies..."
apt update -qq
apt install -y -qq python3 python3-pip python3-venv python3-yaml sqlite3 certbot acl ufw git curl

echo "[2/7] Creating hydra user and directories..."
if ! id hydra >/dev/null 2>&1 || ! getent group hydra >/dev/null 2>&1; then
    useradd -r -U -s /usr/sbin/nologin -d /nonexistent -M hydra
fi
mkdir -p /opt/hydra /etc/hydra /var/lib/hydra /opt/hydra/keys /var/log/hydra
chown hydra:hydra /opt/hydra /etc/hydra /var/lib/hydra /opt/hydra/keys /var/log/hydra
chmod 755 /opt/hydra
chmod 750 /etc/hydra /var/lib/hydra /opt/hydra/keys /var/log/hydra

echo "[3/7] Cloning repository and setting up venv..."
cd /opt/hydra
if [ ! -d /opt/hydra/repo ]; then
    sudo -u hydra git clone https://github.com/semenovna15133-code/hydra-panel.git /opt/hydra/repo
fi
if [ ! -d /opt/hydra/venv ]; then
    sudo -u hydra python3 -m venv /opt/hydra/venv
fi
sudo -u hydra /opt/hydra/venv/bin/pip install --upgrade pip -q
sudo -u hydra /opt/hydra/venv/bin/pip install -e /opt/hydra/repo -q

echo "[4/7] Generating secrets..."
if [ ! -f /etc/hydra/panel.env ]; then
    openssl rand -base64 32 | tee /etc/hydra/panel.env > /dev/null
    sed -i '1s/^/HYDRA_DB_KEY=/' /etc/hydra/panel.env
    chmod 600 /etc/hydra/panel.env
    chown hydra:hydra /etc/hydra/panel.env
fi

echo "[5/7] Creating panel.yaml..."
if [ ! -f /etc/hydra/panel.yaml ]; then
    cat > /etc/hydra/panel.yaml <<YAML_EOF
panel_domains:
  - $DOMAIN
db_encryption_key_env: HYDRA_DB_KEY
ssh_key_path: /opt/hydra/keys/panel_key
defaults:
  max_passwords_wdtt: 50
  max_connections_per_protocol: 50
alerts_channel_id: -100XXXXXXXX
admin_user_ids: []
agent_token_rotation_days: 90
agent_token_grace_period_hours: 24
YAML_EOF
    chmod 640 /etc/hydra/panel.yaml
    chown hydra:hydra /etc/hydra/panel.yaml
fi

echo "[6/7] Obtaining TLS certificate..."
ufw allow 80/tcp
systemctl stop hydra-panel 2>/dev/null || true
certbot certonly --standalone -d "$DOMAIN" --agree-tos --non-interactive --email "$EMAIL"
ufw delete allow 80/tcp
setfacl -m u:hydra:x /etc/letsencrypt/live /etc/letsencrypt/archive
chown -R hydra:hydra "/etc/letsencrypt/live/$DOMAIN"
chown -R hydra:hydra "/etc/letsencrypt/archive/$DOMAIN"
chmod 750 "/etc/letsencrypt/live/$DOMAIN"
chmod 750 "/etc/letsencrypt/archive/$DOMAIN"
cp /opt/hydra/repo/deploy/hooks/pre-renew /etc/letsencrypt/renewal-hooks/pre/hydra-open-80
cp /opt/hydra/repo/deploy/hooks/post-renew /etc/letsencrypt/renewal-hooks/post/hydra-close-80
cp /opt/hydra/repo/deploy/hooks/deploy /etc/letsencrypt/renewal-hooks/deploy/hydra-panel-reload
chmod +x /etc/letsencrypt/renewal-hooks/pre/hydra-open-80
chmod +x /etc/letsencrypt/renewal-hooks/post/hydra-close-80
chmod +x /etc/letsencrypt/renewal-hooks/deploy/hydra-panel-reload

echo "[7/7] Installing systemd unit..."
sed "s/panel\\.hydra\\.example/$DOMAIN/g" /opt/hydra/repo/deploy/systemd/hydra-panel.service > /etc/systemd/system/hydra-panel.service
systemctl daemon-reload
systemctl enable hydra-panel
systemctl start hydra-panel

echo ""
echo "--- Diagnostics (v0.7.0): verifying panel actually started ---"
sleep 3
if systemctl is-active --quiet hydra-panel; then
    echo "✓ hydra-panel is active"
    if curl -sk --max-time 10 "https://127.0.0.1/health" | grep -q '"ok"'; then
        echo "✓ /health responds OK"
    else
        echo "⚠ /health не отвечает — смотрите journalctl -u hydra-panel -n 50 и /var/log/hydra/panel.log"
    fi
else
    echo "✗ hydra-panel НЕ запущена. Причины видны в логе ошибок запуска:"
    echo "=== journalctl -u hydra-panel -n 50 --no-pager ==="
    journalctl -u hydra-panel -n 50 --no-pager || true
    echo "=== /var/log/hydra/panel.log (tail -50) ==="
    tail -50 /var/log/hydra/panel.log 2>/dev/null || echo "(лог-файл ещё не создан — ошибка на этапе импорта модуля, см. journalctl выше)"
    exit 1
fi

echo ""
echo "✓ Deployment complete!"
echo ""
echo "Panel URL: https://$DOMAIN"
echo "Health check: curl https://$DOMAIN/health"
echo ""
echo "Next steps:"
echo "1. Generate SSH key: sudo -u hydra ssh-keygen -t ed25519 -f /opt/hydra/keys/panel_key -N ''"
echo "2. Check logs: journalctl -u hydra-panel -f"
echo "3. Add SSH public key to managed servers"
