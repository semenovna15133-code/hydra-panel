"""Alert sending with retry and exponential backoff."""
import asyncio
import logging
from typing import Optional

logger = logging.getLogger(__name__)


async def send_alert(
    level: str,
    message: str,
    server_id: Optional[str] = None,
    db=None,
    max_retries: int = 3,
    base_delay: float = 1.0,
) -> bool:
    """Send alert with retry and exponential backoff.
    
    Args:
        level: Alert level (warning, critical, forecast, report, suspicious)
        message: Alert message
        server_id: Optional server ID for context
        db: Database instance for persistence
        max_retries: Maximum retry attempts (default 3)
        base_delay: Base delay in seconds (default 1.0)
    
    Returns:
        True if sent successfully, False otherwise
    
    Retry strategy: exponential backoff (1s, 2s, 4s)
    On failure: logs to file, does not raise exception
    """
    for attempt in range(max_retries):
        try:
            # Store in database
            if db:
                await db.execute(
                    """INSERT INTO alerts (server_id, level, message, triggered_at)
                       VALUES (?, ?, ?, datetime('now'))""",
                    server_id, level, message,
                )
                await db.commit()
            
            # TODO: Send to Telegram channel (implement in Stage 3)
            # For now, just log
            logger.info(f"[{level}] {message}")
            
            return True
            
        except Exception as e:
            delay = base_delay * (2 ** attempt)
            logger.warning(
                f"Alert send failed (attempt {attempt + 1}/{max_retries}): {e}. "
                f"Retrying in {delay}s..."
            )
            
            if attempt < max_retries - 1:
                await asyncio.sleep(delay)
            else:
                logger.error(f"Alert send failed after {max_retries} attempts: {message}")
                return False
    
    return False
