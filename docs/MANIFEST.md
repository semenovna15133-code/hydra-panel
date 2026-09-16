#  HYDRA MANIFEST v3.60 (FINAL — ready for commit & Stage 2)

**Дата фиксации:** 2026-09-16  
**Состояние:**  согласован по итогам финального ревью; готов к коммиту и старту Этапа 2  
**Версия:** v3.60 — восстановлен composite PRIMARY KEY (server_id, token_hash) в agent_tokens (регрессия v3.59 при восстановлении §14 без diff-проверки); возвращены ключевые комментарии схемы §14**Часовой пояс по умолчанию:** UTC. Отображение у админа настраивается через admin_timezone в /etc/hydra/panel.yaml.**Нумерация ревью:** в основной фазе действовала формула «ревью #N проверяет v3.(N+28), порождает v3.(N+29)» (ревью #25 → v3.54, финал фазы манифеста). Полировочные раунды v3.55–v3.59 шли без сквозной нумерации. Ревью #26 проверило v3.59 и породило v3.60. Дальше нумерация продолжается сквозная.**Процесс-заметка (v3.46, расширена в v3.47, v3.50, v3.54, v3.60):** при восстановлении секций из предыдущих версий требуется diff-проверка, а не copy-paste по памяти; после правки проверяются все блоки, использующие изменённые переменные/пути; перед отправкой новой версии выполняется самопроверка целостности (пункт 4 чек-листа). Git diff-гейт против предыдущей версии появится в Этапе 2 как scripts/check_manifest_integrity.sh; до его появления чек-лист обеспечивается вручную. Инциденты: v3.45 (регрессия §15 при восстановлении), v3.50-rc1 (повреждение при отправке), **v3.59 (регрессия §14: agent_tokens потеряла composite PK при восстановлении секции из снимка до v3.37 без diff-проверки; восстановлено в v3.60)**. Урок v3.59: при сборке новой версии каждая секция сверяется с последней согласованной версией, особенно схемы БД и контракты.**Чек-лист ревью (v3.47, расширен в v3.50, v3.51, v3.53, v3.58):**Любое восстановление секции из предыдущей версии проходит через diff против источника (не copy-paste по памяти) — см. процесс-заметку и инциденты v3.45, v3.50-rc1, v3.59.
После правки проверяются все блоки, использующие изменённые переменные/пути (инцидент v3.45: jq -n с unbound variable; инцидент v3.59: verify_agent_token при regressed-схеме).
Инструкции для оператора читаются вслух на предмет опечаток в именах сервисов/пакетов.
Перед отправкой новой версии — самопроверка целостности: нет вырожденных повторов, нет служебного/мета-текста, **ключевые маркеры текущей версии** (обновляются при каждом патче) присутствуют с ожидаемой кратностью. Скрипт check_manifest_integrity.sh поддерживает актуальный список маркеров; до его появления — ручной grep. Основание — инцидент v3.50-rc1.
Сквозная проверка «от нулевого Ubuntu до работающей панели» после крупных правок §13.2: убедиться что unit-файл не ссылается на артефакты, которых нигде выше не создаётся (инцидент v3.50: venv и код панели отсутствовали до ревью #22).
Для каждого smoke-теста проверять семантику exit code: если тест состоит из pipe + &&, exit code определяется последней командой pipe, а не тестируемым процессом (инцидент v3.51: timeout | head && echo "OK" всегда проходил, даже при падении uvicorn).
Проверка портативности sudo -u user VAR=value cmd: на Ubuntu 24.04 по умолчанию env_reset включён, setenv — нет, команда может упасть с command not found. Предпочтительные альтернативы: sudo -u user env VAR=value cmd или убрать VAR=value и положиться на уже настроенное окружение (например, editable install для Python-путей).
Блоки с смешением языков (Python + bash) должны быть разделены явно: каждый язык в своём отдельном блоке, передача данных через переменные окружения или аргументы, не через inline-вставки. Основание — v3.56 правка §7 WDTT.
При написании awk-fallback для YAML-подобных структур проверять нумерацию полей: awk с FS=whitespace обрезает ведущие пробелы, и первое значащее поле (например - в списке) становится $1. Основание — баг v3.56 awk-fallback deploy-hook ($3 вместо $2).
Паттерн grep -c PATTERN || echo 0 всегда даёт двойной вывод при 0 совпадений: grep -c уже печатает «0» и возвращает exit 1, || echo 0 добавляет второй «0», результат «0\n0» ломает jq --argjson. Использовать || true для снятия exit code без добавления вывода, либо убрать fallback вовсе. Основание — баг v3.57 скелет агента (aivpn_conns / awg_conns).

---

## Changelog

**v3.60** — по итогам ревью #26 (проверявшего v3.59): §14 — восстановлен composite PRIMARY KEY (server_id, token_hash) в agent_tokens; регрессия v3.59 возникла при восстановлении секции из снимка схемы до v3.37 без diff-проверки (инциденты того же класса: v3.45, v3.50-rc1); без composite PK политика ротации §13.2 физически нереализуема (UNIQUE constraint failed на втором токене сервера, grace period — фикция, verify_agent_token проверяет одного кандидата). Возвращены ключевые комментарии схемы §14 (масштаб device_connections ~7M строк и TTL, назначение composite PK agent_tokens, пояснения к пороговым колонкам). Changelog фиксирует регрессию явно, по требованию ревью
**v3.59** — финальные уточнения спецификации: §7 WDTT — MAX_PW читается через venv Python панели /opt/hydra/venv/bin/python (pyyaml там есть после развёртывания кода), с fallback на host python3 только если venv ещё не существует и явной проверкой наличия pyyaml (чистая Ubuntu без python3-yaml падала на import yaml); согласовано с deploy-hook (§13.2, v3.56); текст ошибки уточнён до «§13.2, шаги 1-3 „Развёртывание кода панели“». §15 таблица метрик — строка «Подключения» уточнена: WDTT через jq (.passwords | length), AIVPN и AWG через grep -c по выводу --list-clients / awg show. **Известный дефект v3.59: §14 восстановлен без diff-проверки, agent_tokens потеряла composite PK — исправлено в v3.60**
**v3.58** — закрытие блокирующего бага в скелете агента (§15): паттерн grep -c PATTERN || echo 0 при нуле совпадений печатал «0» дважды, результат «0\n0» ломал jq --argjson connections_*. Заменено на || true в двух строках скелета: aivpn_conns и awg_conns. Добавлен пункт 10 чек-листа. WDTT-строка (jq ... || echo 0) не тронута: jq при ошибке не печатает ничего
**v3.57** — баг в awk-fallback deploy-hook ($3 → $2), объединение двух вызовов awg show в один (через awg_output), обновление версии в шапке, комментарии integrity-скрипта
**v3.56** — финальная полировка: §7 WDTT Python/bash разделены; §13.2 primary TLS правило, unit-комментарий, multi-domain certbot; §15 awg_status переведён на awg show; §9 IPv6 объяснение прямо в секции; deploy-hook — venv Python вместо host Python
**v3.55** — приём замечаний внимательного перечитывания: §7 AWG установка с fallback; §13.2 deploy-hook цикл по panel_domains; §15 regex-match /proc/net/dev; §15 CPU комментарий guest/guest_nice
**v3.54** — финальная косметика ревью #25: timeout 3→5, детальная диагностика rc=124, комментарий о частичной информационности маркера --app-dir
**v3.53** — по итогам ревью #24: portable sudo (убран PYTHONPATH), printf в smoke-тесте, специфичные маркеры в integrity-скрипте
**v3.52** — по итогам ревью #23: smoke-тест uvicorn (перехват $(...) + rc + grep), --app-dir /opt/hydra/src, упрощение if перед git clone
**v3.51** — по итогам ревью #22: развёртывание кода/venv, cross-reference §13.1, getent group hydra, обобщение пункта 4 чек-листа
**v3.50** — по итогам ревью #21: подготовка пользователя hydra и рабочих директорий; пункт 4 чек-листа; спецификация check_manifest_integrity.sh (после инцидента v3.50-rc1)
**v3.49** — по итогам ревью #20: getfacl UX, smoke-тест (nologin/execve)
**v3.48** — по итогам ревью #19: пакет acl, setfacl guard, smoke-тест, multi-domain
**v3.47** — по итогам ревью #18: ACL на родителях letsencrypt, deploy-hook схема, чек-лист ревью
**v3.46** — hotfix по итогам ревью #17: регрессия §15 восстановлена
**v3.45** — по итогам ревью #16: §14-16 восстановлены (самодостаточность)
**v3.44** — по итогам ревью #15: grep вместо awk для panel.env
**v3.43** — по итогам ревью #14: StartLimit в [Unit], Restart=always
**v3.42** — по итогам ревью #13: честный restart вместо SIGHUP-фикции
**v3.41** — по итогам ревью #12: EnvironmentFile, chown в deploy-hook
**v3.40** — по итогам ревью #11: systemd AmbientCapabilities
**v3.39** — по итогам ревью #10: IPv6-примеры, PANEL_PORT
**v3.38** — по итогам ревью #9: убран LC_ALL=C, ipaddress
**v3.37** — по итогам ревью #8: закавыченный heredoc; **впервые исправлен PK agent_tokens на composite (server_id, token_hash)**
**v3.36** — по итогам ревью #7: SQL /24, gawk, PANEL_HOST
**v3.35** — по итогам ревью #6: regex latency, NOT NULL
**v3.34** — по итогам ревью #5: CPU 8 полей, flock, AGENT_TOKEN
**v3.33** — по итогам ревью #4: curl NDJSON, /etc/cron.d
**v3.32** — по итогам ревью #3: effective MTU, heartbeat
**v3.31** — по итогам ревью #2: H1-H4 диапазоны
**v3.30** — по итогам ревью #1: профили AWG, redeem-модель
**v3.29** — система мониторинга
**v3.21-28** — верификация протоколов на FI
**v3.00** — старт проекта

---

## Протоколы (триплет)

| Протокол | Версия сервера | Версия клиента | Порты (файрвол) | Статус |
|---|---|---|---|---|
| WDTT Plus | v17 | v18 | 56000/udp, 56001/udp, 9000/udp |  закрыт |
| AIVPN | v1.1.0 | v1.1.0 | 443/udp |  закрыт |
| AmneziaWG | v3.1 | v3.1 | 51820/udp |  закрыт |

---

## Архитектура проекта

### Этап 2: Серверная панель Hydra (старт после коммита v3.60)
Отдельный VPS для Hydra (без reverse proxy — см. §9, §13.2)
Управление множеством серверов в разных локациях
На каждом сервере все три протокола
Гибридное управление: SSH + агент
Мониторинг через агентов
Порядок плагинов: WDTT → AIVPN → AWG
**Single-process режим** (см. §13.2): панель запускается одним процессом (uvicorn без --workers)
**TLS-ограничение** (см. §13.2): single-process uvicorn слушает один сертификат, поэтому первый домен в panel_domains = primary TLS для unit-файла, остальные домены — либо в SAN-лист того же сертификата (certbot с -d domain1 -d domain2), либо требуют отдельных инстансов панелей (пока не поддерживается; уточнить на FI)

### Этап 3: Телеграм-бот + оплата (ожидание)
### Этап 4: Клиентское приложение (ожидание)

---

## §8 Требования к окружению разработки

### ПК (локальная разработка)
**OS:** Windows 10 64-bit или современный Linux
**Python:** 3.12.x или выше
**Редактор:** VS Code + Python, Pylance, GitLens
**Git:** 2.40+

### VPS под Hydra
**OS:** Ubuntu 24.04 LTS (обязательно) или новее
**CPU:** 1 vCPU
**RAM:** 1-2 GB
**SSD:** 10+ GB
**IPv4:** обязательно
**Системные зависимости:**sudo apt install -y sqlite3 python3 python3-pip python3-venv certbot acl  # v3.48: пакет acl нужен для setfacl/getfacl (§13.2). На стандартном Ubuntu
  # Server он присутствует транзитивно, но на minimal cloud images и в
  # Docker-образе ubuntu:24.04 отсутствует — без него шаг ACL молча
  # провалился бы и панель упала с PermissionError на старте.

### Зависимости проекта

[project.dependencies]
dependencies = [
    "asyncssh>=2.14.0",
    "pydantic>=2.5.0",
    "aiosqlite>=0.19.0",
    "cryptography>=42.0.0",
    "uvicorn>=0.27.0",
    "fastapi>=0.110.0",
    "pyyaml>=6.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "ruff>=0.3.0",
    "mypy>=1.8.0",
]---

## §7 WDTT v17 (сервер) / v18 (клиент)

### Статус версий
**Сервер:** v17 (актуально)
**Клиент:** v18 (вышел 11 сентября 2026)

### Порты и файрвол

| Порт | Протокол | Назначение | Файрвол |
|---|---|---|---|
| 56000/udp | DTLS | Основной транспорт клиентов | **ОТКРЫТЬ внешне** |
| 56001/udp | WireGuard | WG-туннель (backend) | **ОТКРЫТЬ внешне** |
| 9000/udp | служебный | Клиентский порт (уточнить на FI) | **ОТКРЫТЬ внешне** |

sudo ufw allow 56000/udp
sudo ufw allow 56001/udp
sudo ufw allow 9000/udp### Установка сервера (v3.56 — Python/bash разделение; v3.59 — venv Python для YAML)

**На панели (два отдельных блока):**Блок 1 — чтение panel.yaml и валидация MAX_PW:# v3.59: предпочитаем venv Python панели (pyyaml там есть после развёртывания
# кода панели), а не host python3 (на чистой Ubuntu без python3-yaml упал бы
# на import yaml). Fallback на host python3 только если venv ещё не существует
# (первый запуск до развёртывания кода панели) — в этом случае требуем pyyaml.
# Согласовано с deploy-hook (§13.2), который уже использует venv Python.
PY=/opt/hydra/venv/bin/python
if [ ! -x "$PY" ]; then
    PY=python3
    if ! python3 -c "import yaml" 2>/dev/null; then
        echo "ERROR: /opt/hydra/venv/bin/python отсутствует и host python3 не имеет pyyaml." >&2
        echo "Установите: sudo apt install -y python3-yaml, или разверните код панели (§13.2, шаги 1-3 «Развёртывание кода панели»)." >&2
        exit 1
    fi
fi
MAX_PW=$("$PY" -c "
import yaml
config = yaml.safe_load(open('/etc/hydra/panel.yaml'))
print(config['defaults']['max_passwords_wdtt'])
")

# Валидация MAX_PW (bash)
if ! [[ "$MAX_PW" =~ ^[0-9]+$ ]] || [ "$MAX_PW" -lt 1 ] || [ "$MAX_PW" -gt 10000 ]; then
    echo "ERROR: max_passwords_wdtt must be an integer in [1, 10000], got: '$MAX_PW'" >&2
    exit 1
fiБлок 2 — SSH с закавыченным heredoc, MAX_PW передаётся через префикс ssh:ssh -o ConnectTimeout=30 -o ServerAliveInterval=15 -o ServerAliveCountMax=3 \
    root@server_ip "HYDRA_MAX_PW=$MAX_PW bash -s" <<'REMOTE_SCRIPT_HYDRA_EOF'
set -euo pipefail

GO_VER=$(curl -s "https://go.dev/VERSION?m=text" | head -1)
wget -q "https://go.dev/dl/${GO_VER}.linux-amd64.tar.gz"
sudo tar -C /usr/local -xzf "${GO_VER}.linux-amd64.tar.gz"
echo 'export PATH=$PATH:/usr/local/go/bin' | sudo tee /etc/profile.d/go.sh
sudo chmod +x /etc/profile.d/go.sh
export PATH=$PATH:/usr/local/go/bin

MAX_PW=${HYDRA_MAX_PW:-50}

cd /root
openssl rand -base64 32 | tr -d '\n' | sudo tee /root/wdtt-main.pass > /dev/null
sudo chmod 600 /root/wdtt-main.pass
if [ ! -s /root/wdtt-main.pass ]; then
    echo "ERROR: wdtt-main.pass is empty"
    exit 1
fi

git clone https://github.com/Ivan4537/WDTT-Plus.git
cd WDTT-Plus
go build -o wdtt-server .

cd server-installer
sudo bash install.sh init-config \
  --output /root/wdtt-initial.json \
  --password-file /root/wdtt-main.pass \
  --dtls-port 56000 --wg-port 56001 --client-port 9000 \
  --dns 1.1.1.1 --max-passwords "$MAX_PW" --yes

sudo bash install.sh install \
  --binary /root/WDTT-Plus/wdtt-server \
  --config /root/wdtt-initial.json \
  --dtls-port 56000 --wg-port 56001 --client-port 9000 \
  --dns 1.1.1.1 --max-passwords "$MAX_PW" \
  --wg-backend kernel --firewall open --yes

echo "WDTT installed successfully, MAX_PW=$MAX_PW"
REMOTE_SCRIPT_HYDRA_EOF### Пути
/usr/local/bin/wdtt-server
/etc/wdtt/
/etc/wdtt/passwords.json
/root/wdtt-main.pass
unit: wdtt.service

### API (JSON в stdin)

MAIN_PW=$(tr -d '\n' < /root/wdtt-main.pass 2>/dev/null || echo "")
if [ -z "$MAIN_PW" ]; then
    echo "WARNING: /root/wdtt-main.pass is empty or missing, skipping wdtt_conns" >&2
    wdtt_conns=0
else
    wdtt_conns=$(jq -n --arg mp "$MAIN_PW" '{main_password:$mp,args:["list"]}' | \
      sudo /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null | \
      jq '.passwords | length' 2>/dev/null || echo 0)
fi###  Заметки
is_deactivated всегда null — не фильтровать
delete принимает password (16 симв), не label
В bash: set +H или printf
wdtt-main.pass не содержит trailing newline (убран через tr -d '\n')

---

## §7 AIVPN v1.1.0

### Порты и файрвол
443/udp — **ОТКРЫТЬ внешне**### Установка

bash -e -c '
git clone https://github.com/infosave2007/aivpn.git
cd aivpn/deploy
sudo bash install-server.sh --mode systemd --port 443

UNIT_FILE=/etc/systemd/system/aivpn-server.service
sudo cp "$UNIT_FILE" "${UNIT_FILE}.bak"
sudo sed -i '\''s/^CapabilityBoundingSet=CAP_NET_ADMIN$/CapabilityBoundingSet=CAP_NET_ADMIN CAP_NET_BIND_SERVICE/; s/^AmbientCapabilities=CAP_NET_ADMIN$/AmbientCapabilities=CAP_NET_ADMIN CAP_NET_BIND_SERVICE/'\'' "$UNIT_FILE"

if ! grep -q "CAP_NET_ADMIN CAP_NET_BIND_SERVICE" "$UNIT_FILE"; then
    echo "ERROR: sed-патч не применился, откат"
    sudo mv "${UNIT_FILE}.bak" "$UNIT_FILE"
    exit 1
fi
rm -f "${UNIT_FILE}.bak"

sudo systemctl daemon-reload
sudo systemctl restart aivpn-server
'###  Заметки
--remove-client принимает **только ID**
Hydra хранит маппинг name  ID в SQLite
stats flush 300s
Порт 443 требует CAP_NET_BIND_SERVICE

---

## §7 AWG v3.1

### Порты и файрвол
51820/udp — **ОТКРЫТЬ внешне**### Реализация: kernel vs userspace

| Реализация | Пакет | Когда использовать |
|---|---|---|
| **kernel module** (рекомендуется) | `amneziawg-dkms` | Продакшен: максимальная производительность |
| **userspace** (fallback) | `amneziawg-go` | Контейнеры, неподдерживаемые ядра |

### Параметры обфускации

**Эволюция заголовков:****1.x:** фиксированные значения
**2.0:** H1-H4 стали **диапазонами**
**3.x:** диапазоны сохранены, **плюс** HeaderProtectionKey, RandomTrailers, DisableCookies и новые таймеры

**Имена в netlink:** init_header, resp_header, cookie_header, transport_header (u32_range). Имена в awg-quick: **уточнить на FI**.### Установка (kernel, с headers и debconf)

sudo apt install -y linux-headers-$(uname -r)
DEBIAN_FRONTEND=noninteractive sudo apt install -y iptables-persistent
sudo add-apt-repository ppa:amnezia/ppa -y
sudo apt update

# v3.55: имя пакета в PPA Amnezia варьируется. Обычно это метапакет
# `amneziawg`, который тянет `amneziawg-dkms` + `amneziawg-tools`.
# На некоторых сборках PPA могут быть отдельные пакеты. Пробуем
# метапакет первым; если его нет — ставим отдельные.
# Уточнить точные имена пакетов на FI-сессии перед продакшен-деплоем.
if apt-cache show amneziawg >/dev/null 2>&1; then
    sudo apt install -y amneziawg
else
    sudo apt install -y amneziawg-dkms amneziawg-tools
fi

# Проверка что модуль доступен
sudo modprobe amneziawg
lsmod | grep amneziawg || { echo "ERROR: amneziawg module не загружен"; exit 1; }### Обязательные шаги (идемпотентные iptables)

sudo sysctl -w net.ipv4.ip_forward=1
echo 'net.ipv4.ip_forward=1' | sudo tee /etc/sysctl.d/99-forward.conf
sudo sysctl -w net.ipv6.conf.all.forwarding=1
echo 'net.ipv6.conf.all.forwarding=1' | sudo tee -a /etc/sysctl.d/99-forward.conf

EXT_IFACE=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
[ -z "$EXT_IFACE" ] && EXT_IFACE=$(ip -4 route ls default | awk '{print $5; exit}')
[ -z "$EXT_IFACE" ] && EXT_IFACE="eth0"

sudo iptables -t nat -C POSTROUTING -o "$EXT_IFACE" -j MASQUERADE 2>/dev/null || \
  sudo iptables -t nat -A POSTROUTING -o "$EXT_IFACE" -j MASQUERADE

if ip -6 addr show scope global dev "$EXT_IFACE" 2>/dev/null | grep -q inet6; then
    sudo ip6tables -t nat -C POSTROUTING -o "$EXT_IFACE" -j MASQUERADE 2>/dev/null || \
      sudo ip6tables -t nat -A POSTROUTING -o "$EXT_IFACE" -j MASQUERADE
fi

sudo ufw allow 51820/udp

sudo iptables-save | sudo tee /etc/iptables/rules.v4
if command -v ip6tables-save >/dev/null 2>&1; then
    sudo ip6tables-save | sudo tee /etc/iptables/rules.v6
fi---

## §7.1 AWG Obfuscation Policy

### Принцип
Handshake-механизмы бесплатны для throughput. Per-packet дороги.| Механизм | Уровень | Цена |
|---|---|---|
| Jc, Jmin/Jmax | handshake | Jc × (Jmin+Jmax)/2 Б |
| S1/S2 | handshake | +S1+S2 Б |
| S3/S4 (jitter) | handshake | +0…S мс |
| **HeaderProtectionKey** | **per-packet** | **~0 Б** (in-place) |
| **RandomTrailers** | **per-packet** | **+0…trailers_max Б к DATA** |
| **DisableCookies** | **handshake** | **~0** |

### ЖЁСТКОЕ ПРАВИЛО ЯДРА (коммит 31.07.2026)

**Если установлен HeaderProtectionKey, то ВСЕ S1..S4 должны быть >= 12.** Иначе ядро возвращает -EINVAL.### Профили

| Параметр | fast | balanced | stealth |
|---|---|---|---|
| Jc | 2 | 6 | 12 |
| Jmin-Jmax | 10-100 | 32-512 | 64-1024 |
| S1 / S2 | 12 / 12 | 24 / 24 | 48 / 48 |
| S3 / S4 | 12 / 12 | 16 / 16 | 24 / 24 |
| header_protection | ON | ON | ON |
| trailers_max | 0 | 0 | 32 |
| RandomTrailers | OFF | OFF | ON |
| DisableCookies | OFF | ON | ON |
| mtu | 1420 | 1400 | 1360 |
| effective MTU | 1420 | 1400 | 1328 |
| **Оверхед** | **<0.1%** | **<0.5%** | **~2-3%** |

**Дефолт:** balanced### Валидатор

VALIDATOR = {
    "Jc":           (2, 20),
    "Jmin":         (10, 1200),
    "Jmax":         (64, 1200),
    "S1":           (12, 128),
    "S2":           (12, 128),
    "S3":           (12, 128),
    "S4":           (12, 128),
    "trailers_max": (0, 64),
    "mtu":          (1280, 1420),
}

def validate(params):
    errors = []
    for key, (lo, hi) in VALIDATOR.items():
        if not (lo <= params[key] <= hi):
            errors.append(f"{key} out of range [{lo},{hi}]")
    if params["Jmin"] > params["Jmax"]:
        errors.append("Jmin must be <= Jmax")
    if params.get("header_protection"):
        for s in ("S1", "S2", "S3", "S4"):
            if params[s] < 12:
                errors.append(f"HeaderProtectionKey requires {s} >= 12")
    if params["S1"] + 56 == params["S2"]:
        errors.append("S1 + 56 must not equal S2")
    if params["RandomTrailers"] != (params["trailers_max"] > 0):
        errors.append("RandomTrailers must be ON iff trailers_max > 0")
    if params["mtu"] - params["trailers_max"] < 1280:
        errors.append("effective MTU must be >= 1280")
    if params["Jc"] == 1:
        errors.append("Jc == 1 gives constant signature")
    return errors### Интерфейс плагина

from typing import Optional, Literal

PROFILE_ORDER = ("fast", "balanced", "stealth")
ProfileName = Literal["fast", "balanced", "stealth"]

PROFILE_DEFAULTS = {
    "fast":     {"header_protection": True,  "trailers_max": 0,  "disable_cookies": False, "mtu": 1420},
    "balanced": {"header_protection": True,  "trailers_max": 0,  "disable_cookies": True,  "mtu": 1400},
    "stealth":  {"header_protection": True,  "trailers_max": 32, "disable_cookies": True,  "mtu": 1360},
}

class AWGObfuscationPolicy:
    profile: ProfileName
    header_protection: bool
    trailers_max: int
    disable_cookies: bool
    mtu: int

    def __init__(self, profile: ProfileName = "balanced", **overrides):
        if profile not in PROFILE_DEFAULTS:
            raise ValueError(f"Unknown profile: {profile}. Valid: {list(PROFILE_DEFAULTS.keys())}")
        defaults = PROFILE_DEFAULTS[profile].copy()
        defaults.update(overrides)
        self.profile = profile
        self.header_protection = defaults["header_protection"]
        self.trailers_max = defaults["trailers_max"]
        self.disable_cookies = defaults["disable_cookies"]
        self.mtu = defaults["mtu"]

    @property
    def random_trailers(self) -> bool:
        return self.trailers_max > 0

    def server_params(self) -> dict:
        """ВНУТРЕННЯЯ СТРУКТУРА ПЛАГИНА (не awg-quick). Транслируется при записи."""
        return {
            "Jc": ..., "Jmin": ..., "Jmax": ...,
            "S1": ..., "S2": ..., "S3": ..., "S4": ...,
            "header_protection": self.header_protection,
            "trailers_max": self.trailers_max,
            "disable_cookies": self.disable_cookies,
            "mtu": self.mtu,
        }

    def client_params(self) -> dict:
        """ВНУТРЕННЯЯ СТРУКТУРА ПЛАГИНА (не awg-quick). Транслируется при записи."""
        return {
            "Jc": ..., "Jmin": ..., "Jmax": ...,
            "S1": ..., "S2": ..., "S3": ..., "S4": ...,
            "header_protection": self.header_protection,
            "trailers_max": self.trailers_max,
            "mtu": self.mtu,
        }

    def overhead_pct(self, mtu: int) -> float: ...

    def degrade(self) -> Optional["AWGObfuscationPolicy"]:
        """stealth -> balanced -> fast -> None."""
        idx = PROFILE_ORDER.index(self.profile)
        if idx == 0: return None
        return AWGObfuscationPolicy(profile=PROFILE_ORDER[idx - 1])

    def upgrade(self) -> Optional["AWGObfuscationPolicy"]:
        """fast -> balanced -> stealth -> None."""
        idx = PROFILE_ORDER.index(self.profile)
        if idx == len(PROFILE_ORDER) - 1: return None
        return AWGObfuscationPolicy(profile=PROFILE_ORDER[idx + 1])---

## §9 Клиентское приложение (Этап 4)

### Концепция
**Один ключ = доступ ко всем серверам.** Клиент сам решает к какому серверу подключаться.### Источник IP для device_connections

Панель получает IP клиента из request.remote_addr (стандартная функция фреймворка).**Запрет reverse proxy:** панель Hydra **НЕ** должна ставиться за reverse proxy (nginx, caddy, cloudflare и т.п.). Причины:reverse proxy подменяет remote_addr на свой IP → детекция /24-коллизий теряет смысл
X-Forwarded-For / X-Real-IP заголовки легко подделываются без trust-list

**TLS-терминация** осуществляется самой панелью (см. §13.2), без промежуточных прокси.### Универсальный ключ (redeem-модель, БЕЗ секретов)

**Формат ссылки:**hydra://redeem?key=<key_id>&panel=<panel_url>**Ограничение на panel_url (v3.56 — объяснение прямо здесь, ссылка на §7.1 убрана):**
panel_url должен быть **доменом или IPv4-адресом**, IPv6 literal **осознанно не поддерживается**. Причина: IPv6 требует bracket-синтаксиса https://[2001:db8::1]:443 в URL, который нужно поддержать и в regex-валидаторе панели, и в клиенте при построении запросов. Это не было приоритетом в рамках Этапа 2; если потребуется — добавить на FI.Regex-валидация:import re
import ipaddress

# TODO на FI: regex всё ещё пропускает странные смеси типа "1.2.3.4evil.com"
# (матчится как домен, не как IPv4). Не security-проблема (whitelist + HTTPS
# всё равно защищают), но стоит ужесточить.
PANEL_URL_REGEX = re.compile(
    r'^https://('
    r'(?:[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}'
    r'|'
    r'(?:\d{1,3}\.){3}\d{1,3}'
    r')'
    r'(?::(\d{1,5}))?'
    r'/?$'
)

def validate_panel_url(url: str) -> bool:
    m = PANEL_URL_REGEX.match(url)
    if not m:
        return False
    
    host, port_str = m.group(1), m.group(2)
    
    if port_str is not None:
        port = int(port_str)
        if not (1 <= port <= 65535):
            return False
    
    if re.match(r'^(?:\d{1,3}\.){3}\d{1,3}$', host):
        try:
            ipaddress.IPv4Address(host)
        except ipaddress.AddressValueError:
            return False
    
    return True**Защита panel_url:****Обязательный HTTPS**
**Whitelist доменов** (хардкод в клиенте, должен совпадать с panel_domains в panel.yaml):ALLOWED_PANEL_DOMAINS = [
    "panel.hydra.example",
    "panel2.hydra.example",
]
**Fallback при UNTRUSTED_PANEL:** клиент **не блокируется** — продолжает работать с сохранённым кэшем. Показывает предупреждение + ссылку на обновление.

**Инвариант синхронизации:** при смене домена панели обязательны два одновременных изменения: panel.yaml и ALLOWED_PANEL_DOMAINS в клиенте. Проверяется в CI (§16).**Процесс redeem:**Клиент парсит key_id и panel_url
Валидирует panel_url через whitelist + HTTPS + regex + ipaddress
Генерирует device_id = SHA256(hardware_id + app_install_id)
Отправляет: POST {panel_url}/api/v1/client/redeem:{
  "key_id": "ABC123XYZ789",
  "device_id": "<sha256>",
  "device_name": "Pixel 8"
}
Панель проверяет (с race protection через BEGIN IMMEDIATE, см. §14):Ключ активен (не истёк, не отозван)
Если device_id уже зарегистрирован → **возвращает актуальный конфиг** (идемпотентно)
Если новый и лимит < 3 → регистрирует, возвращает конфиг
Если новый и лимит = 3 → DEVICE_LIMIT_REACHED

Возвращает полный конфиг по TLS
Клиент кэширует локально (AES-256)
Периодически обновляет список серверов через GET /api/v1/client/servers?key_id=...

### Политика device_id

**Формула:** device_id = SHA256(hardware_id + app_install_id)**Источники:**hardware_id: Android — Settings.Secure.ANDROID_ID; iOS — identifierForVendor; Windows — MachineGuid; Linux — /etc/machine-id
app_install_id: случайный UUID при первом запуске

**Детекция подозрительной активности:**Каждый redeem пишет запись в device_connections с предвычисленным subnet_key (IPv4 → /24, IPv6 → /64, нормализовано, см. §14).SELECT COUNT(DISTINCT subnet_key)
FROM device_connections
WHERE key_id = ? AND device_id = ? AND connected_at > datetime('now', '-5 minutes')

Если результат > 1:
    → АЛЕРТ в @hydra_alerts
    → redeem НЕ отклоняется
    → Админ решает вручную через /kick### Алгоритм выбора (двухуровневый)

**Уровень 1:** Выбор сервера (по задержке/потерям)  
**Уровень 2:** Выбор протокола (по скорости/стабильности)**Метрики:**| Метрика | Вес |
|---|---|
| RTT | 30% |
| Packet Loss | 25% |
| Bandwidth | 25% |
| Jitter | 10% |
| Stability | 10% |

### Переключение при деградации

Сначала переключаем протокол на том же сервере
Если все три протокола деградировали → переключаемся на другой сервер
При плохой сети — degrade() обфускации AWG
При восстановлении — upgrade()

---

## §10 Телеграм-бот (Этап 3)

### Архитектура бота
**Один бот:** @hydra_panel_bot**Личка:** покупка, управление
**Приватный канал `@hydra_alerts`:** алерты

### Технология
**Фреймворк:** aiogram 3.x
**Хостинг:** тот же VPS что и панель
**БД:** общая с панелью (SQLite)

### Функционал
Пользователи: /start, /buy, /mykeys, /revoke, /devices, /kick, /support
Админ: /status, /load, /forecast, /report, /alerts_on, /alerts_off

### Логика покупки
Пользователь выбирает тариф
Бот принимает оплату
Бот вызывает панель: issue_universal_key()
Панель создаёт клиентов на всех серверах для всех протоколов
Бот отправляет ссылку hydra://redeem?key=...&panel=...

### Приём платежей
Telegram Stars, СБП, криптовалюта

### Интеграция с панелью

async def issue_universal_key(user_id, expiry_days, max_devices=3) -> str: ...
async def revoke_key(key_id) -> bool: ...
async def list_devices(key_id) -> list: ...
async def kick_device(key_id, device_id) -> bool: ...
async def bot_heartbeat(bot_id, uptime, last_update_id, pending_updates) -> bool: ...### Fallback healthcheck

# GET /api/v1/bot/token-check — возвращает ТОЛЬКО {"ok": bool}
# Вызывается ТОЛЬКО панелью
async def token_check() -> dict:
    ok = await curl_getme_to_telegram()
    return {"ok": ok}---

## §11 Безопасность

### Клиентская часть
Универсальные ключи НЕ содержат секретов — только key_id
panel_url валидируется через whitelist + HTTPS + ужесточённый regex + ipaddress
Конфиги с секретами хранятся локально в зашифрованном виде (AES-256)
Секреты протоколов хранятся в зашифрованном виде
device_id не содержит персональных данных
Логи не содержат ключей или секретов
При UNTRUSTED_PANEL — клиент работает со старым кэшем, показывает предупреждение

### Серверная часть
Пароли клиентов хранятся как хеши
SSH с ключами между панелью и серверами
Redeem только по TLS
БД: секреты в client_config_enc зашифрованы AES-256 через cryptography; **ключ шифрования в EnvironmentFile, не в Environment=** (защита от systemctl show); проверка файла через grep без печати секрета (§13.2)
Доступ к панели только через SSH-ключи
**Панель НЕ ставится за reverse proxy** (см. §9) — IP читается из request.remote_addr
TLS-терминация на самой панели (§13.2): systemd AmbientCapabilities; применение новых сертификатов — restart через deploy-hook или вручную (ExecReload в unit отсутствует)
Токены агентов: **constant-time** сравнение через hmac.compare_digest в Python

### Телеграм-бот
Платежи через официальные системы
Ссылки только в личку
Админ-команды с 2FA

### Обновление клиента
Проверка подписи
HTTPS + подпись

---

## §12 Мониторинг и алертинг

### Архитектура

**Агенты** (§15) → каждые 60 сек → **Панель** → **Канал @hydra_alerts**### Уровни алертов

| Уровень | Триггер | Канал |
|---|---|---|
|  Предупреждение | Порог превышен | Канал алертов |
|  Критический | Упало или перегружено | Канал + личка |
|  Прогноз | Пора масштабироваться | Канал |
|  Отчёт | Еженедельно (пн 09:00 UTC, с catch-up) | Канал |
|  Подозрительная активность | Один device_id с 2+ subnet_key за 5 мин | Канал |

### Пороги алертов

| Метрика | Предупреждение | Критический | Источник |
|---|---|---|---|
| **CPU** | > 80% (5 мин) | > 95% (2 мин) | `/proc/stat` (все 8 полей) |
| **Память** | > 85% | > 95% | `free` (available, LANG=C) |
| **Сетевой трафик** | > 80% канала | > 95% канала | агент / `servers.bandwidth_mbps` (NOT NULL) |
| **Подключения** | > 80% лимита | > 95% лимита | агент / `protocol_instances.max_connections` (NOT NULL DEFAULT 50) |
| **Статус протокола** | — | Протокол упал | `systemctl is-active` (WDTT/AIVPN), `awg show` (AWG) |
| **Задержка до панели** | > 200 мс | > 500 мс | агент (TCP primary, ICMP fallback) |

### Прогноз масштабирования (по max)

max_загрузка = max(загрузка_серверов)
тренд = (max_сегодня - max_7_дней_назад) / 7
дней_до_критической = (95 - max_загрузка) / тренд

Edge-cases:
1. Мало данных (< 7 дней) → не прогнозировать
2. тренд <= 0 → не прогнозировать
3. дней_до_критической > 90 → не спамить
4. Серверы < 7 дней → исключать из max
5. Отправка только при 0 < дней < 14### Еженедельный отчёт (catch-up с N отчётами)

Планируется на понедельник 09:00 UTC
**Catch-up логика:** если панель была недоступна, cron-джоб проверяет weekly_reports на наличие записей за последние N недель (максимум 12). Для каждой пропущенной недели генерирует отдельный отчёт

### Healthcheck бота

Процесс бота каждые 5 минут: POST /api/v1/bot/heartbeat
Панель сохраняет в bot_heartbeats, проверяет свежесть
Если heartbeat старше 10 минут → критический алерт
Если last_update_id не растёт при pending_updates > 0 → алерт «бот завис»

**Fallback:** панель каждые 5 минут вызывает GET /api/v1/bot/token-check.---

## §13 Серверная инфраструктура

### Управление серверами

| Операция | Метод |
|---|---|
| Проверка статуса | SSH (с ConnectTimeout=30, ServerAliveInterval=15) |
| Установка протокола | SSH + плагины |
| Создание/удаление клиента | SSH + API протокола |
| Мониторинг | Агент (§15) |
| Обновление | Агент |
| Резервное копирование | Агент |
| Диагностика | Агент |

### §13.1 Конфигурация панели

**Примечание (v3.51):** директория /etc/hydra создаётся в §13.2 («Подготовка пользователя и директорий») до unit-файла. Если читаете §13.1 первым — сначала выполните mkdir -p /etc/hydra из §13.2.Файл /etc/hydra/panel.yaml:admin_timezone: "Europe/Moscow"

# v3.56: порядок доменов имеет значение. Первый — primary для TLS
# (на него выдаётся сертификат, его имя используется в unit-файле).
# Остальные — либо добавляются в SAN того же сертификата через
# certbot -d domain1 -d domain2 (multi-domain cert), либо требуют
# отдельных инстансов панелей (пока не поддерживается).
panel_domains:
  - panel.hydra.example      # primary TLS
  - panel2.hydra.example     # SAN или отдельный инстанс

defaults:
  max_passwords_wdtt: 50
  max_connections_per_protocol: 50

alerts_channel_id: -100XXXXXXXX
admin_user_ids:
  - 123456789

db_encryption_key_env: "HYDRA_DB_KEY"

agent_token_rotation_days: 90
agent_token_grace_period_hours: 24**Инвариант синхронизации:** при смене panel_domains **обязательно** одновременное обновление ALLOWED_PANEL_DOMAINS в клиенте. Проверяется в CI (§16).### §13.2 TLS на панели (v3.57 — баг в awk-fallback исправлен)

Поскольку reverse proxy запрещён (§9), TLS-терминация выполняется **самой панелью**. Это требует валидного сертификата и привязки к порту 443.**Архитектурное ограничение single-process TLS (v3.56):**Uvicorn в single-process режиме слушает **один** сертификат, который задаётся в --ssl-certfile и --ssl-keyfile при старте. Поэтому:**Первый домен в `panel_domains` = primary TLS** для unit-файла (его имя используется в ExecStart)
**Остальные домены** обрабатываются одним из двух способов:**SAN (Subject Alternative Name)** — все домены в одном сертификате через certbot -d domain1 -d domain2. Uvicorn видит один cert-файл, но cert валиден для всех доменов. Это рекомендуемый путь.
**Отдельные инстансы панелей** — каждая панель со своим unit-файлом на своём порту. Пока **не поддерживается** в рамках single-process архитектуры.

Если в panel_domains >1 домена — использовать certbot с -d для каждого домена, чтобы cert был валиден для всех. Иначе второй домен будет отдавать ошибку TLS «certificate not valid for this hostname».**Осознанный архитектурный выбор:**Uvicorn (и большинство ASGI-серверов) **не перечитывают TLS-сертификаты по SIGHUP** без хрупкого monkey-patch, зависящего от версии uvicorn. Поэтому:SIGHUP-обработчика в коде нет (убран в v3.42)
**ExecReload в unit отсутствует** (убран в v3.43): systemctl reload hydra-panel честно отвечает ошибкой «Job type reload is not applicable for this unit»
Новые сертификаты применяются **рестартом**: автоматически certbot deploy-hook после renew, либо вручную systemctl restart hydra-panel

**Почему это приемлемо:**Панель на 1 vCPU, количество одновременных пользователей — единицы
Restart занимает ~1-2 секунды
Restart по renew сертификата происходит раз в ~60 дней
Downtime ~1-2 секунды раз в 2 месяца — приемлемо
Взамен: простота, надёжность, независимость от версии uvicorn, отсутствие нерекомендуемого systemd вызова systemctl из ExecReload

**Single-process режим (обязательное требование):**Панель запускается через uvicorn **без `--workers`** (single-process). Это упрощает управление состоянием и гарантирует что все операции выполняются в одном процессе.**Подготовка пользователя и директорий (v3.50/v3.51 — выполняется ДО unit-файла):**# v3.50: идемпотентное создание системного пользователя hydra.
# v3.51: проверка усилена — добавлена проверка группы (edge-case:
# пользователь есть, группа удалена/не создана вручную).
# Без этого шага chown hydra:hydra падает с "invalid user"/"invalid group",
# sudo -u hydra — с "unknown user", а unit — с
# "Failed to determine user credentials" (exit 200).
# -r → system user (UID < 1000)
# -U → создать одноимённую группу
# -s /usr/sbin/nologin → запрет интерактивного входа
# -d /nonexistent → честный home для системного пользователя
#                   (не создаётся; рабочая директория /opt/hydra ниже)
# -M → НЕ создавать домашнюю директорию
if ! id hydra >/dev/null 2>&1 || ! getent group hydra >/dev/null 2>&1; then
    sudo useradd -r -U -s /usr/sbin/nologin -d /nonexistent -M hydra
fi

# Проверка
id hydra
# Ожидаемо: uid=...(hydra) gid=...(hydra) groups=...(hydra)

# v3.50: рабочие директории. Без /etc/hydra команда
# `tee /etc/hydra/panel.env` падает с "No such file or directory";
# без /opt/hydra unit падает на WorkingDirectory (exit 200);
# в /var/lib/hydra живёт panel.db (§14, cleanup cron).
sudo mkdir -p /opt/hydra /etc/hydra /var/lib/hydra
sudo chown hydra:hydra /opt/hydra /etc/hydra /var/lib/hydra

# Права: /opt/hydra — 755 (venv и код), /etc/hydra и /var/lib/hydra —
# 750 (секреты и БД не должны быть доступны посторонним)
sudo chmod 755 /opt/hydra
sudo chmod 750 /etc/hydra /var/lib/hydra

# Проверка
ls -ld /opt/hydra /etc/hydra /var/lib/hydra
# Ожидаемо:
# drwxr-xr-x hydra hydra ... /opt/hydra
# drwxr-x--- hydra hydra ... /etc/hydra
# drwxr-x--- hydra hydra ... /var/lib/hydra**Развёртывание кода панели (v3.51/v3.52/v3.53 — до unit-файла):**Точный процесс (release tarball vs git clone, конкретный тег/коммит) уточняется в Этапе 2. Ниже — минимальный скелет, который гарантирует, что unit-файл сможет стартовать. Без этих шагов systemd упадёт с «Failed at step EXEC spawning /opt/hydra/venv/bin/uvicorn: No such file or directory» или uvicorn — с ModuleNotFoundError.# 1. Код панели → /opt/hydra/src (от имени hydra, чтобы не было root-owned файлов).
#    v3.52: упрощено — [ ! -d /opt/hydra/src ] покрывает все случаи:
#    директория отсутствует, или присутствует как пустая (после ручной распаковки
#    tarball или неудачного clone) — в обоих случаях нужен clone/распаковка.
#    URL и тег уточняются в Этапе 2; в прототипе используется dev-ветка.
if [ ! -d /opt/hydra/src ]; then
    sudo -u hydra git clone https://github.com/<org>/hydra-panel.git /opt/hydra/src
    # ИЛИ: распаковка release tarball в /opt/hydra/src:
    #   sudo -u hydra tar -xzf /tmp/hydra-panel-<version>.tar.gz -C /opt/hydra/src --strip-components=1
fi

# Проверка что в /opt/hydra/src есть pyproject.toml (иначе pip install ниже упадёт)
if [ ! -f /opt/hydra/src/pyproject.toml ]; then
    echo "ERROR: /opt/hydra/src/pyproject.toml не найден — clone/распаковка не удалась"
    exit 1
fi

# 2. venv (в /opt/hydra/venv — именно сюда ссылается unit-файл)
if [ ! -d /opt/hydra/venv ]; then
    sudo -u hydra python3 -m venv /opt/hydra/venv
fi

# 3. Зависимости из pyproject.toml панели (§8). -e → editable install
#    (удобно при разработке; в продакшене можно `pip install /opt/hydra/src`).
#    v3.52: благодаря --app-dir в unit-файле (см. ниже) editable install стал
#    удобным, но не критичным — uvicorn импортирует hydra из исходников напрямую.
sudo -u hydra /opt/hydra/venv/bin/pip install --upgrade pip
sudo -u hydra /opt/hydra/venv/bin/pip install -e /opt/hydra/src

# 4. Проверка что модуль импортируется и атрибут app существует.
#    v3.53: убран PYTHONPATH=/opt/hydra/src — editable install (шаг 3)
#    уже настроил путь импорта через egg-link в site-packages.
#    Это решает проблему портативности: на Ubuntu 24.04 по умолчанию
#    env_reset включён, setenv — нет, команда
#    `sudo -u hydra PYTHONPATH=... /opt/hydra/venv/bin/python`
#    упадёт с "sudo: PYTHONPATH=...: command not found".
#    --app-dir путь уже покрыт smoke-тестом шага 5.
sudo -u hydra /opt/hydra/venv/bin/python -c 'import hydra.panel; print(hydra.panel.app)'
# Ожидаемо: <fastapi.applications.FastAPI object at 0x...>
# Если падает с ModuleNotFoundError — зависимости не установились.
# Если падает с AttributeError — не определён атрибут `app` в hydra/panel.py.

# 5. Smoke-тест: запуск uvicorn на тестовом порту 8443, ожидаем строку
#    "Application startup complete" в логах.
#    v3.52: перехват через output=$(...) вместо pipe `| head -20`.
#    Старый вариант `timeout ... | head -20 && echo OK` всегда «проходил»:
#    exit code pipe = exit code последней команды (head), который 0 при
#    любом выводе. Новый вариант:
#    - `rc=$?` — реальный код возврата uvicorn/timeout
#    - `rc == 124` — timeout сработал, uvicorn ещё работал (успешный старт)
#    - `grep "Application startup complete"` — uvicorn действительно стартовал,
#      а не упал с ModuleNotFoundError на первой строке
#    --app-dir /opt/hydra/src — тот же путь, что в unit-файле
#    v3.53: echo "$output" → printf '%s\n' "$output" — защита от edge-case
#    если output начинается с -n или содержит backslash-последовательности
#    v3.54: timeout 3 → timeout 5 — запас для тяжёлых импортов
#    (fastapi + asyncssh + cryptography на 1 vCPU могут стартовать 2-3 сек
#    под нагрузкой; 3 сек было на грани). Также детализация диагностики:
#    при rc=124 без "Application startup complete" выводится раздельная
#    интерпретация (timeout fired vs uvicorn crashed), чтобы оператор не
#    искал ошибку в коде uvicorn при обычном медленном старте
output=$(timeout 5 sudo -u hydra /opt/hydra/venv/bin/uvicorn hydra.panel:app \
    --app-dir /opt/hydra/src \
    --host 127.0.0.1 --port 8443 --no-access-log 2>&1)
rc=$?
if [ "$rc" -eq 124 ] && printf '%s\n' "$output" | grep -q "Application startup complete"; then
    echo "✓ uvicorn smoke-test OK"
else
    echo "✗ uvicorn smoke-test FAILED"
    if [ "$rc" -eq 124 ]; then
        echo "  rc=124 (timeout fired) but 'Application startup complete' not found in logs"
        echo "  → uvicorn was still starting up when killed. Possible causes:"
        echo "    - system under load (try again with no parallel operations)"
        echo "    - very slow disk / cold cache (first run after boot)"
        echo "    - missing dependency (check pip install step 3 above)"
    else
        echo "  rc=$rc (uvicorn crashed before startup)"
        echo "  → check logs below for ModuleNotFoundError / syntax errors"
    fi
    printf '%s\n' "$output" | head -20
    exit 1
fi**Unit-файл systemd (v3.56 — комментарий про primary domain):**# /etc/systemd/system/hydra-panel.service
[Unit]
Description=Hydra Control Panel
# network-online.target обеспечивает синхронизацию старта
# с настроенной маршрутизацией (если соответствующий wait-online-сервис включён).
# БЕЗ включённого systemd-networkd-wait-online.service или
# NetworkManager-wait-online.service target сработает мгновенно
# вместе с network.target, и гарантии не будет (см. чек-лист ниже).
After=network-online.target
Wants=network-online.target

# StartLimit* принадлежат секции [Unit] (перенесены туда в systemd v229, 2016).
# В [Service] они ИГНОРИРУЮТСЯ с warning в journal.
StartLimitIntervalSec=60
StartLimitBurst=5

[Service]
Type=simple
User=hydra
Group=hydra
WorkingDirectory=/opt/hydra

# Секреты читаются из файла, не видны через systemctl show
EnvironmentFile=/etc/hydra/panel.env

# AmbientCapabilities привязан к unit, не к бинарнику Python
CapabilityBoundingSet=CAP_NET_BIND_SERVICE
AmbientCapabilities=CAP_NET_BIND_SERVICE

# v3.56: ExecStart ссылается на cert первого домена из panel_domains
# (primary TLS). Остальные домены должны быть в SAN того же cert
# через certbot -d domain1 -d domain2, иначе клиенты второго домена
# получат TLS-ошибку "certificate not valid for this hostname".
# Если нужны отдельные сертификаты для каждого домена — требуется
# отдельный unit-файл с отдельным портом (не поддерживается в
# single-process архитектуре v3.56).
#
# v3.52: --app-dir /opt/hydra/src добавляет /opt/hydra/src в sys.path,
# поэтому uvicorn импортирует hydra напрямую из исходников, независимо
# от editable install (pip install -e). Если editable install провалится,
# unit всё равно запустится; если будет обычный pip install, --app-dir
# переопределит путь импорта. Это делает зависимость от -e удобной, но
# не критичной.
ExecStart=/opt/hydra/venv/bin/uvicorn hydra.panel:app \
    --app-dir /opt/hydra/src \
    --host 0.0.0.0 --port 443 \
    --ssl-certfile /etc/letsencrypt/live/panel.hydra.example/fullchain.pem \
    --ssl-keyfile /etc/letsencrypt/live/panel.hydra.example/privkey.pem

# ExecReload ОТСУТСТВУЕТ (осознанно, с v3.43 — см. changelog).
# Применение новых сертификатов: deploy-hook (авто) или systemctl restart (вручную).
# ЗАМЕТКА: если ExecReload когда-либо вернётся — использовать %n вместо
# литерального имени unit.

# Restart=always + уточнение про kill -TERM
Restart=always
RestartSec=5
# ВАЖНО: Restart=always перезапускает при ЛЮБОМ exit (включая clean exit 0,
# signal, failure). Явная остановка — ТОЛЬКО через systemctl stop.
# kill -TERM $MAINPID напрямую (не через systemctl) systemd видит как
# обычный exit и ПЕРЕЗАПУСКАЕТ процесс. Это стандартная семантика systemd.

[Install]
WantedBy=multi-user.target**Чек-лист деплоя (проверка network-online.target работает):**# Проверить что один из wait-online-сервисов включён
# (без этого network-online.target срабатывает мгновенно с network.target,
# и гарантии настроенной маршрутизации перед стартом НЕТ)

# Для systemd-networkd:
systemctl is-enabled systemd-networkd-wait-online.service 2>/dev/null
# Для NetworkManager:
systemctl is-enabled NetworkManager-wait-online.service 2>/dev/null

# Интерпретация вывода:
# - "enabled" — сервис включён, network-online.target работает корректно ✓
# - "disabled" или "masked" — включить нужный:
#     sudo systemctl enable systemd-networkd-wait-online.service
#     # или
#     sudo systemctl enable NetworkManager-wait-online.service
# - Пустой вывод + exit 1 — сервис отсутствует в системе.
#   Это нормально для минимального Ubuntu без systemd-networkd или
#   NetworkManager. В этом случае:
#   * На VPS с cloud-init — cloud-init сам настраивает сеть, можно положиться на него
#   * На bare-metal или custom-образе — установить нужный пакет:
#       sudo apt install -y systemd-networkd
#       sudo systemctl enable systemd-networkd-wait-online.service

# На типичных VPS с cloud-init systemd-networkd-wait-online обычно включён.
# На чистом Ubuntu Server без cloud-init — может быть выключен.**Верификация после установки unit:**sudo systemctl daemon-reload
systemctl show hydra-panel | grep -E 'StartLimit|Restart'
# Ожидаемо:
# StartLimitIntervalUSec=1min
# StartLimitBurst=5
# Restart=always
# RestartUSec=5s          <-- соответствует RestartSec=5
# Если StartLimitIntervalUSec=10s — параметры остались не в [Unit], исправить unit.**Файл с секретами `/etc/hydra/panel.env` (grep-верификация):**Создаётся **один раз при первом деплое панели** (после подготовки директорий выше):openssl rand -base64 32 | sudo tee /etc/hydra/panel.env > /dev/null
sudo sed -i '1s/^/HYDRA_DB_KEY=/' /etc/hydra/panel.env
sudo chmod 600 /etc/hydra/panel.env
sudo chown hydra:hydra /etc/hydra/panel.env

# Проверка через grep (не печатает секрет, не режет по = как awk -F=)
if sudo grep -qE '^HYDRA_DB_KEY=.{40,}$' /etc/hydra/panel.env; then
    echo "✓ panel.env создан корректно"
else
    echo "✗ panel.env создан НЕВЕРНО, пересоздайте"
    exit 1
fi**Альтернатива через setcap (с оговорками):**>  **Этот метод имеет существенные недостатки и используется только если systemd-вариант невозможен.**
>
> **Проблемы:**
> - `setcap` на `/opt/hydra/venv/bin/python3` работает с **target'ом symlink'а** (обычно `/usr/bin/python3.x`). Это даёт `CAP_NET_BIND_SERVICE` **всем** пользователям, запускающим системный Python — нежелательно.
> - При пересоздании venv, обновлении Python **capability теряется без предупреждения**. Панель перестанет стартовать на 443.
>
> **Cleanup инструкция:** если экспериментировали с setcap и решили перейти на systemd AmbientCapabilities, обязательно снимите capability:
>
>     getcap /usr/bin/python3* /opt/hydra/venv/bin/python3* 2>/dev/null
>     sudo setcap -r /usr/bin/python3.12  # замените путь на фактический
>
> Без этого система остаётся в небезопасном состоянии: любой скрипт от любого пользователя сможет привязываться к портам < 1024.

**Получение сертификата (certbot standalone с ufw 80/tcp, v3.56 — multi-domain):**sudo apt install -y certbot

# certbot standalone слушает порт 80 для HTTP-01 challenge
sudo ufw allow 80/tcp

# Остановить панель (явный stop не перезапускается при Restart=always)
sudo systemctl stop hydra-panel

# Получить сертификат. v3.56: если panel_domains содержит >1 домена,
# добавить -d для каждого, чтобы cert был валиден для всех.
# Пример для двух доменов:
sudo certbot certonly --standalone \
    -d panel.hydra.example \
    -d panel2.hydra.example \
    --agree-tos \
    --non-interactive \
    --email admin@hydra.example
# ИЛИ для одного домена:
# sudo certbot certonly --standalone -d panel.hydra.example --agree-tos --non-interactive --email admin@hydra.example

# Закрыть 80/tcp обратно
sudo ufw delete allow 80/tcp

# Запустить панель
sudo systemctl start hydra-panel**Права на файлы сертификата (v3.48/v3.49 — acl, getfacl, smoke-тест):**sudo chown -R hydra:hydra /etc/letsencrypt/live/panel.hydra.example
sudo chown -R hydra:hydra /etc/letsencrypt/archive/panel.hydra.example
sudo chmod 750 /etc/letsencrypt/live/panel.hydra.example
sudo chmod 750 /etc/letsencrypt/archive/panel.hydra.example

# v3.48: проверка что setfacl доступен (пакет acl; может отсутствовать
# на minimal cloud images и в Docker-образе ubuntu:24.04)
command -v setfacl >/dev/null || { echo "ERROR: setfacl missing, install: apt install -y acl"; exit 1; }

# v3.47: родительские директории должны пропускать пользователя hydra.
# На части систем /etc/letsencrypt/live/ и /etc/letsencrypt/archive/
# создаются с mode 700 root:root — тогда hydra не пройдёт в поддиректорию,
# даже если сама она 750 hydra:hydra (uvicorn упадёт с PermissionError).
# Предпочтительно ACL: не меняет группу родителя и наследуется на новые
# поддиректории, которые certbot создаёт при renew или смене домена.
sudo setfacl -m u:hydra:x /etc/letsencrypt/live /etc/letsencrypt/archive

# Fallback без ACL (меняет группу родителя; менее устойчиво при renew):
# sudo chgrp hydra /etc/letsencrypt/live /etc/letsencrypt/archive
# sudo chmod 750 /etc/letsencrypt/live /etc/letsencrypt/archive

# Проверка ВСЕГО пути до ключа (все компоненты должны давать hydra право x):
namei -l /etc/letsencrypt/live/panel.hydra.example/privkey.pem

# v3.48: явный просмотр ACL на родителях (ожидаемо в выводе: user:hydra:--x).
# v3.49: если строки user:hydra в выводе нет — ACL не применился, повторите setfacl.
getfacl /etc/letsencrypt/live /etc/letsencrypt/archive

# v3.48: smoke-тест реальной возможности чтения — наиболее надёжная
# проверка, независимая от нюансов ACL/chgrp/namei. Прогонять ПЕРВЫМ
# делом при развёртывании панели.
# v3.49: работает даже для nologin-пользователя: `sudo -u` исполняет команду
# напрямую через execve(2), не запуская shell, поэтому /usr/sbin/nologin
# не препятствует выполнению.
sudo -u hydra test -r /etc/letsencrypt/live/panel.hydra.example/privkey.pem \
  && echo "OK: hydra can read cert" \
  || echo "FAIL: hydra cannot read cert"**Расширение на несколько доменов (v3.48):**setfacl -m u:hydra:x ставит **access ACL** на родительские директории — он одноразовый и НЕ наследуется на поддиректории, создаваемые позже (default ACL -d здесь не используется осознанно). Родительский ACL покрывает traversal для любых будущих поддиректорий, но права на сами новые поддиректории и ключи внутри них нужно выставлять вручную. Поэтому при добавлении каждого нового домена панели повторите:chown -R hydra:hydra /etc/letsencrypt/live/<new-domain>
chown -R hydra:hydra /etc/letsencrypt/archive/<new-domain>
chmod 750 /etc/letsencrypt/live/<new-domain>
chmod 750 /etc/letsencrypt/archive/<new-domain>

# и проверить:
sudo -u hydra test -r /etc/letsencrypt/live/<new-domain>/privkey.pem && echo OK**Автоматическое обновление сертификата с chown в deploy-hook (v3.55 цикл, v3.56 venv, v3.57 баг-фикс в awk):**# Pre-hook: временно открыть 80/tcp для HTTP-01 renew
sudo tee /etc/letsencrypt/renewal-hooks/pre/hydra-open-80 > /dev/null <<'EOF'
#!/bin/bash
ufw allow 80/tcp
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/pre/hydra-open-80

# Post-hook: закрыть 80/tcp после renew (идемпотентно)
sudo tee /etc/letsencrypt/renewal-hooks/post/hydra-close-80 > /dev/null <<'EOF'
#!/bin/bash
ufw delete allow 80/tcp 2>/dev/null || true
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/post/hydra-close-80

# Deploy-hook: chown новых cert-файлов + restart панели.
# v3.55: цикл по panel_domains из panel.yaml вместо хардкода одного
# домена. Раньше проверялось RENEWED_LINEAGE = /etc/letsencrypt/live/panel.hydra.example,
# и renew второго домена (panel2.hydra.example) не делал chown/restart —
# новые cert-файлы оставались root:root, панель теряла доступ.
# Теперь читаем panel_domains из panel.yaml и обрабатываем любой из них.
# v3.56: используем /opt/hydra/venv/bin/python вместо host python3,
# т.к. pyyaml установлен только в venv панели, host Python упадёт с
# ImportError "No module named 'yaml'". Fallback на awk остаётся для
# случая когда venv ещё не создан (например, первый deploy до шага
# "Развёртывание кода панели").
# v3.57: исправлен баг в awk-fallback — было print $3, стало print $2.
# awk с дефолтным FS=whitespace обрезает ведущие пробелы, "-" становится
# первым полем, домен — вторым. Старое $3 было пустой строкой, fallback
# молча не срабатывал. Плюс: $2 корректно работает если в строке есть
# комментарий после домена ("- panel.hydra.example   # primary TLS"),
# тогда $3 был бы "#", а $2 — домен.
sudo tee /etc/letsencrypt/renewal-hooks/deploy/hydra-panel-reload > /dev/null <<'EOF'
#!/bin/bash
# Читаем список доменов из panel.yaml через venv Python (pyyaml там есть)
# или fallback на awk (если venv недоступен в этом контексте)
PANEL_YAML=/etc/hydra/panel.yaml
VENV_PYTHON=/opt/hydra/venv/bin/python
DOMAINS=""
if [ -x "$VENV_PYTHON" ] && "$VENV_PYTHON" -c "import yaml" 2>/dev/null; then
    DOMAINS=$("$VENV_PYTHON" -c "
import yaml
c = yaml.safe_load(open('$PANEL_YAML'))
for d in c.get('panel_domains', []):
    print(d)
")
else
    # v3.57: print $2 вместо $3. awk с FS=whitespace обрезает ведущие
    # пробелы: $1="-", $2=домен. Старое $3 было пустым, fallback не работал.
    # $2 также корректно работает с комментариями в строке домена.
    DOMAINS=$(awk '/^panel_domains:/{flag=1; next} /^[^ ]/{flag=0} flag && /^  - /{print $2}' "$PANEL_YAML")
fi

# Проверяем, соответствует ли RENEWED_LINEAGE одному из доменов панели
LINEAGE_DOMAIN=$(basename "$RENEWED_LINEAGE")
MATCHED=""
for d in $DOMAINS; do
    if [ "$LINEAGE_DOMAIN" = "$d" ]; then
        MATCHED="$d"
        break
    fi
done

if [ -n "$MATCHED" ]; then
    # Применяем chown к обоим путям: live/<domain> и archive/<domain>
    chown -R hydra:hydra "/etc/letsencrypt/live/$MATCHED"
    chown -R hydra:hydra "/etc/letsencrypt/archive/$MATCHED"

    # v3.47: родительские директории (/etc/letsencrypt/live, /etc/letsencrypt/archive)
    # здесь НЕ трогаем: при рекомендованной схеме ACL права на них наследуются
    # и переживают renew. Раскомментируйте строку ниже ТОЛЬКО если использовали
    # fallback-схему chgrp при деплое:
    # chgrp hydra /etc/letsencrypt/live /etc/letsencrypt/archive 2>/dev/null || true

    # ExecReload в unit отсутствует — restart является единственным
    # способом применить новые сертификаты
    systemctl --no-block restart hydra-panel || true
fi
# Если RENEWED_LINEAGE не соответствует ни одному panel_domains — hook молча
# завершается (другой сертификат на этой машине, не панели Hydra)
EOF
sudo chmod +x /etc/letsencrypt/renewal-hooks/deploy/hydra-panel-reload**Примечание о cron.d/certbot:** после apt install certbot автоматически создаётся /etc/cron.d/certbot, который запускает certbot renew дважды в день. Pre/post-хуки вызываются при каждом запуске (даже если renew не нужен — ufw allow/delete идемпотентны). Это нормальное поведение.**Примечание о Restart loop (с reset-failed):** без chown в deploy-hook через ~60 дней панель начнёт падать с PermissionError на новых cert-файлах, systemd будет её перезапускать (Restart=always), и через StartLimitBurst=5 за StartLimitIntervalSec=60 сервис перейдёт в failed. После исправления причины (например, chown) нужно сделать:sudo systemctl reset-failed hydra-panel
sudo systemctl start hydra-panelЭто стандартная семантика systemd: после достижения StartLimitBurst сервис переходит в failed и не стартует автоматически до явного reset-failed.**Альтернатива для внутренних доменов без Let's Encrypt:**Если панель на домене без публичного DNS (например, internal.corp):Использовать self-signed сертификат (сгенерировать один раз, распространить клиентам)
Или DNS-01 challenge через certbot (требует API-доступа к DNS-провайдеру)
Клиент добавляет CA-сертификат в доверенные

### Политика ротации токенов агентов (корректный UPDATE; схема БД — §14, composite PK)

**Автоматическая ротация** раз в agent_token_rotation_days (default 90 дней)
При ротации:Генерируется новый токен
Новая запись в agent_tokens с expires_at = now + rotation_days — возможна только благодаря composite PRIMARY KEY (server_id, token_hash): два токена одного сервера сосуществуют в grace period
**Только активные** старые записи помечаются expires_at = now + grace_period_hours:UPDATE agent_tokens 
SET expires_at = datetime('now', '+24 hours'), rotated_at = datetime('now')
WHERE server_id = ? 
  AND (expires_at IS NULL OR expires_at > datetime('now'))
  AND token_hash != ?;Фильтр гарантирует что давно истёкшие токены НЕ «оживают».
Агент получает новый токен при следующем heartbeat через X-New-Token header

**Ручная ротация** при подозрении на компрометацию — немедленно, все старые expires_at = now
Cleanup cron удаляет из agent_tokens записи с expires_at < now - 1 день

### Разворачивание нового сервера

Панель подключается через SSH (с ConnectTimeout=30, ServerAliveInterval=15)
**Передача MAX_PW через префикс ssh + закавыченный heredoc `<<'REMOTE_SCRIPT_HYDRA_EOF'`** + валидация regex + границы [1, 10000]
Устанавливает зависимости (Go, iptables-persistent, iputils-ping, netcat-openbsd, jq, gawk, linux-headers)
**Bandwidth:** оператор **обязан** задать bandwidth_mbps вручную (нет silent default)
Разворачивает три протокола
Настраивает файрвол (идемпотентно через iptables -C) + условный IPv6
Генерирует AGENT_TOKEN, устанавливает агента с проверкой SHA256
Регистрирует сервер в БД
Обновляет конфиги всех активных ключей

---

## §14 Структура БД

### Схема (11 таблиц)

-- Серверы
CREATE TABLE servers (
    id TEXT PRIMARY KEY,
    location TEXT,
    city TEXT,
    ip TEXT,
    ssh_port INTEGER DEFAULT 22,
    bandwidth_mbps INTEGER NOT NULL,   -- задаётся вручную при деплое; на нём пороги «> 80% канала» (§12)
    status TEXT DEFAULT 'active',
    agent_installed BOOLEAN DEFAULT 0,
    created_at TIMESTAMP
);
CREATE TABLE protocol_instances (
    id INTEGER PRIMARY KEY,
    server_id TEXT REFERENCES servers,
    protocol TEXT,
    port INTEGER,
    max_connections INTEGER NOT NULL DEFAULT 50,  -- лимит для порога «подключения > 80% лимита» (§12)
    status TEXT DEFAULT 'active',
    config JSON
);
CREATE TABLE access_keys (
    key_id TEXT PRIMARY KEY,
    user_id INTEGER,
    expires_at TIMESTAMP,
    max_devices INTEGER DEFAULT 3,
    revoked_at TIMESTAMP,          -- NOT NULL = отозван; revoke не удаляет строки (см. §10)
    created_at TIMESTAMP
);
CREATE TABLE device_registrations (
    id INTEGER PRIMARY KEY,
    key_id TEXT REFERENCES access_keys,
    device_id TEXT,
    device_name TEXT,
    last_ip TEXT,
    registered_at TIMESTAMP,
    last_seen_at TIMESTAMP
);
-- История подключений устройств
-- При 10k устройств × ~24 подключения/день = ~240k/день = ~7.2M/мес
-- Cleanup удаляет старше 30 дней → стабильно ~7M строк в таблице (TTL через cron, §14)
CREATE TABLE device_connections (
    id INTEGER PRIMARY KEY,
    key_id TEXT REFERENCES access_keys,
    device_id TEXT,
    ip TEXT NOT NULL,
    subnet_key TEXT NOT NULL,      -- IPv4 → /24, IPv6 → /64 (compute_subnet_key)
    connected_at TIMESTAMP NOT NULL
);
CREATE TABLE key_server_clients (
    id INTEGER PRIMARY KEY,
    key_id TEXT REFERENCES access_keys,
    server_id TEXT REFERENCES servers,
    protocol TEXT,
    client_config_enc BLOB,        -- AES-256; секреты не хранятся в plaintext
    created_at TIMESTAMP
);
CREATE TABLE metrics (
    id INTEGER PRIMARY KEY,
    server_id TEXT REFERENCES servers,
    timestamp TIMESTAMP,
    cpu_percent REAL,
    memory_percent REAL,
    network_rx_mbps REAL,
    network_tx_mbps REAL,
    connections_wdtt INTEGER,
    connections_aivpn INTEGER,
    connections_awg INTEGER,
    status_wdtt TEXT,
    status_aivpn TEXT,
    status_awg TEXT,
    latency_ms REAL
);
CREATE TABLE alerts (
    id INTEGER PRIMARY KEY,
    server_id TEXT REFERENCES servers,
    level TEXT,
    message TEXT,
    triggered_at TIMESTAMP,
    resolved_at TIMESTAMP
);
CREATE TABLE weekly_reports (
    id INTEGER PRIMARY KEY,
    week_start DATE,
    week_end DATE,
    total_keys INTEGER,
    total_devices INTEGER,
    avg_load REAL,
    peak_load REAL,
    protocol_distribution JSON,
    recommendation TEXT,
    generated_at TIMESTAMP
);
CREATE TABLE bot_heartbeats (
    bot_id TEXT PRIMARY KEY,
    last_heartbeat_at TIMESTAMP,
    last_update_id INTEGER,
    pending_updates INTEGER,
    uptime_sec INTEGER
);
-- Токены агентов: composite PK (server_id, token_hash) — ОБЯЗАТЕЛЕН для ротации
-- с grace period: два токена одного сервера сосуществуют (новый активный +
-- старый с expires_at = now + grace). PK только по server_id делал ротацию
-- невозможной (UNIQUE constraint failed на втором токене) — регрессия v3.59,
-- восстановлено в v3.60 (впервые исправлено в v3.37).
CREATE TABLE agent_tokens (
    server_id TEXT REFERENCES servers ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL DEFAULT '9999-12-31 23:59:59',
    created_at TIMESTAMP,
    rotated_at TIMESTAMP,
    PRIMARY KEY (server_id, token_hash)
);### Индексы (8 шт.)

CREATE INDEX idx_metrics_server_time ON metrics(server_id, timestamp);
CREATE UNIQUE INDEX idx_device_reg_unique ON device_registrations(key_id, device_id);
CREATE UNIQUE INDEX idx_key_clients_key_server ON key_server_clients(key_id, server_id, protocol);
CREATE INDEX idx_access_keys_active ON access_keys(revoked_at, expires_at);
CREATE INDEX idx_alerts_server_resolved ON alerts(server_id, resolved_at);
CREATE INDEX idx_device_connections_lookup ON device_connections(key_id, device_id, connected_at);
CREATE INDEX idx_device_connections_subnet ON device_connections(subnet_key, connected_at);
CREATE INDEX idx_agent_tokens_expires ON agent_tokens(expires_at);### Функция вычисления subnet_key (каноническая сжатая форма IPv6)

import ipaddress

def compute_subnet_key(ip: str) -> str:
    """
    Для IPv4: канонический префикс /24 через ipaddress.ip_network.
    Для IPv6: канонический сжатый префикс /64 через ipaddress.ip_network.
    IPv4-mapped IPv6 (::ffff:192.168.1.1) — трактуется как IPv4.
    """
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return ip

    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
        addr = addr.ipv4_mapped

    if isinstance(addr, ipaddress.IPv4Address):
        net = ipaddress.ip_network(f"{addr}/24", strict=False)
        return str(net)
    else:
        net = ipaddress.ip_network(f"{addr}/64", strict=False)
    return str(net)**Примеры:**compute_subnet_key("10.20.30.40")              # → "10.20.30.0/24"
compute_subnet_key("2001:db8:85a3::1")         # → "2001:db8:85a3::/64"
compute_subnet_key("2001:0db8:85a3:0000:...")  # → "2001:db8:85a3::/64" (тот же)
compute_subnet_key("::ffff:192.168.1.42")      # → "192.168.1.0/24"### Cleanup cron-джобы

**/etc/cron.d/hydra-cleanup:**0 3 * * * root /usr/bin/sqlite3 /var/lib/hydra/panel.db "DELETE FROM device_connections WHERE connected_at < datetime('now', '-30 days');" >> /var/log/hydra-cleanup.log 2>&1
0 4 * * * root /usr/bin/sqlite3 /var/lib/hydra/panel.db "DELETE FROM agent_tokens WHERE expires_at < datetime('now', '-1 day');" >> /var/log/hydra-cleanup.log 2>&1
0 5 * * 0 root /usr/bin/sqlite3 /var/lib/hydra/panel.db "DELETE FROM alerts WHERE resolved_at IS NOT NULL AND resolved_at < datetime('now', '-90 days');" >> /var/log/hydra-cleanup.log 2>&1### Примеры типичных запросов

**Активные ключи:**SELECT * FROM access_keys WHERE revoked_at IS NULL AND expires_at > datetime('now');**Redeemed-статус ключа:**SELECT ak.*, EXISTS(SELECT 1 FROM device_registrations dr WHERE dr.key_id = ak.key_id) AS redeemed
FROM access_keys ak WHERE ak.key_id = ?;**Активные токены для сервера (с поддержкой grace period):**SELECT * FROM agent_tokens
WHERE server_id = ? AND expires_at > datetime('now');**Детекция subnet-коллизий за 5 минут:**SELECT COUNT(DISTINCT subnet_key)
FROM device_connections
WHERE key_id = ? AND device_id = ? AND connected_at > datetime('now', '-5 minutes');### Идемпотентность redeem + race protection

async def redeem(key_id, device_id, device_name, ip):
    # IP читается из request.remote_addr (см. §9, запрет reverse proxy)
    
    await db.execute("BEGIN IMMEDIATE")
    try:
        row = await db.fetchone(
            "SELECT id FROM device_registrations WHERE key_id = ? AND device_id = ?",
            key_id, device_id
        )
        if row:
            await db.execute(
                "UPDATE device_registrations SET last_seen_at = ?, last_ip = ? WHERE id = ?",
                (now(), ip, row[0])
            )
        else:
            count = await db.scalar(
                "SELECT COUNT(*) FROM device_registrations WHERE key_id = ?", key_id
            )
            max_dev = await db.scalar(
                "SELECT max_devices FROM access_keys WHERE key_id = ?", key_id
            )
            if count >= max_dev:
                raise DeviceLimitReached()
            await db.execute(
                "INSERT INTO device_registrations "
                "(key_id, device_id, device_name, last_ip, registered_at, last_seen_at) "
                "VALUES (?,?,?,?,?,?)",
                (key_id, device_id, device_name, ip, now(), now())
            )
        
        subnet_key = compute_subnet_key(ip)
        
        await db.execute(
            "INSERT INTO device_connections (key_id, device_id, ip, subnet_key, connected_at) "
            "VALUES (?,?,?,?,?)",
            (key_id, device_id, ip, subnet_key, now())
        )
        
        await db.execute("COMMIT")
    except Exception:
        await db.execute("ROLLBACK")
        raise
    
    await detect_subnet_collision(key_id, device_id)
    return await build_config(key_id)

async def detect_subnet_collision(key_id: str, device_id: str):
    """
    Контракт send_alert:
    - async def send_alert(level: str, message: str) -> bool
    - Retry: 3 попытки с exponential backoff (1s, 2s, 4s)
    - При неудачах — логирует в файл, не падает
    """
    sql = """
        SELECT COUNT(DISTINCT subnet_key)
        FROM device_connections
        WHERE key_id = ? AND device_id = ? AND connected_at > datetime('now', '-5 minutes')
    """
    subnet_count = await db.scalar(sql, key_id, device_id)
    if subnet_count and subnet_count > 1:
        ips = await db.fetchall(
            "SELECT DISTINCT ip FROM device_connections "
            "WHERE key_id = ? AND device_id = ? AND connected_at > datetime('now', '-5 minutes')",
            key_id, device_id
        )
        ip_list = ", ".join(row[0] for row in ips)
        await send_alert(
            level="",
            message=(
                f"Подозрительная активность: key_id={key_id}, device_id={device_id} "
                f"подключался с {subnet_count} разных подсетей за 5 минут. IP: {ip_list}"
            )
        )### Verify agent token (constant-time через hmac.compare_digest; схема — composite PK, v3.60)

import hmac, hashlib

async def verify_agent_token(server_id: str, token: str) -> bool:
    """
    Constant-time сравнение через hmac.compare_digest.
    SQL B-tree не гарантирует constant-time, поэтому сравнение в Python.
    Composite PK (server_id, token_hash) допускает НЕСКОЛЬКО активных токенов
    на сервер (grace period ротации), поэтому fetchall + any(...) по всем
    кандидатам — корректно только при composite PK (регрессия v3.59 это ломала).
    """
    candidates = await db.fetchall(
        """SELECT token_hash FROM agent_tokens
           WHERE server_id = ? AND expires_at > datetime('now')""",
        server_id
    )
    if not candidates:
        return False
    candidate_hash = hashlib.sha256(token.encode()).hexdigest()
    return any(
        hmac.compare_digest(candidate_hash, row[0]) 
        for row in candidates
    )---

## §15 Агент мониторинга

### Что это
Лёгкий скрипт на каждом сервере. Собирает метрики, отправляет на панель.### Метрики

| Метрика | Как собирается |
|---|---|
| CPU | Дельта `/proc/stat` (все 8 полей) |
| Память | `(total - available) / total` из `LANG=C free` + :=default |
| Сеть | Дельта байт `/proc/net/dev`, float через awk |
| Подключения | WDTT — jq (`.passwords | length`); AIVPN, AWG — `grep -c` (по выводу `--list-clients` / `awg show`) |
| Статус протоколов | `systemctl is-active` (WDTT/AIVPN), `awg show` (AWG) |
| Задержка | bash-builtin `time`, TCP-connect primary (PANEL_PORT из URL), ICMP fallback |

### Определение сетевого интерфейса

IFACE=$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="dev"){print $(i+1); exit}}')
[ -z "$IFACE" ] && IFACE=$(ip -4 route ls default | awk '{print $5; exit}')
[ -z "$IFACE" ] && IFACE="eth0"### Расчёт CPU (полная защита STAT_FILE)

STAT_FILE=/var/lib/hydra-agent/prev.stat

user=0; nice=0; sys=0; idle=0; iowait=0; irq=0; softirq=0; steal=0; guest=0; guest_nice=0

read -r _ user nice sys idle iowait irq softirq steal guest guest_nice < /proc/stat
# v3.55 (замечание внимательного перечитывания): вычитание guest/guest_nice
# из user/nice — спорная эвристика. В документации ядра Linux guest/guest_nice
# показывают время, проведённое в гостевом режиме (KVM виртуализация), и
# часть трактовок включает их в user/nice, часть — считает отдельно.
# Для 1 vCPU на VPS (обычно не KVM-хост) коррекция не критична, но может
# чуть занижать CPU если guest уже учтён. Оставляем текущую коррекцию;
# обсудить на FI-сессии, не стоит ли убрать вычитание для упрощения.
user=$((user - ${guest:-0}))
nice=$((nice - ${guest_nice:-0}))
idle_all=$((idle + iowait))
sys_all=$((sys + irq + softirq))
total=$((user + nice + sys_all + idle_all + steal))

cpu_percent=0
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
echo "$total $idle_all" > "$STAT_FILE"### Расчёт памяти (LANG=C + :=default)

mem_percent=$(LANG=C free -m 2>/dev/null | awk '/^Mem:/ {printf "%.1f", ($2 - $7) * 100 / $2}')
: "${mem_percent:=0.0}"### Расчёт сети (v3.55 — regex-match для надёжности)

PREV_FILE=/var/lib/hydra-agent/prev.net

# v3.55: на части систем /proc/net/dev содержит строки вида
# "  ens3: 12345 67 89 ..." с ведущими пробелами. Сравнение $1==iface:
# работает только когда iface — первое поле без пробелов. Надёжнее
# regex-match: $1 ~ "^"iface":" — ловит "ens3:" даже если строка
# начинается с пробелов или если имя интерфейса парсится неоднозначно.
rx_now=$(awk -v iface="$IFACE" '$1 ~ "^"iface":" {print $2}' /proc/net/dev)
tx_now=$(awk -v iface="$IFACE" '$1 ~ "^"iface":" {print $10}' /proc/net/dev)

rx_mbps="0.0"
tx_mbps="0.0"
rx_prev=0
tx_prev=0
if [ -f "$PREV_FILE" ] && [ -s "$PREV_FILE" ] && [ -n "$rx_now" ]; then
    read -r rx_prev tx_prev _ < "$PREV_FILE" || true
    rx_prev=${rx_prev:-0}
    tx_prev=${tx_prev:-0}
    rx_mbps=$(awk -v r="$rx_now" -v p="$rx_prev" 'BEGIN {printf "%.2f", (r - p) * 8 / 60 / 1000000}')
    tx_mbps=$(awk -v r="$tx_now" -v p="$tx_prev" 'BEGIN {printf "%.2f", (r - p) * 8 / 60 / 1000000}')
fi
echo "$rx_now $tx_now" > "$PREV_FILE"### Задержка до панели (PANEL_PORT из URL)

PANEL_HOST=$(echo "$PANEL_URL" | sed -E 's#^https?://([^/:]+).*#\1#')
PANEL_PORT=$(echo "$PANEL_URL" | sed -nE 's#^https?://[^/:]+:([0-9]+).*#\1#p')
[ -z "$PANEL_PORT" ] && PANEL_PORT=443

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
[ -z "$latency_ms" ] && latency_ms=0### AGENT_TOKEN lifecycle (с валидацией new_token)

**Генерация при развёртывании сервера:**import secrets, hashlib, yaml
from datetime import datetime, timedelta

config = yaml.safe_load(open('/etc/hydra/panel.yaml'))
rotation_days = config.get("agent_token_rotation_days", 90)

token = secrets.token_urlsafe(32)
token_hash = hashlib.sha256(token.encode()).hexdigest()
expires_at = datetime.utcnow() + timedelta(days=rotation_days)
# INSERT INTO agent_tokens (server_id, token_hash, expires_at, created_at)
# composite PK (server_id, token_hash) допускает второй токен в grace period**Инжект в скрипт агента:**sudo tee /var/lib/hydra-agent/config.env > /dev/null <<'EOF'
PANEL_URL=https://panel.hydra.example
AGENT_TOKEN=<token>
SERVER_ID=<server_id>
EOF
sudo chmod 600 /var/lib/hydra-agent/config.env**Приём X-New-Token агентом (с валидацией):**send_single() {
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
    
    new_token=$(grep -i '^X-New-Token:' "$response_file" | head -1 | awk '{print $2}' | tr -d '\r')
    
    if [ -n "$new_token" ] && echo "$new_token" | grep -qE '^[A-Za-z0-9_-]+$'; then
        TMP_CONFIG=$(mktemp /var/lib/hydra-agent/config.env.XXXXXX)
        cat > "$TMP_CONFIG" <<NEWEOF
PANEL_URL=$PANEL_URL
AGENT_TOKEN=$new_token
SERVER_ID=$SERVER_ID
NEWEOF
        chmod 600 "$TMP_CONFIG"
        mv "$TMP_CONFIG" /var/lib/hydra-agent/config.env
        echo "$(date -Iseconds): token rotated" >> /var/log/hydra-agent.log
    elif [ -n "$new_token" ]; then
        echo "$(date -Iseconds): WARNING: invalid token format, skipping rotation" >> /var/log/hydra-agent.log
    fi
    
    rm -f "$response_file"
    [ "$http_code" = "200" ] || [ "$http_code" = "201" ]
}### Отправка метрик

DEAD_LETTER=/var/lib/hydra-agent/dead.json

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
            echo "ERROR: batch rejected (400), moved to dead-letter" >&2
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
}**Документация по асимметрии:** send_single при 400 → запись остаётся в буфере, повторяется в следующем цикле. send_batch при 400 → буфер уходит в dead-letter.### Буфер с ротацией

