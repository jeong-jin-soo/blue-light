"""트랙 B 검증용 — sp_meter / ct_meter / non_meter 세 시나리오의 solver+adapter
출력을 한 번에 렌더링한다. 각 시나리오는 별도 demo_<name>.{dxf,pdf,svg}로 저장.
"""
from __future__ import annotations

import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.dxf_backend import DxfBackend
from app.sld.generator import SldPipeline
from app.sld.layout.models import LayoutConfig
from app.sld.page_config import A2_LANDSCAPE, A3_LANDSCAPE
from app.sld.solver import adapt_to_layout_result, place_layout
from app.sld.solver.scenario import build_scene
from app.sld.title_block import (
    TitleBlockConfig, draw_border, draw_title_block_frame, fill_title_block_data,
)


def _sub(i: int, rating: int, kind: str = "MCB", poles: str = "SPN",
         cable: str = "2.5mm²", load: str = "Lighting") -> dict:
    return {
        "id": f"C{i}", "type": kind, "rating": rating, "poles": poles,
        "cable": cable, "load": load,
    }


SCENARIOS = {
    "sp_meter_16_A2": {
        "page": {"size": "A2"},
        "page_config": A2_LANDSCAPE,
        "supply_type": "three_phase", "voltage": 400,
        "metering": {"type": "sp_meter"},
        "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN",
                         "fault_kA": 10, "characteristic": "Type B"},
        "elcb": {"type": "RCCB", "rating": 100, "poles": "4P", "sensitivity_mA": 30},
        "incoming_cable": "4x35mm² PVC + 25mm² ECC",
        "internal_cable": "4x25mm² PVC + 16mm² ECC",
        "earth_label": "EARTH BAR 35mm² CPC",
        "sub_circuits": [_sub(i, 20 if i % 3 else 32) for i in range(1, 17)],
    },
    "ct_meter_12_A2": {
        "page": {"size": "A2"},
        "page_config": A2_LANDSCAPE,
        "supply_type": "three_phase", "voltage": 400,
        "metering": {"type": "ct_meter"},
        "main_breaker": {"type": "MCCB", "rating": 200, "poles": "TPN",
                         "fault_kA": 25, "characteristic": "Type C"},
        "elcb": {"type": "RCCB", "rating": 200, "poles": "4P", "sensitivity_mA": 100},
        "incoming_cable": "4x70mm² PVC + 35mm² ECC",
        "internal_cable": "4x50mm² PVC + 25mm² ECC",
        "ct_metering_label": "200/5A CT\nELR / ASS / AMM\nVSS / VLM\nkWh BY SP\nBI CONNECTOR",
        "earth_label": "EARTH BAR 35mm² CPC",
        "sub_circuits": [_sub(i, 32, kind="MCCB", poles="TPN", cable="10mm²", load="Aircon")
                         for i in range(1, 13)],
    },
    "non_meter_8_A3": {
        "page_config": A3_LANDSCAPE,
        "supply_type": "three_phase", "voltage": 400,
        "metering": {"type": "non_meter"},
        "main_breaker": {"type": "MCCB", "rating": 63, "poles": "TPN",
                         "fault_kA": 10, "characteristic": "Type B"},
        "elcb": {"type": "RCCB", "rating": 63, "poles": "4P", "sensitivity_mA": 30},
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "isolator_label": "63A 4P UNIT ISOLATOR",
        "earth_label": "EARTH BAR 25mm² CPC",
        "sub_circuits": [_sub(i, 20) for i in range(1, 9)],
    },
}


def render_one(name: str, req: dict, out_dir: Path) -> None:
    page_config = req.pop("page_config")
    scene = build_scene(req)
    result = place_layout(scene, time_limit_s=15.0)
    if not result.ok:
        print(f"  {name}: SOLVER FAIL ({result.status})")
        return
    layout = adapt_to_layout_result(scene, result, req)
    layout.config = LayoutConfig()
    print(f"  {name}: status={result.status} time={result.solve_time_s:.2f}s "
          f"components={len(layout.components)} connections={len(layout.port_connections)} "
          f"symbols={sorted(layout.symbols_used)}")

    tb_config = TitleBlockConfig.from_page_config(page_config)
    dxf = DxfBackend(page_config=page_config)
    draw_border(dxf, page_config=page_config)
    draw_title_block_frame(dxf, tb_config=tb_config)

    pipeline = SldPipeline()
    pipeline._draw_components(dxf, layout)
    pipeline._draw_connections(dxf, layout)
    pipeline._draw_dashed_connections(dxf, layout)
    pipeline._draw_junction_arrows(dxf, layout)
    pipeline._draw_junction_dots(dxf, layout)

    fill_title_block_data(
        dxf,
        project_name=f"Track B demo — {name}",
        address="(test fixture)",
        kva=43, voltage=400, supply_type="three_phase",
        lew_name="—", lew_licence="—", lew_mobile="—",
        tb_config=tb_config,
    )
    fname = f"demo_{name}"
    (out_dir / f"{fname}.dxf").write_bytes(dxf.get_bytes())
    (out_dir / f"{fname}.pdf").write_bytes(dxf.to_pdf_bytes())
    (out_dir / f"{fname}.svg").write_text(dxf.to_svg_string(), encoding="utf-8")


def main() -> int:
    logging.basicConfig(level=logging.WARNING)
    out_dir = Path(__file__).resolve().parent.parent / "poc" / "cpsat_sld"
    out_dir.mkdir(parents=True, exist_ok=True)
    for name, req in SCENARIOS.items():
        render_one(name, dict(req), out_dir)
    print(f"Output → {out_dir}/demo_*.{{dxf,pdf,svg}}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
