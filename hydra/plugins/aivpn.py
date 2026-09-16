"""AIVPN v1.1.0 plugin for Hydra Panel."""
import re
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport

# AIVPN CLI constants
CLI = "/usr/local/bin/aivpn-server"
KEY_FILE = "/etc/aivpn/server.key"
CLIENTS_DB = "/etc/aivpn/clients.json"

# Regex для валидации ID (16 hex)
ID_REGEX = re.compile(r'^[0-9a-fA-F]{16}$')


class AIVPNPlugin(ProtocolPlugin):
    """AIVPN v1.1.0 implementation."""
    
    @property
    def name(self) -> str:
        return "aivpn"
    
    @property
    def version(self) -> str:
        return "1.1.0"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        raise NotImplementedError("AIVPN installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,  # ignored, used as label
        **kwargs
    ) -> ClientConfig:
        """Add a new AIVPN client.
        
        Args:
            client_id: used as NAME (label) for the client
            **kwargs:
                role: admin|user (default: user)
        """
        label = client_id or "hydra-client"
        role = kwargs.get("role", "user")
        
        if not all(c.isalnum() or c in "-_" for c in label):
            raise ValueError(f"Invalid label: {label}")
        
        cmd = f'''
set -euo pipefail
{CLI} --add-client "{label}" \\
  --server-ip {server_ip}:443 \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>&1
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            if not result.success or "ERROR" in output.upper():
                raise RuntimeError(f"AIVPN add_client failed: {output}")
            
            # Парсинг вывода: ищем ID (16 hex) и aivpn:// URL
            # Формат обычно:
            # Client added: NAME
            # ID: abcd1234567890ab
            # Key: aivpn://...
            id_match = re.search(r'ID:\s*([0-9a-fA-F]{16})', output)
            url_match = re.search(r'(aivpn://[^\s]+)', output)
            
            if not id_match:
                raise RuntimeError(f"Cannot find ID in AIVPN output: {output}")
            
            aivpn_id = id_match.group(1)
            aivpn_url = url_match.group(1) if url_match else None
            
            return ClientConfig(
                protocol="aivpn",
                client_id=aivpn_id,
                config_data={
                    "label": label,
                    "role": role,
                    "aivpn_url": aivpn_url,
                },
                connection_string=aivpn_url,
            )
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all AIVPN clients."""
        cmd = f'''
{CLI} --list-clients \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>/dev/null
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            # Парсинг табличного вывода
            # Формат зависит от версии, обычно:
            # ID                 NAME        STATUS    ...
            # ------------------ ----------- ---------
            # abcd1234567890ab   client1     enabled   ...
            
            clients = []
            for line in output.strip().split('\n'):
                line = line.strip()
                if not line:
                    continue
                if line.startswith('-') or line.startswith('='):
                    continue
                # Пропускаем заголовок таблицы
                if 'ID' in line and 'NAME' in line:
                    continue
                
                # Парсим строку клиента
                parts = line.split()
                if len(parts) < 2:
                    continue
                
                potential_id = parts[0]
                if ID_REGEX.match(potential_id):
                    clients.append({
                        "protocol": "aivpn",
                        "client_id": potential_id,
                        "label": parts[1] if len(parts) > 1 else "",
                        "status": parts[2] if len(parts) > 2 else "",
                        "active": True,  # TODO: parse status
                    })
            
            return clients
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove an AIVPN client.
        
        Args:
            client_id: 16-hex ID (NOT name!)
        """
        if not ID_REGEX.match(client_id):
            raise ValueError(
                f"AIVPN client_id must be 16-hex ID, got: {client_id!r}. "
                f"Use list_clients() to find ID by name."
            )
        
        cmd = f'''
set -euo pipefail
{CLI} --remove-client "{client_id}" \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>&1
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            
            if not result.success:
                return PluginResult(
                    success=False,
                    message=f"Remove failed: {result.stdout or result.stderr}",
                )
            
            return PluginResult(
                success=True,
                message=f"Client {client_id} removed",
            )
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get AIVPN status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            status_result = await ssh.run("systemctl is-active aivpn-server 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            cmd = f'''
{CLI} --list-clients \\
  --key-file {KEY_FILE} \\
  --clients-db {CLIENTS_DB} 2>/dev/null || \\
  echo "ERROR: command failed"
'''
            
            clients_result = await ssh.run(cmd)
            
            if "ERROR:" in clients_result.stdout:
                client_count = 0
                error_msg = clients_result.stdout
            else:
                lines = clients_result.stdout.strip().split('\n')
                client_lines = [
                    line for line in lines
                    if line.strip()
                    and not line.startswith('-')
                    and not line.startswith('=')
                    and 'ID' not in line
                    and 'NAME' not in line
                ]
                client_count = len(client_lines)
                error_msg = None
            
            return {
                "service_status": service_status,
                "active_clients": client_count,
                "error": error_msg,
            }
