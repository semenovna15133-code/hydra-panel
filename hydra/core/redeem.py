"""Redeem logic for universal keys."""
from datetime import datetime
from typing import Optional, Dict, Any
from .db import Database
from .subnet import compute_subnet_key
from .alerts import send_alert


class DeviceLimitReached(Exception):
    """Raised when device limit exceeded."""
    pass


class KeyNotFound(Exception):
    """Raised when key_id not found."""
    pass


class KeyRevoked(Exception):
    """Raised when key is revoked."""
    pass


class KeyExpired(Exception):
    """Raised when key is expired."""
    pass


async def redeem_key(
    db: Database,
    key_id: str,
    device_id: str,
    device_name: str,
    ip: str,
) -> Dict[str, Any]:
    """Redeem universal key and return client config.
    
    Implements:
    - Idempotency: if device_id already registered, return existing config
    - Device limit check (max_devices from access_keys, default 3)
    - Race protection via BEGIN IMMEDIATE
    - Subnet collision detection (alert if 2+ subnets in 5 min)
    """
    now = datetime.utcnow().isoformat()
    subnet_key = compute_subnet_key(ip)
    
    # BEGIN IMMEDIATE для race protection
    await db.execute("BEGIN IMMEDIATE")
    rolled_back = False
    
    try:
        # 1. Check key exists and is active
        key = await db.fetchone(
            """SELECT * FROM access_keys 
               WHERE key_id = ? 
                 AND revoked_at IS NULL 
                 AND (expires_at IS NULL OR expires_at > datetime('now'))""",
            key_id,
        )
        
        if not key:
            key_any = await db.fetchone(
                "SELECT * FROM access_keys WHERE key_id = ?",
                key_id,
            )
            
            if not key_any:
                await db.execute("ROLLBACK")
                rolled_back = True
                raise KeyNotFound(f"Key {key_id} not found")
            
            if key_any.get("revoked_at"):
                await db.execute("ROLLBACK")
                rolled_back = True
                raise KeyRevoked(f"Key {key_id} is revoked")
            
            await db.execute("ROLLBACK")
            rolled_back = True
            raise KeyExpired(f"Key {key_id} is expired")
        
        max_devices = key.get("max_devices", 3)
        
        # 2. Check if device already registered (idempotency)
        existing = await db.fetchone(
            """SELECT * FROM device_registrations 
               WHERE key_id = ? AND device_id = ?""",
            key_id, device_id,
        )
        
        if existing:
            await db.execute(
                """UPDATE device_registrations 
                   SET last_seen_at = ?, last_ip = ? 
                   WHERE id = ?""",
                now, ip, existing["id"],
            )
            
            await db.execute(
                """INSERT INTO device_connections 
                   (key_id, device_id, ip, subnet_key, connected_at)
                   VALUES (?, ?, ?, ?, ?)""",
                key_id, device_id, ip, subnet_key, now,
            )
            
            await db.execute("COMMIT")
            
            await detect_subnet_collision(db, key_id, device_id)
            
            config = await build_client_config(db, key_id)
            return {"status": "ok", "config": config, "device_registered": False}
        
        # 3. Check device limit
        count = await db.scalar(
            "SELECT COUNT(*) FROM device_registrations WHERE key_id = ?",
            key_id,
        )
        
        if count >= max_devices:
            await db.execute("ROLLBACK")
            rolled_back = True
            raise DeviceLimitReached(
                f"Device limit reached: {count}/{max_devices}"
            )
        
        # 4. Register new device
        await db.execute(
            """INSERT INTO device_registrations 
               (key_id, device_id, device_name, last_ip, registered_at, last_seen_at)
               VALUES (?, ?, ?, ?, ?, ?)""",
            key_id, device_id, device_name, ip, now, now,
        )
        
        # 5. Record connection
        await db.execute(
            """INSERT INTO device_connections 
               (key_id, device_id, ip, subnet_key, connected_at)
               VALUES (?, ?, ?, ?, ?)""",
            key_id, device_id, ip, subnet_key, now,
        )
        
        await db.execute("COMMIT")
        
        # 6. Check for subnet collision
        await detect_subnet_collision(db, key_id, device_id)
        
        # 7. Build and return config
        config = await build_client_config(db, key_id)
        return {"status": "ok", "config": config, "device_registered": True}
        
    except Exception:
        if not rolled_back:
            await db.execute("ROLLBACK")
        raise


async def detect_subnet_collision(db: Database, key_id: str, device_id: str) -> None:
    """Detect if device connected from 2+ subnets in 5 minutes."""
    sql = """
        SELECT COUNT(DISTINCT subnet_key)
        FROM device_connections
        WHERE key_id = ? 
          AND device_id = ? 
          AND connected_at > datetime('now', '-5 minutes')
    """
    
    subnet_count = await db.scalar(sql, key_id, device_id)
    
    if subnet_count and subnet_count > 1:
        ips = await db.fetchall(
            """SELECT DISTINCT ip 
               FROM device_connections
               WHERE key_id = ? 
                 AND device_id = ? 
                 AND connected_at > datetime('now', '-5 minutes')""",
            key_id, device_id,
        )
        
        ip_list = ", ".join(row["ip"] for row in ips)
        
        await send_alert(
            level="suspicious",
            message=(
                f"Подозрительная активность: key_id={key_id}, device_id={device_id} "
                f"подключался с {subnet_count} разных подсетей за 5 минут. IP: {ip_list}"
            ),
            db=db,
        )


async def build_client_config(db: Database, key_id: str) -> Dict[str, Any]:
    """Build client config for all servers where key has active clients."""
    clients = await db.fetchall(
        """SELECT ksc.*, s.ip, s.location, s.city, pi.protocol, pi.port
           FROM key_server_clients ksc
           JOIN servers s ON ksc.server_id = s.id
           JOIN protocol_instances pi ON pi.server_id = s.id 
             AND pi.protocol = ksc.protocol
           WHERE ksc.key_id = ?""",
        key_id,
    )
    
    servers = {}
    for client in clients:
        server_id = client["server_id"]
        if server_id not in servers:
            servers[server_id] = {
                "server_id": server_id,
                "ip": client["ip"],
                "location": client["location"],
                "city": client["city"],
                "protocols": {},
            }
        
        protocol = client["protocol"]
        servers[server_id]["protocols"][protocol] = {
            "port": client["port"],
            "config": "encrypted_config_placeholder",
        }
    
    return {
        "key_id": key_id,
        "servers": list(servers.values()),
    }
