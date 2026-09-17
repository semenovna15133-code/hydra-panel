"""Hydra Recovery: passphrase-шифрованный бандл ключей для DR в одну команду."""
import base64
import json
import os
from datetime import datetime

from cryptography.fernet import Fernet
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

BUNDLE_NAME = "recovery-bundle.enc"


def _bundle_fernet(passphrase: str) -> Fernet:
    key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b"hydra-recovery-bundle-v1",
        info=b"bundle",
    ).derive(passphrase.encode())
    return Fernet(base64.urlsafe_b64encode(key))


def encrypt_bundle(data: dict, passphrase: str) -> bytes:
    return _bundle_fernet(passphrase).encrypt(json.dumps(data).encode())


def decrypt_bundle(blob: bytes, passphrase: str) -> dict:
    return json.loads(_bundle_fernet(passphrase).decrypt(blob))


def build_bundle_payload(ssh_key_path: str, master_key_path: str, github: dict) -> dict:
    payload = {"created_at": datetime.now().strftime("%Y-%m-%d %H:%M:%S"), "github": github}
    if os.path.exists(ssh_key_path):
        with open(ssh_key_path) as f:
            payload["ssh_key"] = f.read()
    pub = ssh_key_path + ".pub"
    if os.path.exists(pub):
        with open(pub) as f:
            payload["ssh_pub"] = f.read()
    if os.path.exists(master_key_path):
        with open(master_key_path, "rb") as f:
            payload["master_key_b64"] = base64.b64encode(f.read()).decode()
    return payload