BUFFER_FILE=/var/lib/hydra-agent/buffer.json
MAX_LINES=3600

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
fi### Полный скелет агента (восстановлен в v3.46, стабилен v3.47–v3.60; v3.58 — grep -c фикс)

#!/bin/bash
# hydra-agent.sh
#
# ЗАВИСИМОСТИ ВНУТРИ СКРИПТА:
# Функции send_single() и send_batch() должны быть определены в этом файле
# (см. раздел «AGENT_TOKEN lifecycle» и «Отправка метрик» выше).
# При сборке агента для деплоя — объединить все разделы в один файл.

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

# Явная инициализация всех переменных перед jq
# (защита от unbound variable при set -u).
# Без этих строк jq -n --argjson упадёт с "unbound variable" на первом же использовании.
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

# ... (функции расчёта CPU/памяти/сети/latency из разделов выше) ...
# Они перезаписывают переменные выше на корректные значения, но если
# какая-то функция провалится — выше гарантированные дефолты.

# v3.56: awg_status переведён с `lsmod | grep amneziawg` на `awg show`.
# lsmod показывает только что модуль загружен, но не что интерфейс создан
# и работает. awg show <iface> надёжнее: возвращает 0 если интерфейс
# существует и отдаёт статистику, ненулевой код если интерфейс отсутствует
# (что именно нужно для алерта "протокол упал").
# v3.57: один вызов awg show вместо двух независимых.
# Сохраняем вывод в переменную и используем для обоих: статуса
# (непустой вывод = интерфейс есть) и числа подключений
# (grep "latest handshake" по тому же выводу).
wdtt_status=$(systemctl is-active wdtt 2>/dev/null | tr -d '"\\' || echo "unknown")
aivpn_status=$(systemctl is-active aivpn-server 2>/dev/null | tr -d '"\\' || echo "unknown")

