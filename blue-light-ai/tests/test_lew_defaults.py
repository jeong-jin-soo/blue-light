"""Tests for LEW common-omission auto-fill (lew_defaults.py)."""

from app.sld.lew_defaults import apply_lew_defaults


def test_ct_meter_fills_ratio_from_breaker_rating():
    """200A breaker → 200/5A CT (sg-sld-domain-knowledge.md §5)."""
    req = {"metering": "ct_meter", "main_breaker": {"rating": 200, "type": "MCCB"}}
    out, applied = apply_lew_defaults(req)
    assert out["ct_ratio"] == "200/5A"
    assert out["metering_ct_class"] == "CL1 5VA"
    assert out["protection_ct_class"] == "5P10 20VA"
    assert any("ct_ratio" in a for a in applied)


def test_ct_meter_picks_next_standard_for_125A_breaker():
    """125A breaker → 150/5A (다음 표준 정격)."""
    req = {"metering": "ct_meter", "main_breaker": {"rating": 125, "type": "MCCB"}}
    out, _ = apply_lew_defaults(req)
    assert out["ct_ratio"] == "150/5A"


def test_ct_meter_400A_uses_400_5_ratio():
    req = {"metering": "ct_meter", "main_breaker": {"rating": 400, "type": "MCCB"}}
    out, _ = apply_lew_defaults(req)
    assert out["ct_ratio"] == "400/5A"


def test_ct_meter_no_breaker_falls_back_to_200():
    req = {"metering": "ct_meter"}
    out, _ = apply_lew_defaults(req)
    assert out["ct_ratio"] == "200/5A"


def test_ct_meter_respects_user_supplied_ratio():
    req = {"metering": "ct_meter", "ct_ratio": "300/5A", "main_breaker": {"rating": 200}}
    out, _ = apply_lew_defaults(req)
    assert out["ct_ratio"] == "300/5A"


def test_sp_meter_does_not_apply_ct_defaults():
    req = {"metering": "sp_meter"}
    out, applied = apply_lew_defaults(req)
    assert "ct_ratio" not in out
    assert not any("ct_ratio" in a for a in applied)


def test_incoming_cable_inferred_from_breaker_rating():
    req = {"main_breaker": {"type": "MCCB", "rating": 100}}
    out, applied = apply_lew_defaults(req)
    assert out.get("incoming_cable")
    assert any("incoming_cable" in a for a in applied)


def test_incoming_cable_three_phase_uses_3phase_spec():
    """3상 100A는 INCOMING_SPEC_3PHASE[100]=50sqmm XLPE를 사용해야 한다."""
    req = {"supply_type": "three_phase", "main_breaker": {"type": "MCCB", "rating": 100}}
    out, _ = apply_lew_defaults(req)
    cable = out["incoming_cable"]
    assert "50sqmm" in cable, f"3-phase 100A should use 50sqmm cable, got: {cable}"


def test_incoming_cable_format_normalized():
    """sld-drawing-principles §P6 정규형 — `{count} x {cores}C {size}sqmm ... CPC IN {method}`."""
    req = {"supply_type": "single_phase", "main_breaker": {"type": "MCB", "rating": 63}}
    out, _ = apply_lew_defaults(req)
    cable = out["incoming_cable"]
    assert "sqmm" in cable
    assert "CPC" in cable
    # "16 + 16mmsq E" 같은 비정규 표기가 그대로 남으면 안 됨
    assert "mmsq" not in cable
    assert " E" not in cable.split("CPC")[0]


def test_elcb_sensitivity_single_phase_30ma():
    req = {"supply_type": "single_phase", "elcb": {"rating": 63, "type": "RCCB"}}
    out, applied = apply_lew_defaults(req)
    assert out["elcb"]["sensitivity_ma"] == 30
    assert any("30mA" in a for a in applied)


