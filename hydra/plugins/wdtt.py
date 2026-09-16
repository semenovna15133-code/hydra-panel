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
        # TODO: Implement in later session
        raise NotImplementedError("WDTT installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new WDTT client."""
        # TODO: Implement in later session
        raise NotImplementedError("WDTT add_client not yet implemented")
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all WDTT clients."""
        # TODO: Implement in later session
        raise NotImplementedError("WDTT list_clients not yet implemented")
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove a WDTT client."""
        # TODO: Implement in later session
        raise NotImplementedError("WDTT remove_client not yet implemented")
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get WDTT status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Проверить статус сервиса
            status_result = await ssh.run("systemctl is-active wdtt 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            # Получить количество клиентов через admin API
            # Читаем пароль из файла
            cmd = '''
set -e
if [ ! -f /root/wdtt-main.pass ]; then
    echo '{"error": "password file not found"}'
    exit 0
fi
MAIN_PW=$(cat /root/wdtt-main.pass)
echo '{"main_password":"'"$MAIN_PW"'","args":["list"]}' | \\
  /usr/local/bin/wdtt-server admin --config-dir /etc/wdtt --request-stdin 2>/dev/null || \\
  echo '{"error": "admin command failed"}'
'''
            
            clients_result = await ssh.run(cmd)
            
            try:
                clients_data = json.loads(clients_result.stdout)
                if "error" in clients_data:
                    client_count = 0
                    error_msg = clients_data["error"]
                else:
                    # Фильтруем только активных клиентов (is_deactivated == null)
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