AWG_IFACE=$(basename "$(ls /etc/amnezia/amneziawg/*.conf 2>/dev/null | head -1)" .conf 2>/dev/null || echo "awg0")
[ -z "$AWG_IFACE" ] && AWG_IFACE="awg0"

awg_output=$(awg show "$AWG_IFACE" 2>/dev/null)
if [ -n "$awg_output" ]; then
    awg_status="active"
else
    awg_status="inactive"
fi

MAIN_PW=$(tr -d '\n' < /root/wdtt-main.pass 2>/dev/null || echo "")
if [ -z "$MAIN_PW" ]; then
    echo "$(date -Iseconds): WARNING: /root/wdtt-main.pass is empty or missing" >> /var/log/hydra-agent.log
    wdtt_conns=0
else
    wdtt_conns=$(jq -n --arg mp "$MAIN_PW" '{main_password:$mp,args:["list"]}' | \
      /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null | \
      jq '.passwords | length' 2>/dev/null || echo 0)
fi

# v3.58: убран `|| echo 0` после `grep -c`. grep -c уже печатает «0» при нуле
# совпадений и возвращает exit 1; `|| echo 0` добавлял второй «0», результат
# «0\n0» ломал `jq --argjson connections_*`. Используем `|| true` — снимает
# exit code без добавления вывода. WDTT-строка (jq ... || echo 0) не тронута:
# jq при ошибке не печатает ничего, fallback корректно добавляет единственный «0».
aivpn_conns=$(/usr/local/bin/aivpn-server --list-clients \
  --key-file /etc/aivpn/server.key --clients-db /etc/aivpn/clients.json 2>/dev/null | \
  grep -c "active" || true)
