"""Component Catalog — SLD 컴포넌트 단일 정의원.

18종 전기 심볼의 치수, 핀 위치, 앵커, 렌더링 파라미터를
한 곳에서 선언적으로 정의한다.

Usage:
    from app.sld.catalog import get_catalog

    catalog = get_catalog()
    mccb = catalog.get("MCCB")
    mccb.width          # 5.5 mm
    mccb.pin("top")     # Pin(x=2.75, y=11.0)
    mccb.pin_absolute("bottom", x=207.25, y=150.0)  # (210.0, 148.0)
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

_BASE_DIR = Path(__file__).resolve().parent.parent.parent  # blue-light-ai/
_CATALOG_PATH = _BASE_DIR / "data" / "templates" / "component_catalog.json"


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class Pin:
    """선언적 연결 포인트 (심볼 원점 기준 상대 좌표)."""
    x: float
    y: float


@dataclass(frozen=True)
class ComponentDef:
    """단일 컴포넌트의 완전한 정의.

    좌표 규약:
        원점 (0, 0) = 심볼 body 좌하단 (stubs 제외)
        top pin = body 상단 + stub
        bottom pin = body 하단 - stub
    """
    name: str
    category: str       # breaker, meter, protection, connector, auxiliary
    width: float        # mm (body bbox)
    height: float       # mm (body bbox)
    stub: float         # mm (연결 스텁 길이)

    pins: dict[str, Pin]
    anchors: dict[str, Pin]
    render_params: dict[str, float]

    dxf_block: str | None = None
    h_extent: float | None = None   # 수평 배치 시 extent (None → height)

    def pin(self, name: str) -> Pin:
        """핀 조회. KeyError on missing."""
        return self.pins[name]

    def pin_absolute(self, name: str, x: float, y: float) -> tuple[float, float]:
        """절대 좌표로 핀 위치 반환."""
        p = self.pins[name]
        return (x + p.x, y + p.y)

    def center_x(self) -> float:
        """body 중심 X (원점 기준): width / 2."""
        return self.width / 2

    @property
    def effective_h_extent(self) -> float:
        """수평 배치 시 실제 폭."""
        return self.h_extent if self.h_extent is not None else self.height


# ---------------------------------------------------------------------------
# Catalog
# ---------------------------------------------------------------------------

class ComponentCatalog:
    """18종 컴포넌트의 단일 정의원."""

    def __init__(self, components: dict[str, ComponentDef]):
        self._components = components

    def get(self, name: str) -> ComponentDef:
        """이름으로 컴포넌트 조회. CB_ 접두사 자동 제거."""
        # CB_MCCB → MCCB, CB_MCB → MCB 등
        lookup = name.removeprefix("CB_")
        if lookup in self._components:
            return self._components[lookup]
        if name in self._components:
            return self._components[name]
        raise KeyError(f"Component '{name}' not in catalog. Available: {list(self._components.keys())}")

    def has(self, name: str) -> bool:
        lookup = name.removeprefix("CB_")
        return lookup in self._components or name in self._components

    def by_category(self, category: str) -> list[ComponentDef]:
        return [c for c in self._components.values() if c.category == category]

    def all_names(self) -> list[str]:
        return list(self._components.keys())

    def __len__(self) -> int:
        return len(self._components)

    @classmethod
    def load(cls, path: Path | None = None) -> ComponentCatalog:
        """JSON에서 카탈로그 로드."""
        path = path or _CATALOG_PATH
        with open(path) as f:
            data = json.load(f)

        components: dict[str, ComponentDef] = {}
        for name, raw in data.get("components", {}).items():
            pins = {k: Pin(**v) for k, v in raw.get("pins", {}).items()}
            anchors = {k: Pin(**v) for k, v in raw.get("anchors", {}).items()}
            components[name] = ComponentDef(
                name=name,
                category=raw["category"],
                width=raw["width"],
                height=raw["height"],
                stub=raw["stub"],
                pins=pins,
                anchors=anchors,
                render_params=raw.get("render_params", {}),
                dxf_block=raw.get("dxf_block"),
                h_extent=raw.get("h_extent"),
            )
        return cls(components)


# ---------------------------------------------------------------------------
# Module-level singleton
# ---------------------------------------------------------------------------

_catalog: ComponentCatalog | None = None


def get_catalog() -> ComponentCatalog:
    """싱글턴 카탈로그 반환 (lazy load)."""
    global _catalog
    if _catalog is None:
        _catalog = ComponentCatalog.load()
    return _catalog


def reset_catalog():
    """테스트용: 싱글턴 리셋."""
    global _catalog
    _catalog = None
