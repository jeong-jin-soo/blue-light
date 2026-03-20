"""
Real-world SLD scenario tests based on analysis of 62 LEW DWG/DXF files.

Covers DB ratings from 32A to 500A with realistic circuit configurations
found in actual Singapore electrical installations. Each scenario uses
breaker ratings, cable specs, and ELCB sensitivity values drawn from
real LEW submissions.

Test tiers:
- Small residential: 32A-63A single-phase / 3-phase landlord
- Medium commercial: 80A-125A 3-phase with CT metering
- Large commercial/industrial: 150A-500A 3-phase MCCB + CT metering
"""

import math

import pytest

from app.sld.layout.engine import compute_layout
from app.sld.layout.models import LayoutConfig, LayoutResult


# =====================================================================
# Helpers
# =====================================================================

def _cable(mm2: float, cpc_mm2: float | None = None, method: str = "METAL TRUNKING") -> str:
    """Build a canonical SG cable spec string."""
    cpc = cpc_mm2 or mm2
    return f"2 x 1C {mm2}sqmm PVC + {cpc}sqmm PVC CPC IN {method}"


def _lighting(name: str = "LIGHTING", rating: int = 10) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "B",
        "cable": _cable(1.5, 1.5),
    }


def _power(name: str = "POWER POINTS", rating: int = 20) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "B",
        "cable": _cable(2.5, 2.5),
    }


def _socket_13a(name: str = "13A SOCKET OUTLET") -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": 13,
        "breaker_characteristic": "B",
        "cable": _cable(2.5, 2.5),
    }


def _aircon(name: str = "AIRCON", rating: int = 20) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "B",
        "cable": _cable(4.0, 4.0),
    }


def _heavy_power(name: str = "HEAVY POWER", rating: int = 32) -> dict:
    return {
        "name": name,
        "breaker_type": "MCB",
        "breaker_rating": rating,
        "breaker_characteristic": "C",
        "cable": _cable(6.0, 4.0),
    }


def _spare(name: str = "SPARE", rating: int = 20) -> dict:
    return {"name": name, "breaker_type": "MCB", "breaker_rating": rating}


def _submain(name: str = "SUB-MAIN", rating: int = 63) -> dict:
    return {
        "name": name,
        "breaker_type": "MCCB",
        "breaker_rating": rating,
        "cable": _cable(16.0, 10.0),
    }


# =====================================================================
# Scenario definitions — parametrized data
# =====================================================================

