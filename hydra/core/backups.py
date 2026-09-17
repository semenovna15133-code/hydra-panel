"""Hydra backups: локальные снапшоты, ротация, восстановление, GitHub offsite."""
import base64
import gzip
import json
import os
import shutil
import sqlite3
import urllib.request
from datetime import datetime

BACKUP_DIR = os.environ.get("HYDRA_BACKUP_DIR", "backups")


def ensure_dir() -> None:
    os.makedirs(BACKUP_DIR, exist_ok=True)


def list_backups():
    ensure_dir()
    items = []
    for name in sorted(os.listdir(BACKUP_DIR), reverse=True):
        if name.endswith(".db"):
            p = os.path.join(BACKUP_DIR, name)
            items.append({
                "name": name,
                "size_kb": round(os.path.getsize(p) / 1024, 1),
                "mtime": datetime.fromtimestamp(os.path.getmtime(p)).strftime("%Y-%m-%d %H:%M:%S"),
                "auto": name.startswith("hydra-backup-"),
            })
    return items


def create_backup(db_path: str = "panel.db", prefix: str = "hydra-backup", tag: str = ""):
    ensure_dir()
    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    name = f"{prefix}-{ts}" + (f"-{tag}" if tag else "") + ".db"
    dst = os.path.join(BACKUP_DIR, name)
    src = sqlite3.connect(db_path)
    out = sqlite3.connect(dst)
    src.backup(out)
    out.close()
    src.close()
    return name


def rotate(keep: int = 3):
    removed = []
    autos = [i["name"] for i in list_backups() if i["auto"]]
    for name in autos[keep:]:
        os.remove(os.path.join(BACKUP_DIR, name))
        removed.append(name)
    return removed


def verify(path: str) -> bool:
    try:
        conn = sqlite3.connect(path)
        ok = conn.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        conn.close()
        return ok
    except Exception:
        return False


def restore(name: str, db_path: str = "panel.db") -> str:
    src = os.path.join(BACKUP_DIR, name)
    if not os.path.exists(src):
        raise ValueError("файл не найден")
    if not verify(src):
        raise ValueError("бэкап повреждён (integrity_check)")
    create_backup(db_path, prefix="pre-restore")
    shutil.copy2(src, db_path)
    return name


def push_github(repo: str, token: str, backup_name: str, compress: bool = True):
    path = os.path.join(BACKUP_DIR, backup_name)
    with open(path, "rb") as f:
        data = f.read()
    if compress:
        comp = gzip.compress(data, 6)
        remote = f"backups/{backup_name}.gz"
    else:
        comp = data
        remote = f"backups/{backup_name}"
    if len(comp) > 900_000:
        return {"ok": False, "error": "размер > 900 KB — лимит GitHub API"}
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "User-Agent": "hydra-panel",
        "Content-Type": "application/json",
    }
    sha = None
    try:
        req = urllib.request.Request(f"https://api.github.com/repos/{repo}/contents/{remote}", headers=headers)
        with urllib.request.urlopen(req, timeout=30) as r:
            sha = json.loads(r.read()).get("sha")
    except Exception:
        pass
    body = {"message": f"hydra auto-backup {backup_name}", "content": base64.b64encode(comp).decode()}
    if sha:
        body["sha"] = sha
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/contents/{remote}",
        data=json.dumps(body).encode(), method="PUT", headers=headers)
    with urllib.request.urlopen(req, timeout=60) as r:
        return {"ok": r.status in (200, 201)}
