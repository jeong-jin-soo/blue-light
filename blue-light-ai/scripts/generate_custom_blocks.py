#!/usr/bin/env python3
"""프로시저럴 심볼을 DXF 블록으로 변환하여 블록 라이브러리에 등록.

real_symbol_paths.json 캘리브레이션 데이터와 real_symbols.py의 draw() 로직을 기반으로
ezdxf 블록 정의(엔티티 JSON)를 프로그래매틱 생성한다.

Usage:
    python scripts/generate_custom_blocks.py
    # → dxf_block_library.json의 custom_blocks 업데이트
    # → sld_block_library.dxf에 블록 추가

    python scripts/generate_custom_blocks.py --dry-run
    # → 변경사항 미리보기만 출력
"""

import argparse
import json
import math
import sys
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# DU/mm scale factor — derived from MCCB block (height_du=597.82, height_mm=9.0)
# This matches the existing blocks in the library.
_DU_PER_MM = 597.82 / 9.0  # ≈66.42 DU per mm


def _load_symbol_dims() -> dict:
    p = Path(__file__).resolve().parent.parent / "data" / "templates" / "real_symbol_paths.json"
    with open(p) as f:
        return json.load(f)


def _load_library() -> dict:
    p = Path(__file__).resolve().parent.parent / "data" / "templates" / "dxf_block_library.json"
    with open(p) as f:
        return json.load(f)


def _save_library(lib: dict) -> None:
    p = Path(__file__).resolve().parent.parent / "data" / "templates" / "dxf_block_library.json"
    with open(p, "w") as f:
        json.dump(lib, f, indent=2, ensure_ascii=False)
    print(f"  Saved: {p}")


def _mm_to_du(mm: float) -> float:
    return mm * _DU_PER_MM


def _compute_pins(bounds: dict) -> dict:
    """Auto-compute 4 pins from bounds."""
    min_x = bounds["min_x"]
    max_x = bounds["max_x"]
    min_y = bounds["min_y"]
    max_y = bounds["max_y"]
    cx = (min_x + max_x) / 2
    cy = (min_y + max_y) / 2
    return {
        "top": [round(cx, 2), round(max_y, 2)],
        "bottom": [round(cx, 2), round(min_y, 2)],
        "left": [round(min_x, 2), round(cy, 2)],
        "right": [round(max_x, 2), round(cy, 2)],
    }


# ---------------------------------------------------------------------------
# Block generators — each returns a block definition dict
# ---------------------------------------------------------------------------

def gen_ammeter(dims: dict) -> dict:
    """AMMETER: circle with letter 'A' — IEC 60617."""
    r = _mm_to_du(dims["radius_mm"])
    entities = [
        {"type": "CIRCLE", "center": [0, 0], "radius": round(r, 2)},
        {"type": "TEXT", "text": "A", "insert": [round(-r * 0.3, 2), round(-r * 0.3, 2)],
         "height": round(r * 1.2, 2)},
    ]
    bounds = {"min_x": -r, "max_x": r, "min_y": -r, "max_y": r}
    return {
        "width_du": round(2 * r, 2),
        "height_du": round(2 * r, 2),
        "bounds": {k: round(v, 2) for k, v in bounds.items()},
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "AMMETER",
    }


def gen_voltmeter_custom(dims: dict) -> dict:
    """VOLTMETER: circle with letter 'V' — identical structure to AMMETER."""
    r = _mm_to_du(dims["radius_mm"])
    entities = [
        {"type": "CIRCLE", "center": [0, 0], "radius": round(r, 2)},
        {"type": "TEXT", "text": "V", "insert": [round(-r * 0.3, 2), round(-r * 0.3, 2)],
         "height": round(r * 1.2, 2)},
    ]
    bounds = {"min_x": -r, "max_x": r, "min_y": -r, "max_y": r}
    return {
        "width_du": round(2 * r, 2),
        "height_du": round(2 * r, 2),
        "bounds": {k: round(v, 2) for k, v in bounds.items()},
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "VOLTMETER",
    }


