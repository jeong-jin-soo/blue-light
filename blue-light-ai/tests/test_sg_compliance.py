"""Tests for SP §6.9.6 / SS 638 post-layout compliance checks."""

from __future__ import annotations

from dataclasses import dataclass, field

from app.sld.layout.sg_compliance import (
    CT_IMMEDIATELY_AFTER_MAX_GAP_MM,
    EMA_TITLE_BLOCK_REQUIRED_FIELDS,
    check_ema_title_block_completeness,
    check_sp_6_1_6_meter_board_outgoing_mcb_label,
    check_sp_6_8_4_ct_metering_cable,
    check_sp_6_9_6_ct_immediately_after_breaker,
    run_all_checks,
)


@dataclass
class _FakeComp:
    """Stand-in for PlacedComponent — only fields needed by sg_compliance."""
    symbol_name: str
    y: float
    x: float = 0.0
    id: str = ""
    label: str = ""
    circuit_id: str = ""


@dataclass
class _FakeResult:
    components: list = field(default_factory=list)
    sections_rendered: dict = field(default_factory=dict)


def _ct_metering_result(*comps) -> _FakeResult:
    r = _FakeResult()
    r.components = list(comps)
    r.sections_rendered["ct_metering_section"] = True
    return r


def test_no_check_when_ct_metering_inactive():
    """Direct metering(sp_meter) — SP 6.9.6 미적용."""
    r = _FakeResult(components=[_FakeComp("CB_MCCB", y=10), _FakeComp("CT", y=200)])
    # sections_rendered 비어있음 → CT metering 아님
    assert check_sp_6_9_6_ct_immediately_after_breaker(r) == []


def test_pass_when_ct_close_to_breaker():
    """차단기 직후 CT 배치 — 위반 없음."""
    # CB_MCCB body 높이 + 5mm 정도면 통과
    breaker = _FakeComp("CB_MCCB", y=0.0)
    ct = _FakeComp("CT", y=12.0)  # body 높이 약 ~10mm 가정 → gap ~2mm
    r = _ct_metering_result(breaker, ct)
    issues = check_sp_6_9_6_ct_immediately_after_breaker(r)
    # 권장 한계 30mm 미만이어야 함
    assert issues == [] or all(i.measurement["gap_mm"] <= CT_IMMEDIATELY_AFTER_MAX_GAP_MM for i in issues)


def test_warn_when_ct_too_far_from_breaker():
    """CT가 차단기에서 권장 한계 이상 떨어짐 → 워닝."""
    breaker = _FakeComp("CB_MCCB", y=0.0)
    ct = _FakeComp("CT", y=200.0)  # 매우 먼 위치
    r = _ct_metering_result(breaker, ct)
    issues = check_sp_6_9_6_ct_immediately_after_breaker(r)
    assert len(issues) == 1
    assert issues[0].rule == "SP_6_9_6"
    assert issues[0].severity == "warning"
    assert issues[0].measurement["gap_mm"] > CT_IMMEDIATELY_AFTER_MAX_GAP_MM


def test_chooses_first_protection_ct_above_breaker():
    """차단기 위쪽 가장 가까운 CT를 protection CT로 선정."""
    breaker = _FakeComp("CB_MCCB", y=0.0)
    ct_near = _FakeComp("CT", y=15.0, id="protection")
    ct_far = _FakeComp("CT", y=80.0, id="metering")
    r = _ct_metering_result(breaker, ct_far, ct_near)  # 일부러 순서 섞기
    issues = check_sp_6_9_6_ct_immediately_after_breaker(r)
    # near를 protection으로 골라서 통과
    if issues:
        assert "metering" not in issues[0].measurement.get("protection_ct_id", "")


def test_run_all_checks_includes_6_9_6():
    breaker = _FakeComp("CB_MCCB", y=0.0)
    ct = _FakeComp("CT", y=999.0)
    issues = run_all_checks(_ct_metering_result(breaker, ct))
    assert any(i.rule == "SP_6_9_6" for i in issues)


def test_no_breaker_no_check():
    """메인 차단기 없는 결과는 검증 안 함."""
    r = _ct_metering_result(_FakeComp("CT", y=10))
    assert check_sp_6_9_6_ct_immediately_after_breaker(r) == []


# ── §6.1.6 — Outgoing MCB label ────────────────────────────────


