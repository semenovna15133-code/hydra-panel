"""Agent token rotation with grace period.

Composite PK (server_id, token_hash) in agent_tokens allows two tokens
to coexist during grace period: new active + old with expires_at = now + grace.

ВАЖНО: все даты хранятся в формате SQLite-compatible: 'YYYY-MM-DD HH:MM:SS'
"""
import hashlib
import secrets
from datetime import datetime, timedelta
from typing import Optional
from .db import Database


def sqlite_datetime(dt: Optional[datetime] = None) -> str:
    """Convert datetime to SQLite-compatible format.
    
    SQLite uses 'YYYY-MM-DD HH:MM:SS' for datetime comparisons.
    Python's isoformat() produces 'YYYY-MM-DDTHH:MM:SS.microseconds'
    which breaks lexicographic comparisons in SQL.
    """
    if dt is None:
        dt = datetime.utcnow()
    return dt.strftime('%Y-%m-%d %H:%M:%S')


async def rotate_agent_token(
    db: Database,
    server_id: str,
    rotation_days: int = 90,
    grace_period_hours: int = 24,
) -> str:
    """Rotate agent token for server.
    
    Steps:
    1. Generate new token
    2. INSERT new token with expires_at = now + rotation_days
       (possible thanks to composite PK — two tokens coexist)
    3. Mark old active tokens with expires_at = now + grace_period
       (filter ensures long-expired tokens are NOT revived)
    """
    now = datetime.utcnow()
    now_str = sqlite_datetime(now)
    
    # 1. Generate new token
    new_token = secrets.token_urlsafe(32)
    new_hash = hashlib.sha256(new_token.encode()).hexdigest()
    new_expires = now + timedelta(days=rotation_days)
    new_expires_str = sqlite_datetime(new_expires)
    
    # 2. INSERT new token (composite PK allows coexistence)
    await db.execute(
        """INSERT INTO agent_tokens (server_id, token_hash, expires_at, created_at)
           VALUES (?, ?, ?, ?)""",
        server_id, new_hash, new_expires_str, now_str,
    )
    
    # 3. Mark old active tokens with grace period
    grace_expires = now + timedelta(hours=grace_period_hours)
    grace_expires_str = sqlite_datetime(grace_expires)
    
    await db.execute(
        """UPDATE agent_tokens
           SET expires_at = ?, rotated_at = ?
           WHERE server_id = ?
             AND (expires_at IS NULL OR expires_at > datetime('now'))
             AND token_hash != ?""",
        grace_expires_str, now_str, server_id, new_hash,
    )
    
    await db.commit()
    return new_token


async def cleanup_expired_tokens(db: Database) -> int:
    """Remove tokens expired more than 1 day ago.
    
    Called by cron daily. Returns number of deleted rows.
    """
    cursor = await db.execute(
        "DELETE FROM agent_tokens WHERE expires_at < datetime('now', '-1 day')"
    )
    await db.commit()
    return cursor.rowcount


async def manual_revoke_all(db: Database, server_id: str) -> int:
    """Immediately revoke all tokens for server (compromise scenario).
    
    Sets expires_at = now for all active tokens. Agent will be locked out
    until new token is provisioned.
    """
    # Используем datetime('now') напрямую в SQL для гарантии совместимости
    cursor = await db.execute(
        """UPDATE agent_tokens
           SET expires_at = datetime('now'), rotated_at = datetime('now')
           WHERE server_id = ?
             AND (expires_at IS NULL OR expires_at > datetime('now'))""",
        server_id,
    )
    await db.commit()
    return cursor.rowcount


async def get_active_token_count(db: Database, server_id: str) -> int:
    """Count active tokens for server (for diagnostics)."""
    count = await db.scalar(
        """SELECT COUNT(*) FROM agent_tokens
           WHERE server_id = ? AND expires_at > datetime('now')""",
        server_id,
    )
    return count or 0
