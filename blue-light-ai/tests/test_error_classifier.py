"""Tests for app.agent.error_classifier."""

from app.agent.error_classifier import classify_error, to_tool_response


def test_vision_503_is_vision_api():
    err = classify_error(RuntimeError("503 UNAVAILABLE: Gemini overloaded"))
    assert err.category == "vision_api"
    assert err.next_steps


def test_layout_overflow():
    err = classify_error(RuntimeError("Row spacing overflow: exceeds page boundary"))
    assert err.category == "layout_overflow"


def test_symbol_conflict():
    err = classify_error(RuntimeError("Symbol body collision detected at bbox"))
    assert err.category == "symbol_conflict"


def test_template_missing():
    err = classify_error(FileNotFoundError("PDF template not found: /tmp/foo.pdf"))
    # FileNotFoundError → invalid_input 우선 (TypeError류) — 다만 키워드가 있으면 template_missing
    assert err.category in ("template_missing", "invalid_input")


def test_spec_violation_via_keyword():
    err = classify_error(ValueError("SS 638: Busbar rating < breaker rating"))
    assert err.category == "spec_violation"


def test_invalid_input_keyerror():
    err = classify_error(KeyError("main_breaker"))
    assert err.category == "invalid_input"
    assert any("main_breaker" in step or "validate" in step for step in err.next_steps)


def test_unknown_fallback():
    err = classify_error(RuntimeError("totally unexpected internal error"))
    assert err.category == "unknown"


def test_to_tool_response_shape():
    err = classify_error(RuntimeError("503 Gemini failure"))
    resp = to_tool_response(err)
    assert resp["success"] is False
    assert resp["error_category"] == "vision_api"
    assert "next_steps" in resp
    assert "raw_error" in resp
