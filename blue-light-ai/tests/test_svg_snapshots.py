"""
SVG snapshot regression tests for SLD generation.

Generates SVG output for 11 representative configurations and compares
against golden files. Differences indicate visual regressions.

Coverage:
- Single-phase: direct metering, SP meter, landlord (no meter)
- Three-phase: direct metering, CT metering, landlord, ISOLATOR,
  ELCB/RCCB, ditto marks, cable extension, many circuits (27)

Usage:
    pytest tests/test_svg_snapshots.py -v           # Compare against golden files
    UPDATE_SNAPSHOTS=1 pytest tests/test_svg_snapshots.py -v  # Regenerate golden files

Golden files are stored in tests/snapshots/*.svg.
"""

import os
import re
from pathlib import Path

import pytest

from app.sld.generator import SldPipeline

SNAPSHOT_DIR = Path(__file__).parent / "snapshots"


def _normalize_svg(svg: str) -> str:
    """Remove non-deterministic content from SVG for stable comparison.

    Strips:
    - Timestamps (ISO 8601, epoch)
    - Random/unique IDs (uuid, generated IDs)
    - Version strings that may change
    """
    # Remove timestamp patterns (e.g., 2026-03-10T12:34:56)
    svg = re.sub(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[^\s\"'<]*", "TIMESTAMP", svg)
    # Remove epoch timestamps (e.g., 1710000000)
    svg = re.sub(r"\b1[67]\d{8}\b", "EPOCH", svg)
    # Remove human-readable dates in title block (e.g., "10 MAR 2026")
    svg = re.sub(r"\d{1,2} [A-Z]{3} \d{4}", "DATE", svg)
    # Normalize floating point precision (avoid 0.0000001 vs 0.0 diffs)
    svg = re.sub(r"(\d+\.\d{4})\d+", r"\1", svg)
    return svg


def _generate_svg(requirements: dict) -> str:
    """Generate SVG from requirements using the full pipeline."""
    svg_string = SldPipeline().run(
        requirements, backend_type="pdf",
    ).svg_string
    return svg_string


# =========================================================================
# Test configurations (5 representative SLD types)
# =========================================================================

SINGLE_PHASE_3CKT = {
    "supply_type": "single_phase",
    "kva": 14,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "direct",
    "sub_circuits": [
        {"name": "Lights", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_6CKT = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "direct",
    "sub_circuits": [
        {"name": "Lights 1", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Lights 2", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Lights 3", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "13A S/S/O 1", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "13A S/S/O 2", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_CT_9CKT = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "ct_meter",
    "ct_ratio": "200/5A",
    "sub_circuits": [
        {"name": "Lights 1", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Lights 2", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Lights 3", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "13A S/S/O 1", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "13A S/S/O 2", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "13A S/S/O 3", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "Water Heater", "breaker_type": "MCB", "breaker_rating": 32},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_LANDLORD_12CKT = {
    "supply_type": "three_phase",
    "kva": 69,
    "voltage": 400,
    "supply_source": "landlord",
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "sub_circuits": [
        {"name": f"Circuit {i+1}", "breaker_type": "MCB", "breaker_rating": 20}
        for i in range(12)
    ],
}

THREE_PHASE_ISOLATOR = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "direct",
    "sub_circuits": [
        {"name": "Lights", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "AC Isolator", "breaker_type": "ISOLATOR", "breaker_rating": 32},
        {"name": "13A Power", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

# =========================================================================
# Additional configurations (C4: expanded snapshot coverage)
# =========================================================================

SINGLE_PHASE_LANDLORD_2CKT = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "supply_source": "landlord",
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2C 1.5sqmm PVC/PVC"},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2C 4.0sqmm PVC/PVC"},
    ],
}

SINGLE_PHASE_SP_METER_3CKT = {
    "supply_type": "single_phase",
    "kva": 14,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 63, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2C 1.5sqmm PVC/PVC"},
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2C 4.0sqmm PVC/PVC"},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_ELCB_6CKT = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "sp_meter",
    "elcb": {"rating": 63, "sensitivity_ma": 100, "poles": 4, "type": "ELCB"},
    "sub_circuits": [
        {"name": "Lighting 1", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"name": "Lighting 2", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"name": "Lighting 3", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC"},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

CABLE_EXTENSION_3PHASE_6CKT = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "is_cable_extension": True,
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "sub_circuits": [
        {"name": "Lights", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Power 1", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "Power 2", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "Aircon 1", "breaker_type": "MCB", "breaker_rating": 32},
        {"name": "Aircon 2", "breaker_type": "MCB", "breaker_rating": 32},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_DITTO_9CKT = {
    "supply_type": "three_phase",
    "kva": 45,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "direct",
    "sub_circuits": [
        # 3 identical lighting circuits → ditto marks expected
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN PVC CONDUIT"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN PVC CONDUIT"},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm CPC IN PVC CONDUIT"},
        # 3 identical power circuits → ditto marks expected
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN PVC CONDUIT"},
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN PVC CONDUIT"},
        {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN PVC CONDUIT"},
        # 3 mixed circuits → no ditto (different specs)
        {"name": "Water Heater", "breaker_type": "MCB", "breaker_rating": 32,
         "cable": "2 x 1C 6sqmm PVC + 4sqmm CPC IN PVC CONDUIT"},
        {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 20,
         "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC IN PVC CONDUIT"},
        {"name": "SPARE", "breaker_type": "MCB", "breaker_rating": 20},
    ],
}

THREE_PHASE_MANY_27CKT = {
    "supply_type": "three_phase",
    "kva": 60,
    "voltage": 400,
    "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 25},
    "busbar_rating": 200,
    "metering": "ct_meter",
    "ct_ratio": "200/5A",
    "sub_circuits": [
        {"name": f"Circuit {i+1}", "breaker_type": "MCB",
         "breaker_rating": 20, "cable": "2 x 1C 2.5sqmm PVC + 2.5sqmm CPC"}
        for i in range(27)
    ],
}

SNAPSHOT_CONFIGS = [
    # Original 5
    ("single_phase_metered_3ckt", SINGLE_PHASE_3CKT),
    ("three_phase_metered_6ckt", THREE_PHASE_6CKT),
    ("three_phase_ct_9ckt", THREE_PHASE_CT_9CKT),
    ("three_phase_landlord_12ckt", THREE_PHASE_LANDLORD_12CKT),
    ("three_phase_isolator", THREE_PHASE_ISOLATOR),
    # C4: 6 additional scenarios
    ("single_phase_landlord_2ckt", SINGLE_PHASE_LANDLORD_2CKT),
    ("single_phase_sp_meter_3ckt", SINGLE_PHASE_SP_METER_3CKT),
    ("three_phase_elcb_6ckt", THREE_PHASE_ELCB_6CKT),
    ("cable_extension_3phase_6ckt", CABLE_EXTENSION_3PHASE_6CKT),
    ("three_phase_ditto_9ckt", THREE_PHASE_DITTO_9CKT),
    ("three_phase_many_27ckt", THREE_PHASE_MANY_27CKT),
]


@pytest.fixture(params=SNAPSHOT_CONFIGS, ids=[c[0] for c in SNAPSHOT_CONFIGS])
def snapshot_config(request):
    """Parametrized fixture yielding (name, requirements) tuples."""
    return request.param


class TestSvgSnapshots:
    """SVG golden file regression tests."""

    def test_snapshot_match(self, snapshot_config):
        """Generated SVG should match golden file (or create it on first run)."""
        name, requirements = snapshot_config
        golden_path = SNAPSHOT_DIR / f"{name}.svg"
        update = os.environ.get("UPDATE_SNAPSHOTS", "0") == "1"

        svg = _generate_svg(requirements)
        normalized = _normalize_svg(svg)

        if update or not golden_path.exists():
            golden_path.write_text(normalized, encoding="utf-8")
            pytest.skip(f"Golden file {'updated' if update else 'created'}: {golden_path.name}")

        golden = golden_path.read_text(encoding="utf-8")
        if normalized != golden:
            # Find first difference for debugging
            for i, (a, b) in enumerate(zip(normalized, golden)):
                if a != b:
                    context_start = max(0, i - 50)
                    actual_ctx = normalized[context_start:i + 50]
                    golden_ctx = golden[context_start:i + 50]
                    pytest.fail(
                        f"SVG snapshot mismatch for {name} at char {i}:\n"
                        f"  actual:  ...{actual_ctx}...\n"
                        f"  golden:  ...{golden_ctx}...\n"
                        f"Run with UPDATE_SNAPSHOTS=1 to regenerate."
                    )
            if len(normalized) != len(golden):
                pytest.fail(
                    f"SVG snapshot length mismatch for {name}: "
                    f"actual={len(normalized)}, golden={len(golden)}\n"
                    f"Run with UPDATE_SNAPSHOTS=1 to regenerate."
                )

    def test_svg_is_valid(self, snapshot_config):
        """Generated SVG should be well-formed."""
        _, requirements = snapshot_config
        svg = _generate_svg(requirements)
        assert svg.startswith("<?xml") or svg.startswith("<svg")
        assert "</svg>" in svg

    def test_svg_has_content(self, snapshot_config):
        """Generated SVG should contain meaningful drawing elements."""
        _, requirements = snapshot_config
        svg = _generate_svg(requirements)
        assert "<line" in svg or "<path" in svg
        assert "<text" in svg or "mtext" in svg.lower()