def gen_selector_switch(dims: dict) -> dict:
    """SELECTOR_SWITCH: circle with diagonal slash (╱) — IEC 60617."""
    r = _mm_to_du(dims["radius_mm"])
    d = r * math.cos(math.radians(45))
    entities = [
        {"type": "CIRCLE", "center": [0, 0], "radius": round(r, 2)},
        {"type": "LINE", "start": [round(d, 2), round(d, 2)],
         "end": [round(-d, 2), round(-d, 2)]},
    ]
    bounds = {"min_x": -r, "max_x": r, "min_y": -r, "max_y": r}
    return {
        "width_du": round(2 * r, 2),
        "height_du": round(2 * r, 2),
        "bounds": {k: round(v, 2) for k, v in bounds.items()},
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "SELECTOR_SWITCH",
    }


def gen_elr(dims: dict) -> dict:
    """ELR: rectangular block with 'ELR' text — IEC earth leakage relay."""
    w = _mm_to_du(dims["width_mm"])
    h = _mm_to_du(dims["height_mm"])
    hw, hh = w / 2, h / 2
    entities = [
        {"type": "LWPOLYLINE",
         "points": [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]],
         "bulges": [0, 0, 0, 0],
         "closed": True},
        {"type": "TEXT", "text": "ELR",
         "insert": [round(-w * 0.2, 2), round(-h * 0.1, 2)],
         "height": round(min(h * 0.35, _mm_to_du(2.5)), 2)},
    ]
    bounds = {"min_x": round(-hw, 2), "max_x": round(hw, 2),
              "min_y": round(-hh, 2), "max_y": round(hh, 2)}
    return {
        "width_du": round(w, 2),
        "height_du": round(h, 2),
        "bounds": bounds,
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "ELR",
    }


def gen_bi_connector(dims: dict) -> dict:
    """BI_CONNECTOR: rectangle with horizontal pass-through line."""
    w = _mm_to_du(dims["width_mm"])
    h = _mm_to_du(dims["height_mm"])
    hw, hh = w / 2, h / 2
    h_ext = w * 0.6  # horizontal extension beyond box (matching real_symbols.py)
    entities = [
        # Rectangle
        {"type": "LWPOLYLINE",
         "points": [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]],
         "bulges": [0, 0, 0, 0],
         "closed": True},
        # Horizontal pass-through line
        {"type": "LINE",
         "start": [round(-hw - h_ext, 2), 0],
         "end": [round(hw + h_ext, 2), 0]},
    ]
    total_w = w + 2 * h_ext
    bounds = {"min_x": round(-hw - h_ext, 2), "max_x": round(hw + h_ext, 2),
              "min_y": round(-hh, 2), "max_y": round(hh, 2)}
    return {
        "width_du": round(total_w, 2),
        "height_du": round(h, 2),
        "bounds": bounds,
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "BI_CONNECTOR",
    }


def gen_fuse(dims: dict) -> dict:
    """FUSE: filled rectangle on conductor — IEC 60617."""
    rw = _mm_to_du(dims["rect_width_mm"])
    rh = _mm_to_du(dims["rect_height_mm"])
    # The rectangle IS the symbol body
    hw, hh = rw / 2, rh / 2
    entities = [
        # Filled rectangle (use LWPOLYLINE, fill handled at render time)
        {"type": "LWPOLYLINE",
         "points": [[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]],
         "bulges": [0, 0, 0, 0],
         "closed": True},
    ]
    bounds = {"min_x": round(-hw, 2), "max_x": round(hw, 2),
              "min_y": round(-hh, 2), "max_y": round(hh, 2)}
    return {
        "width_du": round(rw, 2),
        "height_du": round(rh, 2),
        "bounds": bounds,
        "entities": entities,
        "pins": _compute_pins(bounds),
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "FUSE",
    }