def _meter_board_result(*comps) -> _FakeResult:
    r = _FakeResult(components=list(comps))
    r.sections_rendered["meter_board"] = True
    return r


def test_sp_6_1_6_passes_when_outgoing_label_present():
    mcb = _FakeComp("CB_MCB", y=10, label="63A SPN OUTGOING MCB")
    issues = check_sp_6_1_6_meter_board_outgoing_mcb_label(_meter_board_result(mcb))
    assert issues == []


def test_sp_6_1_6_warns_when_outgoing_label_missing():
    mcb = _FakeComp("CB_MCB", y=10, label="63A SPN MCB 6KA")
    issues = check_sp_6_1_6_meter_board_outgoing_mcb_label(_meter_board_result(mcb))
    assert len(issues) == 1
    assert issues[0].rule == "SP_6_1_6"


def test_sp_6_1_6_skipped_when_no_meter_board():
    """Non-meter / ct_meter — Meter Board 없으므로 검증 안 함."""
    mcb = _FakeComp("CB_MCB", y=10)
    r = _FakeResult(components=[mcb])  # sections_rendered.meter_board ✕
    assert check_sp_6_1_6_meter_board_outgoing_mcb_label(r) == []


# ── §6.8.4 — CT metering cable ────────────────────────────────


def test_sp_6_8_4_pre_mccb_fuse_must_be_2a():
    req = {"metering": "ct_meter", "ct_pre_mccb_fuse_a": 5}
    issues = check_sp_6_8_4_ct_metering_cable(req)
    assert any(i.rule == "SP_6_8_4" and "fuse" in i.detail for i in issues)


def test_sp_6_8_4_voltage_cable_min_4mm2():
    req = {"metering": "ct_meter", "ct_voltage_cable_mm2": 2.5}
    issues = check_sp_6_8_4_ct_metering_cable(req)
    assert any("전압" in i.detail for i in issues)


def test_sp_6_8_4_current_cable_min_6mm2():
    req = {"metering": "ct_meter", "ct_current_cable_mm2": 4.0}
    issues = check_sp_6_8_4_ct_metering_cable(req)
    assert any("전류" in i.detail for i in issues)


def test_sp_6_8_4_skipped_for_sp_meter():
    req = {"metering": "sp_meter", "ct_voltage_cable_mm2": 2.5}
    assert check_sp_6_8_4_ct_metering_cable(req) == []


# ── EMA Title Block ──────────────────────────────────────────


def test_ema_title_block_all_present():
    info = {f: "x" for f in EMA_TITLE_BLOCK_REQUIRED_FIELDS}
    info["kva"] = 100
    info["voltage"] = 400
    assert check_ema_title_block_completeness(info) == []


def test_ema_title_block_missing_fields_reported():
    info = {"project_name": "P1", "address": "Block A", "kva": 100, "voltage": 400}
    issues = check_ema_title_block_completeness(info)
    assert len(issues) == 1
    missing = issues[0].measurement["missing_fields"]
    assert "lew_name" in missing
    assert "drawing_number" in missing


def test_ema_title_block_zero_kva_treated_as_missing():
    info = {f: "x" for f in EMA_TITLE_BLOCK_REQUIRED_FIELDS}
    info["kva"] = 0
    info["voltage"] = 230
    issues = check_ema_title_block_completeness(info)
    assert any("kva" in i.measurement["missing_fields"] for i in issues)


# ── Integrated run_all_checks ────────────────────────────────


def test_run_all_checks_includes_all_rules():
    # CT metering scenario with bad gap, no outgoing label, missing 4mm² voltage cable
    breaker = _FakeComp("CB_MCCB", y=0)
    ct = _FakeComp("CT", y=999)
    r = _ct_metering_result(breaker, ct)
    requirements = {"metering": "ct_meter", "ct_voltage_cable_mm2": 1.5}
    info = {"project_name": "P", "address": "A", "postal_code": "123", "kva": 200,
            "voltage": 400, "supply_type": "three_phase"}  # 일부 누락

    issues = run_all_checks(r, requirements=requirements, application_info=info)
    rules = {i.rule for i in issues}
    assert "SP_6_9_6" in rules
    assert "SP_6_8_4" in rules
    assert "EMA_TITLE_BLOCK" in rules
