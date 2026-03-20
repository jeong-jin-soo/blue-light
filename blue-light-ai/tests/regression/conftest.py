"""회귀 테스트 공유 픽스처.

4가지 SLD 유형별 표준 requirements, 규칙 로더, 레이아웃 캐싱.
"""

from __future__ import annotations

import copy
from functools import lru_cache
from pathlib import Path

import pytest

from app.sld.layout.engine import compute_layout
from app.sld.regression.rules import RuleSet

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "data" / "regression"
_RULES_PATH = _DATA_DIR / "universal_rules.json"

# ---------------------------------------------------------------------------
# Standard requirements (4 SLD types × representative configs)
# ---------------------------------------------------------------------------

_3PHASE_CIRCUITS = [
    {"name": "Lighting L1", "breaker_type": "MCB", "breaker_rating": 10,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"},
    {"name": "Lighting L2", "breaker_type": "MCB", "breaker_rating": 10,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"},
    {"name": "Lighting L3", "breaker_type": "MCB", "breaker_rating": 10,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"},
    {"name": "Power L1", "breaker_type": "MCB", "breaker_rating": 20,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"},
    {"name": "Power L2", "breaker_type": "MCB", "breaker_rating": 20,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"},
    {"name": "Power L3", "breaker_type": "MCB", "breaker_rating": 20,
     "breaker_characteristic": "B",
     "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN METAL TRUNKING"},
]

DIRECT_3PHASE_63A = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "metering": "none",  # 명시적으로 direct (자동보정 방지)
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 100,
    "elcb": {"rating": 63, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
    "sub_circuits": _3PHASE_CIRCUITS,
}

DIRECT_1PHASE_40A = {
    "supply_type": "single_phase",
    "kva": 9.2,
    "voltage": 230,
    "metering": "none",  # 명시적으로 direct
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN TRUNKING/CONDUIT"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "breaker_characteristic": "B",
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm PVC CPC IN TRUNKING/CONDUIT"},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN TRUNKING/CONDUIT"},
        {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

CT_METERING_150A = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 400,
    "metering": "ct_metering",
    "metering_config": {"ct_ratio": "200/5", "ct_class": "CL1 5VA"},
    "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "elcb": {"rating": 100, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
    "sub_circuits": _3PHASE_CIRCUITS + [
        {"name": "Aircon L1", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN METAL TRUNKING"},
        {"name": "Aircon L2", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN METAL TRUNKING"},
        {"name": "Aircon L3", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm PVC CPC IN METAL TRUNKING"},
    ],
}

SP_METER_3PHASE = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "metering": "sp_meter",
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
    "sub_circuits": _3PHASE_CIRCUITS[:3],
}

# All standard configs indexed by test ID
ALL_CONFIGS = {
    "direct_3phase_63a": DIRECT_3PHASE_63A,
    "direct_1phase_40a": DIRECT_1PHASE_40A,
    "ct_metering_150a": CT_METERING_150A,
    "sp_meter_3phase": SP_METER_3PHASE,
}

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

@pytest.fixture(scope="session")
def rules() -> RuleSet:
    """Load universal rules (session-scoped, loaded once)."""
    if not _RULES_PATH.exists():
        pytest.skip("universal_rules.json not found — run rule_deriver first")
    return RuleSet.load(_RULES_PATH)


@pytest.fixture(
    params=list(ALL_CONFIGS.keys()),
    ids=list(ALL_CONFIGS.keys()),
)
def sld_config(request) -> tuple[str, dict]:
    """Parametrized fixture: yields (config_id, requirements_dict)."""
    config_id = request.param
    return config_id, copy.deepcopy(ALL_CONFIGS[config_id])


# Cache layout results per config to avoid recomputing
_layout_cache: dict[str, object] = {}


def get_layout(config_id: str):
    """Get cached LayoutResult for a config."""
    if config_id not in _layout_cache:
        req = copy.deepcopy(ALL_CONFIGS[config_id])
        _layout_cache[config_id] = compute_layout(req)
    return _layout_cache[config_id]


@pytest.fixture
def layout_result(sld_config):
    """Cached LayoutResult for the current config."""
    config_id, _ = sld_config
    return get_layout(config_id)
