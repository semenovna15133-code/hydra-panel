"""AIVPN v1.1.0 plugin for Hydra Panel."""
import re
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport


class AIVPNPlugin(ProtocolPlugin):
    """AIVPN v1.1.0 implementation."""
    
    @property
    def name(self) -> str:
        return "aivpn"
    
    @property
    def version(self) -> str:
        return "1.1.0"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        """Install AIVPN v1.1.0 on remote server."""
        raise NotImplementedError("AIVPN installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new AIVPN client."""
        raise NotImplementedError("AIVPN add_client not yet implemented")
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all AIVPN clients."""
        raise NotImplementedError("AIVPN list_clients not yet implemented")
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove an AIVPN client."""
        raise NotImplementedError("AIVPN remove_client not yet implemented")
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get AIVPN status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Проверить статус сервиса
            status_result = await ssh.run("systemctl is-active aivpn-server 2>/dev/null || echo 'inactive'")
            service_status = status_result.stdout.strip()
            
            # Получить список клиентов через CLI
            cmd = '''
/usr/local/bin/aivpn-server --list-clients \\
  --key-file /etc/aivpn/server.key \\
  --clients-db /etc/aivpn/clients.json 2>/dev/null || \\
  echo "ERROR: command failed"
'''
            
            clients_result = await ssh.run(cmd)
            
            # Парсить табличный вывод
            # Формат обычно: ID | NAME | CREATED | STATUS | ...
            # Нужно подсчитать строки с клиентами (исключая заголовок и разделители)
            
            if "ERROR:" in clients_result.stdout:
                client_count = 0
                error_msg = clients_result.stdout
            else:
                lines = clients_result.stdout.strip().split('\n')
                # Фильтруем пустые строки и разделители (обычно "---" или "===")
                client_lines = [
                    line for line in lines 
                    if line.strip() 
                    and not line.startswith('-') 
                    and not line.startswith('=')
                    and 'ID' not in line  # заголовок таблицы
                    and 'NAME' not in line
                ]
                client_count = len(client_lines)
                error_msg = None
            
            return {
                "service_status": service_status,
                "active_clients": client_count,
                "error": error_msg,
            }
