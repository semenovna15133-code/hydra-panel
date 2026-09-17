# Hydra Panel - паспорт (18.09.2026, финал этапа 2)

## Суть
Панель управления VPN-инфраструктурой: 3 протокола (WDTT, AIVPN, AmneziaWG 3.1)
на удалённых VPS через SSH. Мониторинг через bash-агент с cron.
Около 6500 строк кода, 45+ коммитов. Панель защищена Hydra Auth v1.

## Стек
- Python 3.14 + FastAPI + uvicorn
- SQLite (aiosqlite) - 15 таблиц (11 основных + 4 auth)
- Jinja2 + TailwindCSS (CDN Play) + HTMX + Alpine.js
- asyncssh для SSH (включая одноразовые подключения с паролем)
- bash-агент мониторинга на серверах

## Репозиторий
github.com:semenovna15133-code/hydra-panel.git

## Структура
hydra/
  panel.py - FastAPI app: REST + web routes + middleware auth (~1600 строк)
  cli.py - CLI: --host --port --db-path --ssh-key
  ssh/transport.py - SSHTransport(host, key_path=...) + SSHResult
  core/
    auth.py - Hydra Auth v1: pbkdf2, сессии, rate-limit, CLI-токен
    db.py, server_manager.py, protocol.py, subnet.py, alerts.py,
    forecast.py, weekly_report.py, token_rotation.py, redeem.py
  agent/agent.sh + install.py
  plugins/wdtt.py, aivpn.py, awg.py

templates/
  base.html - Aurora Glass дизайн-система
  auth_layout.html - лейаут login/setup (центрированная карточка)
  components/sidebar.html, navbar.html
  pages/: dashboard, servers, server_detail, server_logs, server_configs,
          clients, keys, reports, database, settings,
          login, setup, setup_done, sessions

deploy/: deploy.sh, systemd/hydra-panel.service, hooks/ (certbot)
scripts/login.sh - cookie-jar для curl
docs/: MANIFEST.md (v3.61), KEYFILE.md, PASSPORT.md

## Схема БД
Основные: servers (id, ip, ssh_port, location, city, bandwidth_mbps,
status[active|offline|unknown], agent_installed), protocol_instances,
metrics (14 колонок incl. connections_*/status_* per protocol),
access_keys, device_registrations, device_connections, key_server_clients,
agent_tokens, alerts, weekly_reports, bot_heartbeats
Auth: admin_credentials (id=1, pbkdf2 hash), sessions (token_hash, ua, ip,
remember, expires), login_attempts, cli_tokens

