"""
Voltage drop calculation for SLD circuits.

References:
- SS 638:2018 Table 6.1 — maximum voltage drop limits
- IEC 60228 — conductor resistance at 70°C operating temperature
- CP 5:2018 — cable installation methods

Limits (SS 638):
- Distribution circuit (DB → final sub-circuit): 2.5% of nominal voltage
- From origin (meter) to final point: 4% total

For SLD validation, we check the distribution circuit VD (2.5% limit)
since the SLD covers DB → sub-circuits.
"""

from __future__ import annotations

import math

# ---------------------------------------------------------------------------
# Cable impedance data — conductor resistance (mΩ/m) at 70°C
# Source: IEC 60228 / BS 7671 Table 4D1B (single-phase) / 4D4B (three-phase)
# Values for copper conductors, PVC insulated, at operating temperature 70°C.
# ---------------------------------------------------------------------------

# Resistance (mΩ/m) per conductor at 70°C — copper PVC
_RESISTANCE_PVC: dict[float, float] = {
    1.0:  22.0,
    1.5:  14.5,
    2.5:   8.71,
    4.0:   5.45,
    6.0:   3.63,
    10.0:  2.16,
    16.0:  1.35,
    25.0:  0.863,
    35.0:  0.627,
    50.0:  0.473,
    70.0:  0.321,
    95.0:  0.236,
    120.0: 0.188,
    150.0: 0.153,
    185.0: 0.123,
    240.0: 0.0943,
    300.0: 0.0761,
    400.0: 0.0601,
    500.0: 0.0470,
}

# Resistance (mΩ/m) per conductor at 90°C — copper XLPE
_RESISTANCE_XLPE: dict[float, float] = {
    1.5:  15.5,
    2.5:   9.30,
    4.0:   5.82,
    6.0:   3.87,
    10.0:  2.31,
    16.0:  1.44,
    25.0:  0.921,
    35.0:  0.669,
    50.0:  0.505,
    70.0:  0.342,
    95.0:  0.252,
    120.0: 0.200,
    150.0: 0.163,
    185.0: 0.131,
    240.0: 0.101,
    300.0: 0.0812,
}

# Reactance (mΩ/m) — approximately constant for all sizes (flat/trefoil)
_REACTANCE: float = 0.08  # mΩ/m (typical for LV cables)


def _get_resistance(cable_size_mm2: float, cable_type: str = "PVC") -> float:
    """Get conductor resistance in mΩ/m at operating temperature.

    Returns the value for the exact size or the next larger standard size.
    """
    table = _RESISTANCE_XLPE if "XLPE" in cable_type.upper() else _RESISTANCE_PVC
    if cable_size_mm2 in table:
        return table[cable_size_mm2]
    # Find next larger size
    for size in sorted(table.keys()):
        if size >= cable_size_mm2:
            return table[size]
    # Larger than any in table — use largest
    return table[max(table.keys())]


def calc_voltage_drop(
    cable_size_mm2: float,
    length_m: float,
    current_a: float,
    voltage: float = 230.0,
    *,
    phase: str = "single_phase",
    cable_type: str = "PVC",
    power_factor: float = 0.8,
) -> dict:
    """Calculate voltage drop for a circuit.

    Formula (single-phase):
        VD = 2 × I × L × (R cos φ + X sin φ) / 1000  [volts]

    Formula (three-phase):
        VD = √3 × I × L × (R cos φ + X sin φ) / 1000  [volts]

    Args:
        cable_size_mm2: Conductor cross-section area in mm².
        length_m: Cable route length in metres (one-way).
        current_a: Design current in amperes.
        voltage: Nominal voltage (230V single-phase, 400V three-phase).
        phase: "single_phase" or "three_phase".
        cable_type: "PVC" or "XLPE" (affects resistance).
        power_factor: Load power factor (default 0.8).

    Returns:
        dict with keys:
        - vd_volts: Voltage drop in volts
        - vd_percent: Voltage drop as percentage of nominal voltage
        - max_percent: Maximum allowed percentage (SS 638)
        - pass: True if within limit
        - message: Human-readable result string
    """
    r = _get_resistance(cable_size_mm2, cable_type)  # mΩ/m
    x = _REACTANCE  # mΩ/m

    cos_phi = power_factor
    sin_phi = math.sqrt(1 - cos_phi ** 2)

    z_eff = r * cos_phi + x * sin_phi  # mΩ/m effective impedance

    if phase == "three_phase":
        multiplier = math.sqrt(3)
        max_pct = 2.5  # SS 638 distribution circuit limit
        if voltage == 230.0:
            voltage = 400.0  # auto-correct for 3-phase
    else:
        multiplier = 2.0  # go + return
        max_pct = 2.5  # SS 638 distribution circuit limit

    vd_volts = multiplier * current_a * length_m * z_eff / 1000.0
    vd_percent = (vd_volts / voltage) * 100.0

    passed = vd_percent <= max_pct

    if passed:
        message = f"VD {vd_percent:.1f}% ({vd_volts:.1f}V) — OK (≤{max_pct}%)"
    else:
        message = (
            f"VD {vd_percent:.1f}% ({vd_volts:.1f}V) — EXCEEDS {max_pct}% limit. "
            f"Consider larger cable or shorter route."
        )

    return {
        "vd_volts": round(vd_volts, 2),
        "vd_percent": round(vd_percent, 2),
        "max_percent": max_pct,
        "pass": passed,
        "message": message,
    }


def calc_circuit_voltage_drop(
    breaker_rating_a: int,
    cable_size_mm2: float,
    cable_length_m: float,
    *,
    phase: str = "single_phase",
    cable_type: str = "PVC",
    power_factor: float = 0.8,
) -> dict:
    """Convenience wrapper: calculate VD using breaker rating as design current.

    For sub-circuits, the design current is typically the breaker rating.
    """
    voltage = 400.0 if phase == "three_phase" else 230.0
    return calc_voltage_drop(
        cable_size_mm2=cable_size_mm2,
        length_m=cable_length_m,
        current_a=float(breaker_rating_a),
        voltage=voltage,
        phase=phase,
        cable_type=cable_type,
        power_factor=power_factor,
    )
