"""트랙 C 회귀 가드 — wire 가 컴포넌트 본체(심볼) 박스를 가로지르지 않음을 보장.

배경 (메모리 sld-cpsat-next-session.md 트랙 C):
  orthogonal wire routing 이 필요한지 판단하기 위해 9개 벤치 시나리오에서
  wire-vs-box 충돌을 실측한 결과 —
  **컴포넌트 본체(심볼) vs wire 충돌 0건**, 라벨 텍스트 박스 충돌만 존재.
  → orthogonal routing 불필요. 본 테스트는 그 결론(본체 충돌 0)이 회귀하지
     않도록 솔버 placements + 어댑터 wire 를 대조한다.

만약 향후 변경(예: multi-DB 분기, 합성 sub-component 확장)으로 본체 충돌이
발생하면 본 테스트가 실패한다. 그때가 orthogonal routing 을 실제로 도입할
시점이다 (현재는 불필요).

검사 로직: `app.sld.solver.wire_check.measure_crossings` (진단 스크립트와 공유).
"""

from __future__ import annotations

import pytest

from app.sld.solver.adapter import adapt_to_layout_result
from app.sld.solver.place import place_layout
from app.sld.solver.scenario import build_scene
from app.sld.solver.wire_check import measure_crossings


def _make_circuit(i: int) -> dict:
    return {
        "id": f"C{i}", "type": "MCB",
        "rating": 20 if i % 3 else 32,
        "poles": "SPN", "cable": "2.5mm²" if i % 3 else "6mm²",
        "load": ["Lighting", "Socket", "Aircon"][i % 3],
    }


def _build_req(metering: str, circuits: int, page_size: str) -> dict:
    return {
        "supply_type": "three_phase",
        "page": {"size": page_size},
        "main_breaker": {"type": "MCCB", "rating": 200 if metering == "ct_meter" else 63,
                         "poles": "TPN", "fault_kA": 25, "characteristic": "B"},
        "elcb": {"type": "RCCB", "rating": 200 if metering == "ct_meter" else 63,
                 "poles": "4P", "sensitivity_mA": 100 if metering == "ct_meter" else 30},
        "metering": {"type": metering},
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "sub_circuits": [_make_circuit(i) for i in range(1, circuits + 1)],
    }


# 솔버 벤치와 동일한 9개 시나리오 (run_solver_bench.py).
_SCENARIOS = [
    ("sp_meter", 4, "A3"),
    ("sp_meter", 8, "A3"),
    ("sp_meter", 16, "A2"),
    ("sp_meter", 24, "A2"),
    ("ct_meter", 8, "A2"),
    ("ct_meter", 12, "A2"),
    ("ct_meter", 18, "A2"),
    ("ct_meter", 24, "A2"),
    ("non_meter", 8, "A3"),
]


@pytest.mark.parametrize("metering,circuits,page", _SCENARIOS,
                         ids=[f"{m}_{n}_{p}" for m, n, p in _SCENARIOS])
def test_no_wire_crosses_component_body(metering: str, circuits: int, page: str):
    """wire 가 어떤 컴포넌트 본체(심볼) 박스도 가로지르지 않아야 한다.

    본체 충돌 0 은 orthogonal routing 이 불필요하다는 트랙 C 결론의 핵심 근거.
    이 보장이 깨지면 wire routing 도입을 검토해야 한다.
    """
    req = _build_req(metering, circuits, page)
    scene = build_scene(req)
    result = place_layout(scene, time_limit_s=30.0)
    assert result.ok, f"solver failed: status={result.status}"

    layout = adapt_to_layout_result(scene, result, req)
    report = measure_crossings(scene, result, layout)

    assert report.n_body_crossings == 0, (
        f"{metering}_{circuits}_{page}: wire 가 컴포넌트 본체 {report.n_body_crossings}개를 "
        f"가로지름 — orthogonal routing 검토 필요.\n"
        + "\n".join(report.body_crossings[:10])
    )


def test_solver_geometry_sane_for_check():
    """검사 인프라 sanity — 박스/wire 가 실제로 측정되는지 (no-op 방지)."""
    req = _build_req("sp_meter", 16, "A2")
    scene = build_scene(req)
    result = place_layout(scene, time_limit_s=30.0)
    assert result.ok
    layout = adapt_to_layout_result(scene, result, req)
    report = measure_crossings(scene, result, layout)

    # 검사가 실제로 박스와 wire 를 보고 있어야 의미 있음.
    assert report.n_body_boxes > 0, "본체 박스가 0 — 검사가 무의미"
    assert report.n_wires > 0, "wire 가 0 — 검사가 무의미"
