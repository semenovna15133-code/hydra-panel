#!/bin/bash
# hydra-agent.sh
#
# Hydra monitoring agent. Collects metrics and sends to panel.
# Config: /var/lib/hydra-agent/config.env
# Logs:   /var/log/hydra-agent.log
#
# ЗАВИСИМОСТИ:
# - jq (для парсинга)
# - iputils-ping (для ICMP fallback)
# - netcat-openbsd (для TCP connect latency)
# - gawk (для парсинга latency)

set -u

exec 9>/var/lock/hydra-agent.lock
if ! flock -n 9; then
    echo "$(date -Iseconds): previous run still active, skip" >&2
    exit 0
fi

[ -f /var/lib/hydra-agent/config.env ] && source /var/lib/hydra-agent/config.env
: "${PANEL_URL:?PANEL_URL not set}"
: "${AGENT_TOKEN:?AGENT_TOKEN not set}"
: "${SERVER_ID:?SERVER_ID not set}"

PANEL_HOST=$(echo "$PANEL_URL" | sed -E 's#^https?://([^/:]+).*#\1#')
PANEL_PORT=$(echo "$PANEL_URL" | sed -nE 's#^https?://[^/:]+:([0-9]+).*#\1#p')
[ -z "$PANEL_PORT" ] && PANEL_PORT=443

IFACE=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
[ -z "$IFACE" ] && IFACE=$(ip -4 route ls default | awk '{print $5; exit}')
[ -z "$IFACE" ] && IFACE="eth0"

# Инициализация переменных (защита от unbound)
cpu_percent=0
mem_percent="0.0"
rx_mbps="0.0"
tx_mbps="0.0"
latency_ms=0
wdtt_conns=0
aivpn_conns=0
awg_conns=0
wdtt_status="unknown"
aivpn_status="unknown"
awg_status="inactive"

STAT_FILE=/var/lib/hydra-agent/prev.stat
PREV_NET=/var/lib/hydra-agent/prev.net
BUFFER_FILE=/var/lib/hydra-agent/buffer.json
MAX_LINES=3600
DEAD_LETTER=/var/lib/hydra-agent/dead.json

# ========== CPU ==========
user=0; nice=0; sys=0; idle=0; iowait=0; irq=0; softirq=0; steal=0; guest=0; guest_nice=0
read -r _ user nice sys idle iowait irq softirq steal guest guest_nice < /proc/stat

# v3.61: убрана guest-коррекция (на VPS guest-время почти всегда 0)
idle_all=$((idle + iowait))
sys_all=$((sys + irq + softirq))
total=$((user + nice + sys_all + idle_all + steal))

prev_total=0
prev_idle_all=0
if [ -s "$STAT_FILE" ]; then
    read -r prev_total prev_idle_all _ < "$STAT_FILE" || true
    prev_total=${prev_total:-0}
    prev_idle_all=${prev_idle_all:-0}
    d_total=$((total - prev_total))
    d_idle=$((idle_all - prev_idle_all))
    if [ "$d_total" -gt 0 ]; then
        cpu_percent=$(( (d_total - d_idle) * 100 / d_total ))
    fi
fi
echo "$total $idle_all" > "$STAT_FILE"

# ========== Память ==========
mem_percent=$(LANG=C free -m 2>/dev/null | awk '/^Mem:/ {printf "%.1f", ($2 - $7) * 100 / $2}')
: "${mem_percent:=0.0}"

# ========== Сеть ==========
rx_now=$(awk -v iface="$IFACE" '$1 ~ "^"iface":" {print $2}' /proc/net/dev)
tx_now=$(awk -v iface="$IFACE" '$1 ~ "^"iface":" {print $10}' /proc/net/dev)

rx_prev=0
tx_prev=0
if [ -f "$PREV_NET" ] && [ -s "$PREV_NET" ] && [ -n "$rx_now" ]; then
    read -r rx_prev tx_prev _ < "$PREV_NET" || true
    rx_prev=${rx_prev:-0}
    tx_prev=${tx_prev:-0}
    rx_mbps=$(awk -v r="$rx_now" -v p="$rx_prev" 'BEGIN {printf "%.2f", (r - p) * 8 / 60 / 1000000}')
    tx_mbps=$(awk -v r="$tx_now" -v p="$tx_prev" 'BEGIN {printf "%.2f", (r - p) * 8 / 60 / 1000000}')
fi
echo "$rx_now $tx_now" > "$PREV_NET"

# ========== Подключения ==========
wdtt_status=$(systemctl is-active wdtt 2>/dev/null | tr -d '"\\' || echo "unknown")
aivpn_status=$(systemctl is-active aivpn-server 2>/dev/null | tr -d '"\\' || echo "unknown")

# AWG: используем awg show вместо lsmod
AWG_IFACE=$(basename "$(ls /etc/amnezia/amneziawg/*.conf 2>/dev/null | head -1)" .conf 2>/dev/null || echo "awg0")
[ -z "$AWG_IFACE" ] && AWG_IFACE="awg0"

awg_output=$(awg show "$AWG_IFACE" 2>/dev/null)
if [ -n "$awg_output" ]; then
    awg_status="active"
else
    awg_status="inactive"
fi

