# Hydra Panel - паспорт (финал этапа 2)

## Суть
Панель управления VPN-инфраструктурой (WDTT/AIVPN/AmneziaWG 3.1) на удалённых VPS.
Защищена Hydra Auth v1, бэкапы в GitHub offsite. Мониторинг через bash-агент с cron.
Около 7500 строк, 50+ коммитов.

## Стек
Python 3.14 + FastAPI + uvicorn + SQLite (aiosqlite, 16 таблиц) + Jinja2 +
TailwindCSS CDN + HTMX + Alpine.js + asyncssh + PyYAML

## Репозиторий
github.com:semenovna15133-code/hydra-panel.git (код)
github.com:semenovna15133-code/hydra-backups.git (offsite бэкапы, private)

## Структура
hydra/
  panel.py - REST + web + middleware auth + backup worker
  cli.py, ssh/transport.py
  core/: auth.py (pbkdf2/sessions/rate-limit/CLI-token),
         backups.py (create/rotate/restore/push_github),
         db.py, server_manager.py, protocol.py, ...
  plugins/: wdtt.py, aivpn.py, awg.py (27 параметров 3.1)
  agent/: agent.sh + install.py
templates/: Aurora Glass, auth_layout, sidebar/navbar, 14 pages
scripts/: login.sh (cookie-jar для curl)
docs/: MANIFEST.md, KEYFILE.md, PASSPORT.md

## БД (16 таблиц)
servers, protocol_instances, metrics (14 колонок), access_keys (+client_id FK),
device_registrations, device_connections, key_server_clients, agent_tokens,
alerts, weekly_reports, bot_heartbeats,
clients (telegram_id UNIQUE, tg_username, display_name, status, notes),
admin_credentials, sessions, login_attempts, cli_tokens

## Hydra Auth v1
pbkdf2_sha256 600k, cookie hydra_session HttpOnly/SameSite/Lax (remember 30д/12ч),
X-Hydra-Token CLI-токен, rate-limit 5/15мин/IP, /sessions управление устройствами.
Middleware: закрыто всё кроме /login /setup /api/v1/agent/* /api/v1/client/*

## Onboarding v1
Поле SSH-пароля root (одноразово) в модалке Add Server -> bootstrap ключа панели
в authorized_keys -> key-only. test-ssh с timeout 8 сек. Статусы unknown/active/offline.
ГЭП: manager/plugins пока не используют ssh_port (дефолт 22).

## Клиенты -> Ключи -> Устройства
Клиент = человек с telegram_id (для бота: восстановление по TG, напоминания по expires_at).
UI: /clients (люди), /clients/{id} (ключи+устройства), /keys (создать с селектом клиентов).
Операции: выпуск, продление, ревьюк/восстановить, отвязать устройства, каскад-удаление.

## Бэкапы v2 (Disaster Recovery)
hydra/core/backups.py:
- create: SQLite backup API (консистентный снапшот даже под нагрузкой)
- rotate(keep=3): удаляет авто-бэкапы старше трёх (uploaded/pre-restore не трогает)
- restore: integrity_check + pre-restore снапшот перед перезаписью
- push_github: gzip+base64 -> GitHub Contents API (обновление по sha), лимит 900 KB
Часовой воркер: раз в сутки -> create -> rotate -> push (если настроен).
Конфиг: panel.yaml backup.{keep, github_repo, github_token} или env HYDRA_GH_*
Env: ~/.config/hydra/env (chmod 600), source перед запуском панели.
Репозиторий hydra-backups (private), fine-grained token: Contents Read+write.

## AWG 3.1 из UI
27 параметров в 7 группах: Jc/Jmin/Jmax/S1-S4, I1-I5 (CPS count:size),
H1-H4 (range), HeaderProtectionKey (base64), ContentPaddingAddition,
RandomTrailers/DisableCookies (on/off), RekeyAfterTime/Timeout/RejectAfterTime/
KeepaliveTimeout/MaxHandshakeAttempts, MTU, ListenPort.
Схема: бекап .hydra-backup-TS -> валидация -> base64-запись -> рестарт ->
проверка через 2с -> авто-откат из бекапа если сервис не поднялся.

## Локальный запуск
source ~/.config/hydra/env
source .venv/bin/activate
python hydra/cli.py --port 8000
Первый вход: /setup (пароль + CLI-токен одноразово)

## Что сделано
Auth/Onboarding/Settings/Clients/Keys/Devices/Servers/Protocols/Configs/
Logs/Metrics/Database/Backups/Aurora Glass Dark+Light/Mobile.
Реальный FI-полигон 31.77.202.131: 247+ метрик в БД, all protocols up.

## Известные грабли
1. Не обрезать panel.py по маркерам (кейс с /database)
2. Проверять применение heredoc'ов (кейс: servers.html ДО-Aurora)
3. buffer.json = конкатенированные pretty JSON -> raw_decode
4. expires_at .conf с пробелом, в БД с T
5. AWG конфиг /etc/amnezia/amneziawg/ (нестандартный путь)
6. SSH timeout через asyncio.wait_for (TCP-висание иначе 2 мин)
7. Секреты только в env-файлах 600, никогда в чатах/коммитах
8. Ротировать всё перед продом после dev-фазы

## Этап 3: Telegram-бот
1. hydra/bot/main.py - aiogram 3.x, Long-polling
2. Роутеры: /start, /redeem, /status, /devices, /restore (по telegram_id), /login (magic-link)
3. Админ: /admin /keys /alerts, alert-канал, heartbeat в panel bot_heartbeats
4. Напоминания: cron-задача по expires_at ключам клиента
5. Страница /bot в UI: heartbeat статус, токен бота, регенерация
6. Интеграция с panel API через X-Hydra-Token или прямой DB-доступ

## Стиль работы
Ответы на русском. Код через bash heredoc + Python-скрипты с проверками.
Коммиты после каждого шага. Secrets только в env-файлах 600.