awg_conns=$(printf '%s\n' "$awg_output" | grep -c "latest handshake" || true)

# Все --argjson выше гарантированно получают определённые значения (см. инициализацию).
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
    [ -s "$BUFFER_FILE" ] && send_batch && > "$BUFFER_FILE"
fi### Установка агента (с PATH в cron)

AGENT_SHA256="..."

sudo apt install -y iputils-ping netcat-openbsd jq gawk
sudo mkdir -p /var/lib/hydra-agent /var/lock

TMP_AGENT=$(mktemp)
if ! curl -f -s -o "$TMP_AGENT" "$PANEL_URL/agent.sh"; then
    echo "ERROR: failed to download agent from $PANEL_URL/agent.sh"
    exit 1
fi

actual_sha=$(sha256sum "$TMP_AGENT" | awk '{print $1}')
if [ "$actual_sha" != "$AGENT_SHA256" ]; then
    echo "ERROR: SHA256 mismatch (possible MITM)"
    rm -f "$TMP_AGENT"
    exit 1
fi

sudo install -m 755 "$TMP_AGENT" /usr/local/bin/hydra-agent.sh
rm -f "$TMP_AGENT"

cat <<'CRONEOF' | sudo tee /etc/cron.d/hydra-agent > /dev/null
# Hydra monitoring agent
PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
* * * * * root /usr/local/bin/hydra-agent.sh >> /var/log/hydra-agent.log 2>&1
CRONEOF
sudo chmod 644 /etc/cron.d/hydra-agent

