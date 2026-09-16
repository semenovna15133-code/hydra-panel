"""Weekly usage reports with catch-up logic.

Generates reports every Sunday at 00:00 UTC.
If panel was offline, generates reports for all missed weeks.
"""
from datetime import datetime, timedelta, timezone
from typing import Optional
from .db import Database
from .forecast import analyze_server_load


async def generate_weekly_report(
    db: Database,
    week_start: datetime,
    week_end: datetime,
) -> dict:
    """Generate weekly usage report.
    
    Args:
        db: Database instance
        week_start: Monday 00:00 UTC
        week_end: Sunday 23:59 UTC
    
    Returns:
        Dict with report data
    """
    # Собрать статистику за неделю
    # Ключ считается использованным если есть хотя бы одна регистрация устройства
    keys_stats = await db.fetchone(
        """SELECT 
             COUNT(DISTINCT ak.key_id) as total_keys,
             COUNT(DISTINCT CASE WHEN dr.key_id IS NOT NULL THEN ak.key_id END) as redeemed_keys,
             COUNT(DISTINCT CASE WHEN ak.expires_at < datetime('now') THEN ak.key_id END) as expired_keys
           FROM access_keys ak
           LEFT JOIN device_registrations dr ON ak.key_id = dr.key_id
           WHERE ak.created_at >= ? AND ak.created_at <= ?""",
        week_start.isoformat(), week_end.isoformat(),
    )
    
    devices_stats = await db.fetchone(
        """SELECT COUNT(DISTINCT device_id) as unique_devices,
                  COUNT(*) as total_connections
           FROM device_connections
           WHERE connected_at >= ? AND connected_at <= ?""",
        week_start.isoformat(), week_end.isoformat(),
    )
    
    # Собрать метрики по серверам
    servers = await db.fetchall("SELECT id, location FROM servers WHERE status = 'active'")
    
    server_summaries = []
    total_load = 0
    peak_load = 0
    
    for server in servers:
        # Средние метрики за неделю
        metrics = await db.fetchone(
            """SELECT 
                 AVG(cpu_percent) as avg_cpu,
                 MAX(cpu_percent) as peak_cpu,
                 AVG(memory_percent) as avg_memory,
                 AVG(network_rx_mbps + network_tx_mbps) as avg_network,
                 COUNT(*) as data_points
               FROM metrics
               WHERE server_id = ?
                 AND timestamp >= ?
                 AND timestamp <= ?""",
            server['id'], week_start.isoformat(), week_end.isoformat(),
        )
        
        if metrics and metrics['data_points'] > 0:
            avg_load = (
                (metrics['avg_cpu'] or 0) +
                (metrics['avg_memory'] or 0)
            ) / 2
            
            server_summaries.append({
                "server_id": server['id'],
                "location": server['location'],
                "avg_cpu": round(metrics['avg_cpu'] or 0, 1),
                "peak_cpu": round(metrics['peak_cpu'] or 0, 1),
                "avg_memory": round(metrics['avg_memory'] or 0, 1),
                "avg_network_mbps": round(metrics['avg_network'] or 0, 1),
                "avg_load_percent": round(avg_load, 1),
                "data_points": metrics['data_points'],
            })
            
            total_load += avg_load
            peak_load = max(peak_load, metrics['peak_cpu'] or 0)
    
    # Прогноз для каждого сервера
    forecasts = []
    for server in servers:
        forecast = await analyze_server_load(db, server['id'], days=7)
        if forecast['status'] == 'analyzed':
            forecasts.append({
                "server_id": server['id'],
                "critical_metric": forecast.get('critical_metric'),
                "days_until_critical": forecast.get('days_until_critical'),
                "recommendation": forecast.get('recommendation'),
            })
    
    # Общая рекомендация
    avg_server_load = total_load / len(servers) if servers else 0
    
    if avg_server_load > 80:
        overall_recommendation = "CRITICAL: Average server load > 80%. Add more servers immediately."
    elif avg_server_load > 60:
        overall_recommendation = "WARNING: Average server load > 60%. Plan to add servers within 2 weeks."
    elif any(f.get('days_until_critical') and f['days_until_critical'] < 14 for f in forecasts):
        overall_recommendation = "Some servers approaching critical load. Review forecasts."
    else:
        overall_recommendation = "All servers operating within normal parameters."
    
    report = {
        "week_start": week_start.isoformat(),
        "week_end": week_end.isoformat(),
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "summary": {
            "total_keys": keys_stats['total_keys'] if keys_stats else 0,
            "redeemed_keys": keys_stats['redeemed_keys'] if keys_stats else 0,
            "expired_keys": keys_stats['expired_keys'] if keys_stats else 0,
            "unique_devices": devices_stats['unique_devices'] if devices_stats else 0,
            "total_connections": devices_stats['total_connections'] if devices_stats else 0,
            "active_servers": len(servers),
            "avg_server_load_percent": round(avg_server_load, 1),
            "peak_server_load_percent": round(peak_load, 1),
        },
        "server_summaries": server_summaries,
        "forecasts": forecasts,
        "recommendation": overall_recommendation,
    }
    
    return report


