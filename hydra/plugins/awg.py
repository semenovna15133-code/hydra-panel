"""AmneziaWG v3.1 plugin for Hydra Panel."""
import json
import re
from typing import Dict, Any
from ..core.protocol import ProtocolPlugin, ClientConfig, PluginResult
from ..ssh import SSHTransport

# Пути на сервере
AWG_CONF = "/etc/amnezia/amneziawg/awg0.conf"
KEYS_DIR = "/etc/amnezia/amneziawg"
CLIENTS_MAP = "/etc/amnezia/amneziawg/clients.json"

# Базовый диапазон для клиентов
IP_BASE = "10.0.1"
IP_START = 2
IP_END = 250


class AWGPlugin(ProtocolPlugin):
    """AmneziaWG v3.1 implementation.
    
    NOTE: Обфускационные параметры задаются на уровне интерфейса и общие для всех пиров.
    Клиент получает их с сервера автоматически.
    """
    
    @property
    def name(self) -> str:
        return "awg"
    
    @property
    def version(self) -> str:
        return "3.1"
    
    async def install(self, server_ip: str, ssh_key_path: str) -> PluginResult:
        raise NotImplementedError("AWG installation not yet implemented")
    
    async def add_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str,
        **kwargs
    ) -> ClientConfig:
        """Add a new AWG client.
        
        Args:
            client_id: used as label/comment for the peer
        """
        label = client_id or "hydra-client"
        
        if not all(c.isalnum() or c in "-_" for c in label):
            raise ValueError(f"Invalid label: {label}")
        
        cmd = f'''
set -euo pipefail

# Генерация ключей через awg
CLIENT_PRIV=$(awg genkey)
CLIENT_PUB=$(echo "$CLIENT_PRIV" | awg pubkey)

# Чтение серверного публичного ключа
SERVER_PUB=$(awg pubkey < {KEYS_DIR}/server_private.key)
if [ -z "$SERVER_PUB" ]; then
    echo "ERROR: Cannot read server public key"
    exit 1
fi

# Чтение порта
LISTEN_PORT=$(awg show awg0 | grep "listening port" | awk '{{print $3}}')

# Поиск свободного IP в диапазоне {IP_BASE}.0/24
USED_IPS=$(awg show awg0 | grep "allowed ips" | awk '{{print $3}}' | cut -d'/' -f1)
NEW_IP="{IP_BASE}.{IP_START}"
i={IP_START}
while echo "$USED_IPS" | grep -q "^{IP_BASE}.$i$"; do
    i=$((i + 1))
    if [ $i -gt {IP_END} ]; then
        echo "ERROR: No free IPs available"
        exit 1
    fi
    NEW_IP="{IP_BASE}.$i"
done

# Добавление peer в конфиг
cat >> {AWG_CONF} <<PEER_EOF

[Peer]
# {label}
PublicKey = $CLIENT_PUB
AllowedIPs = $NEW_IP/32
PEER_EOF

# Перезапуск интерфейса через setconf
awg setconf awg0 <(awg-quick strip awg0)

# Чтение параметров обфускации для клиентской конфигурации
CONFIG=$(awg-quick strip awg0)
JC=$(echo "$CONFIG" | grep "^Jc = " | awk '{{print $3}}')
JMIN=$(echo "$CONFIG" | grep "^Jmin = " | awk '{{print $3}}')
JMAX=$(echo "$CONFIG" | grep "^Jmax = " | awk '{{print $3}}')
S1=$(echo "$CONFIG" | grep "^S1 = " | awk '{{print $3}}')
S2=$(echo "$CONFIG" | grep "^S2 = " | awk '{{print $3}}')
S3=$(echo "$CONFIG" | grep "^S3 = " | awk '{{print $3}}')
S4=$(echo "$CONFIG" | grep "^S4 = " | awk '{{print $3}}')
HP_KEY=$(echo "$CONFIG" | grep "^HeaderProtectionKey = " | awk '{{print $3}}')

# Сохранение маппинга в clients.json
if [ ! -f {CLIENTS_MAP} ]; then
    echo '{{}}' > {CLIENTS_MAP}
fi
python3 -c "
import json
with open('{CLIENTS_MAP}') as f:
    clients = json.load(f)
clients['{label}'] = {{
    'public_key': '$CLIENT_PUB',
    'private_key': '$CLIENT_PRIV',
    'ip': '$NEW_IP',
    'server_pub': '$SERVER_PUB',
    'endpoint': '{server_ip}:' + '$LISTEN_PORT',
}}
with open('{CLIENTS_MAP}', 'w') as f:
    json.dump(clients, f, indent=2)
"

# Вывод результата для парсинга
cat <<OUTPUT_EOF
CLIENT_PRIV=$CLIENT_PRIV
CLIENT_PUB=$CLIENT_PUB
SERVER_PUB=$SERVER_PUB
NEW_IP=$NEW_IP
LISTEN_PORT=$LISTEN_PORT
JC=$JC
JMIN=$JMIN
JMAX=$JMAX
S1=$S1
S2=$S2
S3=$S3
S4=$S4
HP_KEY=$HP_KEY
OUTPUT_EOF
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout
            
            if not result.success or "ERROR" in output:
                raise RuntimeError(f"AWG add_client failed: {output}\nStderr: {result.stderr}")
            
            # Парсинг вывода
            def parse(key):
                m = re.search(rf'{key}=([^\s]+)', output)
                return m.group(1) if m else ""
            
            client_priv = parse("CLIENT_PRIV")
            client_pub = parse("CLIENT_PUB")
            server_pub = parse("SERVER_PUB")
            new_ip = parse("NEW_IP")
            listen_port = parse("LISTEN_PORT")
            jc = parse("JC")
            jmin = parse("JMIN")
            jmax = parse("JMAX")
            s1 = parse("S1")
            s2 = parse("S2")
            s3 = parse("S3")
            s4 = parse("S4")
            hp_key = parse("HP_KEY")
            
            if not all([client_priv, client_pub, server_pub, new_ip]):
                raise RuntimeError(f"Cannot parse AWG output: {output}")
            
            endpoint = f"{server_ip}:{listen_port}"
            
            # Формирование клиентской конфигурации
            # Клиент должен использовать ТЕ ЖЕ параметры обфускации что и сервер
            config_lines = [
                "[Interface]",
                f"PrivateKey = {client_priv}",
                f"Address = {new_ip}/32",
                "DNS = 1.1.1.1",
                "",
                f"Jc = {jc}",
                f"Jmin = {jmin}",
                f"Jmax = {jmax}",
            ]
            
            # Добавляем параметры обфускации если они есть
            if s1 and s1 != "0":
                config_lines.append(f"S1 = {s1}")
            if s2 and s2 != "0":
                config_lines.append(f"S2 = {s2}")
            if s3 and s3 != "0":
                config_lines.append(f"S3 = {s3}")
            if s4 and s4 != "0":
                config_lines.append(f"S4 = {s4}")
            
            if hp_key:
                config_lines.append(f"HeaderProtectionKey = {hp_key}")
            
            config_lines.extend([
                "",
                "[Peer]",
                f"PublicKey = {server_pub}",
                f"Endpoint = {endpoint}",
                "AllowedIPs = 0.0.0.0/0",
                "PersistentKeepalive = 25",
            ])
            
            client_config = "\n".join(config_lines)
            
            return ClientConfig(
                protocol="awg",
                client_id=client_pub,
                config_data={
                    "label": label,
                    "public_key": client_pub,
                    "private_key": client_priv,
                    "ip": new_ip,
                    "server_pub": server_pub,
                    "endpoint": endpoint,
                    "obfuscation": {
                        "Jc": jc, "Jmin": jmin, "Jmax": jmax,
                        "S1": s1, "S2": s2, "S3": s3, "S4": s4,
                        "HeaderProtectionKey": hp_key,
                    },
                },
                connection_string=client_config,
            )
    
    async def list_clients(self, server_ip: str, ssh_key_path: str) -> list[Dict[str, Any]]:
        """List all AWG clients."""
        cmd = f'''