def test_elcb_three_phase_left_to_sld_spec_validator():
    """3상은 100/300mA 모두 가능하므로 lew_defaults가 강제하지 않는다.

    실제 자동 결정은 ``sld_spec._validate_elcb`` 가 부하/등급 컨텍스트로 수행.
    """
    req = {"supply_type": "three_phase", "elcb": {"rating": 200, "type": "RCCB"}}
    out, applied = apply_lew_defaults(req)
    # lew_defaults는 sensitivity를 채우지 않아야 한다
    assert out["elcb"].get("sensitivity_ma") in (None, 0)
    assert not any("sensitivity_ma" in a for a in applied)


def test_elcb_sensitivity_user_supplied_unchanged():
    req = {"supply_type": "single_phase", "elcb": {"rating": 63, "sensitivity_ma": 100}}
    out, _ = apply_lew_defaults(req)
    assert out["elcb"]["sensitivity_ma"] == 100  # user value preserved


def test_subcircuit_cable_inferred_by_rating():
    req = {"sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "Power", "breaker_type": "MCB", "breaker_rating": 32},
        {"name": "AC", "breaker_type": "MCB", "breaker_rating": 20, "cable": "USER SPEC"},
    ]}
    out, applied = apply_lew_defaults(req)
    # format_cable_spec은 정수 사이즈를 "6sqmm"로 정규화 (sld-drawing-principles §P6).
    assert "1.5sqmm" in out["sub_circuits"][0]["cable"]
    assert "6sqmm" in out["sub_circuits"][1]["cable"]
    assert out["sub_circuits"][2]["cable"] == "USER SPEC"  # respected
    assert any("sub_circuits cables" in a for a in applied)


def test_subcircuit_cable_80a_uses_outgoing_spec_value():
    """OUTGOING_SPEC[80]=35sqmm — 이전 _SUBCIRCUIT_CABLE_BY_RATING의 25sqmm 버그 재발 방지."""
    req = {"sub_circuits": [
        {"name": "Sub-DB feeder", "breaker_type": "MCCB", "breaker_rating": 80},
    ]}
    out, _ = apply_lew_defaults(req)
    cable = out["sub_circuits"][0]["cable"]
    assert "35sqmm" in cable, f"80A breaker should use 35sqmm per OUTGOING_SPEC, got: {cable}"


def test_multi_row_hint_when_many_circuits():
    circuits = [{"name": f"C{i}", "breaker_type": "MCB", "breaker_rating": 10} for i in range(10)]
    out, applied = apply_lew_defaults({"sub_circuits": circuits})
    assert out["layout_hints"]["multi_row_recommended"] is True
    assert any("multi_row" in a for a in applied)


def test_multi_row_not_set_for_few_circuits():
    out, _ = apply_lew_defaults({"sub_circuits": [{"name": "C1", "breaker_rating": 10}]})
    assert "layout_hints" not in out


def test_spare_circuits_flagged():
    req = {"sub_circuits": [
        {"name": "Spare 1", "breaker_type": "MCB", "breaker_rating": 20},
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10},
        {"name": "SPARE-2", "breaker_type": "MCB", "breaker_rating": 20},
    ]}
    out, applied = apply_lew_defaults(req)
    assert out["sub_circuits"][0]["is_spare"] is True
    assert "is_spare" not in out["sub_circuits"][1]
    assert out["sub_circuits"][2]["is_spare"] is True
    assert any("spare" in a for a in applied)


def test_no_op_when_everything_specified():
    req = {
        "supply_type": "single_phase",
        "main_breaker": {"type": "MCB", "rating": 63},
        "incoming_cable": "USER PROVIDED",
        "elcb": {"rating": 63, "sensitivity_ma": 30, "type": "RCCB"},
        "sub_circuits": [{"name": "C1", "breaker_rating": 10, "cable": "USER"}],
    }
    out, applied = apply_lew_defaults(req)
    assert out["incoming_cable"] == "USER PROVIDED"
    assert out["sub_circuits"][0]["cable"] == "USER"
    # ct_ratio 안 적용 (sp_meter), elcb sensitivity 안 변경, 케이블 안 바꿈
    # 다만 supply_type이 single_phase + elcb sensitivity 30mA 이미 있으므로 변동 없음
    assert "ct_ratio" not in out