async def check_and_generate_missed_reports(db: Database) -> list:
    """Check for missed weekly reports and generate them.
    
    Called on panel startup. Generates reports for all weeks since last report.
    
    Returns:
        List of generated report IDs
    """
    # Найти последний отчёт
    last_report = await db.fetchone(
        "SELECT MAX(week_end) as last_week FROM weekly_reports"
    )
    
    if last_report and last_report['last_week']:
        # Парсим дату из строки
        try:
            last_week_end = datetime.fromisoformat(last_report['last_week'].replace('Z', '+00:00'))
        except (ValueError, AttributeError):
            last_week_end = datetime.now(timezone.utc) - timedelta(days=28)
        
        # Следующая неделя после последнего отчёта
        next_week_start = last_week_end + timedelta(days=1)
        # Нормализуем на понедельник 00:00
        days_since_monday = next_week_start.weekday()
        next_week_start = next_week_start - timedelta(days=days_since_monday)
    else:
        # Нет отчётов — начать с 4 недель назад
        now = datetime.now(timezone.utc)
        # Найти понедельник 4 недели назад
        days_since_monday = now.weekday()
        next_week_start = now - timedelta(days=days_since_monday + 28)
        # Убираем время
        next_week_start = next_week_start.replace(hour=0, minute=0, second=0, microsecond=0)
    
    # Генерировать отчёты для всех пропущенных недель
    generated = []
    now = datetime.now(timezone.utc)
    
    while next_week_start < now:
        week_end = next_week_start + timedelta(days=6, hours=23, minutes=59, seconds=59)
        
        # Проверить что отчёт ещё не существует
        exists = await db.fetchone(
            "SELECT id FROM weekly_reports WHERE week_start = ?",
            next_week_start.isoformat(),
        )
        
        if not exists:
            report = await generate_weekly_report(db, next_week_start, week_end)
            
            # Сохранить в БД (используем JSON для report_data)
            import json
            report_json = json.dumps(report, default=str)
            
            await db.execute(
                """INSERT INTO weekly_reports
                   (week_start, week_end, total_keys, total_devices, avg_load, peak_load, protocol_distribution, recommendation, generated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                next_week_start.isoformat(),
                week_end.isoformat(),
                report['summary']['total_keys'],
                report['summary']['unique_devices'],
                report['summary']['avg_server_load_percent'],
                report['summary']['peak_server_load_percent'],
                '{}',  # protocol_distribution placeholder
                report['recommendation'],
                datetime.now(timezone.utc).isoformat(),
            )
            await db.commit()
            
            generated.append({
                "week_start": next_week_start.isoformat(),
                "week_end": week_end.isoformat(),
            })
        
        # Перейти к следующей неделе
        next_week_start += timedelta(days=7)
    
    return generated
