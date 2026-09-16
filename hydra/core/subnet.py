"""Subnet key computation for device collision detection."""
import ipaddress


def compute_subnet_key(ip: str) -> str:
    """Compute canonical subnet key for IP address.
    
    IPv4: /24 prefix (e.g., 10.20.30.40 → "10.20.30.0/24")
    IPv6: /64 prefix (canonical compressed form)
    IPv4-mapped IPv6 (::ffff:192.168.1.1): treated as IPv4
    
    Used for detecting suspicious activity: same device_id connecting
    from multiple subnets in 5 minutes triggers alert.
    
    Examples:
        >>> compute_subnet_key("10.20.30.40")
        '10.20.30.0/24'
        >>> compute_subnet_key("2001:db8:85a3::1")
        '2001:db8:85a3::/64'
        >>> compute_subnet_key("2001:0db8:85a3:0000::1")
        '2001:db8:85a3::/64'  # same canonical form
        >>> compute_subnet_key("::ffff:192.168.1.42")
        '192.168.1.0/24'
    """
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        # Invalid IP — return as-is (will not match anything)
        return ip
    
    # IPv4-mapped IPv6 → treat as IPv4
    if isinstance(addr, ipaddress.IPv6Address) and addr.ipv4_mapped:
        addr = addr.ipv4_mapped
    
    if isinstance(addr, ipaddress.IPv4Address):
        net = ipaddress.ip_network(f"{addr}/24", strict=False)
    else:
        net = ipaddress.ip_network(f"{addr}/64", strict=False)
    
    return str(net)
