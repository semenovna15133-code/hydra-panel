from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.templating import Jinja2Templates
from fastapi.staticfiles import StaticFiles
from fastapi.responses import HTMLResponse, JSONResponse
import os
from datetime import datetime
from fastapi import FastAPI, HTTPException, Header, Request
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel
from typing import Optional
from .core.db import Database
from .core.server_manager import ServerManager

DB_PATH = os.environ.get("HYDRA_DB_PATH", "panel.db")


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

# Setup templates and static files
templates = Jinja2Templates(directory="templates")
app.mount("/static", StaticFiles(directory="static"), name="static")


# Global state
db: Optional[Database] = None
manager: Optional[ServerManager] = None


@app.on_event("startup")
async def startup():
    """Initialize database and manager on startup."""
    global db, manager
    
    # Пути настраиваются через переменные окружения
    db_path = os.environ.get("HYDRA_DB_PATH", DB_PATH)
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
    """Receive metrics from agent.
    
    If server has a pending rotation (needs_new_token flag), returns
    X-New-Token header. Agent picks it up and updates config.env.
    """
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
    
    # Проверить нужна ли ротация (token истекает через < 7 дней)
    response = {"status": "accepted"}
    headers = {}
    
    needs_rotation = await db.scalar(
        """SELECT COUNT(*) FROM agent_tokens
           WHERE server_id = ?
             AND expires_at > datetime('now')
             AND expires_at < datetime('now', '+7 days')""",
        metrics.server_id,
    )
    
    if needs_rotation and needs_rotation > 0:
        # Только если это самый свежий токен (по created_at)
        latest = await db.fetchone(
            """SELECT token_hash, expires_at FROM agent_tokens
               WHERE server_id = ? AND expires_at > datetime('now')
               ORDER BY created_at DESC LIMIT 1""",
            metrics.server_id,
        )
        
        # Если текущий токен истекает скоро и он самый свежий — выдать новый
        import hashlib
        current_hash = hashlib.sha256(token.encode()).hexdigest()
        if latest and latest['token_hash'] == current_hash:
            from .core.token_rotation import rotate_agent_token
            new_token = await rotate_agent_token(db, metrics.server_id)
            headers["X-New-Token"] = new_token
    
    from fastapi.responses import JSONResponse, HTMLResponse
    return JSONResponse(content=response, headers=headers)


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


# Redeem endpoint (full implementation)
from .core.redeem import (
    redeem_key as redeem_key_logic,
    DeviceLimitReached,
    KeyNotFound,
    KeyRevoked,
    KeyExpired,
)
from fastapi import Request


