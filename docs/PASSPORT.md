# Hydra Panel — паспорт (финал Этапа 2, 18.09.2026)

## Суть
Панель управления VPN-инфраструктурой (WDTT/AIVPN/AmneziaWG 3.1) на VPS.
Product-ready: auth, self-hosted установка, DR в одну команду.
Около 8000 строк кода, 55+ коммитов.

## Стек
Python 3.14 + FastAPI + uvicorn + SQLite (aiosqlite, 16 таблиц) +
Jinja2 + TailwindCSS CDN + HTMX + Alpine.js + asyncssh + PyYAML +
cryptography (Fernet)

## Репозитории
- Код: github.com/semenovna15133-code/hydra-panel.git
- Backups (offsite, private): github.com/semenovna15133-code/hydra-backups.git

## БД (16 таблиц)
servers (ssh_port, status=active/offline/unknown, agent_installed)
protocol_instances, metrics (14 колонок incl. per-protocol counts)
clients (telegram_id UNIQUE, tg_username, display_name, status, notes)
access_keys (+client_id FK, expires_at, revoked_at, max_devices)
device_registrations, device_connections, key_server_clients
agent_tokens, alerts, weekly_reports, bot_heartbeats
admin_credentials (pbkdf2), sessions, login_attempts, cli_tokens
secrets (payload=Fernet-blob, per-provider)

## Hydra Auth v1
pbkdf2_sha256 600k, cookie hydra_session HttpOnly/SameSite/Lax
(remember 30д/12ч), X-Hydra-Token CLI-токен, rate-limit 5/15мин/IP.
Middleware: закрыто всё кроме /login /setup /api/v1/agent/* /api/v1/client/*

## Onboarding v1
Поле SSH-пароля root (одноразово) в модалке Add Server -> bootstrap
ключа панели в authorized_keys -> key-only. test-ssh с timeout 8 сек
(asyncio.wait_for). Статусы unknown/active/offline. Бейджи:
emerald/rose/slate.
ГЭП: manager/plugins пока не используют ssh_port (дефолт 22).

## Клиенты -> Ключи -> Устройства
Клиент = человек с telegram_id (для бота: восстановление по TG, напоминания
по expires_at). UI: /clients (люди), /clients/{id} (ключи+устройства),
/keys (создать с селектом клиентов). Операции: выпуск, продление,
ревьюк/восстановить, отвязать устройства, каскад-удаление.

## Бэкапы v3 + Recovery v4 (product-ready)

hydra/core/secrets.py: master.key (~/.config/hydra/master.key, 600)
создаётся один раз; HKDF-SHA256 per-provider keyspaces; Fernet AES
с base64url-wrap; SecretStore в таблице secrets (payload = Fernet-blob,
plaintext в БД отсутствует).

Wizard /settings/backup:
- ping ДО сохранения (_github_ping: права push + приватность репо)
- сохранение только после успеха
- генератор recovery-passphrase (crypto.getRandomValues, 36 hex)
  с плашкой копирования — показывается один раз
- push-now, отключение, инструкция popup, providers: GitHub (S3/WebDAV placeholder)

Worker: раз в сутки create -> rotate(keep из meta) -> push_github +
push_recovery_bundle.

Recovery v4:
- recovery-bundle.enc в GitHub: SSH-ключ + master.key + GitHub-токен
  под passphrase (compress=False — бандл уже зашифрован)
- scripts/recover.sh: чистый сервер -> clone/venv -> последний бэкап +
  бандл -> decrypt по passphrase -> keys/ + master.key + panel.db
- DR-комплект: GitHub-доступ + passphrase (без флешек и ручных копий)

Проверено симуляцией: ssh_key True, master_key True, github repo ok.

## AWG 3.1 из UI
27 параметров в 7 группах: Jc/Jmin/Jmax/S1-S4, I1-I5 (CPS count:size),
H1-H4 (range), HeaderProtectionKey (base64), ContentPaddingAddition,
RandomTrailers/DisableCookies (on/off), RekeyAfterTime/Timeout/
RejectAfterTime/KeepaliveTimeout/MaxHandshakeAttempts, MTU, ListenPort.
Схема: бекап .hydra-backup-TS -> валидация -> base64-запись -> рестарт ->
проверка через 2с -> авто-откат из бекапа если сервис не поднялся.

## Локальный запуск
source ~/.config/hydra/env && source .venv/bin/activate
python hydra/cli.py --port 8000
Первый вход: /setup (пароль + CLI-токен одноразово)

## Что сделано
Auth/Onboarding/Settings/Clients/Keys/Devices/Servers/Protocols/Configs/
Logs/Metrics/Database/Backups+Recovery/Aurora Glass Dark+Light/Mobile.
FI-полигон 31.77.202.131: all protocols up, 247+ метрик в БД.

## Известные грабли
1. Не обрезать panel.py по маркерам (кейс с /database)
2. Проверять применение heredoc'ов (кейс: servers.html ДО-Aurora)
3. Все патч-скрипты начинать с cd ~/hydra-panel (песочницы /tmp/* не содержат код)
4. buffer.json = конкатенированные pretty JSON -> raw_decode
5. expires_at .conf с пробелом, в БД с T
6. AWG конфиг /etc/amnezia/amneziawg/ (нестандартный путь)
7. SSH timeout через asyncio.wait_for (TCP-висание иначе 2 мин)
8. Секреты только в env-файлах 600 + secrets-таблице (Fernet), никогда в чатах/коммитах
9. push_github: compress=False для бандлов (они уже зашифрованы)
10. Старая панель на порту: pkill -f "python hydra/cli.py" перед рестартом

## Этап 3: Telegram-бот
1. hydra/bot/main.py - aiogram 3.x, Long-polling
2. Роутеры: /start, /redeem, /status, /devices, /restore (по telegram_id),
   /login (magic-link), /extend
3. Админ: /admin /keys /alerts, alert-канал, heartbeat в bot_heartbeats
4. Напоминания: cron-задача по expires_at ключам клиента (7д/3д/1д/истёк)
5. Страница /bot в UI: heartbeat статус, токен бота, регенерация
6. Интеграция с panel через X-Hydra-Token или прямой DB-доступ

## Стиль работы
Ответы на русском. Код через bash heredoc + Python-скрипты с проверками
(anchor in content + print count). Коммиты после каждого шага.
Secrets только в env-файлах 600 + Fernet в БД. Все патчи начинаются с
cd ~/hydra-panel.
