"""규칙 데이터 모델.

레퍼런스 DXF에서 도출된 범용 규칙을 정의한다.
규칙은 JSON으로 직렬화/역직렬화 가능하며, 검증기에서 사용한다.
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# Default paths
_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent  # blue-light-ai/
RULES_PATH = _BASE_DIR / "data" / "regression" / "universal_rules.json"
SPECS_PATH = _BASE_DIR / "data" / "regression" / "reference_specs.json"


@dataclass
class Rule:
    """단일 범용 규칙."""
    name: str
    description: str
    category: str  # "structural", "order", "spacing", "semantic"
    applies_to: list[str]  # SLD 유형 목록
    params: dict[str, Any] = field(default_factory=dict)
    evidence_count: int = 0  # 규칙을 뒷받침하는 레퍼런스 파일 수

    def applies_to_type(self, sld_type: str) -> bool:
        return sld_type in self.applies_to or "*" in self.applies_to


@dataclass
class RuleSet:
    """규칙 집합. JSON 파일에서 로드/저장."""
    rules: list[Rule] = field(default_factory=list)
    meta: dict[str, Any] = field(default_factory=dict)

    def by_category(self, category: str) -> list[Rule]:
        return [r for r in self.rules if r.category == category]

    def by_type(self, sld_type: str) -> list[Rule]:
        return [r for r in self.rules if r.applies_to_type(sld_type)]

    def get(self, name: str) -> Rule | None:
        for r in self.rules:
            if r.name == name:
                return r
        return None

    def save(self, path: Path | None = None):
        path = path or RULES_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "meta": self.meta,
            "rules": [asdict(r) for r in self.rules],
        }
        with open(path, "w") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        logger.info("Saved %d rules to %s", len(self.rules), path)

    @classmethod
    def load(cls, path: Path | None = None) -> RuleSet:
        path = path or RULES_PATH
        with open(path) as f:
            data = json.load(f)
        rules = [Rule(**r) for r in data.get("rules", [])]
        return cls(rules=rules, meta=data.get("meta", {}))


# -- Reference Spec (DXF 핑거프린트) --

@dataclass
class BlockUsage:
    """DXF에서 사용된 블록 INSERT."""
    name: str
    x: float
    y: float
    scale: float
    rotation: float


@dataclass
class SpineComponent:
    """스파인 위의 컴포넌트 (Y순서대로)."""
    block_name: str
    y: float
    scale: float


@dataclass
class SubcircuitRow:
    """서브회로 행."""
    y: float
    count: int
    x_spacings: list[float]
    phase_labels: list[str]


@dataclass
class ReferenceSpec:
    """단일 DXF 파일의 구조적 핑거프린트."""
    filename: str
    sld_type: str  # ct_metering_3phase, direct_metering_3phase, direct_metering_1phase, cable_extension
    block_counts: dict[str, int] = field(default_factory=dict)
    total_subcircuits: int = 0
    num_dbs: int = 1
    has_rccb: bool = False
    has_ct: bool = False
    has_fuse: bool = False
    has_isolator: bool = False

    # 스파인 분석
    spine_orders: list[list[str]] = field(default_factory=list)  # 각 스파인의 컴포넌트 순서
    spine_components: list[SpineComponent] = field(default_factory=list)

    # 서브회로
    subcircuit_rows: list[SubcircuitRow] = field(default_factory=list)

    # 텍스트/라벨
    has_phase_labels: bool = False
    has_rating_labels: bool = False
    has_cable_annotations: bool = False

    # 도면 범위
    extent_x: tuple[float, float] = (0, 0)
    extent_y: tuple[float, float] = (0, 0)

    # 레이어 사용
    layer_usage: dict[str, int] = field(default_factory=dict)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["extent_x"] = list(d["extent_x"])
        d["extent_y"] = list(d["extent_y"])
        return d

    @classmethod
    def from_dict(cls, d: dict) -> ReferenceSpec:
        d = dict(d)
        d["extent_x"] = tuple(d.get("extent_x", (0, 0)))
        d["extent_y"] = tuple(d.get("extent_y", (0, 0)))
        # Convert nested dicts back to dataclasses
        d["spine_components"] = [
            SpineComponent(**sc) if isinstance(sc, dict) else sc
            for sc in d.get("spine_components", [])
        ]
        d["subcircuit_rows"] = [
            SubcircuitRow(**sr) if isinstance(sr, dict) else sr
            for sr in d.get("subcircuit_rows", [])
        ]
        return cls(**d)


@dataclass
class ReferenceDatabase:
    """28개 DXF 핑거프린트 데이터베이스."""
    specs: list[ReferenceSpec] = field(default_factory=list)
    meta: dict[str, Any] = field(default_factory=dict)

    def by_type(self, sld_type: str) -> list[ReferenceSpec]:
        return [s for s in self.specs if s.sld_type == sld_type]

    def save(self, path: Path | None = None):
        path = path or SPECS_PATH
        path.parent.mkdir(parents=True, exist_ok=True)
        data = {
            "meta": self.meta,
            "specs": [s.to_dict() for s in self.specs],
        }
        with open(path, "w") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        logger.info("Saved %d specs to %s", len(self.specs), path)

    @classmethod
    def load(cls, path: Path | None = None) -> ReferenceDatabase:
        path = path or SPECS_PATH
        with open(path) as f:
            data = json.load(f)
        specs = [ReferenceSpec.from_dict(s) for s in data.get("specs", [])]
        return cls(specs=specs, meta=data.get("meta", {}))
