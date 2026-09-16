"""WDTT Plus v17 plugin for Hydra Panel."""
import json
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport


class WDTTPlugin(ProtocolPlugin):
    """WDTT Plus v17 implementation."""
    
    @property
    def name(self) -> str:
        return "wdtt"
    
    @property
    def version(self) -> str:
        return "17"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        """Install WDTT v17 on remote server."""
        raise NotImplementedError("WDTT installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new WDTT client.
        
        Args:
            server_ip: IP сервера
            ssh_key_path: путь к SSH ключу
            client_id: игнорируется (password генерируется на сервере)
            **kwargs:
                label: метка клиента (default: "hydra-client")
                days: срок действия в днях (default: 0 = бессрочно)
        
        Returns:
            ClientConfig с password в client_id (16 символов)
        """
        label = kwargs.get("label", "hydra-client")
        days = int(kwargs.get("days", 0))
        
        # Валидация label (только alphanumeric + hyphen + underscore)
        if not all(c.isalnum() or c in "-_" for c in label):
            raise ValueError(f"Invalid label: {label}. Use alphanumeric, hyphen, underscore only.")
        
        cmd = f'''
set -euo pipefail
if [ ! -f /root/wdtt-main.pass ]; then
    echo '{{"error":"password file not found"}}'
    exit 1
fi
MAIN_PW=$(cat /root/wdtt-main.pass)
JSON=$(printf '{{"main_password":"%s","args":["create","--days","%d","--label","%s"]}}' "$MAIN_PW" {days} "{label}")
echo "$JSON" | /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse WDTT response: {e}\nOutput: {result.stdout}")
            
            if "error" in data:
                raise RuntimeError(f"WDTT error: {data['error']}")
            
            password_obj = data.get("password")
            if not password_obj or "password" not in password_obj:
                raise RuntimeError(f"Unexpected WDTT response structure: {data}")
            
            password = password_obj["password"]
            
            # client_id = password (16 симв)
            # connection_string в формате wdtt://host:port?password=...
            connection_string = f"wdtt://{server_ip}:56000?password={password}"
            
            return ClientConfig(
                protocol="wdtt",
                client_id=password,
                config_data={
                    "label": label,
                    "password": password,
                    "days": days,
                    "is_deactivated": password_obj.get("is_deactivated"),
                    "expires_at": password_obj.get("expires_at"),
                },
                connection_string=connection_string,
            )
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all WDTT clients."""
        cmd = '''
set -euo pipefail
if [ ! -f /root/wdtt-main.pass ]; then
    echo '{"error":"password file not found"}'
    exit 1
fi
MAIN_PW=$(cat /root/wdtt-main.pass)
JSON=$(printf '{"main_password":"%s","args":["list"]}' "$MAIN_PW")
echo "$JSON" | /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse WDTT list response: {e}\nOutput: {result.stdout}")
            
            if "error" in data:
                raise RuntimeError(f"WDTT error: {data['error']}")
            
            passwords = data.get("passwords", [])
            
            # Нормализация: возвращаем список с одинаковой структурой
            clients = []
            for p in passwords:
                clients.append({
                    "protocol": "wdtt",
                    "client_id": p.get("password"),  # password = client_id
                    "label": p.get("label", ""),
                    "is_deactivated": p.get("is_deactivated"),
                    "expires_at": p.get("expires_at"),
                    "active": p.get("is_deactivated") is None,
                })
            
            return clients
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove a WDTT client.
        
        Args:
            client_id: password (16 символов), не label!
        """
        if len(client_id) != 16:
            raise ValueError(f"WDTT client_id must be 16-char password, got: {client_id!r}")
        
        cmd = f'''
set -euo pipefail
if [ ! -f /root/wdtt-main.pass ]; then
    echo '{{"error":"password file not found"}}'
    exit 1
fi
MAIN_PW=$(cat /root/wdtt-main.pass)
JSON=$(printf '{{"main_password":"%s","args":["delete","--password","%s"]}}' "$MAIN_PW" "{client_id}")
echo "$JSON" | /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            
            try:
                data = json.loads(result.stdout)
            except json.JSONDecodeError as e:
                raise RuntimeError(f"Failed to parse WDTT delete response: {e}\nOutput: {result.stdout}")
            
            if "error" in data:
                return PluginResult(
                    success=False,
                    message=f"WDTT error: {data['error']}",
                    data=data,
                )
            
            return PluginResult(
                success=True,
                message=f"Client {client_id} removed",
                data=data,
            )
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get WDTT status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            status_result = await ssh.run("systemctl is-active wdtt 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            cmd = '''
set -e
if [ ! -f /root/wdtt-main.pass ]; then
    echo '{"error": "password file not found"}'
    exit 0
fi
MAIN_PW=$(cat /root/wdtt-main.pass)
JSON=$(printf '{"main_password":"%s","args":["list"]}' "$MAIN_PW")
echo "$JSON" | /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null || \
  echo '{"error": "admin command failed"}'
'''
            
            clients_result = await ssh.run(cmd)
            
            try:
                clients_data = json.loads(clients_result.stdout)
                if "error" in clients_data:
                    client_count = 0
                    error_msg = clients_data["error"]
                else:
                    passwords = clients_data.get("passwords", [])
                    client_count = sum(1 for p in passwords if p.get("is_deactivated") is None)
                    error_msg = None
            except (json.JSONDecodeError, KeyError) as e:
                client_count = 0
                error_msg = f"Failed to parse response: {e}"
            
            return {
                "service_status": service_status,
                "active_clients": client_count,
                "error": error_msg,
            }