if [ -s /etc/cron.d/hydra-agent ]; then
    last_byte=$(od -An -tx1 /etc/cron.d/hydra-agent | tr -d ' \n' | tail -c 2)
    if [ "$last_byte" != "0a" ]; then
        echo | sudo tee -a /etc/cron.d/hydra-agent > /dev/null
    fi
fi

if ! grep -q "hydra-agent.sh" /etc/cron.d/hydra-agent; then
    echo "ERROR: cron.d file not created correctly"
    exit 1
fi**Инжект AGENT_TOKEN:**sudo tee /var/lib/hydra-agent/config.env > /dev/null <<EOF
PANEL_URL=$PANEL_URL
AGENT_TOKEN=<token>
SERVER_ID=<server_id>
EOF
sudo chmod 600 /var/lib/hydra-agent/config.env### Ротация логов

**/etc/logrotate.d/hydra-agent:**/var/log/hydra-agent.log {
    daily
    rotate 14
    compress
    delaycompress
    missingok
    notifempty
    create 644 root root
}**/etc/logrotate.d/hydra-cleanup:**/var/log/hydra-cleanup.log {
    weekly
    rotate 8
    compress
    delaycompress
    missingok
    notifempty
    create 644 root root
}---

## §16 CI/CD и автоматизация

### Проверка инварианта panel_domains  ALLOWED_PANEL_DOMAINS

