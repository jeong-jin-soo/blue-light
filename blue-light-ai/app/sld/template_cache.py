"""
Thread-safe module-level cache for bridging template data
between find_matching_templates and generate_sld tools.

LangGraph tools are independent functions that don't share state.
This module-level cache allows find_matching_templates to store
the matched template so that generate_sld can retrieve and merge it.
"""

import logging
import threading
import time

logger = logging.getLogger(__name__)

_lock = threading.Lock()
_cache: dict[int, dict] = {}  # key: application_seq


def store(
    application_seq: int,
    template: dict,
    matched_template: dict | None = None,
) -> None:
    """Store normalized template in cache. TTL: 30 minutes.

    Args:
        application_seq: Application ID as cache key.
        template: Normalized template requirements (generator-ready format).
        matched_template: Original matched template metadata (for Track A routing).
    """
    with _lock:
        _cache[application_seq] = {
            "template": template,
            "matched_template": matched_template,
            "stored_at": time.time(),
        }
    logger.info(
        "Template cached for application_seq=%d (%d sub_circuits)",
        application_seq,
        len(template.get("sub_circuits", [])),
    )


def retrieve(application_seq: int) -> dict | None:
    """Retrieve template from cache (non-destructive: keeps entry for retries).

    Returns None if not found or expired (TTL 30 min).
    Use `remove()` to explicitly delete after successful generation.

    Args:
        application_seq: Application ID to look up.

    Returns:
        Normalized template dict, or None.
    """
    with _lock:
        entry = _cache.get(application_seq)

    if entry is None:
        logger.debug("Template cache miss for application_seq=%d", application_seq)
        return None

    elapsed = time.time() - entry["stored_at"]
    if elapsed > 1800:  # 30 minutes
        # Expired — clean up
        with _lock:
            _cache.pop(application_seq, None)
        logger.info(
            "Template cache expired for application_seq=%d (%.0fs old)",
            application_seq,
            elapsed,
        )
        return None

    logger.info(
        "Template cache hit for application_seq=%d (%.0fs old)",
        application_seq,
        elapsed,
    )
    return entry["template"]


def retrieve_matched_template(application_seq: int) -> dict | None:
    """Retrieve original matched template metadata (for Track A routing).

    Returns None if not found, expired, or no matched_template stored.
    """
    with _lock:
        entry = _cache.get(application_seq)

    if entry is None:
        return None

    elapsed = time.time() - entry["stored_at"]
    if elapsed > 1800:
        return None

    return entry.get("matched_template")


def remove(application_seq: int) -> None:
    """Explicitly remove template from cache after successful generation."""
    with _lock:
        removed = _cache.pop(application_seq, None)
    if removed:
        logger.debug("Template cache removed for application_seq=%d", application_seq)


def cleanup_expired() -> int:
    """Remove all expired entries from cache. Called by scheduler.

    Returns:
        Number of entries removed.
    """
    with _lock:
        now = time.time()
        expired = [k for k, v in _cache.items() if now - v["stored_at"] > 1800]
        for k in expired:
            del _cache[k]

    if expired:
        logger.info("Template cache cleanup: removed %d expired entries", len(expired))
    return len(expired)
