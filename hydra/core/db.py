"""SQLite database wrapper for Hydra Panel."""
import aiosqlite
from pathlib import Path
from typing import Optional, Any
from contextlib import asynccontextmanager


class Database:
    """Async SQLite database wrapper."""
    
    def __init__(self, db_path: str = "/var/lib/hydra/panel.db"):
        self.db_path = db_path
        self._db: Optional[aiosqlite.Connection] = None
    
    async def connect(self) -> None:
        """Establish database connection."""
        if self._db is not None:
            return
        
        # Создать директорию если не существует
        Path(self.db_path).parent.mkdir(parents=True, exist_ok=True)
        
        self._db = await aiosqlite.connect(self.db_path)
        self._db.row_factory = aiosqlite.Row
        
        # Включить foreign keys
        await self._db.execute("PRAGMA foreign_keys = ON")
    
    async def close(self) -> None:
        """Close database connection."""
        if self._db is not None:
            await self._db.close()
            self._db = None
    
    async def __aenter__(self):
        await self.connect()
        return self
    
    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()
    
    async def execute(self, query: str, *params) -> aiosqlite.Cursor:
        """Execute SQL query."""
        if self._db is None:
            await self.connect()
        return await self._db.execute(query, params)
    
    async def executemany(self, query: str, params_list: list) -> aiosqlite.Cursor:
        """Execute SQL query with multiple parameter sets."""
        if self._db is None:
            await self.connect()
        return await self._db.executemany(query, params_list)
    
    async def fetchone(self, query: str, *params) -> Optional[dict]:
        """Fetch one row."""
        cursor = await self.execute(query, *params)
        row = await cursor.fetchone()
        return dict(row) if row else None
    
    async def fetchall(self, query: str, *params) -> list[dict]:
        """Fetch all rows."""
        cursor = await self.execute(query, *params)
        rows = await cursor.fetchall()
        return [dict(row) for row in rows]
    
    async def scalar(self, query: str, *params) -> Any:
        """Fetch single scalar value."""
        cursor = await self.execute(query, *params)
        row = await cursor.fetchone()
        return row[0] if row else None
    
    async def commit(self) -> None:
        """Commit transaction."""
        if self._db:
            await self._db.commit()
    
    @asynccontextmanager
    async def transaction(self):
        """Context manager for transactions."""
        if self._db is None:
            await self.connect()
        
        await self._db.execute("BEGIN")
        try:
            yield self
            await self._db.commit()
        except Exception:
            await self._db.rollback()
            raise
    
    async def init_schema(self) -> None:
        """Initialize database schema (from manifest §14)."""
        schema = """
-- Servers
CREATE TABLE IF NOT EXISTS servers (
    id TEXT PRIMARY KEY,
    location TEXT,
    city TEXT,
    ip TEXT NOT NULL UNIQUE,
    ssh_port INTEGER DEFAULT 22,
    bandwidth_mbps INTEGER NOT NULL,
    status TEXT DEFAULT 'active',
    agent_installed BOOLEAN DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS protocol_instances (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id TEXT REFERENCES servers(id) ON DELETE CASCADE,
    protocol TEXT NOT NULL,
    port INTEGER,
    max_connections INTEGER NOT NULL DEFAULT 50,
    status TEXT DEFAULT 'active',
    config JSON,
    UNIQUE(server_id, protocol)
);

-- Access keys (universal redeem model)
CREATE TABLE IF NOT EXISTS access_keys (
    key_id TEXT PRIMARY KEY,
    user_id INTEGER,
    expires_at TIMESTAMP,
    max_devices INTEGER DEFAULT 3,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Device registrations
CREATE TABLE IF NOT EXISTS device_registrations (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_id TEXT REFERENCES access_keys(key_id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    device_name TEXT,
    last_ip TEXT,
    registered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(key_id, device_id)
);

-- Device connections history (TTL: 30 days via cron)
CREATE TABLE IF NOT EXISTS device_connections (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_id TEXT REFERENCES access_keys(key_id),
    device_id TEXT,
    ip TEXT NOT NULL,
    subnet_key TEXT NOT NULL,
    connected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Key-server-client mapping
CREATE TABLE IF NOT EXISTS key_server_clients (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    key_id TEXT REFERENCES access_keys(key_id) ON DELETE CASCADE,
    server_id TEXT REFERENCES servers(id) ON DELETE CASCADE,
    protocol TEXT NOT NULL,
    client_config_enc BLOB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(key_id, server_id, protocol)
);

-- Metrics (from agents)
CREATE TABLE IF NOT EXISTS metrics (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id TEXT REFERENCES servers(id) ON DELETE CASCADE,
    timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    cpu_percent REAL,
    memory_percent REAL,
    network_rx_mbps REAL,
    network_tx_mbps REAL,
    connections_wdtt INTEGER,
    connections_aivpn INTEGER,
    connections_awg INTEGER,
    status_wdtt TEXT,
    status_aivpn TEXT,
    status_awg TEXT,
    latency_ms REAL
);

-- Alerts
CREATE TABLE IF NOT EXISTS alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id TEXT REFERENCES servers(id) ON DELETE CASCADE,
    level TEXT NOT NULL,
    message TEXT,
    triggered_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP
);

-- Weekly reports
CREATE TABLE IF NOT EXISTS weekly_reports (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    week_start DATE NOT NULL,
    week_end DATE NOT NULL,
    total_keys INTEGER,
    total_devices INTEGER,
    avg_load REAL,
    peak_load REAL,
    protocol_distribution JSON,
    recommendation TEXT,
    generated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(week_start)
);

-- Bot heartbeats
CREATE TABLE IF NOT EXISTS bot_heartbeats (
    bot_id TEXT PRIMARY KEY,
    last_heartbeat_at TIMESTAMP,
    last_update_id INTEGER,
    pending_updates INTEGER,
    uptime_sec INTEGER
);

-- Agent tokens (composite PK for rotation grace period)
CREATE TABLE IF NOT EXISTS agent_tokens (
    server_id TEXT REFERENCES servers(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL DEFAULT '9999-12-31 23:59:59',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    rotated_at TIMESTAMP,
    PRIMARY KEY (server_id, token_hash)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_metrics_server_time ON metrics(server_id, timestamp);
CREATE INDEX IF NOT EXISTS idx_device_reg_unique ON device_registrations(key_id, device_id);
CREATE INDEX IF NOT EXISTS idx_key_clients_key_server ON key_server_clients(key_id, server_id, protocol);
CREATE INDEX IF NOT EXISTS idx_access_keys_active ON access_keys(revoked_at, expires_at);
CREATE INDEX IF NOT EXISTS idx_alerts_server_resolved ON alerts(server_id, resolved_at);
CREATE INDEX IF NOT EXISTS idx_device_connections_lookup ON device_connections(key_id, device_id, connected_at);
CREATE INDEX IF NOT EXISTS idx_device_connections_subnet ON device_connections(subnet_key, connected_at);
CREATE INDEX IF NOT EXISTS idx_agent_tokens_expires ON agent_tokens(expires_at);
"""
        
        if self._db is None:
            await self.connect()
        
        await self._db.executescript(schema)
        await self.commit()