# WDTT: количество клиентов через admin API
MAIN_PW=$(tr -d '\n' < /root/wdtt-main.pass 2>/dev/null || echo "")
if [ -z "$MAIN_PW" ]; then
    echo "$(date -Iseconds): WARNING: /root/wdtt-main.pass is empty or missing" >> /var/log/hydra-agent.log
    wdtt_conns=0
else
    wdtt_conns=$(jq -n --arg mp "$MAIN_PW" '{main_password:$mp,args:["list"]}' | \
      /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null | \
      jq '.passwords | length' 2>/dev/null || echo 0)
fi

# AIVPN: количество клиентов через --list-clients
# v3.58: убран || echo 0 (grep -c печатает 0 сам)
aivpn_conns=$(/usr/local/bin/aivpn-server --list-clients \
  --key-file /etc/aivpn/server.key --clients-db /etc/aivpn/clients.json 2>/dev/null | \
  grep -c "active" || true)

# AWG: количество peers из вывода
awg_conns=$(printf '%s\n' "$awg_output" | grep -c "latest handshake" || true)

# ========== Latency до панели ==========
latency_ms=0
time_output=$( { time nc -z -w2 "$PANEL_HOST" "$PANEL_PORT"; } 2>&1 )
nc_exit=$?

if [ "$nc_exit" -eq 0 ]; then
    latency_ms=$(echo "$time_output" | gawk '
        /real/ {
            if (match($2, /([0-9]+)m([0-9.]+)s/, a)) {
                printf "%.0f", (a[1]*60 + a[2]) * 1000
            } else if (match($2, /([0-9.]+)s/, a)) {
                printf "%.0f", a[1] * 1000
            }
        }
    ')
fi

if [ -z "$latency_ms" ]; then
    latency_ms=$(ping -c 1 -W 2 "$PANEL_HOST" 2>/dev/null | \
        awk -F'time=' 'NF>1 {split($2,a," "); printf "%.0f", a[1]}' | head -1)
fi
[ -z "$latency_ms" ] && latency_ms=0

# ========== JSON payload ==========
json=$(jq -n \
  --arg server_id "$SERVER_ID" \
  --arg timestamp "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --argjson cpu_percent "$cpu_percent" \
  --argjson memory_percent "$mem_percent" \
  --argjson network_rx_mbps "$rx_mbps" \
  --argjson network_tx_mbps "$tx_mbps" \
  --argjson connections_wdtt "$wdtt_conns" \
  --argjson connections_aivpn "$aivpn_conns" \
  --argjson connections_awg "$awg_conns" \
  --arg status_wdtt "$wdtt_status" \
  --arg status_aivpn "$aivpn_status" \
  --arg status_awg "$awg_status" \
  --argjson latency_ms "$latency_ms" \
  '{
    server_id: $server_id,
    timestamp: $timestamp,
    cpu_percent: $cpu_percent,
    memory_percent: $memory_percent,
    network_rx_mbps: $network_rx_mbps,
    network_tx_mbps: $network_tx_mbps,
    connections_wdtt: $connections_wdtt,
    connections_aivpn: $connections_aivpn,
    connections_awg: $connections_awg,
    status_wdtt: $status_wdtt,
    status_aivpn: $status_aivpn,
    status_awg: $status_awg,
    latency_ms: $latency_ms
  }')

# ========== Отправка с буферизацией ==========
send_single() {
    local json="$1"
    local response_file=$(mktemp)
    local http_code
    
    http_code=$(curl -s -D "$response_file" -o /dev/null -w "%{http_code}" \
        --fail \
        --data-binary "$json" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AGENT_TOKEN" \
        --max-time 10 \
        "$PANEL_URL/api/v1/agent/metrics")
    
    rm -f "$response_file"
    [ "$http_code" = "200" ] || [ "$http_code" = "201" ]
}

send_batch() {
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" \
        --fail \
        --data-binary @"$BUFFER_FILE" \
        -H "Content-Type: application/x-ndjson" \
        -H "Authorization: Bearer $AGENT_TOKEN" \
        --max-time 60 \
        "$PANEL_URL/api/v1/agent/batch")
    
    case "$http_code" in
        200|202) return 0 ;;
        400)
            cat "$BUFFER_FILE" >> "$DEAD_LETTER"
            echo "---" >> "$DEAD_LETTER"
            > "$BUFFER_FILE"
            echo "$(date -Iseconds): ERROR: batch rejected (400), moved to dead-letter" >&2
            if [ -f "$DEAD_LETTER" ]; then
                lines=$(wc -l < "$DEAD_LETTER")
            else
                lines=0
            fi
            if [ "$lines" -gt 1000 ]; then
                tail -n 1000 "$DEAD_LETTER" > "$DEAD_LETTER.tmp"
                mv "$DEAD_LETTER.tmp" "$DEAD_LETTER"
            fi
            return 1
            ;;
        *) return 1 ;;
    esac
}

if ! send_single "$json"; then
    echo "$json" >> "$BUFFER_FILE"
    if [ -f "$BUFFER_FILE" ]; then
        lines=$(wc -l < "$BUFFER_FILE")
    else
        lines=0
    fi
    if [ "$lines" -gt "$MAX_LINES" ]; then
        tail -n "$MAX_LINES" "$BUFFER_FILE" > "$BUFFER_FILE.tmp"
        mv "$BUFFER_FILE.tmp" "$BUFFER_FILE"
    fi
else
    if [ -s "$BUFFER_FILE" ] && send_batch; then
        > "$BUFFER_FILE"
    fi
fi
