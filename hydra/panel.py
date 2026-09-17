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
        if status in ("success", "already_installed"):
            # Синхронизируем флаг с реальным состоянием на сервере
            await db.execute("UPDATE servers SET agent_installed = 1 WHERE id = ?", server_id)
            await db.commit()
            # Взятие под управление: выставляем часовой пояс панели
            try:
                async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
                    await ssh.run(f"timedatectl set-timezone {PANEL_TIMEZONE}")
            except Exception:
                pass
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


# === SSH operations (web UI) ===

from .ssh import SSHTransport

PROTOCOL_RESTART_CMDS = {
    "wdtt": "systemctl restart wdtt",
    "aivpn": "systemctl restart aivpn-server",
    "awg": "awg-quick down awg0 && awg-quick up awg0",
}

PROTOCOL_LOG_CMDS = {
    "agent": '{ echo "=== hydra-agent.log (last 200) ==="; tail -200 /var/log/hydra-agent.log 2>/dev/null; echo; echo "=== cron runs (hydra) ==="; journalctl -n 300 --no-pager 2>/dev/null | grep -i hydra | tail -20; echo; echo "=== buffer status ==="; ls -la /var/lib/hydra-agent/ 2>/dev/null; wc -l /var/lib/hydra-agent/buffer.ndjson 2>/dev/null; }',
    "wdtt": '{ echo "=== События (подключения/ошибки, без [СТАТ]) ==="; journalctl -u wdtt -n 3000 --no-pager -q | grep -v "\[СТАТ\]" | tail -80; echo; echo "=== Свежая статистика (последние 10) ==="; journalctl -u wdtt -n 40 --no-pager -q | grep "\[СТАТ\]" | tail -10; }',
    "aivpn": '{ echo "=== События (без DEBUG) ==="; journalctl -u aivpn-server -n 400 --no-pager -q | grep -v " DEBUG " | tail -100; }',
    "awg": '{ awg show awg0 2>/dev/null; echo; echo "=== dmesg (awg0) ==="; dmesg | grep awg0 | tail -100; }',
}


@app.post("/servers/{server_id}/reboot")
async def reboot_server_form(server_id: str):
    """Перезагрузка VPS через SSH (с задержкой для корректного закрытия сессии)."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            await ssh.run("nohup sh -c 'sleep 2 && reboot' >/dev/null 2>&1 &")
        msg = "reboot_started"
    except Exception as e:
        msg = quote("Ошибка перезагрузки: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/restart-service")
async def restart_service_form(server_id: str, protocol: str = Form(...)):
    """Перезапуск сервиса протокола на сервере."""
    from urllib.parse import quote
    cmd = PROTOCOL_RESTART_CMDS.get(protocol)
    if not cmd:
        return RedirectResponse(f"/servers/{server_id}?msg=unknown_protocol", status_code=303)
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(cmd)
        if result.exit_code == 0:
            msg = f"service_restarted_{protocol}"
        else:
            msg = quote(f"Сервис ответил ошибкой: {(result.stderr or result.stdout)[:200]}")
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.post("/servers/{server_id}/rotate-token")
async def rotate_token_form(server_id: str):
    """Ротация токена агента (старый в grace-периоде, агент получит новый по X-New-Token)."""
    from urllib.parse import quote
    try:
        await manager.generate_agent_token(server_id)
        msg = "token_rotated"
    except Exception as e:
        msg = quote("Ошибка ротации: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


@app.get("/servers/{server_id}/logs", response_class=HTMLResponse)
async def server_logs(request: Request, server_id: str, source: str = "agent"):
    """Страница логов сервера (SSH tail/journalctl)."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    cmd = PROTOCOL_LOG_CMDS.get(source, PROTOCOL_LOG_CMDS["agent"])
    logs = ""
    error = None
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(cmd)
        logs = result.stdout or result.stderr or "(пусто)"
    except Exception as e:
        error = str(e)
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_logs.html",
        context={
            "server": server,
            "source": source,
            "logs": logs,
            "error": error,
            "sources": list(PROTOCOL_LOG_CMDS.keys()),
        },
    )


