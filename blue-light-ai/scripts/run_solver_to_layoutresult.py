"""End-to-end demo: CP-SAT solver → LayoutResult → 기존 DXF 렌더 파이프라인.

Phase 1 어댑터(`app.sld.solver.adapter`)가 솔버의 placement 결과를
기존 PlacedComponent/PortConnection 그래프로 변환할 수 있는지를 검증한다.

출력
----
- poc/cpsat_sld/adapter_demo.dxf
- poc/cpsat_sld/adapter_demo.pdf
- poc/cpsat_sld/adapter_demo.svg

Usage:
    python scripts/run_solver_to_layoutresult.py
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.dxf_backend import DxfBackend
from app.sld.generator import SldPipeline
from app.sld.layout.models import LayoutConfig
from app.sld.page_config import A3_LANDSCAPE
from app.sld.solver import adapt_to_layout_result, place_layout
from app.sld.solver.scenario import build_scene
from app.sld.title_block import (
    TitleBlockConfig, draw_border, draw_title_block_frame, fill_title_block_data,
)


def main() -> int:
    logging.basicConfig(level=logging.INFO,
                        format="%(levelname)s %(name)s: %(message)s")

    requirements = {
        "supply_type": "three_phase",
        "voltage": 400,
        "metering": {"type": "sp_meter"},
        "main_breaker": {
            "type": "MCCB", "rating": 63, "poles": "TPN",
            "fault_kA": 10, "characteristic": "Type B",
        },
        "elcb": {
            "type": "RCCB", "rating": 63, "poles": "4P", "sensitivity_mA": 30,
        },
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "supply_label": "FROM LANDLORD SUPPLY",
        "meter_label": "KWH METER BY SP",
        "earth_label": "EARTH BAR 35mm² CPC",
        "sub_circuits": [
            {"id": "C1", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Lighting"},
            {"id": "C2", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Lighting"},
            {"id": "C3", "type": "MCB", "rating": 32, "poles": "SPN",
             "cable": "6mm²", "load": "Aircon"},
            {"id": "C4", "type": "MCB", "rating": 32, "poles": "SPN",
             "cable": "6mm²", "load": "Aircon"},
            {"id": "C5", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Socket"},
            {"id": "C6", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Socket"},
            {"id": "C7", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "WaterHeater"},
            {"id": "C8", "type": "MCB", "rating": 20, "poles": "SPN",
             "cable": "2.5mm²", "load": "Spare"},
        ],
    }

    # ── ① CP-SAT 솔버 ──────────────────────────────────────────
    scene = build_scene(requirements)
    result = place_layout(scene, time_limit_s=15.0)
    print(f"Solver status: {result.status} "
          f"(time {result.solve_time_s:.2f}s, overlaps={result.overlaps})")
    if not result.ok:
        print("Solver failed — abort.")
        return 1

    # ── ② SolveResult → LayoutResult 어댑터 ───────────────────
    layout = adapt_to_layout_result(scene, result, requirements)
    # 기존 렌더러가 config 없이도 동작하지만, 라벨 char_height 등은
    # LayoutConfig 디폴트가 안전하다.
    layout.config = LayoutConfig()
    print(f"Adapted: {len(layout.components)} components, "
          f"{len(layout.port_connections)} port_connections")
    print(f"  busbar y={layout.busbar_y:.1f}, "
          f"x=[{layout.busbar_start_x:.1f}, {layout.busbar_end_x:.1f}]")
    print(f"  symbols_used: {sorted(layout.symbols_used)}")

    # ── ③ 기존 DXF 백엔드로 렌더 ───────────────────────────────
    pc = A3_LANDSCAPE
    tb_config = TitleBlockConfig.from_page_config(pc)
    dxf = DxfBackend(page_config=pc)

    draw_border(dxf, page_config=pc)
    draw_title_block_frame(dxf, tb_config=tb_config)

    pipeline = SldPipeline()
    n = pipeline._draw_components(dxf, layout)
    pipeline._draw_connections(dxf, layout)
    print(f"Drawn: {n} components")

    fill_title_block_data(
        dxf,
        project_name="CP-SAT Solver Adapter Demo",
        address="(test fixture)",
        kva=43,
        voltage=400,
        supply_type="three_phase",
        lew_name="—",
        lew_licence="—",
        lew_mobile="—",
        tb_config=tb_config,
    )

    out_dir = Path(__file__).resolve().parent.parent / "poc" / "cpsat_sld"
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / "adapter_demo.dxf").write_bytes(dxf.get_bytes())
    (out_dir / "adapter_demo.pdf").write_bytes(dxf.to_pdf_bytes())
    (out_dir / "adapter_demo.svg").write_text(
        dxf.to_svg_string(), encoding="utf-8",
    )
    print(f"Output → {out_dir}/adapter_demo.{{dxf,pdf,svg}}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