SCENARIOS = [
    # ------------------------------------------------------------------
    # 1. 32A Single-Phase Landlord — 7 circuits (residential)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "kva": 7.36,
            "voltage": 230,
            "main_breaker": {"type": "MCB", "rating": 32, "poles": "DP", "fault_kA": 6},
            "busbar_rating": 63,
            "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                _lighting("LIGHTING 1"),
                _lighting("LIGHTING 2"),
                _lighting("LIGHTING 3"),
                _lighting("LIGHTING 4"),
                _lighting("LIGHTING 5"),
                _power("POWER POINTS 1"),
                _power("POWER POINTS 2"),
            ],
        },
        id="32A_1PH_landlord_7ckt",
    ),
    # ------------------------------------------------------------------
    # 2. 40A Single-Phase Landlord — 10 circuits
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "kva": 9.2,
            "voltage": 230,
            "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 6},
            "busbar_rating": 63,
            "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                _lighting("LIGHTING 1"),
                _lighting("LIGHTING 2"),
                _lighting("LIGHTING 3"),
                _power("POWER POINTS 1"),
                _power("POWER POINTS 2"),
                _power("POWER POINTS 3"),
                _power("POWER POINTS 4"),
                _power("POWER POINTS 5"),
                _aircon("AIRCON"),
                _spare(),
            ],
        },
        id="40A_1PH_landlord_10ckt",
    ),
    # ------------------------------------------------------------------
    # 3. 40A 3-Phase Landlord — 12 circuits (4 per phase)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "landlord",
            "kva": 22,
            "voltage": 400,
            "main_breaker": {"type": "MCB", "rating": 40, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                _lighting("LIGHTING R1"),
                _lighting("LIGHTING Y1"),
                _lighting("LIGHTING B1"),
                _lighting("LIGHTING R2"),
                _lighting("LIGHTING Y2"),
                _lighting("LIGHTING B2"),
                _power("POWER R1"),
                _power("POWER Y1"),
                _power("POWER B1"),
                _power("POWER R2"),
                _power("POWER Y2"),
                _power("POWER B2"),
            ],
        },
        id="40A_3PH_landlord_12ckt",
    ),
    # ------------------------------------------------------------------
    # 4. 63A Single-Phase Landlord — 15 circuits (heavy residential)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "kva": 14.49,
            "voltage": 230,
            "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                _lighting("LIGHTING 1"),
                _lighting("LIGHTING 2"),
                _lighting("LIGHTING 3"),
                _lighting("LIGHTING 4"),
                _power("POWER POINTS 1"),
                _power("POWER POINTS 2"),
                _power("POWER POINTS 3"),
                _power("POWER POINTS 4"),
                _power("POWER POINTS 5"),
                _power("POWER POINTS 6"),
                _power("POWER POINTS 7"),
                _power("POWER POINTS 8"),
                _aircon("AIRCON 1"),
                _aircon("AIRCON 2"),
                _spare(),
            ],
        },
        id="63A_1PH_landlord_15ckt",
    ),
    # ------------------------------------------------------------------
    # 5. 63A 3-Phase Landlord — 21 circuits (7 per phase)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "landlord",
            "kva": 40,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                # Phase R
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _power("POWER R1"), _power("POWER R2"), _power("POWER R3"),
                _aircon("AIRCON R1"),
                _spare("SPARE R"),
                # Phase Y
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _power("POWER Y1"), _power("POWER Y2"), _power("POWER Y3"),
                _aircon("AIRCON Y1"),
                _spare("SPARE Y"),
                # Phase B
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _power("POWER B1"), _power("POWER B2"), _power("POWER B3"),
                _aircon("AIRCON B1"),
                _spare("SPARE B"),
            ],
        },
        id="63A_3PH_landlord_21ckt",
    ),
    # ------------------------------------------------------------------
    # 6. 63A 3-Phase Building Riser — 18 circuits
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "building_riser",
            "kva": 40,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 63, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "metering": "sp_meter",
            "sub_circuits": [
                _lighting("LIGHTING R1"), _power("POWER R1"), _power("POWER R2"),
                _aircon("AIRCON R1"), _socket_13a("SOCKET R1"), _spare("SPARE R"),
                _lighting("LIGHTING Y1"), _power("POWER Y1"), _power("POWER Y2"),
                _aircon("AIRCON Y1"), _socket_13a("SOCKET Y1"), _spare("SPARE Y"),
                _lighting("LIGHTING B1"), _power("POWER B1"), _power("POWER B2"),
                _aircon("AIRCON B1"), _socket_13a("SOCKET B1"), _spare("SPARE B"),
            ],
        },
        id="63A_3PH_riser_18ckt",
    ),
    # ------------------------------------------------------------------
    # 7. 80A 3-Phase — 24 circuits
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 55,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 80, "poles": "TPN", "fault_kA": 16},
            "busbar_rating": 100,
            "elcb": {"rating": 80, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "metering": "sp_meter",
            "sub_circuits": [
                # 8 per phase: 2 lighting, 3 power, 1 AC, 1 heavy, 1 spare
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _power("POWER R1"), _power("POWER R2"), _power("POWER R3"),
                _aircon("AIRCON R1"), _heavy_power("HEAVY R1"), _spare("SPARE R"),
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _power("POWER Y1"), _power("POWER Y2"), _power("POWER Y3"),
                _aircon("AIRCON Y1"), _heavy_power("HEAVY Y1"), _spare("SPARE Y"),
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _power("POWER B1"), _power("POWER B2"), _power("POWER B3"),
                _aircon("AIRCON B1"), _heavy_power("HEAVY B1"), _spare("SPARE B"),
            ],
        },
        id="80A_3PH_24ckt",
    ),
    # ------------------------------------------------------------------
    # 8. 100A Single-Phase Landlord — 20 circuits (heavy power)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "single_phase",
            "supply_source": "landlord",
            "kva": 23,
            "voltage": 230,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "DP", "fault_kA": 10},
            "busbar_rating": 100,
            "elcb": {"rating": 100, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                _lighting("LIGHTING 1"), _lighting("LIGHTING 2"),
                _lighting("LIGHTING 3"), _lighting("LIGHTING 4"),
                _power("POWER 1"), _power("POWER 2"),
                _power("POWER 3"), _power("POWER 4"),
                _power("POWER 5"), _power("POWER 6"),
                _power("POWER 7"), _power("POWER 8"),
                _power("POWER 9"), _power("POWER 10"),
                _aircon("AIRCON 1"), _aircon("AIRCON 2"),
                _aircon("AIRCON 3"), _aircon("AIRCON 4"),
                _heavy_power("HEAVY 1"),
                _spare(),
            ],
        },
        id="100A_1PH_landlord_20ckt",
    ),
    # ------------------------------------------------------------------
    # 9. 100A 3-Phase Landlord — 30 circuits (10 per phase)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "landlord",
            "kva": 69,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 16},
            "busbar_rating": 100,
            "elcb": {"rating": 100, "sensitivity_ma": 100, "poles": 4, "type": "RCCB"},
            "metering": "none",
            "sub_circuits": [
                # Phase R (10)
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _power("POWER R1"), _power("POWER R2"), _power("POWER R3"),
                _power("POWER R4"), _power("POWER R5"),
                _aircon("AIRCON R1"), _heavy_power("HEAVY R1"), _spare("SPARE R"),
                # Phase Y (10)
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _power("POWER Y1"), _power("POWER Y2"), _power("POWER Y3"),
                _power("POWER Y4"), _power("POWER Y5"),
                _aircon("AIRCON Y1"), _heavy_power("HEAVY Y1"), _spare("SPARE Y"),
                # Phase B (10)
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _power("POWER B1"), _power("POWER B2"), _power("POWER B3"),
                _power("POWER B4"), _power("POWER B5"),
                _aircon("AIRCON B1"), _heavy_power("HEAVY B1"), _spare("SPARE B"),
            ],
        },
        id="100A_3PH_landlord_30ckt",
    ),
    # ------------------------------------------------------------------
    # 10. 125A 3-Phase — 30 circuits, MCCB main, CT metering
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 86,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 125, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "150/5",
                "metering_ct_class": "CL1 5VA",
            },
            "sub_circuits": [
                # 10 per phase
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _power("POWER R1"), _power("POWER R2"), _power("POWER R3"),
                _power("POWER R4"),
                _aircon("AIRCON R1"), _aircon("AIRCON R2"),
                _heavy_power("HEAVY R1"), _spare("SPARE R"),
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _power("POWER Y1"), _power("POWER Y2"), _power("POWER Y3"),
                _power("POWER Y4"),
                _aircon("AIRCON Y1"), _aircon("AIRCON Y2"),
                _heavy_power("HEAVY Y1"), _spare("SPARE Y"),
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _power("POWER B1"), _power("POWER B2"), _power("POWER B3"),
                _power("POWER B4"),
                _aircon("AIRCON B1"), _aircon("AIRCON B2"),
                _heavy_power("HEAVY B1"), _spare("SPARE B"),
            ],
        },
        id="125A_3PH_ct_30ckt",
    ),
    # ------------------------------------------------------------------
    # 11. 150A 3-Phase — 36 circuits, MCCB, CT metering
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 104,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 150, "poles": "TPN", "fault_kA": 25},
            "busbar_rating": 200,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "200/5",
                "metering_ct_class": "CL1 5VA",
            },
            "sub_circuits": [
                # 12 per phase
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"), _lighting("LIGHTING R3"),
                _power("POWER R1"), _power("POWER R2"), _power("POWER R3"),
                _power("POWER R4"),
                _socket_13a("SOCKET R1"),
                _aircon("AIRCON R1"), _aircon("AIRCON R2"),
                _heavy_power("HEAVY R1"), _spare("SPARE R"),
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"), _lighting("LIGHTING Y3"),
                _power("POWER Y1"), _power("POWER Y2"), _power("POWER Y3"),
                _power("POWER Y4"),
                _socket_13a("SOCKET Y1"),
                _aircon("AIRCON Y1"), _aircon("AIRCON Y2"),
                _heavy_power("HEAVY Y1"), _spare("SPARE Y"),
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"), _lighting("LIGHTING B3"),
                _power("POWER B1"), _power("POWER B2"), _power("POWER B3"),
                _power("POWER B4"),
                _socket_13a("SOCKET B1"),
                _aircon("AIRCON B1"), _aircon("AIRCON B2"),
                _heavy_power("HEAVY B1"), _spare("SPARE B"),
            ],
        },
        id="150A_3PH_ct_36ckt",
    ),
    # ------------------------------------------------------------------
    # 12. 200A 3-Phase — 42 circuits, MCCB, CT metering
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 138,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 200, "poles": "TPN", "fault_kA": 36},
            "busbar_rating": 250,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "250/5",
                "metering_ct_class": "CL1 5VA",
            },
            "sub_circuits": [
                # 14 per phase
                _lighting(f"LIGHTING R{i}") for i in range(1, 4)
            ] + [
                _power(f"POWER R{i}") for i in range(1, 6)
            ] + [
                _socket_13a("SOCKET R1"),
                _aircon("AIRCON R1"), _aircon("AIRCON R2"),
                _heavy_power("HEAVY R1"), _spare("SPARE R"),
            ] + [
                _lighting(f"LIGHTING Y{i}") for i in range(1, 4)
            ] + [
                _power(f"POWER Y{i}") for i in range(1, 6)
            ] + [
                _socket_13a("SOCKET Y1"),
                _aircon("AIRCON Y1"), _aircon("AIRCON Y2"),
                _heavy_power("HEAVY Y1"), _spare("SPARE Y"),
            ] + [
                _lighting(f"LIGHTING B{i}") for i in range(1, 4)
            ] + [
                _power(f"POWER B{i}") for i in range(1, 6)
            ] + [
                _socket_13a("SOCKET B1"),
                _aircon("AIRCON B1"), _aircon("AIRCON B2"),
                _heavy_power("HEAVY B1"), _spare("SPARE B"),
            ],
        },
        id="200A_3PH_ct_42ckt",
    ),
    # ------------------------------------------------------------------
    # 13. 300A 3-Phase Building Riser — 30 circuits (MSB scenario)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "building_riser",
            "kva": 207,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 300, "poles": "TPN", "fault_kA": 50},
            "busbar_rating": 400,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "400/5",
                "metering_ct_class": "CL1 5VA",
            },
            "sub_circuits": [
                # 10 per phase — MSB with sub-mains and heavy loads
                _submain("SUB-MAIN R1", 63), _submain("SUB-MAIN R2", 63),
                _heavy_power("MOTOR R1", 32), _heavy_power("MOTOR R2", 32),
                _power("POWER R1"), _power("POWER R2"),
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _aircon("AIRCON R1"),
                _spare("SPARE R"),
                _submain("SUB-MAIN Y1", 63), _submain("SUB-MAIN Y2", 63),
                _heavy_power("MOTOR Y1", 32), _heavy_power("MOTOR Y2", 32),
                _power("POWER Y1"), _power("POWER Y2"),
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _aircon("AIRCON Y1"),
                _spare("SPARE Y"),
                _submain("SUB-MAIN B1", 63), _submain("SUB-MAIN B2", 63),
                _heavy_power("MOTOR B1", 32), _heavy_power("MOTOR B2", 32),
                _power("POWER B1"), _power("POWER B2"),
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _aircon("AIRCON B1"),
                _spare("SPARE B"),
            ],
        },
        id="300A_3PH_riser_30ckt",
    ),
    # ------------------------------------------------------------------
    # 14. 400A 3-Phase — 30 circuits (large commercial)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 276,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 400, "poles": "TPN", "fault_kA": 50},
            "busbar_rating": 500,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "500/5",
                "metering_ct_class": "CL0.5 10VA",
            },
            "sub_circuits": [
                # 10 per phase — large commercial with sub-mains
                _submain("SUB-MAIN R1", 63), _submain("SUB-MAIN R2", 63),
                _submain("SUB-MAIN R3", 63),
                _heavy_power("CHILLER R1", 32),
                _power("POWER R1"), _power("POWER R2"),
                _lighting("LIGHTING R1"), _lighting("LIGHTING R2"),
                _aircon("AIRCON R1"),
                _spare("SPARE R"),
                _submain("SUB-MAIN Y1", 63), _submain("SUB-MAIN Y2", 63),
                _submain("SUB-MAIN Y3", 63),
                _heavy_power("CHILLER Y1", 32),
                _power("POWER Y1"), _power("POWER Y2"),
                _lighting("LIGHTING Y1"), _lighting("LIGHTING Y2"),
                _aircon("AIRCON Y1"),
                _spare("SPARE Y"),
                _submain("SUB-MAIN B1", 63), _submain("SUB-MAIN B2", 63),
                _submain("SUB-MAIN B3", 63),
                _heavy_power("CHILLER B1", 32),
                _power("POWER B1"), _power("POWER B2"),
                _lighting("LIGHTING B1"), _lighting("LIGHTING B2"),
                _aircon("AIRCON B1"),
                _spare("SPARE B"),
            ],
        },
        id="400A_3PH_ct_30ckt",
    ),
    # ------------------------------------------------------------------
    # 15. 500A 3-Phase — 30 circuits (industrial)
    # ------------------------------------------------------------------
    pytest.param(
        {
            "supply_type": "three_phase",
            "supply_source": "sp_powergrid",
            "kva": 346,
            "voltage": 400,
            "main_breaker": {"type": "MCCB", "rating": 500, "poles": "TPN", "fault_kA": 65},
            "busbar_rating": 630,
            "metering": "ct_meter",
            "metering_detail": {
                "ct_ratio": "600/5",
                "metering_ct_class": "CL0.5 10VA",
            },
            "sub_circuits": [
                # 10 per phase — industrial with heavy sub-mains
                _submain("SUB-MAIN R1", 63), _submain("SUB-MAIN R2", 63),
                _submain("SUB-MAIN R3", 63), _submain("SUB-MAIN R4", 63),
                _heavy_power("MOTOR R1", 32), _heavy_power("MOTOR R2", 32),
                _power("POWER R1"), _power("POWER R2"),
                _lighting("EMERGENCY LTG R1"),
                _spare("SPARE R"),
                _submain("SUB-MAIN Y1", 63), _submain("SUB-MAIN Y2", 63),
                _submain("SUB-MAIN Y3", 63), _submain("SUB-MAIN Y4", 63),
                _heavy_power("MOTOR Y1", 32), _heavy_power("MOTOR Y2", 32),
                _power("POWER Y1"), _power("POWER Y2"),
                _lighting("EMERGENCY LTG Y1"),
                _spare("SPARE Y"),
                _submain("SUB-MAIN B1", 63), _submain("SUB-MAIN B2", 63),
                _submain("SUB-MAIN B3", 63), _submain("SUB-MAIN B4", 63),
                _heavy_power("MOTOR B1", 32), _heavy_power("MOTOR B2", 32),
                _power("POWER B1"), _power("POWER B2"),
                _lighting("EMERGENCY LTG B1"),
                _spare("SPARE B"),
            ],
        },
        id="500A_3PH_ct_30ckt",
    ),
]


