# Hydra Key File v1 (.conf)

Формат файла ключа доступа для клиентских приложений.
INI-совместимый, парсится стандартным configparser.

## Пример

    # Hydra Key File v1
    [hydra]
    version = 1
    type = access-key
    key = abc123...
    panel_url = https://panel.example
    expires_at = 2027-01-01 00:00:00
    max_devices = 3
    checksum_sha256 = <sha256(key + panel_url)>

## Поля

| Поле | Тип | Описание |
|---|---|---|
| version | int | Версия формата (сейчас 1) |
| type | str | access-key (зарезервировано: device-config) |
| key | str | Полный ключ доступа (key_id) |
| panel_url | str | Базовый URL панели для redeem |
| expires_at | str | 'YYYY-MM-DD HH:MM:SS' или 'never' |
| max_devices | int | Лимит устройств на ключе |
| checksum_sha256 | str | sha256(key + panel_url) — проверка целостности |

## Поведение клиента

1. Прочитать файл, распарсить секцию [hydra]
2. Проверить version == 1 (иначе отказать)
3. Пересчитать checksum_sha256 = sha256(key + panel_url), сверить
4. Выполнить POST {panel_url}/api/v1/client/redeem с key и device_id
5. Сохранить выданные конфиги протоколов рядом (device-config v1 — будущий формат)

## Безопасность

- Файл содержит секрет — хранить с правами 600
- checksum защищает от повреждения, не от подмены (не签名)
- Для подписи в v2 планируется поле signature_ed25519