def gen_ct_hook(dims: dict) -> dict:
    """CT (hook): two interlocking semicircular arcs — chain-link style.

    The CT symbol consists of two half-circle arcs that protrude to the right
    of the conductor, interlocking vertically. Uses bulge=-1.0 for semicircles.
    """
    r = _mm_to_du(dims["ring_radius_mm"])
    offset = _mm_to_du(dims["ring_offset_mm"])
    half_d = offset / 2

    # Two semicircular arcs (right-facing hooks)
    # Upper arc: center at (0, +half_d), right-facing semicircle
    # Lower arc: center at (0, -half_d), right-facing semicircle
    entities = [
        # Upper semicircle: from bottom to top, curving right (bulge = -1.0)
        {"type": "LWPOLYLINE",
         "points": [[0, round(half_d - r, 2)], [0, round(half_d + r, 2)]],
         "bulges": [-1.0, 0],
         "closed": False},
        # Lower semicircle: from bottom to top, curving right (bulge = -1.0)
        {"type": "LWPOLYLINE",
         "points": [[0, round(-half_d - r, 2)], [0, round(-half_d + r, 2)]],
         "bulges": [-1.0, 0],
         "closed": False},
    ]
    total_h = 2 * (half_d + r)
    bounds = {"min_x": 0, "max_x": round(2 * r, 2),
              "min_y": round(-(half_d + r), 2), "max_y": round(half_d + r, 2)}
    return {
        "width_du": round(2 * r, 2),
        "height_du": round(total_h, 2),
        "bounds": bounds,
        "entities": entities,
        "pins": {
            "top": [0, round(half_d + r, 2)],
            "bottom": [0, round(-(half_d + r), 2)],
        },
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "CT",
    }


def gen_acb(dims: dict) -> dict:
    """ACB: circuit breaker with horizontal crossbar — Air Circuit Breaker.

    Same as MCCB but with additional horizontal bar through center.
    """
    ar = _mm_to_du(dims["arc_radius_mm"])
    cr = _mm_to_du(dims["contact_radius_mm"])
    h = _mm_to_du(dims["height_mm"])
    w = _mm_to_du(dims["width_mm"])
    crossbar = _mm_to_du(dims.get("crossbar_extend_mm", 1.5))

    hh = h / 2
    hw = w / 2

    # Arc angles (same as MCCB)
    arc_start = dims.get("arc_start_deg", 297.1)
    arc_end = dims.get("arc_end_deg", 62.9)

    # Use LWPOLYLINE with bulge for arc (same as MCCB block)
    # The MCCB block in the library uses a LWPOLYLINE with bulge=0.611 for the arc
    # For ACB we'll use individual entities for clarity
    entities = [
        # Bottom contact circle
        {"type": "CIRCLE", "center": [0, round(-ar, 2)], "radius": round(cr, 2)},
        # Top contact circle
        {"type": "CIRCLE", "center": [0, round(ar, 2)], "radius": round(cr, 2)},
        # Arc (right-facing) — use ARC entity
        {"type": "ARC",
         "center": [0, 0],
         "radius": round(ar, 2),
         "start_angle": arc_start,
         "end_angle": arc_end},
        # Horizontal crossbar (distinctive ACB feature)
        {"type": "LINE",
         "start": [round(-(ar + crossbar), 2), 0],
         "end": [round(ar + crossbar, 2), 0]},
    ]

    ext = ar + crossbar
    bounds = {"min_x": round(-ext, 2), "max_x": round(ext, 2),
              "min_y": round(-ar - cr, 2), "max_y": round(ar + cr, 2)}
    return {
        "width_du": round(2 * ext, 2),
        "height_du": round(2 * (ar + cr), 2),
        "bounds": bounds,
        "entities": entities,
        "pins": {
            "top": [0, round(ar + cr, 2)],
            "bottom": [0, round(-(ar + cr), 2)],
            "left": [round(-ext, 2), 0],
            "right": [round(ext, 2), 0],
        },
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "ACB",
    }


