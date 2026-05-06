"""Agent end-to-end conversation flow integration test.

LangGraph 전체 LLM 호출은 모킹/스킵하지만, 실제 도구 함수의 호출 시퀀스
( gathering → validate → generate → 분류된 에러 가이드 )를 시뮬레이션해
대화 라이프사이클의 핵심 분기들이 깨지지 않았는지 검증한다.
"""

from __future__ import annotations

import json

from app.agent.error_classifier import classify_error
from app.agent.tools import generate_sld, validate_sld_requirements


def _invoke(tool, **kwargs) -> dict:
    """LangChain @tool 객체를 호출해 결과 dict 반환."""
    raw = tool.invoke(kwargs)
    return json.loads(raw)


def test_conversation_lifecycle_incomplete_then_corrected():
    """
    시나리오:
    1) LEW가 부분 정보만 제공 → validate가 missing field 알려줌
    2) ELCB 없는 상태로 generate_sld → MANDATORY 에러로 차단
    3) ELCB 추가 후 validate → valid
    """

    # ── Step 1: 불완전 입력 ────────────────────────────
    partial: dict = {
        "supply_type": "three_phase",
        "kva": 100,
        # main_breaker, busbar_rating, sub_circuits 누락
    }
    v1 = _invoke(validate_sld_requirements, requirements=partial)
    assert v1["valid"] is False
    assert any("main_breaker" in m for m in v1["missing_fields"])
    assert any("sub_circuits" in m for m in v1["missing_fields"])

    # ── Step 2: ELCB 누락된 상태로 generate_sld 시도 ──
    no_elcb: dict = {
        "supply_type": "three_phase",
        "kva": 100,
        "main_breaker": {"type": "MCCB", "rating": 100, "poles": "TPN", "fault_kA": 35},
        "busbar_rating": 100,
        "sub_circuits": [
            {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10}
        ],
        # elcb 누락
    }
    g1 = _invoke(generate_sld, requirements=no_elcb)
    assert g1["success"] is False
    assert "ELCB" in g1["error"] or "elcb" in g1["error"].lower()

    # ── Step 3: ELCB 추가 후 검증 ─────────────────────
    complete: dict = dict(no_elcb)
    complete["elcb"] = {"rating": 100, "sensitivity_ma": 100, "poles": "TPN", "type": "RCCB"}
    v2 = _invoke(validate_sld_requirements, requirements=complete)
    assert v2["valid"] is True or len(v2.get("missing_fields", [])) == 0


def test_generate_sld_classifies_missing_main_breaker():
    """generate_sld가 main_breaker 누락을 invalid_input으로 잡고 가이드 메시지를 반환."""
    bad: dict = {
        "supply_type": "single_phase",
        "kva": 23,
        "main_breaker": "100A",  # dict이어야 하는데 string
        "busbar_rating": 100,
        "sub_circuits": [
            {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 6}
        ],
        "elcb": {"rating": 63, "sensitivity_ma": 30, "poles": "DP"},
    }
    out = _invoke(generate_sld, requirements=bad)
    assert out["success"] is False
    # generate_sld 자체 검증으로 main_breaker dict 형식 거부
    assert "main_breaker" in out["error"]


def test_classify_error_in_flow_returns_user_actionable_steps():
    """대화 도중 발생할 수 있는 4가지 주요 에러 카테고리 모두에서 next_steps가 비어있지 않아야 한다."""
    cases = [
        (RuntimeError("503 UNAVAILABLE"), "vision_api"),
        (RuntimeError("Row spacing overflow exceeds page"), "layout_overflow"),
        (RuntimeError("Symbol body collision detected"), "symbol_conflict"),
        (KeyError("main_breaker"), "invalid_input"),
    ]
    for exc, expected in cases:
        cls = classify_error(exc)
        assert cls.category == expected, f"{exc!r} → {cls.category}"
        assert cls.next_steps, f"empty next_steps for {expected}"
        assert cls.user_message, f"empty user_message for {expected}"
