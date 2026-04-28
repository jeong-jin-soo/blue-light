"""SLD generation error classifier.

generate_sld 등 도구 호출 중 발생하는 예외를 카테고리화하고,
LEW가 무엇을 수정해야 하는지 알려주는 사용자 가이드 메시지를 만든다.

Categories:
  layout_overflow    — 페이지 경계 초과, 회로 너무 많음
  symbol_conflict    — 심볼 충돌, 라벨 겹침
  template_missing   — Track A에 필요한 템플릿/PDF 없음
  spec_violation     — SS 638 / SP Group 규정 위반
  vision_api         — Gemini Vision API 일시 장애
  invalid_input      — 입력 데이터 형식 오류 (KeyError, TypeError)
  unknown            — 분류 불가
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class ClassifiedError:
    category: str
    user_message: str          # LEW가 읽을 한국어/영어 가이드
    next_steps: list[str]      # 구체적 액션 (UI 체크리스트로 표시 가능)
    raw_error: str             # 원본 메시지 (디버깅용)


_LAYOUT_KEYWORDS = (
    "overflow", "exceeds page", "out of bounds",
    "no space", "row spacing", "horizontal_spacing",
)
_SYMBOL_KEYWORDS = (
    "collision", "overlap", "symbol body",
    "bbox", "intersect",
)
_TEMPLATE_KEYWORDS = (
    "template not found", "pdf template",
    "PdfTemplateEditor", "title block",
)
_SPEC_KEYWORDS = (
    "SS 638", "SP Group", "compliance", "non-compliant",
    "busbar", "ELCB", "RCCB", "CT enclosure", "§6.",
)
_VISION_KEYWORDS = (
    "Gemini", "Vision", "503", "429",
    "UNAVAILABLE", "RESOURCE_EXHAUSTED", "DEADLINE_EXCEEDED",
    "google.api_core",
)


def classify_error(exc: BaseException) -> ClassifiedError:
    """예외를 카테고리화하여 LEW용 가이드 메시지를 반환."""
    msg = str(exc)
    msg_lower = msg.lower()
    exc_type = type(exc).__name__

    # 1. Vision/API 에러 (가장 먼저 — 일시적이라 재시도 권장)
    if any(k.lower() in msg_lower for k in _VISION_KEYWORDS):
        return ClassifiedError(
            category="vision_api",
            user_message=(
                "AI Vision 서비스가 일시적으로 응답하지 않았습니다. "
                "잠시 후 다시 시도하거나, Vision 검증을 끈 상태로 생성을 진행할 수 있습니다."
            ),
            next_steps=[
                "1~2분 후 동일 요청을 재시도",
                "급한 경우 관리자에게 문의 (Vision 검증 임시 비활성화 가능)",
            ],
            raw_error=msg,
        )

    # 2. 입력 데이터 형식 오류
    if exc_type in ("KeyError", "TypeError", "ValueError", "AttributeError"):
        # 단, spec violation 키워드가 있으면 그쪽으로 분류
        if any(k.lower() in msg_lower for k in _SPEC_KEYWORDS):
            return _spec_classified(msg)
        return ClassifiedError(
            category="invalid_input",
            user_message=(
                "입력 데이터의 형식이 올바르지 않습니다. "
                f"필드 누락이나 타입 오류가 있을 수 있습니다 ({exc_type})."
            ),
            next_steps=[
                "main_breaker, sub_circuits, elcb 등 핵심 필드의 키/값을 다시 확인",
                "validate_sld_requirements 도구로 누락 항목 점검",
                "재추출이 필요하면 회로 정보를 다시 입력해주세요",
            ],
            raw_error=msg,
        )

    # 3. 레이아웃 오버플로우
    if any(k in msg_lower for k in _LAYOUT_KEYWORDS):
        return ClassifiedError(
            category="layout_overflow",
            user_message=(
                "회로 수가 많아 한 페이지에 모두 배치할 수 없거나, "
                "심볼 간격이 너무 좁아 레이아웃이 페이지를 넘어갔습니다."
            ),
            next_steps=[
                "회로 9개 이상이라면 멀티-row 레이아웃이 자동 적용되는지 확인",
                "DB를 두 개로 분할하는 것을 검토 (예: DB1 — Lighting, DB2 — Power)",
                "특정 회로가 누락되어도 무방하면 sub_circuits 목록에서 제거",
            ],
            raw_error=msg,
        )

    # 4. 심볼 충돌
    if any(k in msg_lower for k in _SYMBOL_KEYWORDS):
        return ClassifiedError(
            category="symbol_conflict",
            user_message=(
                "도면 심볼이 서로 겹치거나 라벨이 충돌하여 배치할 수 없었습니다."
            ),
            next_steps=[
                "회로 라벨/이름이 너무 길지 않은지 확인 (20자 이내 권장)",
                "동일 등급 회로가 너무 많은 경우 그룹화 검토",
                "문제가 반복되면 관리자에게 도면 스냅샷과 함께 문의",
            ],
            raw_error=msg,
        )

    # 5. 템플릿 누락 (Track A)
    if any(k in msg_lower for k in _TEMPLATE_KEYWORDS):
        return ClassifiedError(
            category="template_missing",
            user_message=(
                "선택된 레퍼런스 템플릿 파일을 찾을 수 없거나 처리에 실패했습니다. "
                "Track B(자동 생성) 모드로 재시도가 필요합니다."
            ),
            next_steps=[
                "find_matching_templates 단계를 건너뛰고 자동 생성 모드로 재시도",
                "관리자에게 템플릿 자산 점검 요청",
            ],
            raw_error=msg,
        )

    # 6. 규정 위반
    if any(k.lower() in msg_lower for k in _SPEC_KEYWORDS):
        return _spec_classified(msg)

    # 7. 분류 불가
    return ClassifiedError(
        category="unknown",
        user_message=(
            "SLD 생성 중 예기치 못한 오류가 발생했습니다. "
            "입력을 점검 후 재시도해주세요."
        ),
        next_steps=[
            "지난 메시지에서 오타/누락된 값이 없는지 확인",
            "동일 오류 반복 시 관리자에게 thread_id와 함께 문의",
        ],
        raw_error=msg,
    )


def _spec_classified(msg: str) -> ClassifiedError:
    return ClassifiedError(
        category="spec_violation",
        user_message=(
            "싱가포르 SS 638 / SP Group 규정에 부합하지 않는 항목이 있습니다."
        ),
        next_steps=[
            "validate_sld_requirements를 다시 호출해 위반 항목 확인",
            "Busbar 정격 ≥ Main Breaker 정격, ELCB 감도(1상=30mA) 등 핵심 규칙 확인",
            "수정 후 generate_sld 재시도",
        ],
        raw_error=msg,
    )


def to_tool_response(err: ClassifiedError) -> dict:
    """generate_sld 등 LangChain 도구가 반환할 dict 형식으로 변환."""
    return {
        "success": False,
        "error_category": err.category,
        "error": err.user_message,
        "next_steps": err.next_steps,
        "raw_error": err.raw_error,
    }
