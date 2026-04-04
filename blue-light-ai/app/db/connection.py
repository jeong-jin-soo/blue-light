"""
MySQL connection management using PyMySQL.
Spring Boot와 동일한 MySQL(bluelight DB)에 접속하여 sld_templates 테이블 활용.
"""

import logging
from contextlib import contextmanager

import pymysql
from pymysql.cursors import DictCursor

from app.config import settings

logger = logging.getLogger(__name__)


def get_connection() -> pymysql.Connection:
    """Create a new MySQL connection."""
    return pymysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        database=settings.mysql_database,
        charset="utf8mb4",
        cursorclass=DictCursor,
        connect_timeout=10,
        read_timeout=30,
    )


@contextmanager
def get_db():
    """Context manager for database connection with auto-commit/rollback."""
    conn = get_connection()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


# ── system_settings 캐시 (Spring Boot GeminiConfig과 동일한 60초 TTL) ──

import time

_setting_cache: dict[str, tuple[str, float]] = {}
_CACHE_TTL = 60.0  # seconds


def get_system_setting(key: str) -> str | None:
    """system_settings 테이블에서 설정값을 읽음. 60초 캐시.

    Spring Boot의 GeminiConfig.getApiKey()와 동일한 로직:
    DB 값이 있으면 DB, 없으면 None 반환 (환경변수 fallback은 호출측에서).
    """
    now = time.time()
    if key in _setting_cache:
        val, ts = _setting_cache[key]
        if now - ts < _CACHE_TTL:
            return val

    try:
        with get_db() as conn:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT setting_value FROM system_settings WHERE setting_key = %s",
                    (key,),
                )
                row = cur.fetchone()
                val = row["setting_value"] if row and row["setting_value"] else None
                _setting_cache[key] = (val, now)
                return val
    except Exception:
        logger.debug("Failed to read system_setting '%s' from DB", key)
        return None