## Hydra Auth v1 (РАБОТАЕТ)
- pbkdf2_sha256 600k итераций, соль; в БД только хеши
- Cookie hydra_session: HttpOnly, SameSite=Lax, remember 30д / 12ч
- Middleware: закрыто всё кроме /login, /setup, /api/v1/agent/*, /api/v1/client/*
- API без сессии -> 401 JSON; страницы -> 303 на /login
- X-Hydra-Token: CLI-токен для curl/скриптов (ротация в /settings)
- Rate-limit: 5 неудач / 15 мин / IP
- /sessions: список устройств + отзыв + "завершить все"
- scripts/login.sh: curl -b scripts/.cookies ...

## Onboarding v1 (РАБОТАЕТ)
- Поле "SSH-пароль root (одноразово)" в модалке Add Server
- Пароль НЕ сохраняется: bootstrap через asyncssh (password=...):
  дописывает hydra_key.pub в authorized_keys, затем key-only
- _ssh_run_once: timeout 8 сек (asyncio.wait_for) - иначе TCP-висание ~2 мин
- POST /servers/{id}/test-ssh + кнопка "Проверить SSH"
- Статусы: unknown при создании -> active/offline после проверки
- Бейджи: emerald "активен" / rose "недоступен" / slate "не проверен"
- ИЗВЕСТНЫЙ ГАП: manager/plugins при установке протоколов ходят на порт 22
  (не получают ssh_port) - рефактор портов в будущем

## Дизайн-система Aurora Glass
- .glass = backdrop-blur(20px) saturate(160%) + полупрозрачный фон
- Aurora-фон: 3 дрейфующих градиентных пятна (keyframes drift1-3)
- Dark/Light + LocalStorage, переход .35s без мигания
- Gradient indigo->violet->fuchsia, glow-тени, staggered fade-up d1-d6
- Inter (UI) + JetBrains Mono (IP/токены/код)
- Mobile: drawer sidebar, карточки вместо таблиц
- Tailwind Play CDN генерирует классы из server-rendered HTML
  (динамические bg-{{ badge[0] }}-500/10 работают)

## FI-полигон (реальный тест)
- 31.77.202.131, ключ keys/hydra_key, порт 22
- wdtt 56000 / aivpn 443 / awg 51820 (AmneziaWG 3.1.20260812, kernel module)
- AWG конфиг: /etc/amnezia/amneziawg/awg0.conf (27 параметров в UI-форме)
- Агент: cron每分钟, буфер сливается batch'ем (247+ метрик в БД)
- Туннель для метрик: ssh -i keys/hydra_key -R 8000:localhost:8000 -N root@31.77.202.131

## Локальный запуск
source .venv/bin/activate && python hydra/cli.py --port 8000
Первый вход: /setup (пароль админа + CLI-токен показываются один раз)

## Что сделано (полностью)
- Auth: setup/login/logout/sessions, rate-limit, CLI-токен
- Серверы: CRUD, reboot, sync-time (Europe/Moscow), test-ssh, bootstrap ключа
- Протоколы: install/restart/edit конфигов (AWG 27 параметров 7 групп,
  WDTT/AIVPN raw JSON) с бекапом .hydra-backup-TS и авто-откатом
- Логи SSH: agent (лог+cron+буфер), wdtt (без [СТАТ]), aivpn (без DEBUG), awg
- Клиенты/ключи: CRUD, revoke/restore, copy, download .conf (KEYFILE v1)
- Метрики: batch streaming parser (raw_decode), история, свёртка 15 строк
- БД: SQL-консоль read-only, бэкап panel.db
- Настройки: /settings (panel.yaml с валидацией и бекапом), ротация CLI-токена

## Известные грабли (ВАЖНО)
1. НЕ обрезать panel.py по маркерам - хвост файла теряется (кейс с /database)
2. Проверять применение heredoc'ов: grep -c "glass" templates/pages/*.html
   (кейс: servers.html остался ДО-Aurora, все патчи промахивались)
3. buffer.json агента = конкатенированные pretty JSON объекты -> raw_decode
4. expires_at в .conf с пробелом, в БД с T - нормализовать
5. AWG конфиг в /etc/amnezia/amneziawg/ (нестандартный путь)
6. agent_installed синхронизировать при install_agent (already_installed)
7. SSH к мёртвому IP висит до таймаута ОС - всегда asyncio.wait_for
8. python только из venv; sqlite3 CLI нет - использовать Python sqlite3
9. Хрупкие якоря в патчах: предпочитать полную перезапись файла или
   regex по устойчивым признакам; всегда печатать count замен

## Что осталось (Этап 3)
1. Telegram-бот aiogram 3.x: /start /redeem /status /devices (клиенты),
   админ-команды, alert-канал, magic-link /login <code> для входа в панель
2. Страница /bot: heartbeat, токен бота, регенерация
3. Рефактор ssh_port в manager/plugins (установки на нестандартный порт)
4. Polish: favicon, CI/CD, README

## Первое задание в новом чате
Telegram-бот: hydra/bot/ (aiogram 3.x),Long-polling, таблица bot_heartbeats,
интеграция с panel API через X-Hydra-Token или внутренний DB-доступ.
Начать с hydra/bot/main.py + роутер /start + heartbeat в панель.

## Стиль работы
Ответы на русском. Код через bash heredoc или Python-скрипты с проверками
(anchor in content + print count). Коммиты после каждого шага.