**Скрипт `scripts/check_panel_domains_sync.sh` (прототип):**#!/bin/bash
set -e

PANEL_YAML="${PANEL_YAML_PATH:-/etc/hydra/panel.yaml}"
CLIENT_CONFIG="${CLIENT_CONFIG_PATH:-client/src/config.py}"

if [ ! -f "$PANEL_YAML" ] || [ ! -f "$CLIENT_CONFIG" ]; then
    echo "WARNING: files not found, skipping check"
    exit 0
fi

PANEL_DOMAINS=$(python3 -c "
import yaml, sys
config = yaml.safe_load(open('$PANEL_YAML'))
domains = config.get('panel_domains', [])
print(' '.join(sorted(domains)))
")

CLIENT_DOMAINS=$(python3 -c "
import ast, sys
with open('$CLIENT_CONFIG') as f:
    tree = ast.parse(f.read())
for node in ast.walk(tree):
    if isinstance(node, ast.Assign):
        for target in node.targets:
            if isinstance(target, ast.Name) and target.id == 'ALLOWED_PANEL_DOMAINS':
                if isinstance(node.value, ast.List):
                    domains = [e.value for e in node.value.elts if isinstance(e, ast.Constant)]
                    print(' '.join(sorted(domains)))
                    sys.exit(0)
print('')
")

if [ "$PANEL_DOMAINS" != "$CLIENT_DOMAINS" ]; then
    echo "ERROR: panel_domains не совпадает с ALLOWED_PANEL_DOMAINS"
    exit 1
fi
echo "✓ panel_domains синхронизированы: $PANEL_DOMAINS"### Целостность манифеста (v3.50, маркеры актуальны с v3.53)

**Скрипт `scripts/check_manifest_integrity.sh` (появится в Этапе 2, спецификация):**#!/bin/bash
# Проверяет docs/MANIFEST.md на артефакты повреждённой сборки/отправки.
# v3.51: явно помечен как частично-информационный: блокирующая логика
# (сверка diff с заявленными правками changelog) — в Этапе 2.
# v3.52: список маркеров обновлён под v3.52 (smoke-тест, --app-dir).
# v3.53: маркер 'pyproject.toml' заменён на уникальный
# 'if [ ! -f /opt/hydra/src/pyproject.toml ]' (уникальная проверка fail-fast,
# не дублируется в changelog); проверка '--app-dir /opt/hydra/src' усилена
# с -ge 1 до -ge 2 (должно быть в unit-файле и smoke-тесте).
# v3.54–v3.60: маркеры не менялись (правки были в других местах манифеста).
# v3.54: явный комментарий об известном ограничении маркера --app-dir:
# проверка -ge 2 частично информационная, т.к. строка встречается и в
# changelog, и в комментариях. Если кто-то удалит --app-dir из unit-файла,
# но оставит в smoke-тесте и комментариях, grep -c вернёт 5+ и -ge 2
# пройдёт. Настоящее решение через grep -A1 по контексту команд появится
# в Этапе 2 при наличии репозитория. До этого — полагаемся на ревью +
# smoke-тест как основной защитный механизм (он проверяет реальный импорт).
set -e
F="${1:-docs/MANIFEST.md}"

# 1. Вырожденные повторы команд (инцидент v3.50-rc1) — БЛОКИРУЮЩЕЕ
n=$(grep -c 'chown -hydra:hydra chown' "$F" || true)
[ "$n" -eq 0 ] || { echo "FAIL: degenerate repetition found ($n)"; exit 1; }

# 2. Служебный/мета-текст не должен попадать в документ — БЛОКИРУЮЩЕЕ
if grep -qn 'I need to stop' "$F"; then
    echo "FAIL: leaked meta-text"; exit 1
fi

# 3. Ключевые маркеры текущей версии присутствуют.
#    Маркеры актуальны с v3.53, изменений в v3.54–v3.60 не потребовалось.
#    'getent group hydra' — >= 1 (появляется в §13.2 + changelog)
#    '--app-dir /opt/hydra/src' — >= 2 (unit-файл + smoke-тест)
#    'Application startup complete' — >= 1 (smoke-тест + changelog)
#    'if [ ! -f /opt/hydra/src/pyproject.toml ]' — >= 1 (уникальная проверка fail-fast в §13.2)
#    'PRIMARY KEY (server_id, token_hash)' — >= 1 (v3.60: защита от регрессии v3.59)
for marker in 'getent group hydra' '--app-dir /opt/hydra/src' 'Application startup complete' 'if [ ! -f /opt/hydra/src/pyproject.toml ]' 'PRIMARY KEY (server_id, token_hash)'; do
    c=$(grep -cF "$marker" "$F" || true)
    case "$marker" in
        '--app-dir /opt/hydra/src')
            [ "$c" -ge 2 ] || { echo "FAIL: marker '$marker' count=$c (expected >= 2: unit + smoke)"; exit 1; }
            ;;
        *)
            [ "$c" -ge 1 ] || { echo "FAIL: marker '$marker' not found (expected >= 1)"; exit 1; }
            ;;
    esac
