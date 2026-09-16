"""SSH transport layer for Hydra Panel using asyncssh."""
from pathlib import Path
from typing import Optional
import asyncssh


class SSHResult:
    """Result of SSH command execution."""
    
    def __init__(self, stdout: str, stderr: str, exit_code: int):
        self.stdout = stdout
        self.stderr = stderr
        self.exit_code = exit_code
    
    @property
    def success(self) -> bool:
        return self.exit_code == 0
    
    def __str__(self) -> str:
        return f"SSHResult(exit={self.exit_code}, stdout={self.stdout!r}, stderr={self.stderr!r})"


class SSHTransport:
    """Async SSH transport for remote server management."""
    
    def __init__(
        self,
        host: str,
        username: str = "root",
        key_path: Optional[str] = None,
        connect_timeout: int = 30,
    ):
        self.host = host
        self.username = username
        self.key_path = key_path
        self.connect_timeout = connect_timeout
        self._conn: Optional[asyncssh.SSHClientConnection] = None
    
    async def __aenter__(self):
        await self.connect()
        return self
    
    async def __aexit__(self, exc_type, exc_val, exc_tb):
        await self.close()
    
    async def connect(self) -> None:
        """Establish SSH connection."""
        if self._conn is not None:
            return
        
        connect_args = {
            "host": self.host,
            "username": self.username,
            "connect_timeout": self.connect_timeout,
            "known_hosts": None,
        }
        
        if self.key_path:
            key_path = Path(self.key_path).expanduser()
            if not key_path.exists():
                raise FileNotFoundError(f"SSH key not found: {key_path}")
            connect_args["client_keys"] = [str(key_path)]
        
        try:
            self._conn = await asyncssh.connect(**connect_args)
        except asyncssh.Error as e:
            raise ConnectionError(f"SSH connection failed: {e}") from e
    
    async def close(self) -> None:
        """Close SSH connection."""
        if self._conn is not None:
            self._conn.close()
            await self._conn.wait_closed()
            self._conn = None
    
    async def run(self, command: str, check: bool = False) -> SSHResult:
        """Execute command on remote server."""
        if self._conn is None:
            await self.connect()
        
        try:
            result = await self._conn.run(command, check=False)
            ssh_result = SSHResult(
                stdout=result.stdout,
                stderr=result.stderr,
                exit_code=result.exit_status or 0,
            )
        except asyncssh.Error as e:
            ssh_result = SSHResult(stdout="", stderr=str(e), exit_code=-1)
        
        if check and not ssh_result.success:
            raise RuntimeError(
                f"Command failed on {self.host}: {command}\n"
                f"Exit code: {ssh_result.exit_code}\n"
                f"Stderr: {ssh_result.stderr}"
            )
        
        return ssh_result
    
    async def upload(self, local_path: str, remote_path: str) -> None:
        """Upload file to remote server."""
        if self._conn is None:
            await self.connect()
        
        local = Path(local_path).expanduser()
        if not local.exists():
            raise FileNotFoundError(f"Local file not found: {local}")
        
        async with self._conn.start_sftp_client() as sftp:
            await sftp.put(str(local), remote_path)
    
    async def download(self, remote_path: str, local_path: str) -> None:
        """Download file from remote server."""
        if self._conn is None:
            await self.connect()
        
        local = Path(local_path).expanduser()
        local.parent.mkdir(parents=True, exist_ok=True)
        
        async with self._conn.start_sftp_client() as sftp:
            await sftp.get(remote_path, str(local))
