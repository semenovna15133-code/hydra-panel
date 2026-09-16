"""Base protocol plugin interface for Hydra Panel."""
from abc import ABC, abstractmethod
from typing import Optional, Dict, Any
from pydantic import BaseModel


class ClientConfig(BaseModel):
    """Configuration returned when adding a client."""
    protocol: str
    client_id: str
    config_data: Dict[str, Any]
    connection_string: Optional[str] = None


class PluginResult(BaseModel):
    """Result of a plugin operation."""
    success: bool
    message: str = ""
    data: Optional[Dict[str, Any]] = None


class ProtocolPlugin(ABC):
    """Abstract base class for protocol plugins."""
    
    @property
    @abstractmethod
    def name(self) -> str:
        """Protocol name (e.g., 'wdtt', 'aivpn', 'awg')."""
        pass
    
    @property
    @abstractmethod
    def version(self) -> str:
        """Protocol version (e.g., '17', '1.1.0', '3.1')."""
        pass
    
    @abstractmethod
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        """Install protocol on remote server."""
        pass
    
    @abstractmethod
    async def add_client(
        self, 
        server_ip: str, 
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new client to the protocol."""
        pass
    
    @abstractmethod
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all clients for this protocol."""
        pass
    
    @abstractmethod
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove a client from the protocol."""
        pass
    
    @abstractmethod
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get protocol status and metrics."""
        pass