# =====================================================================
# Tests
# =====================================================================

class TestRealWorldScenarios:
    """Integration tests for real-world SLD scenarios (32A-500A)."""

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_layout_completes_without_error(self, requirements: dict):
        """compute_layout must complete without exception for every scenario."""
        result = compute_layout(requirements, skip_validation=True)
        assert isinstance(result, LayoutResult)

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_has_components(self, requirements: dict):
        """Every SLD must produce at least one placed component."""
        result = compute_layout(requirements, skip_validation=True)
        assert len(result.components) > 0, "Layout produced zero components"

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_has_connections(self, requirements: dict):
        """Every SLD must produce at least one electrical connection."""
        result = compute_layout(requirements, skip_validation=True)
        assert len(result.connections) > 0, "Layout produced zero connections"

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_all_coordinates_are_finite(self, requirements: dict):
        """No component may have NaN or infinite coordinates."""
        result = compute_layout(requirements, skip_validation=True)
        for comp in result.components:
            assert math.isfinite(comp.x), (
                f"Component {comp.symbol_name} has non-finite x={comp.x}"
            )
            assert math.isfinite(comp.y), (
                f"Component {comp.symbol_name} has non-finite y={comp.y}"
            )

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_busbar_is_set(self, requirements: dict):
        """Busbar Y and X range must be set for every SLD."""
        result = compute_layout(requirements, skip_validation=True)
        assert result.busbar_y > 0, "busbar_y not set"
        assert result.busbar_start_x > 0, "busbar_start_x not set"
        assert result.busbar_end_x > result.busbar_start_x, (
            "busbar_end_x must be greater than busbar_start_x"
        )

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_breaker_count_matches_circuits(self, requirements: dict):
        """Number of breaker blocks should be >= number of requested sub-circuits.

        The engine may add spares via triplet padding (3-phase) or other rules,
        so we check >= rather than ==.
        """
        result = compute_layout(requirements, skip_validation=True)
        n_circuits = len(requirements["sub_circuits"])
        breakers = [c for c in result.components if c.label_style == "breaker_block"]
        assert len(breakers) >= n_circuits, (
            f"Expected >= {n_circuits} breaker blocks, got {len(breakers)}"
        )

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_connection_endpoints_are_finite(self, requirements: dict):
        """All connection line endpoints must be finite numbers."""
        result = compute_layout(requirements, skip_validation=True)
        for idx, ((x1, y1), (x2, y2)) in enumerate(result.connections):
            assert math.isfinite(x1) and math.isfinite(y1), (
                f"Connection {idx} start ({x1}, {y1}) not finite"
            )
            assert math.isfinite(x2) and math.isfinite(y2), (
                f"Connection {idx} end ({x2}, {y2}) not finite"
            )

    @pytest.mark.parametrize("requirements", SCENARIOS)
    def test_deterministic_output(self, requirements: dict):
        """Same input must produce identical output (no randomness)."""
        r1 = compute_layout(requirements, skip_validation=True)
        r2 = compute_layout(requirements, skip_validation=True)
        assert len(r1.components) == len(r2.components)
        for c1, c2 in zip(r1.components, r2.components):
            assert abs(c1.x - c2.x) < 0.01, (
                f"Non-deterministic x for {c1.symbol_name}: {c1.x} vs {c2.x}"
            )
            assert abs(c1.y - c2.y) < 0.01, (
                f"Non-deterministic y for {c1.symbol_name}: {c1.y} vs {c2.y}"
            )


