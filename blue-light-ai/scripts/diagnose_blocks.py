#!/usr/bin/env python3
"""DXF 블록 라이브러리 진단 도구.

기존 블록의 실제 렌더링 형상을 SVG로 시각화하고,
프로시저럴 심볼과 나란히 비교하는 HTML 리포트를 생성한다.

Usage:
    python scripts/diagnose_blocks.py
    # → output/block_diagnosis.html 생성
"""

import json
import math
import sys
from pathlib import Path

# Add project root to path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.sld.block_replayer import BlockReplayer


def _entity_to_svg(entity: dict, scale: float, ox: float, oy: float,
                   max_y: float) -> str:
    """Convert a single block entity to SVG element string."""
    etype = entity.get("type", "")
    # Flip Y for SVG (DXF Y-up → SVG Y-down)
    def tx(x, y):
        return ox + x * scale, oy + (max_y - y) * scale

    if etype == "LINE":
        x1, y1 = tx(entity["start"][0], entity["start"][1])
        x2, y2 = tx(entity["end"][0], entity["end"][1])
        return f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" stroke="black" stroke-width="1"/>'

    elif etype == "CIRCLE":
        cx, cy = tx(entity["center"][0], entity["center"][1])
        r = entity["radius"] * scale
        return f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" fill="none" stroke="black" stroke-width="1"/>'

    elif etype == "ARC":
        cx, cy_raw = entity["center"]
        r = entity["radius"] * scale
        start_a = math.radians(entity["start_angle"])
        end_a = math.radians(entity["end_angle"])
        # SVG arc
        sx = cx + entity["radius"] * math.cos(start_a)
        sy = cy_raw + entity["radius"] * math.sin(start_a)
        ex = cx + entity["radius"] * math.cos(end_a)
        ey = cy_raw + entity["radius"] * math.sin(end_a)
        sx_t, sy_t = tx(sx, sy)
        ex_t, ey_t = tx(ex, ey)
        sweep = (entity["end_angle"] - entity["start_angle"]) % 360
        large = 1 if sweep > 180 else 0
        return f'<path d="M {sx_t:.1f},{sy_t:.1f} A {r:.1f},{r:.1f} 0 {large},0 {ex_t:.1f},{ey_t:.1f}" fill="none" stroke="black" stroke-width="1"/>'

    elif etype == "LWPOLYLINE":
        points = entity.get("points", [])
        bulges = entity.get("bulges", [])
        closed = entity.get("closed", False)
        if not points:
            return ""

        # Simple polyline (no bulge handling for diagnosis)
        parts = []
        for i, pt in enumerate(points):
            px, py = tx(pt[0], pt[1])
            cmd = "M" if i == 0 else "L"
            parts.append(f"{cmd} {px:.1f},{py:.1f}")
        if closed:
            parts.append("Z")
        return f'<path d="{" ".join(parts)}" fill="none" stroke="black" stroke-width="1"/>'

    elif etype == "TEXT":
        px, py = tx(entity["insert"][0], entity["insert"][1])
        h = entity.get("height", 100) * scale
        text = entity.get("text", "")
        return f'<text x="{px:.1f}" y="{py:.1f}" font-size="{max(h, 8):.0f}" fill="black">{text}</text>'

    return ""


def render_block_svg(block_def: dict, size: int = 120) -> str:
    """Render a block definition as inline SVG."""
    entities = block_def.get("entities", [])
    if not entities:
        return '<svg width="120" height="120"><text x="10" y="60" fill="red">No entities</text></svg>'

    bounds = block_def.get("bounds", {})
    min_x = bounds.get("min_x", 0)
    min_y = bounds.get("min_y", 0)
    max_x = bounds.get("max_x", 100)
    max_y = bounds.get("max_y", 100)

    w = max_x - min_x or 1
    h = max_y - min_y or 1
    margin = 10
    scale = (size - 2 * margin) / max(w, h)

    svg_parts = [f'<svg width="{size}" height="{size}" viewBox="0 0 {size} {size}">']
    svg_parts.append(f'<rect width="{size}" height="{size}" fill="#f8f8f8" stroke="#ddd"/>')

    for ent in entities:
        svg_parts.append(_entity_to_svg(ent, scale, margin - min_x * scale, margin, max_y))

    svg_parts.append("</svg>")
    return "\n".join(svg_parts)