done

# 4. Diff против предыдущей версии (тег git) — ИНФОРМАЦИОННОЕ (не блокирующее).
#    Блокирующая логика (сверка diff с changelog) — в Этапе 2:
#    парсинг changelog, извлечение заявленных правок, сверка с фактическим diff.
#    Использование: ./scripts/check_manifest_integrity.sh docs/MANIFEST.md v3.59
if [ -n "$2" ]; then
    git diff "$2" -- "$F" | grep -E '^[+-]' | grep -vE '^(\+\+\+|---)' > /tmp/manifest_diff.txt
    echo "INFO: diff lines: $(wc -l < /tmp/manifest_diff.txt) — сверьте с changelog"
    echo "INFO: блокирующая сверка diffchangelog появится в Этапе 2"
fi

echo "✓ manifest integrity OK"### Набросок CI pipeline

name: CI
on: [push, pull_request]
jobs:
  check:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: '3.12'
      - run: pip install -e ".[dev]"
      - name: Check panel_domains sync
        env:
          PANEL_YAML_PATH: tests/fixtures/panel.yaml
          CLIENT_CONFIG_PATH: client/src/config.py
        run: ./scripts/check_panel_domains_sync.sh
      - name: Check manifest integrity
        run: ./scripts/check_manifest_integrity.sh docs/MANIFEST.md
      - run: ruff check .
      - run: mypy hydra/
      - run: pytest---

