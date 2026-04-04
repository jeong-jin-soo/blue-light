"""텍스트 폭 실측 유틸리티 — ezdxf 폰트 엔진 기반.

모든 레이아웃 코드에서 `len(text) * char_w` 추정 대신 이 모듈의 함수를 사용한다.
ezdxf.fonts 가 사용 불가하면 fallback 추정으로 자동 전환.
"""

from __future__ import annotations

import logging
from functools import lru_cache
from typing import Sequence

logger = logging.getLogger(__name__)

# ─── ezdxf 폰트 초기화 ───────────────────────────────────────────
_EZDXF_AVAILABLE = False
_fonts_mod = None

try:
    from ezdxf.fonts import fonts as _fonts_mod  # type: ignore[assignment]
    _EZDXF_AVAILABLE = True
except Exception:
    logger.debug("ezdxf.fonts not available — using fallback text measurement")


@lru_cache(maxsize=32)
def _get_font(font_name: str, cap_height: float):
    """ezdxf Font 객체를 캐시하여 반환."""
    if _EZDXF_AVAILABLE and _fonts_mod is not None:
        try:
            return _fonts_mod.make_font(font_name, cap_height=cap_height)
        except Exception:
            pass
    return None


# ─── Public API ───────────────────────────────────────────────────

# DXF SLD에서 사용하는 기본 폰트
DEFAULT_FONT = "txt"
# fallback 비율: cap_height * FALLBACK_RATIO ≈ 평균 문자 폭
FALLBACK_RATIO = 0.6


def measure_text_width(
    text: str,
    cap_height: float = 2.8,
    font_name: str = DEFAULT_FONT,
) -> float:
    """단일 줄 텍스트의 실제 폭(mm)을 반환.

    Args:
        text: 측정할 텍스트 (단일 줄, \\P 없는 것을 권장)
        cap_height: 텍스트 높이(mm) — DXF MTEXT의 char_height
        font_name: ezdxf 폰트 이름 (기본 "txt")

    Returns:
        텍스트 폭(mm). ezdxf 불가 시 `len(text) * cap_height * FALLBACK_RATIO` 반환.
    """
    if not text:
        return 0.0
    font = _get_font(font_name, cap_height)
    if font is not None:
        try:
            return font.text_width(text)
        except Exception:
            pass
    return len(text) * cap_height * FALLBACK_RATIO


def measure_mtext_width(
    mtext: str,
    cap_height: float = 2.8,
    font_name: str = DEFAULT_FONT,
) -> float:
    """MTEXT(\\P 줄바꿈 포함)의 최대 줄 폭(mm)을 반환."""
    lines = mtext.replace("\\P", "\n").split("\n")
    if not lines:
        return 0.0
    return max(measure_text_width(ln, cap_height, font_name) for ln in lines)


def measure_mtext_size(
    mtext: str,
    cap_height: float = 2.8,
    line_gap: float = 0.5,
    font_name: str = DEFAULT_FONT,
) -> tuple[float, float]:
    """MTEXT의 (width, height)를 반환.

    Returns:
        (max_line_width_mm, total_height_mm)
    """
    lines = mtext.replace("\\P", "\n").split("\n")
    if not lines:
        return (0.0, 0.0)
    widths = [measure_text_width(ln, cap_height, font_name) for ln in lines]
    w = max(widths) if widths else 0.0
    h = len(lines) * cap_height + max(0, len(lines) - 1) * line_gap
    return (w, h)


def measure_lines_widths(
    lines: Sequence[str],
    cap_height: float = 2.8,
    font_name: str = DEFAULT_FONT,
) -> list[float]:
    """여러 줄의 개별 폭 리스트를 반환."""
    return [measure_text_width(ln, cap_height, font_name) for ln in lines]