set -euo pipefail

if [ ! -f {CLIENTS_MAP} ]; then
    echo "[]"
    exit 0
fi

AWG_SHOW=$(awg show awg0 2>/dev/null || echo "")

python3 <<PYTHON_EOF
import json
import re

with open('{CLIENTS_MAP}') as f:
    clients = json.load(f)

awg_show = """$AWG_SHOW"""

peer_status = {{}}
current_peer = None
for line in awg_show.split('\\n'):
    if line.startswith('peer:'):
        pub_key = line.split()[1]
        current_peer = pub_key
        peer_status[pub_key] = {{
            'latest_handshake': None,
            'transfer': None,
            'endpoint': None,
        }}
    elif current_peer and ':' in line:
        key, value = line.split(':', 1)
        key = key.strip().replace(' ', '_')
        value = value.strip()
        peer_status[current_peer][key] = value

result = []
for label, data in clients.items():
    pub_key = data['public_key']
    status = peer_status.get(pub_key, {{}})
    
    latest_hs = status.get('latest_handshake')
    active = latest_hs is not None and latest_hs != '0'
    
    result.append({{
        'protocol': 'awg',
        'client_id': pub_key,
        'label': label,
        'ip': data.get('ip'),
        'active': active,
        'latest_handshake': latest_hs,
        'transfer': status.get('transfer'),
        'endpoint': status.get('endpoint'),
    }})

