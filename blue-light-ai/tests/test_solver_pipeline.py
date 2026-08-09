"""CP-SAT 솔버 본선 통합 회귀 테스트.

가드 범위:
1. capability 가드 — 미지원 requirements가 v3 폴백으로 정확히 걸러지는가
2. 페이지 경계 — 솔버 placement(라벨 포함)가 페이지·타이틀블록을 침범하지 않는가
3. 14-섹션 흐름 순서 — 스파인 박스 Y가 섹션 번호와 단조 증가(하단=전원)하는가
4. 어댑터 연결 무결성 — 모든 PortConnection이 좌표로 해석되는가
5. 파이프라인 통합 — `_try_solver_layout`이 solver 결과를 반환하고,
   미지원 케이스는 None(→v3 폴백)을 반환하는가
6. 렌더 스모크 — 솔버 LayoutResult가 기존 DXF 백엔드로 PDF/SVG까지 나오는가
"""

from __future__ import annotations

import pytest

from app.sld.page_config import A2_LANDSCAPE, A3_LANDSCAPE
from app.sld.solver import adapt_to_layout_result, place_layout
from app.sld.solver.capability import solver_can_handle
from app.sld.solver.scenario import build_scene


def make_circuit(i: int) -> dict:
    return {
        "id": f"C{i}", "type": "MCB",
        "rating": 20 if i % 3 else 32,
        "poles": "SPN", "cable": "2.5mm²" if i % 3 else "6mm²",
        "load": ["Lighting", "Socket", "Aircon"][i % 3],
    }


def make_requirements(metering: str = "sp_meter", circuits: int = 8) -> dict:
    return {
        "supply_type": "three_phase",
        "voltage": 400,
        "main_breaker": {"type": "MCCB",
                         "rating": 200 if metering == "ct_meter" else 63,
                         "poles": "TPN", "fault_kA": 25, "characteristic": "B"},
        "elcb": {"type": "RCCB",
                 "rating": 200 if metering == "ct_meter" else 63,
                 "poles": "4P",
                 "sensitivity_mA": 100 if metering == "ct_meter" else 30},
        "metering": {"type": metering},
        "incoming_cable": "4x25mm² PVC + 16mm² ECC",
        "internal_cable": "4x16mm² PVC + 10mm² ECC",
        "sub_circuits": [make_circuit(i) for i in range(1, circuits + 1)],
    }


SCENARIOS = [
    ("sp_meter", 8, A3_LANDSCAPE),
    ("ct_meter", 12, A2_LANDSCAPE),
    ("non_meter", 8, A3_LANDSCAPE),
]


@pytest.fixture(scope="module")
def solved():
    """시나리오별 (scene, result, layout) 캐시 — solve는 모듈당 1회."""
    out = {}
    for metering, n, pc in SCENARIOS:
        req = make_requirements(metering, n)
        scene = build_scene(req, page_config=pc)
        result = place_layout(scene, time_limit_s=60.0)
        assert result.ok, f"{metering}/{n}: solve failed ({result.status})"
        layout = adapt_to_layout_result(scene, result, req)
        out[metering] = (pc, scene, result, layout)
    return out


# ── 1. capability 가드 ──────────────────────────────────────────────

def test_capability_accepts_standard_cases():
    for metering, n, _pc in SCENARIOS:
        ok, reason = solver_can_handle(make_requirements(metering, n))
        assert ok, f"{metering}: unexpectedly rejected ({reason})"


@pytest.mark.parametrize("mutate,expect_reason", [
    (lambda r: r.update(is_cable_extension=True), "cable extension"),
    (lambda r: r.update(distribution_boards=[{}, {}]), "multi-DB"),
    (lambda r: r.update(protection_groups=[{"phase": "L1"}]), "protection_groups"),
    (lambda r: r.update(post_elcb_mcb={"rating": 63}), "post-ELCB"),
    (lambda r: r.update(supply_source="direct_hv"), "supply_source"),
    (lambda r: r.update(metering={"type": "landlord_bulk"}), "metering"),
    (lambda r: r.update(sub_circuits=[]), "no sub_circuits"),
    (lambda r: r.update(
        sub_circuits=[make_circuit(i) for i in range(1, 26)]), "single-row limit"),
    (lambda r: r["sub_circuits"].append(
        {"id": "ISO1", "type": "ISOLATOR", "rating": 20}), "ISOLATOR"),
])
def test_capability_rejects_unsupported(mutate, expect_reason):
    req = make_requirements()
    mutate(req)
    ok, reason = solver_can_handle(req)
    assert not ok
    assert expect_reason.lower().split()[0] in reason.lower()


# ── 2. 페이지 경계 (라벨 포함 전 박스) ─────────────────────────────

