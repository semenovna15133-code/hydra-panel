# Hydra Panel - паспорт (17.09.2026)

## Суть
Панель управления VPN-инфраструктурой: 3 протокола (WDTT, AIVPN, AmneziaWG 3.1)
на удалённых VPS через SSH. Мониторинг через bash-агент с cron.
Сейчас около 5500 строк кода, 35+ коммитов, полностью рабочее состояние.

## Стек
- Python 3.14 + FastAPI + uvicorn
- SQLite (aiosqlite) - 11 таблиц
- Jinja2 + TailwindCSS (CDN) + HTMX + Alpine.js
- asyncssh для SSH-транспорта
- bash-агент мониторинга на серверах

## Репозиторий
github.com:semenovna15133-code/hydra-panel.git

## Структура
hydra/
  panel.py - FastAPI app, все REST + web routes (около 1200 строк)
  cli.py - CLI: --host --port --db-path --ssh-key
  ssh/transport.py - SSHTransport(host, key_path=...) + SSHResult
  agent/
    agent.sh - bash-агент (cron, буфер, dead-letter)
    install.py - установка агента через SSH
  core/
    db.py - Database class (aiosqlite, fetchall/fetchone/execute)
    server_manager.py - ServerManager (register, install, get_status)
    protocol.py - ProtocolPlugin ABC
    subnet.py, alerts.py, forecast.py, weekly_report.py,
    token_rotation.py, redeem.py
  plugins/
    wdtt.py - systemctl wdtt, конфиг /etc/wdtt/passwords.json
    aivpn.py - systemctl aivpn-server, /etc/aivpn/server.json
    awg.py - awg-quick awg0, /etc/amnezia/amneziawg/awg0.conf

templates/
  base.html - Aurora Glass дизайн-система
  components/sidebar.html - drawer mobile + sidebar desktop
  components/navbar.html - glass bar + theme toggle
  pages/
    dashboard.html, servers.html, server_detail.html,
    server_logs.html, server_configs.html,
    clients.html, keys.html, reports.html, database.html

deploy/
  deploy.sh, systemd/hydra-panel.service, hooks/ (certbot)

docs/
  MANIFEST.md - v3.61, источник истины архитектуры
  KEYFILE.md - формат Hydra Key File v1 (.conf)

## Схема БД (11 таблиц)
servers (id, ip, ssh_port, location, city, bandwidth_mbps, status, agent_installed)
protocol_instances (server_id, protocol, port, max_connections, status)
metrics (server_id, timestamp, cpu_percent, memory_percent, network_rx/tx_mbps,
         connections_wdtt/aivpn/awg, status_wdtt/aivpn/awg, latency_ms)
access_keys (key_id, max_devices, created_at, expires_at, revoked_at)
device_registrations (key_id, device_id, device_name, last_ip, registered_at, last_seen_at)
device_connections (key_id, device_id, server_id, protocol, connected_at)
key_server_clients (key_id, server_id, protocol)
agent_tokens (server_id, token_hash, expires_at)
alerts (server_id, type, message, severity, resolved, created_at)
weekly_reports (week_start, week_end, total_keys, total_devices, avg_load, peak_load, generated_at)
bot_heartbeats (server_id, last_seen_at, status)

## Дизайн-система Aurora Glass
- Glassmorphism: .glass = backdrop-blur(20px) + saturate(160%) + полупрозрачный фон
- Aurora-фон: 3 дрейфующих градиентных пятна (indigo/fuchsia/cyan) через keyframes
- Dark/Light mode с LocalStorage, плавный переход 0.35s (без мигания)
- Акцент: gradient indigo->violet->fuchsia, glow-тени
- Шрифты: Inter (UI) + JetBrains Mono (IP, токены, код)
- Анимации: staggered fade-up (d1..d6), hover-lift (-translate-y-1), ping dots
- Responsive: desktop sidebar (lg:fixed), mobile drawer с backdrop
- Статус-бейджи: emerald/rose/amber с точкой + ring-свечением

## Состояние FI-полигона (реальный тест)
- Сервер: 31.77.202.131, SSH key: keys/hydra_key
- Протоколы установлены: wdtt (port 56000), aivpn (443), awg (51820)
- AmneziaWG 3.1.20260812 (kernel module amneziawg, 135 KB)
- Конфиг: /etc/amnezia/amneziawg/awg0.conf - полный набор 3.1 параметров
- Агент: /usr/local/bin/hydra-agent.sh в cron, шлёт одиночные метрики + batch-слив
- Для тестирования метрик нужен SSH-туннель:
  ssh -i keys/hydra_key -R 8000:localhost:8000 -N root@31.77.202.131

## Локальный запуск
source .venv/bin/activate
python hydra/cli.py --port 8000
(автоматически: ./panel.db, keys/hydra_key если есть)

## Что сделано (полностью)
- CRUD серверов с модалками и каскадным удалением
- Установка/перезапуск протоколов через SSH
- Перезагрузка VPS, синхронизация времени (Europe/Moscow)
- Логи через SSH с умными фильтрами:
  - wdtt: события без [СТАТ] + 10 строк свежей статистики
  - aivpn: без DEBUG-шума
  - awg: awg show + dmesg
