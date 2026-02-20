"""
FastAPI dependencies: authentication, shared resources.
"""

import logging

from fastapi import Header, HTTPException, status

from app.config import settings

logger = logging.getLogger(__name__)


async def verify_service_key(
    x_service_key: str = Header(..., alias="X-Service-Key"),
) -> str:
    """
    Verify the service-to-service authentication key.
    Spring Boot sends this key with every request.
    """
    if not settings.service_key:
        logger.warning("SERVICE_KEY not configured — accepting all requests")
        return x_service_key

    if x_service_key != settings.service_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid service key",
        )
    return x_service_key