@app.post("/api/v1/agent/batch")
async def agent_batch(request: Request, authorization: str = Header(None)):
    """Приём пакета метрик от агента: JSON-массив или NDJSON."""
    import hashlib as _hashlib
    import json as _json
    from datetime import datetime as _dt

    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing token")
    token = authorization[7:]

    server = await db.fetchone(
        """SELECT s.id FROM servers s
           JOIN agent_tokens t ON t.server_id = s.id
           WHERE t.token_hash = ? AND (t.expires_at IS NULL OR t.expires_at > datetime('now'))""",
        _hashlib.sha256(token.encode()).hexdigest(),
    )
    if not server:
        raise HTTPException(status_code=401, detail="Invalid token")

    body = (await request.body()).decode("utf-8", errors="replace").strip()
    if not body:
        return {"status": "ok", "inserted": 0}

    items = []
    try:
        parsed = _json.loads(body)
        if isinstance(parsed, list):
            items = parsed
        elif isinstance(parsed, dict):
            items = [parsed]
    except Exception:
        # buffer.json = конкатенированные pretty JSON объекты
        decoder = _json.JSONDecoder()
        idx, n = 0, len(body)
        while idx < n:
            while idx < n and body[idx] in " \t\r\n":
                idx += 1
            if idx >= n:
                break
            try:
                obj, end_idx = decoder.raw_decode(body, idx)
                if isinstance(obj, dict):
                    items.append(obj)
                elif isinstance(obj, list):
                    items.extend(obj)
                idx = end_idx
            except ValueError:
                idx += 1

    inserted = 0
    for m in items:
        if not isinstance(m, dict):
            continue
        try:
            await db.execute(
                """INSERT OR IGNORE INTO metrics
                   (server_id, timestamp,
                    cpu_percent, memory_percent,
                    network_rx_mbps, network_tx_mbps,
                    connections_wdtt, connections_aivpn, connections_awg,
                    status_wdtt, status_aivpn, status_awg,
                    latency_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                server["id"],
                m.get("timestamp") or _dt.now().strftime("%Y-%m-%d %H:%M:%S"),
                float(m.get("cpu_percent", 0) or 0),
                float(m.get("memory_percent", 0) or 0),
                float(m.get("network_rx_mbps", 0) or 0),
                float(m.get("network_tx_mbps", 0) or 0),
                int(m.get("connections_wdtt", 0) or 0),
                int(m.get("connections_aivpn", 0) or 0),
                int(m.get("connections_awg", 0) or 0),
                str(m.get("status_wdtt") or "unknown"),
                str(m.get("status_aivpn") or "unknown"),
                str(m.get("status_awg") or "unknown"),
                float(m.get("latency_ms", 0) or 0),
            )
            inserted += 1
        except Exception as e:
            continue

    await db.commit()
    return {"status": "ok", "inserted": inserted, "parsed": len(items)}
    inserted = 0
    for m in items:
        if not isinstance(m, dict):
            continue
        try:
            await db.execute(
                """INSERT OR IGNORE INTO metrics
                   (server_id, timestamp, cpu_percent, memory_percent,
                    network_rx_mbps, network_tx_mbps, latency_ms)
                   VALUES (?, ?, ?, ?, ?, ?, ?)""",
                server["id"],
                m.get("timestamp") or _dt.now().strftime("%Y-%m-%d %H:%M:%S"),
                float(m.get("cpu_percent", 0) or 0),
                float(m.get("memory_percent", 0) or 0),
                float(m.get("network_rx_mbps", 0) or 0),
                float(m.get("network_tx_mbps", 0) or 0),
                float(m.get("latency_ms", 0) or 0),
            )
            inserted += 1
        except Exception:
            continue

    await db.commit()
    return {"status": "ok", "inserted": inserted}


# === Timezone management ===

PANEL_TIMEZONE = "Europe/Moscow"


@app.post("/servers/{server_id}/sync-time")
async def sync_time_form(server_id: str):
    """Выставить часовой пояс панели на сервере."""
    from urllib.parse import quote
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            result = await ssh.run(f"timedatectl set-timezone {PANEL_TIMEZONE} && date")
        if result.exit_code == 0:
            msg = "time_synced"
        else:
            msg = quote("Ошибка времени: " + (result.stderr or result.stdout)[:200])
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    return RedirectResponse(f"/servers/{server_id}?msg={msg}", status_code=303)


# === Protocol configs viewer (read-only) ===

CONFIG_DIRS = {
    "wdtt": "/etc/wdtt",
    "aivpn": "/etc/aivpn",
    "awg": "/etc/amnezia/amneziawg /etc/wireguard /etc/amnezia-wg",
}


@app.get("/servers/{server_id}/configs", response_class=HTMLResponse)
async def server_configs(request: Request, server_id: str, source: str = "wdtt"):
    """Просмотр конфигов протокола на сервере (read-only)."""
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    
    dirs = CONFIG_DIRS.get(source, "/etc/wdtt").split()
    listing = ""
    files = {}
    error = None
    
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            for d in dirs:
                ls = await ssh.run(f"ls -la {d} 2>/dev/null")
                if ls.stdout.strip():
                    listing += f"=== {d} ===\n{ls.stdout}\n"
                    names = await ssh.run(
                        f"find {d} -maxdepth 1 -type f -size -64k 2>/dev/null"
                    )
                    for path in (names.stdout or "").split():
                        cat = await ssh.run(f"cat '{path}' 2>/dev/null")
                        files[path] = cat.stdout or "(пусто или бинарный)"
            if not listing:
                listing = "(каталоги конфигов не найдены)"
    except Exception as e:
        error = str(e)
    
    # Парсим текущие значения для формы редактирования
    import re as _re
    awg_params = {}
    raw_content = ""
    edit_path = ""
    
    if not error:
        try:
            async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
                conf_path = await _find_config(ssh, source)
                if conf_path:
                    edit_path = conf_path
                    cat = await ssh.run(f"cat {conf_path}")
                    raw_content = cat.stdout or ""
                    if source == "awg":
                        for key in AWG_PARAMS:
                            m = _re.search(rf"^{key}\s*=\s*(\S+)", raw_content, _re.M)
                            awg_params[key] = m.group(1) if m else ""
        except Exception:
            pass
    
    return templates.TemplateResponse(
        request=request,
        name="pages/server_configs.html",
        context={
            "server": server,
            "source": source,
            "sources": ["wdtt", "aivpn", "awg"],
            "listing": listing,
            "files": files,
            "error": error,
            "msg": request.query_params.get("msg"),
            "awg_params": awg_params,
            "raw_content": raw_content,
            "edit_path": edit_path,
            "awg_param_list": AWG_PARAMS,
            "awg_param_types": AWG_PARAM_TYPES,
            "awg_param_groups": AWG_PARAM_GROUPS,
        },
    )


# === Config editing with backup + rollback ===

import base64 as _b64
import re as _re

AWG_PARAMS = [
    "Jc", "Jmin", "Jmax",
    "S1", "S2", "S3", "S4",
    "I1", "I2", "I3", "I4", "I5",
    "H1", "H2", "H3", "H4",
    "HeaderProtectionKey",
    "ContentPaddingAddition",
    "RandomTrailers", "DisableCookies",
    "RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts",
    "MTU", "ListenPort",
]

AWG_PARAM_TYPES = {
    "Jc": "uint16", "Jmin": "uint16", "Jmax": "uint16",
    "S1": "uint16", "S2": "uint16", "S3": "uint16", "S4": "uint16",
    "I1": "cps", "I2": "cps", "I3": "cps", "I4": "cps", "I5": "cps",
    "H1": "range", "H2": "range", "H3": "range", "H4": "range",
    "HeaderProtectionKey": "base64",
    "ContentPaddingAddition": "range",
    "RandomTrailers": "toggle", "DisableCookies": "toggle",
    "RekeyAfterTime": "range", "RekeyTimeout": "range", "RejectAfterTime": "range",
    "KeepaliveTimeout": "range", "MaxHandshakeAttempts": "range",
    "MTU": "uint16", "ListenPort": "uint16",
}

AWG_PARAM_GROUPS = [
    ("Анти-DPI базовый", ["Jc", "Jmin", "Jmax", "S1", "S2", "S3", "S4"]),
    ("Маскировка handshake", ["I1", "I2", "I3", "I4", "I5"]),
    ("Идентификаторы сообщений", ["H1", "H2", "H3", "H4"]),
    ("Защита заголовков", ["HeaderProtectionKey"]),
    ("Поведение трафика", ["ContentPaddingAddition", "RandomTrailers", "DisableCookies"]),
    ("Таймеры", ["RekeyAfterTime", "RekeyTimeout", "RejectAfterTime", "KeepaliveTimeout", "MaxHandshakeAttempts"]),
    ("Интерфейс", ["MTU", "ListenPort"]),
]

CONFIG_PATHS = {
    "wdtt": ["/etc/wdtt/server.json", "/etc/wdtt/passwords.json"],
    "aivpn": ["/etc/aivpn/server.json", "/etc/aivpn/clients.json"],
    "awg": ["/etc/amnezia/amneziawg/awg0.conf", "/etc/wireguard/awg0.conf", "/etc/amnezia-wg/awg0.conf"],
}

SERVICE_CHECK = {
    "wdtt": "systemctl is-active wdtt",
    "aivpn": "systemctl is-active aivpn-server",
    "awg": "awg show awg0",
}


async def _find_config(ssh, source: str):
    """Найти первый существующий конфиг протокола."""
    for path in CONFIG_PATHS.get(source, []):
        r = await ssh.run(f"test -f {path} && echo yes")
        if r.stdout.strip() == "yes":
            return path
    return None


@app.post("/servers/{server_id}/config/apply")
async def apply_config(request: Request, server_id: str):
    """Применить правку конфига: бекап → правка → рестарт → проверка → откат."""
    from urllib.parse import quote
    from datetime import datetime as _dt
    
    form = await request.form()
    source = form.get("source", "awg")
    
    server = await db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    if not server:
        return RedirectResponse("/servers?error=Server+not+found", status_code=303)
    
    try:
        async with SSHTransport(server["ip"], key_path=manager.ssh_key_path) as ssh:
            conf_path = await _find_config(ssh, source)
            if not conf_path:
                return RedirectResponse(
                    f"/servers/{server_id}/configs?source={source}&msg=" + quote("Конфиг не найден"),
                    status_code=303,
                )
            
            # 1. Текущее содержимое
            cur = await ssh.run(f"cat {conf_path}")
            old_conf = cur.stdout
            
            # 2. Формируем новый конфиг
            if source == "awg":
                new_conf = old_conf
                for key in AWG_PARAMS:
                    val = form.get(key)
                    if val and val.strip():
                        param_type = AWG_PARAM_TYPES.get(key, "uint16")
                        valid = True
                        
                        if param_type == "uint16":
                            try:
                                n = int(val)
                                if not (0 <= n <= 65535):
                                    valid = False
                            except ValueError:
                                valid = False
                            if not valid:
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: число 0-65535"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "range":
                            if not _re.match(r"^\d+(-\d+)?$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: формат a или a-b"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "cps":
                            # I1-I5: формат "3:40" или "2:30,5:40"
                            if not _re.match(r"^\d+:\d+(,\d+:\d+)*$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: формат count:size или count:size,count:size"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "base64":
                            # HeaderProtectionKey: 44 символа base64
                            if not _re.match(r"^[A-Za-z0-9+/]{43}=$", val):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: base64 44 символа"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
                        
                        elif param_type == "toggle":
                            if val not in ("on", "off"):
                                return RedirectResponse(
                                    f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"{key}: on или off"),
                                    status_code=303,
                                )
                            new_conf = _re.sub(rf"^{key}\s*=\s*\S+", f"{key} = {val}", new_conf, flags=_re.M)
            else:
                new_conf = form.get("raw_config", "")
                try:
                    import json as _j
                    _j.loads(new_conf)
                except Exception as e:
                    return RedirectResponse(
                        f"/servers/{server_id}/configs?source={source}&msg=" + quote(f"Невалидный JSON: {str(e)[:100]}"),
                        status_code=303,
                    )
            
            if new_conf == old_conf:
                return RedirectResponse(
                    f"/servers/{server_id}/configs?source={source}&msg=Без+изменений",
                    status_code=303,
                )
            
            # 3. Бекап
            ts = _dt.now().strftime("%Y%m%d-%H%M%S")
            backup_path = f"{conf_path}.hydra-backup-{ts}"
            await ssh.run(f"cp -p {conf_path} {backup_path}")
            
            # 4. Запись нового конфига (base64 для надёжности)
            b64 = _b64.b64encode(new_conf.encode()).decode()
            await ssh.run(f"echo '{b64}' | base64 -d > {conf_path}")
            
            # 5. Перезапуск сервиса
            restart_cmd = PROTOCOL_RESTART_CMDS.get(source, "true")
            await ssh.run(restart_cmd)
            
            # 6. Проверка
            import asyncio as _aio
            await _aio.sleep(2)
            check = await ssh.run(SERVICE_CHECK.get(source, "true"))
            
            if check.exit_code != 0 or "inactive" in check.stdout:
                # Откат
                await ssh.run(f"cp -p {backup_path} {conf_path}")
                await ssh.run(restart_cmd)
                msg = quote(f"Сервис не поднялся — выполнен откат из {backup_path}")
            else:
                msg = f"config_applied_{source}"
    except Exception as e:
        msg = quote("SSH ошибка: " + str(e))
    
    return RedirectResponse(f"/servers/{server_id}/configs?source={source}&msg={msg}", status_code=303)


# === Database console (read-only) ===

@app.get("/database", response_class=HTMLResponse)
async def database_console(request: Request, result: Optional[str] = None, error: Optional[str] = None):
    """Read-only SQL console."""
    import base64 as _b64mod
    import json as _jsonmod
    tables = await db.fetchall(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
    )
    decoded_result = None
    if result:
        try:
            decoded_result = _jsonmod.loads(_b64mod.b64decode(result).decode())
        except Exception:
            decoded_result = None
    return templates.TemplateResponse(
        request=request,
        name="pages/database.html",
        context={"tables": tables, "result": decoded_result, "error": error},
    )


@app.post("/database/query")
async def database_query(request: Request, query: str = Form(...)):
    """Execute read-only SQL query."""
    import base64 as _b64mod
    import json as _jsonmod
    from urllib.parse import quote
    q = query.strip().rstrip(";")
    ql = q.lower()
    if not (ql.startswith("select") or ql.startswith("pragma")):
        return RedirectResponse("/database?error=" + quote("Разрешены только SELECT и PRAGMA"), status_code=303)
    # Word-boundary check: не блокирует колонки вида updated_at
    if _re.search(r"\b(insert|update|delete|drop|alter|create|attach|detach|vacuum|replace)\b", ql):
        return RedirectResponse("/database?error=" + quote("Запрещённая операция в запросе"), status_code=303)
    try:
        rows = await db.fetchall(q)
        b64 = _b64mod.b64encode(_jsonmod.dumps(rows, default=str).encode()).decode()
        return RedirectResponse("/database?result=" + quote(b64), status_code=303)
    except Exception as e:
        return RedirectResponse("/database?error=" + quote(str(e)), status_code=303)


@app.get("/database/backup")
async def database_backup():
    """Download panel.db copy."""
    import os as _os
    from datetime import datetime as _dt
    from fastapi.responses import FileResponse
    db_path = _os.environ.get("HYDRA_DB_PATH", "./panel.db")
    if not _os.path.exists(db_path):
        raise HTTPException(status_code=404, detail="Database not found")
    return FileResponse(
        db_path,
        media_type="application/octet-stream",
        filename=f"hydra-backup-{_dt.now().strftime('%Y%m%d-%H%M%S')}.db",
    )
