"""FastAPI application for Hydra Control Panel."""
import os
from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel
from typing import Optional
from .core.db import Database
from .core.server_manager import ServerManager


# Pydantic models
class ServerRegister(BaseModel):
    server_id: str
    ip: str
    location: str = ""
    city: str = ""
    bandwidth_mbps: int = 1000
    ssh_port: int = 22


class ClientCreate(BaseModel):
    label: str
    days: int = 0
    profile: str = "balanced"


class RedeemRequest(BaseModel):
    key_id: str
    device_id: str
    device_name: str


class AgentMetrics(BaseModel):
    server_id: str
    timestamp: str
    cpu_percent: float
    memory_percent: float
    network_rx_mbps: float
    network_tx_mbps: float
    connections_wdtt: int
    connections_aivpn: int
    connections_awg: int
    status_wdtt: str
    status_aivpn: str
    status_awg: str
    latency_ms: float


# Application setup
app = FastAPI(
    title="Hydra Control Panel",
    description="Multi-protocol VPN server management",
    version="0.1.0",
)

# Global state
db: Optional[Database] = None
manager: Optional[ServerManager] = None


@app.on_event("startup")
async def startup():
    """Initialize database and manager on startup."""
    global db, manager
    
    # Пути настраиваются через переменные окружения
    db_path = os.environ.get("HYDRA_DB_PATH", "/var/lib/hydra/panel.db")
    ssh_key = os.environ.get("HYDRA_SSH_KEY", "/opt/hydra/keys/panel_key")
    
    db = Database(db_path)
    await db.connect()
    await db.init_schema()
    manager = ServerManager(db, ssh_key)


@app.on_event("shutdown")
async def shutdown():
    """Close database on shutdown."""
    if db:
        await db.close()


# Health check
@app.get("/health")
async def health():
    """Simple health check."""
    return {"status": "ok", "service": "hydra-panel"}


# Server endpoints
@app.post("/api/v1/servers")
async def register_server(data: ServerRegister):
    """Register a new server."""
    try:
        server = await manager.register_server(
            server_id=data.server_id,
            ip=data.ip,
            location=data.location,
            city=data.city,
            bandwidth_mbps=data.bandwidth_mbps,
            ssh_port=data.ssh_port,
        )
        return {"status": "created", "server": server}
    except Exception as e:
        raise HTTPException(status_code=400, detail=str(e))


@app.get("/api/v1/servers")
async def list_servers():
    """List all registered servers."""
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC")
    return {"servers": servers}


@app.get("/api/v1/servers/{server_id}")
async def get_server(server_id: str):
    """Get server details."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    return server


@app.post("/api/v1/servers/{server_id}/install")
async def install_protocols(server_id: str):
    """Install all protocols on server."""
    try:
        results = await manager.install_all_protocols(server_id)
        return {"status": "completed", "results": results}
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/servers/{server_id}/status")
async def get_server_status(server_id: str):
    """Get aggregated status for all protocols."""
    try:
        status = await manager.get_server_status(server_id)
        return status
    except ValueError as e:
        raise HTTPException(status_code=404, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# Client endpoints
@app.post("/api/v1/servers/{server_id}/clients/{protocol}")
async def add_client(server_id: str, protocol: str, data: ClientCreate):
    """Add client to specific protocol on server."""
    try:
        result = await manager.add_client_to_server(
            server_id=server_id,
            protocol=protocol,
            label=data.label,
            days=data.days,
            profile=data.profile,
        )
        return {"status": "created", "client": result}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/v1/servers/{server_id}/clients/{protocol}")
async def list_clients(server_id: str, protocol: str):
    """List all clients for protocol on server."""
    try:
        clients = await manager.list_clients_on_server(server_id, protocol)
        return {"clients": clients}
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.delete("/api/v1/servers/{server_id}/clients/{protocol}/{client_id}")
async def remove_client(server_id: str, protocol: str, client_id: str):
    """Remove client from protocol on server."""
    try:
        result = await manager.remove_client_from_server(
            server_id=server_id,
            protocol=protocol,
            client_id=client_id,
        )
        return result
    except ValueError as e:
        raise HTTPException(status_code=400, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


# Agent endpoints
@app.post("/api/v1/agent/metrics")
async def agent_metrics(
    metrics: AgentMetrics,
    authorization: Optional[str] = Header(None),
):
    """Receive metrics from agent."""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing or invalid token")
    
    token = authorization.split(" ", 1)[1]
    
    valid = await manager.verify_agent_token(metrics.server_id, token)
    if not valid:
        raise HTTPException(status_code=401, detail="Invalid token")
    
    await db.execute(
        """INSERT INTO metrics 
           (server_id, timestamp, cpu_percent, memory_percent, 
            network_rx_mbps, network_tx_mbps,
            connections_wdtt, connections_aivpn, connections_awg,
            status_wdtt, status_aivpn, status_awg, latency_ms)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        metrics.server_id, metrics.timestamp,
        metrics.cpu_percent, metrics.memory_percent,
        metrics.network_rx_mbps, metrics.network_tx_mbps,
        metrics.connections_wdtt, metrics.connections_aivpn, metrics.connections_awg,
        metrics.status_wdtt, metrics.status_aivpn, metrics.status_awg,
        metrics.latency_ms,
    )
    await db.commit()
    
    return {"status": "accepted"}


# Client redeem endpoint
@app.post("/api/v1/client/redeem")
async def redeem_key(data: RedeemRequest):
    """Redeem universal key and get configs for all servers."""
    return {
        "status": "not_implemented",
        "message": "Redeem logic pending - see manifest §9",
        "key_id": data.key_id,
        "device_id": data.device_id,
    }


@app.get("/api/v1/client/servers")
async def get_client_servers(key_id: str):
    """Get list of servers for a key."""
    return {"status": "not_implemented", "key_id": key_id}


# Bot endpoints
@app.post("/api/v1/bot/heartbeat")
async def bot_heartbeat(
    bot_id: str,
    uptime: int,
    last_update_id: int = 0,
    pending_updates: int = 0,
):
    """Receive heartbeat from Telegram bot."""
    await db.execute(
        """INSERT OR REPLACE INTO bot_heartbeats 
           (bot_id, last_heartbeat_at, last_update_id, pending_updates, uptime_sec)
           VALUES (?, datetime('now'), ?, ?, ?)""",
        bot_id, last_update_id, pending_updates, uptime,
    )
    await db.commit()
    return {"status": "ok"}


@app.get("/api/v1/bot/token-check")
async def bot_token_check():
    """Check bot token validity (called by panel itself)."""
    return {"ok": True}
