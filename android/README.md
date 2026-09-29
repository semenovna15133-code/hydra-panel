# Hydra Panel — Android Client

Мобильный клиент для [Hydra Control Panel](../hydra) — админ-панели
управления мультипротокольными VPN-серверами (WDTT, AIVPN, AmneziaWG).

Приложение работает напрямую с REST API панели, поэтому весь функционал
веб-панели доступен с телефона в нативном Material 3 интерфейсе.

## Архитектура: два слоя

```
┌─────────────────────────────┐
│  android/  (этот проект)    │  Kotlin + Jetpack Compose (Material 3)
│  Нативное Android-приложение│  → REST API панели (session-token / x-hydra-token)
└──────────────┬──────────────┘
               │ HTTPS
┌──────────────▼──────────────┐
│  hydra/panel.py             │  FastAPI (уже есть в репозитории)
│  /api/v1/* + form-эндпоинты │  ← клиент умеет и JSON, и form-encoded запросы
└─────────────────────────────┘
```

- **Слой 1 (здесь):** полностью рабочий Compose-клиент со всеми экранами панели.
  Транспорт (`PanelApi`) поддерживает и существующие JSON `/api/v1/*` эндпоинты,
  и form-encoded POST-действия веб-панели (те же вызовы, что делает браузер),
  включая чтение HTML-страниц с их server-side контекстом.
- **Слой 2 (рекомендуемый следующий шаг на сервере):** добавить в `hydra/panel.py`
  группу `/api/v1/mobile/*` — тонкий JSON BFF поверх того же кода. Тогда клиент
  переключается на чистый JSON без парсинга HTML. Список недостающих эндпоинтов:
  `docs/API-GAP.md`.

## Функционал (parity с веб-панелью)

| Раздел веб-панели | Экран приложения |
|---|---|
| Логин (pbkdf2, rate-limit 5/15мин) | `LoginScreen` — пароль + «запомнить», токен в EncryptedSharedPreferences |
| Dashboard (Обзор): счётчики, последние серверы | `DashboardScreen` |
| Серверы: список / создание (+SSH-bootstrap паролем) / удаление | `ServersScreen`, диалог добавления |
| Сервер: детали, метрики CPU/RAM/сеть/соединения | `ServerDetailScreen` + графики (Canvas) |
| Установка протоколов / агента | кнопки на `ServerDetailScreen` |
| Reboot VPS, restart сервиса, sync-time, test-SSH | действия на `ServerDetailScreen` |
| Логи сервера (agent/wdtt/aivpn/awg) | `ServerLogsScreen` |
| Конфиги протоколов + AWG-параметры (та же валидация, что в панели) | `ServerConfigsScreen` |
| Токены агента: ротация / revoke всех / список | `TokenManagerScreen` |
| Прогноз нагрузки (forecast по 7 дням) | `ForecastScreen` |
| Клиенты (люди): CRUD, блокировка, каскадное удаление | `ClientsScreen`, `ClientDetailScreen` |
| Ключи доступа: создать / отзыв / восстановить / продлить / удалить / скачать .conf | `KeysScreen` |
| Устройства: список, ручное добавление, удаление | `ClientDetailScreen` |
| Недельные отчёты + генерация / catch-up | `ReportsScreen` |
| База данных: SELECT/PRAGMA-консоль, бекапы (создать/восстановить/удалить/скачать/загрузить) | `DatabaseScreen` |
| Настройки panel.yaml (чтение/сохранение) | `SettingsScreen` |
| GitHub-offsite бекапы, recovery-passphrase | `BackupSettingsScreen` |
| Сессии: список, отзыв одной/всех; CLI-токен: показ/ротация | `SessionsScreen` |

## Сборка

Требования: JDK 17, Android SDK 34. Или просто открой папку `android/` в Android Studio.

```bash
cd android
gradle wrapper --gradle-version 8.5   # один раз, если нет gradlew
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug                # на подключённое устройство
```

## Подключение

1. Запусти панель (см. `deploy/systemd/hydra-panel.service`).
2. В приложении укажи базовый URL панели (например `https://hydra.example.com`)
   и админ-пароль. Приложение авторизуется через `POST /login` и сохраняет
   session-cookie как Bearer-эквивалент; альтернативно можно ввести CLI-токен
   (заголовок `x-hydra-token`, выдаётся на экране Setup/Rotate).
3. Для self-signed сертификатов: включи «Доверять этому сертификату» —
   включается certificate pinning по SHA-256 отпечатку хоста.

## Безопасность

- Токен хранится в **EncryptedSharedPreferences** (Android Keystore), не в plaintext.
- Все destructive-действия (reboot, delete, restore БД, revoke-all сессий)
  требуют подтверждения в UI.
- Rate-limit на логин обрабатывается на клиенте: после 401/«слишком много попыток»
  показывается таймер повторной попытки.

## Структура проекта

```
android/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/hydra/panel/
        │   ├── MainActivity.kt            # навигация, тема
        │   ├── data/api/PanelApi.kt       # Retrofit: JSON + form + cookie-auth
        │   ├── data/model/Models.kt       # DTO (servers, keys, metrics…)
        │   ├── data/repo/PanelRepository.kt
        │   ├── ui/screens/*.kt            # все экраны панели
        │   ├── ui/components/*.kt         # карточки, графики, статус-бейджи
        │   └── util/{SessionStore,HtmlParser,Format}.kt
        └── res/…                          # тема, иконки, strings
```
