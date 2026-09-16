"""AmneziaWG v3.1 plugin for Hydra Panel."""
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport


class AWGPlugin(ProtocolPlugin):
    """AmneziaWG v3.1 implementation."""
    
    @property
    def name(self) -> str:
        return "awg"
    
    @property
    def version(self) -> str:
        return "3.1"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        """Install AmneziaWG v3.1 on remote server."""
        raise NotImplementedError("AWG installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new AWG client."""
        raise NotImplementedError("AWG add_client not yet implemented")
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all AWG clients."""
        raise NotImplementedError("AWG list_clients not yet implemented")
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove an AWG client."""
        raise NotImplementedError("AWG remove_client not yet implemented")
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get AWG status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            # Проверить что kernel module загружен
            modprobe_result = await ssh.run("lsmod | grep amneziawg || echo 'not-loaded'")
            module_loaded = 'amneziawg' in modprobe_result.stdout
            
            if not module_loaded:
                return {
                    "service_status": "inactive",
                    "module_loaded": False,
                    "total_peers": 0,
                    "active_peers": 0,
                    "error": "amneziawg kernel module not loaded",
                }
            
            # Получить вывод awg show
            cmd = "awg show awg0 2>/dev/null || echo 'ERROR: awg show failed'"
            show_result = await ssh.run(cmd)
            
            if "ERROR:" in show_result.stdout:
                return {
                    "service_status": "inactive",
                    "module_loaded": True,
                    "total_peers": 0,
                    "active_peers": 0,
                    "error": show_result.stdout,
                }
            
            # Парсить вывод awg show
            # Формат:
            # interface: awg0
            #   public key: ...
            #   listening port: 51820
            #   jc: 8
            #   ...
            # peer: <public-key>
            #   endpoint: ...
            #   allowed ips: ...
            #   latest handshake: ...
            #   transfer: ...
            
            output = show_result.stdout
            
            # Подсчитать общее количество peers
            total_peers = output.count("peer:")
            
            # Подсчитать активных peers (с "latest handshake")
            active_peers = output.count("latest handshake:")
            
            return {
                "service_status": "active",
                "module_loaded": True,
                "total_peers": total_peers,
                "active_peers": active_peers,
                "error": None,
            }
