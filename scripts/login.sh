#!/usr/bin/env bash
# Создаёт cookie-jar для curl: ./scripts/login.sh [пароль]
# Использование: curl -b scripts/.cookies http://localhost:8000/api/v1/servers
set -e
cd "$(dirname "$0")/.."
PASS="${1:-}"
if [ -z "$PASS" ]; then
    read -s -p "Пароль панели: " PASS; echo
fi
BASE="${HYDRA_PANEL_URL:-http://localhost:8000}"
CODE=$(curl -s -c scripts/.cookies -o /dev/null -w "%{http_code}" -X POST "$BASE/login" --data-urlencode "password=$PASS")
if [ "$CODE" = "303" ]; then
    chmod 600 scripts/.cookies
    echo "✓ Cookie сохранена: scripts/.cookies"
    echo "  curl -b scripts/.cookies $BASE/api/v1/servers"
else
    echo "✗ Ошибка входа (HTTP $CODE). Проверь пароль."
    exit 1
fi
