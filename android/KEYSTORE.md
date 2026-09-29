# Release-подпись Hydra Panel Mobile

- Keystore: `android/hydra-release.jks` (PKCS12, RSA 2048, alias `hydra`, срок 30 лет)
- Пароли по умолчанию в build.gradle.kts: `HydraPanel!2026` (можно переопределить env-переменными HYDRA_STORE_PASS / HYDRA_KEY_PASS)
- SHA-256 сертификата: 977563d1a8cc8545a22ec7fd110accc01b5cf97a7f74a505a1c74a7d0db48a4a

ВАЖНО: храните hydra-release.jks и пароли в секрете. Без них невозможно будет
обновлять приложение поверх установленной версии (Android требует ту же подпись).
Скопируйте keystore в надёжное место и при возможности смените пароль.