- Редактирование конфигов протоколов из UI:
  - AWG: 27 параметров 3.1 в 7 группах (Jc/Jmin/Jmax/S1-S4, I1-I5, H1-H4,
    HeaderProtectionKey, ContentPaddingAddition, RandomTrailers/DisableCookies,
    RekeyAfterTime/Timeout, RejectAfterTime, KeepaliveTimeout, MaxHandshakeAttempts,
    MTU, ListenPort) с типизированной валидацией + бекап + авто-откат
  - WDTT/AIVPN: raw JSON с json.loads валидацией
- Клиенты: add/delete устройства вручную
- Ключи: create/revoke/restore/delete, копирование целиком,
  скачивание .conf (Hydra Key File v1, INI-совместимый, docs/KEYFILE.md)
- Метрики: batch endpoint с streaming JSON parser
  (json.JSONDecoder.raw_decode в цикле) - принимает NDJSON, JSON-массив
  и конкатенированные pretty JSON объекты (формат буфера агента)
- SQL-консоль /database: read-only (SELECT/PRAGMA), word-boundary regex
  против INSERT/UPDATE/DELETE (не блокирует колонки вида updated_at)
- Бэкап БД одной кнопкой: /database/backup
- Все страницы в Aurora Glass (dark/light), русская локализация, mobile

## Известные грабли (важно!)
1. НЕ обрезать panel.py по маркерам - endpoints, добавленные после маркера,
   теряются. Использовать только точечные str.replace с проверкой anchor in content.
2. buffer.json агента = конкатенированные pretty JSON объекты, НЕ NDJSON
   и НЕ массив. Парсить через json.JSONDecoder.raw_decode в цикле.
3. expires_at в .conf должен быть YYYY-MM-DD HH:MM:SS (с пробелом),
   в БД хранится с T - нормализовать через .replace(T,  ).
4. Пути конфигов AWG: /etc/amnezia/amneziawg/awg0.conf (нестандартный).
   Добавлен первым кандидатом в CONFIG_DIRS/CONFIG_PATHS.
5. agent_installed = 0 в БД при установленном агенте - синхронизировать
   UPDATE servers SET agent_installed=1 при success/already_installed.
6. Команда python не найдена - всегда python из venv после source .venv/bin/activate,
   или .venv/bin/python.
7. sqlite3 CLI не установлен - для админских операций использовать Python sqlite3.

## Что осталось (Этап 3)
1. Hydra Auth v1 - аутентификация панели перед деплоем на VPS
2. /settings - просмотр/правка panel.yaml из UI
3. Telegram-бот на aiogram 3.x:
   - Клиентские команды: /start, /redeem, /status, /devices
   - Админские: /admin, /servers, /keys, /alerts
   - Heartbeat к панели
   - Alert-канал (админские уведомления)
4. Polish: favicon, CI/CD, README проекта

## Первое задание в новом чате: Hydra Auth v1 + страница /bot

Дизайн Hydra Auth v1 (сохранить как есть):

Таблицы (CREATE IF NOT EXISTS при старте):
- admin_credentials (id=1, password_hash, created_at) - один админ, pbkdf2_sha256 600k итераций
- sessions (id, token_hash, user_agent, ip, last_seen, remember_until)
- login_attempts (ip, success, created_at)
- cli_token (id=1, token_hash, created_at) - токен для curl/CLI

Маршруты:
- GET /setup (wizard, один раз пока нет admin_credentials)
- GET /login - центрированная glass-карточка (Aurora Glass)
- POST /login - проверка пароля, создание сессии, галочка запомнить устройство
- GET /logout - удаление сессии
- GET /sessions - список устройств с кнопкой завершить

Middleware (auth_required):
- /setup, /login, /api/v1/agent/*, /api/v1/client/* - открытый доступ
- /api/v1/client/redeem - публичный но с rate-limit (10 req/min/ip)
- все остальные: cookie hydra_session ИЛИ заголовок X-Hydra-Token

CLI-токен:
- Показывается один раз на /setup
- scripts/login.sh создаёт cookie-jar для curl
- Заголовок X-Hydra-Token: token для API

Rate-limit: 5 неудачных попыток / 15 минут / IP -> lockout с эскалацией (30 мин, 1 ч, 24 ч)

Страница /bot (заготовка под Telegram):
- Стеклянная карточка со статусом heartbeat (последний пинг от бота)
- Токен бота (сгенерировать один раз, показать один раз)
- Кнопка Перегенерировать токен
- Заготовка под magic-link через TG: бот будет принимать /login code,
  код генерируется панелью, 6 цифр, TTL 2 минуты

Порядок работ:
1. hydra/core/auth.py: pbkdf2 hash/verify, sessions CRUD, rate-limit
2. Миграции таблиц в panel.py (при startup)
3. Middleware с whitelist путей
4. Страницы в Aurora Glass: setup wizard, login, sessions, logout
5. scripts/login.sh + README
6. tests/test_auth.py (без логина редирект, с логином доступ, lockout)
7. Заготовка /bot (status + token + regenerate)
8. Коммит, затем - Telegram-бот в aiogram 3.x

Ответы на русском. Код через bash heredoc или Python-скрипты
(избегай вложенных блоков - они ломают терминал при копировании).