## Следующие шаги (старт после коммита v3.60)

### Этап 2: Серверная панель
**Каркас репо:** pyproject.toml, структура hydra/
**SSH-транспорт:** asyncssh, подключение к серверам
**Управление серверами**; **уточнить на FI:**назначение порта 9000 WDTT
имена header ranges в awg-quick (AmneziaWG 3.x)
**точные имена пакетов в PPA amnezia** (метапакет amneziawg vs отдельные amneziawg-dkms + amneziawg-tools — проверить что есть в актуальной версии PPA; v3.55 добавил fallback-логику, но формулировка не финальная)
возможные другие коллизии handshake (кроме S1+56==S2)
имя параметра RandomTrailers в awg-quick
regex для странных доменов типа 1.2.3.4evil.com в panel_url
**эвристика guest/guest_nice в расчёте CPU агента** (v3.55: спорная, проверить на реальных VPS нужна ли коррекция или проще её убрать)
**multi-domain TLS-стратегия** (v3.56): SAN в одном сертификате или отдельные инстансы панелей; зафиксировать перед первым деплоем с несколькими доменами

**Разворачивание протоколов** (плагины: WDTT → AIVPN → AWG)
**Ручной ввод `bandwidth_mbps`** с валидацией (NOT NULL)
**Генерация AGENT_TOKEN** при развёртывании (с expires_at)
**Генерация ключей** (redeem + валидация panel_url)
**Приём метрик** от агентов (с dead-letter на 400 для batch)
**Проверка порогов** и алерты
**Прогноз масштабирования** (по max)
**Эндпоинты** /api/v1/bot/heartbeat, /api/v1/bot/token-check
**Эндпоинты** /api/v1/client/redeem и /api/v1/client/servers
**detect_subnet_collision** на subnet_key
**send_alert** с retry и exponential backoff
**Автоматическая ротация токенов** (composite PK agent_tokens — схема §14 v3.60)
**Catch-up** еженедельного отчёта
**Cleanup cron:** /etc/cron.d/hydra-cleanup
**Logrotate** для логов
**TLS на панели (§13.2):** подготовка пользователя hydra и директорий (v3.50) → развёртывание кода/venv/зависимостей (v3.51) → корректный smoke-тест с перехватом $(...) и --app-dir (v3.52) → portable sudo без PYTHONPATH и printf вместо echo (v3.53) → timeout 5 + детальная диагностика (v3.54) → certbot → systemd → deploy-hook с циклом по panel_domains и venv Python (v3.55/v3.56) → баг-фикс $3 → $2 в awk-fallback (v3.57) → баг-фикс grep -c ... || echo 0 → || true (v3.58) → ACL на родительские директории letsencrypt → smoke-тест sudo -u hydra test -r <privkey> → процедура расширения на несколько доменов
**`scripts/check_manifest_integrity.sh`** + подключение к CI (гейт целостности манифеста; блокирующая сверка diffchangelog в Этапе 2; маркеры актуальны с v3.53, в v3.60 добавлен маркер composite PK agent_tokens)

### Этап 3: Телеграм-бот (после Этапа 2)
### Этап 4: Клиентское приложение (после Этапа 3)

---

**Манифест v3.60 зафиксирован по итогам финального ревью (#26). Регрессия v3.59 (agent_tokens PK) восстановлена, комментарии схемы возвращены, changelog фиксирует инцидент явно.****Итоговая кривая:** 25 раундов черновых ревью (v3.00 → v3.54) + полировка v3.55–v3.59 + финальное ревью #26 → v3.60. Инциденты восстановления секций (v3.45, v3.50-rc1, v3.59) учтены в процесс-заметке и чек-листе; маркер composite PK добавлен в integrity-скрипт как защита от повторения.**Готов к коммиту и старту Этапа 2.** 

