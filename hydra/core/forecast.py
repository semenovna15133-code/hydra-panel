"""Server load forecasting and scaling recommendations.

Analyzes 7-day trends in server load metrics and predicts when servers
will reach critical thresholds. Recommends scaling actions.
"""
from datetime import datetime, timedelta, timezone
from typing import Optional
from .db import Database


async def analyze_server_load(
    db: Database,
    server_id: str,
    days: int = 7,
) -> dict:
    """Analyze server load trends over specified period.
    
    Returns:
        Dict with current load, trend, and days until critical threshold.
    """
    # Получить метрики за период
    metrics = await db.fetchall(
        """SELECT timestamp, cpu_percent, memory_percent,
                  network_rx_mbps, network_tx_mbps,
                  connections_wdtt, connections_aivpn, connections_awg
           FROM metrics
           WHERE server_id = ?
             AND timestamp > datetime('now', ? || ' days')
           ORDER BY timestamp DESC""",
        server_id, -days,
    )
    
    if len(metrics) < 10:
        return {
            "server_id": server_id,
            "status": "insufficient_data",
            "message": f"Need at least 10 data points, got {len(metrics)}",
        }
    
    # Текущие значения (последние 10 точек)
    recent = metrics[:10]
    current_cpu = sum(m['cpu_percent'] or 0 for m in recent) / len(recent)
    current_memory = sum(m['memory_percent'] or 0 for m in recent) / len(recent)
    current_network_rx = sum(m['network_rx_mbps'] or 0 for m in recent) / len(recent)
    current_network_tx = sum(m['network_tx_mbps'] or 0 for m in recent) / len(recent)
    current_conns = sum(
        (m['connections_wdtt'] or 0) + (m['connections_aivpn'] or 0) + (m['connections_awg'] or 0)
        for m in recent
    ) / len(recent)
    
    # Получить данные 7 дней назад для тренда
    week_ago = await db.fetchall(
        """SELECT cpu_percent, memory_percent, network_rx_mbps, network_tx_mbps,
                  connections_wdtt, connections_aivpn, connections_awg
           FROM metrics
           WHERE server_id = ?
             AND timestamp > datetime('now', '-8 days')
             AND timestamp < datetime('now', '-7 days')
           ORDER BY timestamp""",
        server_id,
    )
    
    if len(week_ago) < 5:
        return {
            "server_id": server_id,
            "status": "insufficient_historical_data",
            "message": "Need historical data from 7-8 days ago",
        }
    
    # Средние значения неделю назад
    old_cpu = sum(m['cpu_percent'] or 0 for m in week_ago) / len(week_ago)
    old_memory = sum(m['memory_percent'] or 0 for m in week_ago) / len(week_ago)
    old_network_rx = sum(m['network_rx_mbps'] or 0 for m in week_ago) / len(week_ago)
    old_network_tx = sum(m['network_tx_mbps'] or 0 for m in week_ago) / len(week_ago)
    old_conns = sum(
        (m['connections_wdtt'] or 0) + (m['connections_aivpn'] or 0) + (m['connections_awg'] or 0)
        for m in week_ago
    ) / len(week_ago)
    
    # Расчёт тренда (изменение за неделю)
    cpu_trend = current_cpu - old_cpu
    memory_trend = current_memory - old_memory
    network_rx_trend = current_network_rx - old_network_rx
    network_tx_trend = current_network_tx - old_network_tx
    conns_trend = current_conns - old_conns
    
    # Прогноз дней до критического порога (90% для CPU/memory, 80% для сети)
    CRITICAL_CPU = 90.0
    CRITICAL_MEMORY = 90.0
    CRITICAL_NETWORK = 800.0  # 800 Mbps из 1000 Mbps
    CRITICAL_CONNS = 45  # 45 из 50
    
    days_to_critical = {}
    already_critical = []
    
    # CPU
    if current_cpu >= CRITICAL_CPU:
        already_critical.append('cpu')
    elif cpu_trend > 0:
        days_to_critical['cpu'] = max(0, (CRITICAL_CPU - current_cpu) / (cpu_trend / 7))
    
    # Memory
    if current_memory >= CRITICAL_MEMORY:
        already_critical.append('memory')
    elif memory_trend > 0:
        days_to_critical['memory'] = max(0, (CRITICAL_MEMORY - current_memory) / (memory_trend / 7))
    
    # Network RX
    if current_network_rx >= CRITICAL_NETWORK:
        already_critical.append('network_rx')
    elif network_rx_trend > 0:
        days_to_critical['network_rx'] = max(0, (CRITICAL_NETWORK - current_network_rx) / (network_rx_trend / 7))
    
    # Network TX
    if current_network_tx >= CRITICAL_NETWORK:
        already_critical.append('network_tx')
    elif network_tx_trend > 0:
        days_to_critical['network_tx'] = max(0, (CRITICAL_NETWORK - current_network_tx) / (network_tx_trend / 7))
    
    # Connections
    if current_conns >= CRITICAL_CONNS:
        already_critical.append('connections')
    elif conns_trend > 0:
        days_to_critical['connections'] = max(0, (CRITICAL_CONNS - current_conns) / (conns_trend / 7))
    
    # Самый критичный метрика
    if already_critical:
        critical_metric = already_critical[0]
        days_until = 0.0
    elif days_to_critical:
        critical_metric = min(days_to_critical, key=days_to_critical.get)
        days_until = days_to_critical[critical_metric]
    else:
        critical_metric = None
        days_until = None
    
    # Рекомендация
    if days_until is not None and days_until < 14:
        recommendation = f"URGENT: {critical_metric} will reach critical in {days_until:.1f} days. Scale immediately."
    elif days_until is not None and days_until < 30:
        recommendation = f"Plan scaling: {critical_metric} will reach critical in {days_until:.1f} days."
    else:
        recommendation = "Server load is stable. No immediate action needed."
    
    return {
        "server_id": server_id,
        "status": "analyzed",
        "period_days": days,
        "current": {
            "cpu_percent": round(current_cpu, 1),
            "memory_percent": round(current_memory, 1),
            "network_rx_mbps": round(current_network_rx, 1),
            "network_tx_mbps": round(current_network_tx, 1),
            "connections": round(current_conns, 1),
        },
        "trend_weekly": {
            "cpu_percent": round(cpu_trend, 1),
            "memory_percent": round(memory_trend, 1),
            "network_rx_mbps": round(network_rx_trend, 1),
            "network_tx_mbps": round(network_tx_trend, 1),
            "connections": round(conns_trend, 1),
        },
        "days_to_critical": {k: round(v, 1) for k, v in days_to_critical.items()},
        "already_critical": already_critical,
        "critical_metric": critical_metric,
        "days_until_critical": round(days_until, 1) if days_until is not None else None,
        "recommendation": recommendation,
    }


async def forecast_all_servers(db: Database) -> list:
    """Analyze load trends for all active servers."""
    servers = await db.fetchall(
        "SELECT id FROM servers WHERE status = 'active'"
    )
    
    forecasts = []
    for server in servers:
        forecast = await analyze_server_load(db, server['id'])
        forecasts.append(forecast)
    
    return forecasts