def main():
    replayer = BlockReplayer.load()

    # Load library JSON directly for diagnosis
    lib_path = Path(__file__).resolve().parent.parent / "data" / "templates" / "dxf_block_library.json"
    with open(lib_path) as f:
        library = json.load(f)

    all_blocks = {}
    all_blocks.update(library.get("blocks", {}))
    all_blocks.update(library.get("custom_blocks", {}))

    # Load symbol mapping
    from app.sld.symbol import SYMBOL_TO_DXF_BLOCK

    # Build reverse map: block_name → symbol_names
    block_to_symbols: dict[str, list[str]] = {}
    for sym, blk in SYMBOL_TO_DXF_BLOCK.items():
        block_to_symbols.setdefault(blk, []).append(sym)

    # Known issues
    known_issues = {
        "SLD-CT": "직선 렌더링, hook 형태가 아님",
        "SS": "dual-arc 렌더링, circle+slash가 아님",
        "EF": "치수 불일치 (ELR 프로시저럴 대비)",
        "LED IND LTG": "치수 불일치",
        "VOLTMETER": "AMMETER 미존재로 일관성 미비",
        "2A FUSE": "POTENTIAL_FUSE로 매핑 안 됨",
        "3P SOCKET": "Cable extension 전용, 일반 SLD에서 미사용",
    }

    # Generate HTML report
    html = ["""<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>DXF Block Diagnosis</title>
<style>
body { font-family: sans-serif; margin: 20px; }
table { border-collapse: collapse; width: 100%; }
th, td { border: 1px solid #ccc; padding: 8px; text-align: left; vertical-align: top; }
th { background: #f0f0f0; }
.active { background: #e8f5e9; }
.unused { background: #fff3e0; }
.missing { background: #ffebee; }
.issue { color: #e65100; font-size: 0.85em; }
</style></head><body>
<h1>DXF Block Library Diagnosis</h1>
<p>Generated from dxf_block_library.json</p>

<h2>Block Inventory</h2>
<table>
<tr><th>Block Name</th><th>Size (DU)</th><th>Entities</th><th>SVG Preview</th><th>Status</th><th>Mapped Symbols</th><th>Notes</th></tr>
"""]

    for name in sorted(all_blocks.keys()):
        block = all_blocks[name]
        w = block.get("width_du", 0)
        h = block.get("height_du", 0)
        ent_count = len(block.get("entities", []))
        source = block.get("source_file", "custom")

        svg = render_block_svg(block, size=100)
        mapped = block_to_symbols.get(name, [])
        issue = known_issues.get(name, "")

        if mapped:
            cls = "active"
            status = "Active"
        elif issue:
            cls = "unused"
            status = "Unused"
        else:
            cls = "unused"
            status = "Unmapped"

        html.append(f"""<tr class="{cls}">
<td><b>{name}</b><br><small>{source}</small></td>
<td>{w:.0f} × {h:.0f}</td>
<td>{ent_count}</td>
<td>{svg}</td>
<td>{status}</td>
<td>{", ".join(mapped) if mapped else "-"}</td>
<td class="issue">{issue}</td>
</tr>""")

    html.append("</table>")

    # Symbols without blocks
    html.append("""
<h2>Procedural-Only Symbols (No DXF Block)</h2>
<table>
<tr><th>Symbol Name</th><th>Calibrated Size</th><th>Reason</th></tr>
""")

    # Load real_symbol_paths for dimensions
    sym_path = Path(__file__).resolve().parent.parent / "data" / "templates" / "real_symbol_paths.json"
    with open(sym_path) as f:
        sym_dims = json.load(f)

    procedural_only = [
        ("CT", "SLD-CT 블록이 hook 형태가 아님"),
        ("SELECTOR_SWITCH", "SS 블록이 dual-arc"),
        ("ELR", "EF 블록 치수 불일치"),
        ("INDICATOR_LIGHTS", "LED IND LTG 치수 불일치"),
        ("AMMETER", "레퍼런스 DXF에 블록 미존재"),
        ("BI_CONNECTOR", "레퍼런스 DXF에 블록 미존재"),
        ("FUSE", "레퍼런스 DXF에 블록 미존재"),
        ("POTENTIAL_FUSE", "2A FUSE 블록 존재하나 매핑 안 됨"),
        ("ACB", "레퍼런스 DXF에 블록 미존재"),
        ("VOLTMETER", "AMMETER와 일관성 위해 제외됨"),
    ]

    for sym, reason in procedural_only:
        dims = sym_dims.get(sym, {})
        w = dims.get("width_mm", dims.get("radius_mm", "?"))
        h = dims.get("height_mm", dims.get("radius_mm", "?"))
        if isinstance(w, (int, float)) and isinstance(h, (int, float)):
            size_str = f"{w} × {h} mm"
        else:
            size_str = f"r={w} mm" if "radius_mm" in dims else "?"

        html.append(f"""<tr class="missing">
<td><b>{sym}</b></td>
<td>{size_str}</td>
<td>{reason}</td>
</tr>""")

    html.append("""</table>

<h2>Summary</h2>
""")

    active = sum(1 for n in all_blocks if block_to_symbols.get(n))
    total = len(all_blocks)
    html.append(f"<p>Library: {total} blocks ({active} active, {total - active} unused)</p>")
    html.append(f"<p>Procedural-only: {len(procedural_only)} symbols need conversion</p>")
    html.append("</body></html>")

    output_dir = Path(__file__).resolve().parent.parent / "output"
    output_dir.mkdir(exist_ok=True)
    out_path = output_dir / "block_diagnosis.html"
    out_path.write_text("\n".join(html))
    print(f"Report generated: {out_path}")


if __name__ == "__main__":
    main()