@app.post("/api/v1/client/redeem")
async def redeem_key(data: RedeemRequest, request: Request):
    """Redeem universal key and get configs for all servers.
    
    Implements:
    - Idempotency: same device_id returns existing config
    - Device limit check (max_devices, default 3)
    - Race protection via BEGIN IMMEDIATE
    - Subnet collision detection (alert if 2+ subnets in 5 min)
    
    IP is read from request.remote_addr (reverse proxy forbidden).
    """
    # Get client IP from request (not X-Forwarded-For)
    ip = request.client.host if request.client else "0.0.0.0"
    
    try:
        result = await redeem_key_logic(
            db=db,
            key_id=data.key_id,
            device_id=data.device_id,
            device_name=data.device_name,
            ip=ip,
        )
        return result
        
    except KeyNotFound:
        raise HTTPException(status_code=404, detail="Key not found")
    except KeyRevoked:
        raise HTTPException(status_code=403, detail="Key is revoked")
    except KeyExpired:
        raise HTTPException(status_code=403, detail="Key is expired")
    except DeviceLimitReached as e:
        raise HTTPException(status_code=403, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Redeem failed: {str(e)}")


# Token rotation endpoints
@app.post("/api/v1/servers/{server_id}/tokens/rotate")
async def rotate_token(server_id: str):
    """Manually rotate agent token for server."""
    from .core.token_rotation import rotate_agent_token
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    try:
        new_token = await rotate_agent_token(db, server_id)
        return {
            "status": "rotated",
            "server_id": server_id,
            "note": "Deliver this token to agent via X-New-Token or manual update",
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/v1/servers/{server_id}/tokens/revoke")
async def revoke_tokens(server_id: str):
    """Immediately revoke all tokens (compromise scenario)."""
    from .core.token_rotation import manual_revoke_all
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    count = await manual_revoke_all(db, server_id)
    return {"status": "revoked", "tokens_affected": count}


@app.get("/api/v1/servers/{server_id}/tokens")
async def list_tokens(server_id: str):
    """List token status for server (diagnostics)."""
    from .core.token_rotation import get_active_token_count
    
    tokens = await db.fetchall(
        """SELECT token_hash, expires_at, created_at, rotated_at
           FROM agent_tokens
           WHERE server_id = ?
           ORDER BY created_at DESC""",
        server_id,
    )
    
    # Не возвращаем полные хеши — только первые 8 символов для диагностики
    safe_tokens = [
        {
            "token_hash_prefix": t["token_hash"][:8],
            "expires_at": t["expires_at"],
            "created_at": t["created_at"],
            "rotated_at": t["rotated_at"],
            "active": t["expires_at"] > datetime.utcnow().isoformat() if t["expires_at"] else False,
        }
        for t in tokens
    ]
    
    active_count = await get_active_token_count(db, server_id)
    
    return {
        "server_id": server_id,
        "active_count": active_count,
        "tokens": safe_tokens,
    }


# Forecast and reports endpoints
@app.get("/api/v1/forecast/{server_id}")
async def get_forecast(server_id: str, days: int = 7):
    """Get load forecast for specific server."""
    from .core.forecast import analyze_server_load
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    forecast = await analyze_server_load(db, server_id, days)
    return forecast


@app.get("/api/v1/forecast")
async def get_all_forecasts():
    """Get load forecasts for all servers."""
    from .core.forecast import forecast_all_servers
    
    forecasts = await forecast_all_servers(db)
    return {"forecasts": forecasts}


@app.post("/api/v1/reports/weekly/generate")
async def generate_weekly_report_endpoint(
    week_start: Optional[str] = None,
    week_end: Optional[str] = None,
):
    """Manually generate weekly report."""
    from .core.weekly_report import generate_weekly_report
    
    if week_start and week_end:
        start = datetime.fromisoformat(week_start)
        end = datetime.fromisoformat(week_end)
    else:
        # По умолчанию — последняя полная неделя
        now = datetime.utcnow()
        days_since_monday = now.weekday()
        end = now - timedelta(days=days_since_monday)
        start = end - timedelta(days=7)
    
    report = await generate_weekly_report(db, start, end)
    return report


@app.get("/api/v1/reports/weekly")
async def list_weekly_reports(limit: int = 10):
    """List weekly reports."""
    reports = await db.fetchall(
        """SELECT week_start, week_end, generated_at
           FROM weekly_reports
           ORDER BY week_start DESC
           LIMIT ?""",
        limit,
    )
    return {"reports": reports}


@app.get("/api/v1/reports/weekly/{week_start}")
async def get_weekly_report(week_start: str):
    """Get specific weekly report."""
    report = await db.fetchone(
        "SELECT * FROM weekly_reports WHERE week_start = ?",
        week_start,
    )
    if not report:
        raise HTTPException(status_code=404, detail="Report not found")
    return report




# Web UI Routes
@app.get("/", response_class=HTMLResponse)
async def dashboard(request: Request):
    """Dashboard page."""
    # Get stats
    stats = {
        "total_servers": await db.scalar("SELECT COUNT(*) FROM servers") or 0,
        "total_clients": await db.scalar("SELECT COUNT(*) FROM key_server_clients") or 0,
        "total_keys": await db.scalar("SELECT COUNT(*) FROM access_keys") or 0,
        "active_alerts": await db.scalar("SELECT COUNT(*) FROM alerts WHERE resolved_at IS NULL") or 0,
    }
    
    # Get recent servers
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC LIMIT 5")
    
    return templates.TemplateResponse(
        request=request,
        name="pages/dashboard.html",
        context={"stats": stats, "servers": servers}
    )


@app.get("/servers", response_class=HTMLResponse)
async def servers_list(request: Request, error: Optional[str] = None, deleted: Optional[str] = None):
    """Servers list page."""
    servers = await db.fetchall("SELECT * FROM servers ORDER BY created_at DESC")
    return templates.TemplateResponse(
        request=request,
        name="pages/servers.html",
        context={"servers": servers, "error": error, "deleted": deleted}
    )


@app.get("/servers/{server_id}", response_class=HTMLResponse)
async def server_detail(request: Request, server_id: str, msg: Optional[str] = None):
    """Server detail page."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    # Get protocol instances
    protocols = await db.fetchall(
        "SELECT * FROM protocol_instances WHERE server_id = ?",
        server_id
    )
    
    # Get recent metrics
    metrics = await db.fetchall(
        """SELECT * FROM metrics 
           WHERE server_id = ? 
           ORDER BY timestamp DESC 
           LIMIT 100""",
        server_id
    )
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_detail.html",
        context={"server": server, "protocols": protocols, "metrics": metrics, "msg": msg}
    )


@app.get("/clients", response_class=HTMLResponse)
async def clients_list(request: Request, msg: Optional[str] = None):
    """Clients list page."""
    # Get all client registrations
    clients = await db.fetchall(
        """SELECT dr.*, ak.key_id 
           FROM device_registrations dr
           JOIN access_keys ak ON dr.key_id = ak.key_id
           ORDER BY dr.registered_at DESC"""
    )
    keys = await db.fetchall(
        "SELECT key_id FROM access_keys WHERE revoked_at IS NULL ORDER BY created_at DESC"
    )
    return templates.TemplateResponse(
        request=request,
        name="pages/clients.html",
        context={"clients": clients, "keys": keys, "msg": msg}
    )


@app.get("/keys", response_class=HTMLResponse)
async def keys_list(request: Request, msg: Optional[str] = None):
    """Access keys list page."""
    keys = await db.fetchall(
        """SELECT ak.*, 
                  COUNT(dr.id) as device_count
           FROM access_keys ak
           LEFT JOIN device_registrations dr ON ak.key_id = dr.key_id
           GROUP BY ak.key_id
           ORDER BY ak.created_at DESC"""
    )
    return templates.TemplateResponse(
        request=request,
        name="pages/keys.html",
        context={"keys": keys, "now": datetime.utcnow().isoformat()}
    )


@app.get("/reports", response_class=HTMLResponse)
async def reports_list(request: Request):
    """Reports list page."""
    reports = await db.fetchall(
        "SELECT * FROM weekly_reports ORDER BY week_start DESC LIMIT 20"
    )
    return templates.TemplateResponse(
        request=request,
        name="pages/reports.html",
        context={"reports": reports}
    )

@app.post("/api/v1/reports/weekly/catch-up")
async def catch_up_reports():
    """Generate all missed weekly reports."""
    from .core.weekly_report import check_and_generate_missed_reports
    
    generated = await check_and_generate_missed_reports(db)
    return {
        "status": "completed",
        "reports_generated": len(generated),
        "details": generated,
    }


# Server creation from web form
from fastapi import Form
from fastapi.responses import RedirectResponse


@app.post("/servers/create")
async def create_server_form(
    server_id: str = Form(...),
    ip: str = Form(...),
    location: str = Form(""),
    city: str = Form(""),
    bandwidth_mbps: int = Form(1000),
    ssh_port: int = Form(22),
):
    """Create server from web form (server-side rendering)."""
    from urllib.parse import quote
    try:
        await manager.register_server(
            server_id=server_id,
            ip=ip,
            location=location,
            city=city,
            bandwidth_mbps=bandwidth_mbps,
            ssh_port=ssh_port,
        )
        return RedirectResponse("/servers", status_code=303)
    except Exception as e:
        return RedirectResponse(f"/servers?error={quote(str(e))}", status_code=303)


@app.post("/servers/{server_id}/delete")
async def delete_server_form(server_id: str):
    """Delete server from web form (cascades to related data)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    
    # Каскадное удаление: protocol_instances, metrics, alerts,
    # agent_tokens, key_server_clients (по FOREIGN KEY ... ON DELETE CASCADE)
    await db.execute("DELETE FROM servers WHERE id = ?", server_id)
    await db.commit()
    
    return RedirectResponse(f"/servers?deleted={quote(server_id)}", status_code=303)


# === Управление ключами доступа (UI) ===
from fastapi import Form
from datetime import datetime, timedelta
import secrets
import aiosqlite

@app.post("/api/v1/keys/create")
async def create_key_ui(days_valid: int = Form(30), max_devices: int = Form(3)):
    """Создать новый ключ доступа из UI"""
    key_id = secrets.token_urlsafe(32)
    expires_at = (datetime.utcnow() + timedelta(days=days_valid)).isoformat()
    
    if db is None:
        raise HTTPException(status_code=503, detail="Database not initialized")
    await db.execute(
        "INSERT INTO access_keys (key_id, expires_at, max_devices) VALUES (?, ?, ?)",
        key_id, expires_at, max_devices,
    )
    await db.commit()
    
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/keys", status_code=303)


@app.post("/api/v1/keys/{key_id}/revoke")
async def revoke_key_ui(key_id: str):
    """Отозвать ключ доступа из UI"""
    revoked_at = datetime.utcnow().isoformat()
    
    if db is None:
        raise HTTPException(status_code=503, detail="Database not initialized")
    await db.execute(
        "UPDATE access_keys SET revoked_at = ? WHERE key_id = ?",
        revoked_at, key_id,
    )
    await db.commit()
    
    from fastapi.responses import RedirectResponse
    return RedirectResponse(url="/keys", status_code=303)


@app.post("/servers/{server_id}/install-protocols")
async def install_protocols_form(server_id: str):
    """Install all protocols from web UI (idempotent)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        results = await manager.install_all_protocols(server_id)
        res = results.get("results", {})
        ok = all(r.get("success") for r in res.values())
        msg = "protocols_ok" if ok else "protocols_partial"
    except Exception as e:
        msg = quote("Ошибка установки: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/install-agent")
async def install_agent_form(request: Request, server_id: str):
    """Install monitoring agent from web UI (idempotent)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        panel_url = str(request.base_url).rstrip("/")
        result = await manager.install_agent(server_id=server_id, panel_url=panel_url)
        status = result.get("status", "unknown")
        msg = {"success": "agent_ok", "already_installed": "agent_exists"}.get(status, quote("Агент: " + status))
    except Exception as e:
        msg = quote("Ошибка агента: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


# === Key management (web UI) ===

@app.post("/keys/{key_id}/revoke")
async def revoke_key_form(key_id: str):
    """Приостановить ключ (redeem и новые подключения отклоняются)."""
    key = await db.fetchone("SELECT key_id FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return RedirectResponse("/keys?msg=key_not_found", status_code=303)
    await db.execute(
        "UPDATE access_keys SET revoked_at = datetime('now') WHERE key_id = ?",
        key_id,
    )
    await db.commit()
    return RedirectResponse("/keys?msg=key_revoked", status_code=303)


@app.post("/keys/{key_id}/restore")
async def restore_key_form(key_id: str):
    """Возобновить действие ключа."""
    await db.execute(
        "UPDATE access_keys SET revoked_at = NULL WHERE key_id = ?",
        key_id,
    )
    await db.commit()
    return RedirectResponse("/keys?msg=key_restored", status_code=303)


@app.post("/keys/{key_id}/delete")
async def delete_key_form(key_id: str):
    """Удалить ключ каскадно со всеми привязками."""
    await db.execute("DELETE FROM device_connections WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM device_registrations WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM key_server_clients WHERE key_id = ?", key_id)
    await db.execute("DELETE FROM access_keys WHERE key_id = ?", key_id)
    await db.commit()
    return RedirectResponse("/keys?msg=key_deleted", status_code=303)


@app.get("/keys/{key_id}/download")
async def download_key_conf(request: Request, key_id: str):
    """Скачать ключ в формате Hydra Key File v1 (.conf)."""
    import hashlib
    key = await db.fetchone("SELECT * FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        raise HTTPException(status_code=404, detail="Key not found")
    
    panel_url = str(request.base_url).rstrip("/")
    checksum = hashlib.sha256(f"{key_id}{panel_url}".encode()).hexdigest()
    expires = (key["expires_at"] or "never").replace("T", " ")
    
    content = f"""# Hydra Key File v1
# Сгенерировано Hydra Panel: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}
# Формат: docs/KEYFILE.md (INI-совместимый)

[hydra]
version = 1
type = access-key
key = {key_id}
panel_url = {panel_url}
expires_at = {expires}
max_devices = {key['max_devices']}
checksum_sha256 = {checksum}
"""
    from fastapi.responses import Response
    filename = f"hydra-key-{key_id[:8]}.conf"
    return Response(
        content=content,
        media_type="text/plain",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )


# === Client (device) management (web UI) ===

@app.post("/clients/create")
async def create_client_form(
    key_id: str = Form(...),
    device_id: str = Form(...),
    device_name: str = Form(""),
    last_ip: str = Form(""),
):
    """Добавить устройство вручную (например, перенос из старой системы)."""
    key = await db.fetchone("SELECT key_id FROM access_keys WHERE key_id = ?", key_id)
    if not key:
        return RedirectResponse("/clients?msg=client_nokey", status_code=303)
    try:
        await db.execute(
            """INSERT INTO device_registrations
               (key_id, device_id, device_name, last_ip, registered_at, last_seen_at)
               VALUES (?, ?, ?, ?, datetime('now'), datetime('now'))""",
            key_id, device_id, device_name or None, last_ip or None,
        )
        await db.commit()
        msg = "client_added"
    except Exception:
        msg = "client_exists"
    return RedirectResponse(f"/clients?msg={msg}", status_code=303)


@app.post("/clients/delete")
async def delete_client_form(key_id: str = Form(...), device_id: str = Form(...)):
    """Удалить устройство вместе с историей подключений."""
    await db.execute(
        "DELETE FROM device_connections WHERE key_id = ? AND device_id = ?",
        key_id, device_id,
    )
    await db.execute(
        "DELETE FROM device_registrations WHERE key_id = ? AND device_id = ?",
        key_id, device_id,
    )
    await db.commit()
    return RedirectResponse("/clients?msg=client_deleted", status_code=303)