def gen_indicator_lights(dims: dict) -> dict:
    """INDICATOR_LIGHTS: 3 circles with 4 radial rays each, connected horizontally."""
    r = _mm_to_du(dims["circle_radius_mm"])
    spacing = _mm_to_du(dims.get("circle_spacing_mm", 6.0))
    count = dims.get("circle_count", 3)
    ray_len = _mm_to_du(1.0)

    entities = []
    cos45 = 0.7071
    centers = []

    for i in range(count):
        cx = r + i * spacing
        centers.append(cx)

        # Circle
        entities.append({"type": "CIRCLE", "center": [round(cx, 2), 0], "radius": round(r, 2)})

        # 4 radial rays at 45 degrees
        for dx_s, dy_s in [(1, 1), (-1, 1), (1, -1), (-1, -1)]:
            sx = cx + dx_s * r * cos45
            sy = dy_s * r * cos45
            ex = cx + dx_s * (r + ray_len) * cos45
            ey = dy_s * (r + ray_len) * cos45
            entities.append({"type": "LINE",
                             "start": [round(sx, 2), round(sy, 2)],
                             "end": [round(ex, 2), round(ey, 2)]})

    # Inter-connections between circles
    for i in range(count - 1):
        entities.append({"type": "LINE",
                         "start": [round(centers[i] + r, 2), 0],
                         "end": [round(centers[i + 1] - r, 2), 0]})

    total_w = centers[-1] + r + ray_len * cos45
    total_h = 2 * (r + ray_len * cos45)
    bounds = {"min_x": round(-ray_len * cos45, 2),
              "max_x": round(total_w, 2),
              "min_y": round(-(r + ray_len * cos45), 2),
              "max_y": round(r + ray_len * cos45, 2)}
    return {
        "width_du": round(total_w + ray_len * cos45, 2),
        "height_du": round(total_h, 2),
        "bounds": bounds,
        "entities": entities,
        "pins": {
            "left": [0, 0],
            "right": [round(total_w, 2), 0],
        },
        "_generator": "generate_custom_blocks.py",
        "_source_symbol": "INDICATOR_LIGHTS",
    }


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

GENERATORS = {
    "AMMETER": gen_ammeter,
    "VOLTMETER_CUSTOM": gen_voltmeter_custom,
    "SELECTOR_SWITCH": gen_selector_switch,
    "ELR": gen_elr,
    "BI_CONNECTOR": gen_bi_connector,
    "FUSE": gen_fuse,
    "CT": gen_ct_hook,
    "ACB": gen_acb,
    "INDICATOR_LIGHTS_CUSTOM": gen_indicator_lights,
}


def main():
    parser = argparse.ArgumentParser(description="Generate custom DXF blocks")
    parser.add_argument("--dry-run", action="store_true", help="Preview only, don't save")
    args = parser.parse_args()

    dims = _load_symbol_dims()
    lib = _load_library()

    if "custom_blocks" not in lib:
        lib["custom_blocks"] = {}

    generated = []
    for block_name, gen_fn in GENERATORS.items():
        # Map generator key to symbol dimension key
        dim_key = block_name
        if block_name == "VOLTMETER_CUSTOM":
            dim_key = "VOLTMETER"
        elif block_name == "INDICATOR_LIGHTS_CUSTOM":
            dim_key = "INDICATOR_LIGHTS"

        if dim_key not in dims:
            print(f"  SKIP {block_name}: no dimensions in real_symbol_paths.json")
            continue

        block_def = gen_fn(dims[dim_key])
        ent_count = len(block_def.get("entities", []))
        w = block_def["width_du"]
        h = block_def["height_du"]

        existing = lib["custom_blocks"].get(block_name)
        action = "UPDATE" if existing else "CREATE"

        print(f"  {action} {block_name}: {w:.0f}×{h:.0f} DU, {ent_count} entities")
        generated.append(block_name)

        if not args.dry_run:
            lib["custom_blocks"][block_name] = block_def

    if not args.dry_run and generated:
        # Update meta
        lib["_meta"]["last_updated"] = datetime.now().isoformat(timespec="seconds")
        lib["_meta"]["total_blocks"] = len(lib.get("blocks", {})) + len(lib.get("custom_blocks", {}))
        _save_library(lib)

    print(f"\n{'DRY RUN — ' if args.dry_run else ''}Generated {len(generated)} custom blocks:")
    for name in generated:
        print(f"  - {name}")

    # Summary
    total_extracted = len(lib.get("blocks", {}))
    total_custom = len(lib.get("custom_blocks", {}))
    print(f"\nLibrary total: {total_extracted} extracted + {total_custom} custom = {total_extracted + total_custom} blocks")


if __name__ == "__main__":
    main()
