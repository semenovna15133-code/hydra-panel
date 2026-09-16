"""Server Manager — coordinates plugins and database."""
import hashlib
import secrets
from datetime import datetime, timedelta
from typing import Optional
from .db import Database
from ..plugins import WDTTPlugin, AIVPNPlugin, AWGPlugin
from ..ssh import SSHTransport


class ServerManager:
    """Coordinates protocol plugins and database operations."""
    
    def __init__(self, db: Database, ssh_key_path: str):
        self.db = db
        self.ssh_key_path = ssh_key_path
        self.plugins = {
            "wdtt": WDTTPlugin(),
            "aivpn": AIVPNPlugin(),
            "awg": AWGPlugin(),
        }
    
    async def register_server(
        self,
        server_id: str,
        ip: str,
        location: str = "",
        city: str = "",
        bandwidth_mbps: int = 1000,
        ssh_port: int = 22,
    ) -> dict:
        """Register new server in database."""
        await self.db.execute(
            """INSERT INTO servers (id, location, city, ip, ssh_port, bandwidth_mbps)
               VALUES (?, ?, ?, ?, ?, ?)""",
            server_id, location, city, ip, ssh_port, bandwidth_mbps
        )
        await self.db.commit()
        
        return await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
    
    async def install_all_protocols(self, server_id: str) -> dict:
        """Install all three protocols on server."""
        server = await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        ip = server['ip']
        results = {}
        
        # Install each protocol
        for protocol, plugin in self.plugins.items():
            try:
                result = await plugin.install(ip, self.ssh_key_path)
                results[protocol] = {
                    "success": result.success,
                    "message": result.message,
                }
                
                if result.success:
                    # Register protocol instance
                    port = {
                        "wdtt": 56000,
                        "aivpn": 443,
                        "awg": 51820,
                    }.get(protocol, 0)
                    
                    await self.db.execute(
                        """INSERT OR REPLACE INTO protocol_instances 
                           (server_id, protocol, port, max_connections)
                           VALUES (?, ?, ?, ?)""",
                        server_id, protocol, port, 50
                    )
            except Exception as e:
                results[protocol] = {
                    "success": False,
                    "message": str(e),
                }
        
        await self.db.commit()
        return results
    
    async def get_server_status(self, server_id: str) -> dict:
        """Get aggregated status for all protocols on server."""
        server = await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        ip = server['ip']
        status = {
            "server": server,
            "protocols": {},
        }
        
        for protocol, plugin in self.plugins.items():
            try:
                protocol_status = await plugin.get_status(ip, self.ssh_key_path)
                status["protocols"][protocol] = protocol_status
            except Exception as e:
                status["protocols"][protocol] = {
                    "error": str(e),
                }
        
        return status
    
    async def add_client_to_server(
        self,
        server_id: str,
        protocol: str,
        label: str,
        **kwargs
    ) -> dict:
        """Add client to specific protocol on server."""
        if protocol not in self.plugins:
            raise ValueError(f"Unknown protocol: {protocol}")
        
        server = await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        plugin = self.plugins[protocol]
        config = await plugin.add_client(server['ip'], self.ssh_key_path, label, **kwargs)
        
        return {
            "protocol": protocol,
            "client_id": config.client_id,
            "config_data": config.config_data,
            "connection_string": config.connection_string,
        }
    
    async def list_clients_on_server(self, server_id: str, protocol: str) -> list:
        """List all clients for specific protocol on server."""
        if protocol not in self.plugins:
            raise ValueError(f"Unknown protocol: {protocol}")
        
        server = await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        plugin = self.plugins[protocol]
        return await plugin.list_clients(server['ip'], self.ssh_key_path)
    
    async def remove_client_from_server(
        self,
        server_id: str,
        protocol: str,
        client_id: str
    ) -> dict:
        """Remove client from specific protocol on server."""
        if protocol not in self.plugins:
            raise ValueError(f"Unknown protocol: {protocol}")
        
        server = await self.db.fetchone("SELECT * FROM servers WHERE id = ?", server_id)
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        plugin = self.plugins[protocol]
        result = await plugin.remove_client(server['ip'], self.ssh_key_path, client_id)
        
        return {
            "success": result.success,
            "message": result.message,
        }
    
    async def generate_agent_token(self, server_id: str) -> str:
        """Generate new agent token for server."""
        token = secrets.token_urlsafe(32)
        token_hash = hashlib.sha256(token.encode()).hexdigest()
        expires_at = datetime.utcnow() + timedelta(days=90)
        
        await self.db.execute(
            """INSERT INTO agent_tokens (server_id, token_hash, expires_at)
               VALUES (?, ?, ?)""",
            server_id, token_hash, expires_at.isoformat()
        )
        await self.db.commit()
        
        return token
    
    async def verify_agent_token(self, server_id: str, token: str) -> bool:
        """Verify agent token (constant-time comparison)."""
        import hmac
        
        candidates = await self.db.fetchall(
            """SELECT token_hash FROM agent_tokens
               WHERE server_id = ? AND expires_at > datetime('now')""",
            server_id
        )
        
        if not candidates:
            return False
        
        candidate_hash = hashlib.sha256(token.encode()).hexdigest()
        return any(
            hmac.compare_digest(candidate_hash, row['token_hash'])
            for row in candidates
        )
    
    async def install_agent(
        self,
        server_id: str,
        panel_url: str,
        rotation_days: int = 90,
    ) -> dict:
        """Install monitoring agent on server."""
        from ..agent.install import install_agent as agent_install
        
        server = await self.db.fetchone(
            "SELECT * FROM servers WHERE id = ?",
            server_id,
        )
        if not server:
            raise ValueError(f"Server {server_id} not found")
        
        return await agent_install(
            server_ip=server['ip'],
            ssh_key_path=self.ssh_key_path,
            panel_url=panel_url,
            server_id=server_id,
            db=self.db,
            rotation_days=rotation_days,
        )
