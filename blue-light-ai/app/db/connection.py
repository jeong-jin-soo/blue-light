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