print(json.dumps(result))
PYTHON_EOF
'''
        
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
            result = await ssh.run(cmd)
            output = result.stdout.strip()
            
            try:
                clients = json.loads(output)
            except json.JSONDecodeError:
                clients = []
            
            return clients
    
    async def remove_client(
        self,
        server_ip: str,
        ssh_key_path: str,
        client_id: str
    ) -> PluginResult:
        """Remove an AWG client.
        
        Args:
            client_id: public key (base64)
        """
        if not re.match(r'^[A-Za-z0-9+/]{40,50}={0,2}$', client_id):
            raise ValueError(f"client_id must be a public key (base64), got: {client_id!r}")
        
        cmd = f'''
set -euo pipefail

# Удаление peer из конфига
if grep -q "{client_id}" {AWG_CONF}; then
    python3 <<PYTHON_EOF
with open('{AWG_CONF}') as f:
    lines = f.readlines()

output = []
i = 0
while i < len(lines):
    line = lines[i]
    if line.strip() == '[Peer]':
        peer_section = [line]
        j = i + 1
        while j < len(lines) and not lines[j].strip().startswith('['):
            peer_section.append(lines[j])
            j += 1
        
        section_text = ''.join(peer_section)
        if '{client_id}' in section_text:
            i = j
            continue
        else:
            output.extend(peer_section)
            i = j
    else:
        output.append(line)
        i += 1

with open('{AWG_CONF}', 'w') as f:
    f.writelines(output)
PYTHON_EOF
fi

# Удаление из маппинга
if [ -f {CLIENTS_MAP} ]; then
    python3 -c "
import json
with open('{CLIENTS_MAP}') as f:
    clients = json.load(f)
to_remove = [k for k, v in clients.items() if v.get('public_key') == '{client_id}']
for k in to_remove:
    del clients[k]
with open('{CLIENTS_MAP}', 'w') as f:
    json.dump(clients, f, indent=2)
"
fi

# Перезапуск интерфейса
awg setconf awg0 <(awg-quick strip awg0) 2>/dev/null || true

echo "Client removed"
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
                message=f"Client {client_id[:16]}... removed",
            )
    
    async def get_status(self, server_ip: str, ssh_key_path: str) -> Dict[str, Any]:
        """Get AWG status via SSH."""
        async with SSHTransport(server_ip, key_path=ssh_key_path) as ssh:
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
            
            output = show_result.stdout
            total_peers = output.count("peer:")
            active_peers = output.count("latest handshake:")
            
            return {
                "service_status": "active",
                "module_loaded": True,
                "total_peers": total_peers,
                "active_peers": active_peers,
                "error": None,
            }
