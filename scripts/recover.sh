#!/usr/bin/env bash
# Hydra Panel DR: чистый сервер -> рабочая панель с данными из GitHub.
# Использование:
#   GITHUB_TOKEN=github_pat_... bash recover.sh OWNER/hydra-backups [PANEL_REPO_URL_ИЛИ_ПАПКА]
set -euo pipefail
REPO="${1:?Нужно: OWNER/backup-repo}"
PANEL_SRC="${2:-https://github.com/semenovna15133-code/hydra-panel.git}"
if [ -z "${GITHUB_TOKEN:-}" ]; then read -rs -p "GitHub token (Contents: Read на backup-репо): " GITHUB_TOKEN; echo; fi

SUDO=""; [ "$(id -u)" -ne 0 ] && command -v sudo >/dev/null && SUDO=sudo
command -v git >/dev/null && command -v curl >/dev/null || { $SUDO apt-get update -y && $SUDO apt-get install -y git curl python3 python3-venv; }

DEST="${HYDRA_DEST:-/opt/hydra-panel}"
[ -d "$DEST" ] || { $SUDO mkdir -p "$DEST"; $SUDO chown "$(id -un)" "$DEST"; }
cd "$DEST"
if [ -d "$PANEL_SRC" ]; then cp -a "$PANEL_SRC/." .; else git clone -q "$PANEL_SRC" .; fi
python3 -m venv .venv
.venv/bin/pip install -q -r requirements.txt

API="https://api.github.com/repos/$REPO/contents/backups"
LIST=$(curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" "$API")
DBNAME=$(printf '%s' "$LIST" | .venv/bin/python -c "import json,sys; print(sorted(x['name'] for x in json.load(sys.stdin) if x['name'].startswith('hydra-backup-') and x['name'].endswith('.db.gz'))[-1])")
echo "Последний бэкап: $DBNAME"
curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github.raw" -o backup.db.gz "$API/$DBNAME"
curl -fsS -H "Authorization: Bearer $GITHUB_TOKEN" -H "Accept: application/vnd.github.raw" -o recovery-bundle.enc "$API/recovery-bundle.enc"

read -rs -p "Recovery-passphrase: " PASS; echo
PASS="$PASS" .venv/bin/python - <<'PY'
import base64, os
from hydra.core import recovery
data = recovery.decrypt_bundle(open("recovery-bundle.enc","rb").read(), os.environ["PASS"])
os.makedirs("keys", exist_ok=True)
if data.get("ssh_key"):
    open("keys/hydra_key","w").write(data["ssh_key"]); os.chmod("keys/hydra_key", 0o600)
if data.get("ssh_pub"):
    open("keys/hydra_key.pub","w").write(data["ssh_pub"])
if data.get("master_key_b64"):
    d = os.path.expanduser("~/.config/hydra"); os.makedirs(d, exist_ok=True)
    p = os.path.join(d, "master.key")
    open(p,"wb").write(base64.b64decode(data["master_key_b64"])); os.chmod(p, 0o600)
print("✓ Ключи восстановлены:", ", ".join(k for k in ("ssh_key", "master_key") if k in data))
PY

gunzip -f backup.db.gz && mv -f backup.db panel.db && chmod 600 panel.db
echo
echo "✓ Готово. Запуск:"
echo "  cd $DEST && source .venv/bin/activate && python hydra/cli.py --port 8000"
echo "  Вход: твой пароль администратора (из восстановленной БД)."
echo "  Offsite-бэкапы продолжатся автоматически (токен GitHub внутри бандла)."
