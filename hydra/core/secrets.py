"""Hydra secrets: master key + AES-256-GCM шифрование секретов в БД."""
import base64
import hashlib
import json
import os
from datetime import datetime

from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

SECRETS_SCHEMA = """
CREATE TABLE IF NOT EXISTS secrets (
    provider TEXT PRIMARY KEY,
    payload TEXT NOT NULL,
    meta TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
"""

MASTER_KEY_PATH = os.environ.get(
    "HYDRA_MASTER_KEY",
    os.path.expanduser("~/.config/hydra/master.key"),
)


def _ensure_master_key() -> bytes:
    """Создаёт master.key один раз, возвращает 32 байта."""
    if os.path.exists(MASTER_KEY_PATH):
        with open(MASTER_KEY_PATH, "rb") as f:
            data = f.read()
        if len(data) >= 32:
            return data[:32]
    os.makedirs(os.path.dirname(MASTER_KEY_PATH), exist_ok=True)
    key = os.urandom(32)
    fd = os.open(MASTER_KEY_PATH, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    try:
        os.write(fd, key)
    finally:
        os.close(fd)
    return key


def _derive_fernet_key(master: bytes, purpose: str) -> bytes:
    """Деривация Fernet-ключа из master + purpose (HKDF-SHA256)."""
    return HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b"hydra-secrets-v1",
        info=purpose.encode(),
    ).derive(master)


def encrypt(plaintext: str, provider: str) -> str:
    """Шифрует AES-128-Fernet, возвращает base64-строку."""
    import base64 as _b64
    master = _ensure_master_key()
    key_b64 = _b64.urlsafe_b64encode(_derive_fernet_key(master, f"provider:{provider}"))
    fernet = Fernet(key_b64)
    return fernet.encrypt(plaintext.encode()).decode()


def decrypt(ciphertext: str, provider: str) -> str:
    import base64 as _b64
    master = _ensure_master_key()
    key_b64 = _b64.urlsafe_b64encode(_derive_fernet_key(master, f"provider:{provider}"))
    fernet = Fernet(key_b64)
    return fernet.decrypt(ciphertext.encode()).decode()


def master_fingerprint() -> str:
    """Отпечаток master.key для UI (проверка что ключ на месте)."""
    master = _ensure_master_key()
    return hashlib.sha256(master).hexdigest()[:16]


async def ensure_table(db) -> None:
    for stmt in SECRETS_SCHEMA.strip().split(";"):
        stmt = stmt.strip()
        if stmt:
            await db.execute(stmt)
    await db.commit()


async def set_secret(db, provider: str, plaintext: str, meta: dict | None = None) -> None:
    now = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    encrypted = encrypt(plaintext, provider)
    await db.execute(
        """INSERT INTO secrets (provider, payload, meta, created_at, updated_at)
           VALUES (?, ?, ?, ?, ?)
           ON CONFLICT(provider) DO UPDATE SET
             payload = excluded.payload,
             meta = excluded.meta,
             updated_at = excluded.updated_at""",
        provider, encrypted, json.dumps(meta or {}), now, now,
    )
    await db.commit()


async def get_secret(db, provider: str):
    row = await db.fetchone("SELECT payload, meta FROM secrets WHERE provider = ?", provider)
    if not row:
        return None, None
    try:
        plaintext = decrypt(row["payload"], provider)
    except Exception:
        return None, None
    return plaintext, json.loads(row["meta"] or "{}")


async def delete_secret(db, provider: str) -> None:
    await db.execute("DELETE FROM secrets WHERE provider = ?", provider)
    await db.commit()


async def list_providers(db):
    return await db.fetchall(
        "SELECT provider, meta, created_at, updated_at FROM secrets ORDER BY provider"
    )