@pytest.mark.parametrize("metering", [m for m, _, _ in SCENARIOS])
def test_placements_within_page_bounds(solved, metering):
    pc, scene, result, _layout = solved[metering]
    right = scene.page_w - scene.margin
    top = scene.page_h - scene.margin
    bottom = scene.effective_margin_bottom
    for name, placement in result.placements.items():
        x, y = placement["x"], placement["y"]
        w, h = placement["box"].w, placement["box"].h
        assert x >= scene.margin - 1, f"{name}: x={x} < left margin"
        assert x + w <= right + 1, f"{name}: right edge {x + w} > {right}"
        assert y >= bottom - 1, f"{name}: y={y} intrudes title block (< {bottom})"
        assert y + h <= top + 1, f"{name}: top edge {y + h} > {top}"


# ── 3. 14-섹션 흐름 순서 (하단=전원 → 상단=부하) ───────────────────

@pytest.mark.parametrize("metering", [m for m, _, _ in SCENARIOS])
def test_spine_section_y_monotonic(solved, metering):
    _pc, scene, result, _layout = solved[metering]
    spine = [
        (p["box"].section, name, p["y"])
        for name, p in result.placements.items()
        if p["box"].column == "spine"
    ]
    spine.sort(key=lambda t: t[0])
    for (s_a, n_a, y_a), (s_b, n_b, y_b) in zip(spine, spine[1:]):
        if s_a == s_b:
            continue
        assert y_a <= y_b, (
            f"{metering}: section {s_a}({n_a}) y={y_a} above "
            f"section {s_b}({n_b}) y={y_b} — flow order violated"
        )


# ── 4. 어댑터 연결 무결성 ──────────────────────────────────────────

@pytest.mark.parametrize("metering", [m for m, _, _ in SCENARIOS])
def test_all_port_connections_resolve(solved, metering):
    _pc, _scene, _result, layout = solved[metering]
    assert layout.port_connections, "no port connections emitted"
    for conn in layout.port_connections:
        start, end = layout.resolve_port_connection(conn)
        assert start is not None and end is not None, f"unresolved: {conn}"


@pytest.mark.parametrize("metering", [m for m, _, _ in SCENARIOS])
def test_adapter_metadata(solved, metering):
    _pc, _scene, _result, layout = solved[metering]
    assert layout.engine == "solver"
    assert layout.spine_x > 0
    sections = layout.sections_rendered
    assert sections.get("main_breaker")
    assert sections.get("main_busbar")
    assert sections.get("sub_circuits")
    assert sections.get("earth_bar")
    if metering == "sp_meter":
        assert sections.get("meter_board")
    if metering == "ct_meter":
        assert sections.get("ct_metering_section")
        assert sections.get("ct_pre_mccb_fuse")


# ── 5. 파이프라인 통합 (_try_solver_layout) ────────────────────────

def test_pipeline_uses_solver_for_supported(monkeypatch):
    from app.config import settings
    from app.sld.generator import SldPipeline
    monkeypatch.setattr(settings, "sld_layout_engine", "auto")
    layout = SldPipeline()._try_solver_layout(make_requirements(), A3_LANDSCAPE)
    assert layout is not None
    assert layout.engine == "solver"
    assert layout.config is not None
    assert layout.config.component_scale == 1.0
    assert layout.config.min_y == pytest.approx(A3_LANDSCAPE.title_block_top)


def test_pipeline_falls_back_for_unsupported(monkeypatch):
    from app.config import settings
    from app.sld.generator import SldPipeline
    monkeypatch.setattr(settings, "sld_layout_engine", "auto")
    req = make_requirements()
    req["is_cable_extension"] = True
    assert SldPipeline()._try_solver_layout(req, A3_LANDSCAPE) is None


def test_pipeline_disabled_by_flag(monkeypatch):
    from app.config import settings
    from app.sld.generator import SldPipeline
    monkeypatch.setattr(settings, "sld_layout_engine", "v3")
    assert SldPipeline()._try_solver_layout(make_requirements(), A3_LANDSCAPE) is None


# ── 6. 렌더 스모크 (DXF → PDF/SVG) ─────────────────────────────────

@pytest.mark.slow
def test_render_smoke_dxf_pdf_svg(solved):
    from app.sld.dxf_backend import DxfBackend
    from app.sld.generator import SldPipeline
    from app.sld.layout.models import LayoutConfig
    from app.sld.title_block import (
        TitleBlockConfig, draw_border, draw_title_block_frame,
    )

    pc, _scene, _result, layout = solved["sp_meter"]
    layout.config = LayoutConfig()
    dxf = DxfBackend(page_config=pc)
    draw_border(dxf, page_config=pc)
    draw_title_block_frame(dxf, tb_config=TitleBlockConfig.from_page_config(pc))

    pipeline = SldPipeline()
    n = pipeline._draw_components(dxf, layout)
    pipeline._draw_connections(dxf, layout)
    pipeline._draw_dashed_connections(dxf, layout)
    pipeline._draw_junction_arrows(dxf, layout)
    pipeline._draw_junction_dots(dxf, layout)
    assert n > 10, "too few components drawn"

    dxf_bytes = dxf.get_bytes()
    pdf_bytes = dxf.to_pdf_bytes()
    svg_text = dxf.to_svg_string()
    assert len(dxf_bytes) > 1_000
    assert pdf_bytes.startswith(b"%PDF")
    assert "<svg" in svg_text