class TestRealWorldWithValidation:
    """Run selected scenarios through the full validation pipeline (no skip)."""

    @pytest.mark.parametrize("requirements", [
        # Pick a representative from each supply/metering combination
        SCENARIOS[0],   # 32A 1PH landlord
        SCENARIOS[2],   # 40A 3PH landlord
        SCENARIOS[5],   # 63A 3PH building riser
        SCENARIOS[6],   # 80A 3PH sp_powergrid
        SCENARIOS[9],   # 125A 3PH CT metering
        SCENARIOS[13],  # 400A 3PH CT metering
    ])
    def test_validation_pipeline_succeeds(self, requirements: dict):
        """Full validation + layout must succeed for representative scenarios."""
        result = compute_layout(requirements, skip_validation=False)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0


class TestRealWorldWithApplicationInfo:
    """Verify application_info (title block data) does not break layout."""

    APP_INFO = {
        "drawing_no": "DWG-2026-001",
        "client_name": "ORCHARD TOWER PTE LTD",
        "client_address": "123 ORCHARD ROAD #01-01 SINGAPORE 238858",
        "lew_name": "TAN AH KOW",
        "lew_licence_no": "LEW/2024/12345",
    }

    def test_small_db_with_app_info(self):
        """32A single-phase with full application info."""
        reqs = SCENARIOS[0].values[0]  # 32A 1PH
        result = compute_layout(reqs, application_info=self.APP_INFO, skip_validation=True)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0

    def test_large_db_with_app_info(self):
        """400A three-phase with full application info."""
        reqs = SCENARIOS[13].values[0]  # 400A 3PH
        result = compute_layout(reqs, application_info=self.APP_INFO, skip_validation=True)
        assert isinstance(result, LayoutResult)
        assert len(result.components) > 0
