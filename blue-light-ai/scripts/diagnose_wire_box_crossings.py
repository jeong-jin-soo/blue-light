"""트랙 C 진단 — wire 선분이 컴포넌트 박스를 가로지르는지 실측.

메모리 sld-cpsat-next-session.md 트랙 C 권고:
  "트랙 B를 먼저 진행 후 wire-vs-sub-component 충돌이 실제로 발생하는지 확인"
  "wire가 어떤 박스도 가로지르지 않는다는 자동 검사 함수 추가"

트랙 B 완료 후, 9개 벤치 시나리오에서 wire-vs-box 충돌을 측정한다.
핵심 측정 결과: 컴포넌트 본체 충돌 0, 라벨 텍스트 박스 충돌만 존재
→ orthogonal routing 불필요. (회귀 가드는 tests/test_wire_no_box_cross.py)

검사 로직은 `app.sld.solver.wire_check` 단일 정의원을 재사용.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.sld.solver.adapter import adapt_to_layout_result
from app.sld.solver.place import place_layout
from app.sld.solver.scenario import build_scene
from app.sld.solver.wire_check import measure_crossings


def make_circuit(i: int) -> dict:
    return {
        "id": f"C{i}", "type": "MCB",
        "rating": 20 if i % 3 else 32,
        "poles": "SPN", "cable": "2.5mm²" if i % 3 else "6mm²",
        "load": ["Lighting", "Socket", "Aircon"][i % 3],
    }


def build_req(metering: str, circuits: int, page_size: str) -> dict:
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
        "sub_circuits": [make_circuit(i) for i in range(1, circuits + 1)],
    }


def analyze(metering: str, circuits: int, page_size: str) -> dict:
    req = build_req(metering, circuits, page_size)
    scene = build_scene(req)
    result = place_layout(scene, time_limit_s=30.0)
    if not result.ok:
        return {"case": f"{metering}_{circuits}_{page_size}", "status": result.status,
                "body_crossings": -1, "label_crossings": -1, "details": ["solver failed"]}
    layout = adapt_to_layout_result(scene, result, req)
    report = measure_crossings(scene, result, layout)
    return {
        "case": f"{metering}_{circuits}_{page_size}",
        "status": result.status,
        "n_body": report.n_body_boxes,
        "n_label": report.n_label_boxes,
        "n_wires": report.n_wires,
        "body_crossings": report.n_body_crossings,
        "label_crossings": report.n_label_crossings,
        "details": report.body_crossings + report.label_crossings,
    }


def main() -> int:
    cases = [
        ("sp_meter", 4, "A3"), ("sp_meter", 8, "A3"),
        ("sp_meter", 16, "A2"), ("sp_meter", 24, "A2"),
        ("ct_meter", 8, "A2"), ("ct_meter", 12, "A2"),
        ("ct_meter", 18, "A2"), ("ct_meter", 24, "A2"),
        ("non_meter", 8, "A3"),
    ]
    print(f"{'case':<18} {'status':<10} {'body':>5} {'label':>5} {'wires':>5} "
          f"{'body✗':>6} {'label✗':>7}")
    print("-" * 65)
    total_body = 0
    total_label = 0
    all_details: list[str] = []
    for m, n, p in cases:
        r = analyze(m, n, p)
        total_body += max(0, r.get("body_crossings", 0))
        total_label += max(0, r.get("label_crossings", 0))
        print(f"{r['case']:<18} {r['status']:<10} "
              f"{r.get('n_body', 0):>5} {r.get('n_label', 0):>5} {r.get('n_wires', 0):>5} "
              f"{r.get('body_crossings', 0):>6} {r.get('label_crossings', 0):>7}")
        if r["details"]:
            for d in r["details"][:5]:
                all_details.append(f"  [{r['case']}] {d}")

    print("-" * 65)
    print(f"TOTAL — BODY crossings: {total_body}  |  LABEL crossings: {total_label}")
    print()
    if total_body == 0:
        print("✅ 컴포넌트 본체(심볼) vs wire 충돌 0 — orthogonal routing 불필요.")
        print("   (트랙 C 가 걱정한 'sub-component 가 spine wire 에 걸림' 미발생)")
    else:
        print(f"⚠️  컴포넌트 본체 충돌 {total_body}건 — orthogonal routing 필요.")
    if total_label > 0:
        print(f"ℹ️  라벨 텍스트 박스 충돌 {total_label}건 — 별개 이슈 (라벨 위치 조정 영역, routing 아님).")

    if all_details:
        print("\nDETAILS (first 5 per case):")
        for d in all_details:
            print(d)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
