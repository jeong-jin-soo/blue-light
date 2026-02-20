"""
LangGraph checkpoint persistence using SQLite.
"""

import logging

import aiosqlite
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from app.config import settings

logger = logging.getLogger(__name__)

_checkpointer: AsyncSqliteSaver | None = None


async def get_checkpointer() -> AsyncSqliteSaver:
    """Get or create the async SQLite checkpointer.

    AsyncSqliteSaver.from_conn_string()은 context manager이므로,
    장기 실행 서비스에서는 직접 aiosqlite 연결을 생성하여 사용합니다.
    """
    global _checkpointer
    if _checkpointer is None:
        conn = await aiosqlite.connect(settings.sqlite_db_path)
        _checkpointer = AsyncSqliteSaver(conn)
        await _checkpointer.setup()
        logger.info(f"LangGraph checkpointer initialized: {settings.sqlite_db_path}")
    return _checkpointer
