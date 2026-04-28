"""Singapore SP Group / SS 638 post-layout compliance checks.

레이아웃 결과(LayoutResult)를 받아 싱가포르 규정 준수 여부를 사후 검증한다.
이 모듈은 배치 자체를 바꾸지 않고 위반 사항을 ValidationIssue로 보고만 한다.

Currently checks:
- SP Group §6.9.6: CT enclosure must be placed *immediately after* the
  incoming main breaker. We approximate "immediately after" by measuring
  vertical distance between main breaker top edge and the first protection CT
  bottom edge, and warn if the gap exceeds CT_IMMEDIATELY_AFTER_MAX_GAP_MM.
- SP Group §6.1.6: Meter Board outgoing MCB must be labelled with "OUTGOING"
  marker so the SP technician can identify the supply path.
- SP Group §6.8.4: Metering CT secondary cabling: 4mm² for voltage, 6mm² for
  current. Pre-MCCB protection fuse 2A. (구현 한계: 실측 케이블 사양은
  렌더링 결과에서 추출 불가하므로 requirements 단계에서 간접 검증)
- EMA ELISE Title Block: 12 필수 필드 (project, address, postal, kVA, voltage,
  supply_type, drawing_number, LEW name·licence·mobile, client, contractor)
  존재 여부 사후 검증.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterable

logger = logging.getLogger(__name__)


# SP §6.9.6 — main breaker → first protection CT 권장 최대 거리 (mm, layout 좌표계).
#
# 도메인 근거:
#   "CT enclosure shall be installed immediately after the incoming circuit breaker"
#   (SP Group LV Connection Reqs §6.9.6).
#
# 본 한계 30mm는 layout 단계의 mm 좌표 기준이며, 다음을 토대로 정함:
#   - 표준 ct_metering layout: CB body(약 12mm) + entry_gap(2~5mm) +
#     spine connection(5~10mm) ≈ 20~27mm 가 정상 배치.
#   - 30mm 초과 시 다른 컴포넌트(Unit Isolator, Pre-MCCB Fuse)가 끼어들어 §6.9.6
#     "immediately after" 정신을 위반할 가능성이 큼.
#   - 환경변수 ``SP_6_9_6_MAX_GAP_MM`` 으로 운영에서 조정 가능.
#
# 실측 보강: ``scripts/measure_breaker_ct_gap.py`` 로 레퍼런스 DWG 분포 갱신 가능
# (현재 데이터는 cable_specs 기반 근사값으로 정확도 한계).
import os
CT_IMMEDIATELY_AFTER_MAX_GAP_MM: float = float(os.environ.get("SP_6_9_6_MAX_GAP_MM", "30.0"))


@dataclass(frozen=True)
class ComplianceIssue:
    """One compliance violation, mirroring ValidationIssue shape."""
    rule: str               # 예: "SP_6_9_6"
    severity: str           # "warning" | "error"
    detail: str             # 한국어/영어 설명
    measurement: dict       # 검증 수치 (예: {"gap_mm": 42.5, "limit_mm": 30.0})


def check_sp_6_9_6_ct_immediately_after_breaker(
    result, *, max_gap_mm: float = CT_IMMEDIATELY_AFTER_MAX_GAP_MM
) -> list[ComplianceIssue]:
    """SP Group §6.9.6 — CT enclosure must immediately follow main breaker.

    CT metering (3-phase ≥125A) 시에만 적용. spine 상의 첫 protection CT가
    main breaker 직후에 와야 한다. 두 컴포넌트의 수직 거리가 max_gap_mm을
    초과하면 워닝.

    Args:
        result: LayoutResult (components: list[PlacedComponent] 보유)
        max_gap_mm: 허용 최대 거리. 기본값은 레퍼런스 DWG 기반.

    Returns:
        위반 사항 list. CT metering 미적용 시 빈 리스트.
    """
    components = getattr(result, "components", [])
    if not components:
        return []

    # CT metering 활성 여부 — sections_rendered로 판단
    sections = getattr(result, "sections_rendered", {}) or {}
    if not sections.get("ct_metering_section"):
        return []

    main_breaker = _find_first(
        components,
        lambda c: c.symbol_name in ("CB_MCCB", "CB_ACB", "CB_MCB"),
    )
    if main_breaker is None:
        return []

    # spine 상에서 main breaker 위에 있는 첫 CT (= protection CT)
    breaker_top_y = main_breaker.y + _component_height(main_breaker)
    first_ct = _find_first(
        components,
        lambda c: c.symbol_name == "CT" and c.y >= breaker_top_y,
        sort_by_y=True,
    )
    if first_ct is None:
        return []

    gap = first_ct.y - breaker_top_y
    if gap <= max_gap_mm:
        return []

    return [
        ComplianceIssue(
            rule="SP_6_9_6",
            severity="warning",
            detail=(
                f"SP Group §6.9.6: 보호 CT가 메인 차단기로부터 {gap:.1f}mm 떨어져 있습니다. "
                f"규정상 'immediately after' 요건 — 권장 한계 {max_gap_mm:.0f}mm 초과."
            ),
            measurement={
                "gap_mm": round(gap, 2),
                "limit_mm": max_gap_mm,
                "main_breaker_id": main_breaker.id or main_breaker.symbol_name,
                "protection_ct_id": first_ct.id or first_ct.symbol_name,
            },
        )
    ]


def check_sp_6_1_6_meter_board_outgoing_mcb_label(result) -> list[ComplianceIssue]:
    """SP Group §6.1.6 — Meter Board outgoing MCB는 'OUTGOING' 라벨 필수.

    sp_meter installation 일 때만 적용. Meter Board 내부 MCB의 라벨/circuit_id
    문자열에서 'OUTGOING' 키워드를 찾는다.
    """
    components = getattr(result, "components", [])
    sections = getattr(result, "sections_rendered", {}) or {}
    if not sections.get("meter_board"):
        return []

    # Meter Board에 속한 MCB 후보를 찾는다 — sp_meter 시 spine 위 첫 CB_MCB
    mcbs = [c for c in components if c.symbol_name == "CB_MCB"]
    if not mcbs:
        return []

    # 가장 아래쪽 MCB가 Meter Board 내부 outgoing MCB
    mcbs_sorted = sorted(mcbs, key=lambda c: c.y)
    outgoing_mcb = mcbs_sorted[0]

    label = (outgoing_mcb.label or "").upper()
    cid = (outgoing_mcb.circuit_id or "").upper()
    if "OUTGOING" in label or "OUTGOING" in cid:
        return []

    return [
        ComplianceIssue(
            rule="SP_6_1_6",
            severity="warning",
            detail=(
                "SP Group §6.1.6: Meter Board의 outgoing MCB에 'OUTGOING' 라벨이 없습니다. "
                "SP 기술자가 공급 경로를 식별할 수 있도록 표기 권장."
            ),
            measurement={
                "outgoing_mcb_label": outgoing_mcb.label,
                "outgoing_mcb_id": outgoing_mcb.id or outgoing_mcb.symbol_name,
            },
        )
    ]


def check_sp_6_8_4_ct_metering_cable(requirements: dict) -> list[ComplianceIssue]:
    """SP Group §6.8.4 — CT metering 2차 케이블 사양 검증.

    - Pre-MCCB 보호 fuse: 2A (요구사항 단계는 자동 보완되므로 여기서는
      requirements 에 명시된 값이 다른 경우만 경고)
    - 전압 2차: 4mm², 전류 2차: 6mm² (요구사항 단계 검증)

    구현 한계: 실측 케이블 사이즈는 렌더링 결과에서 추출 불가하므로
    requirements dict 만으로 검증한다. 호출자가 requirements를 함께 넘긴다.
    """
    if not isinstance(requirements, dict):
        return []
    metering = requirements.get("metering")
    is_ct = (
        metering in ("ct_meter", "ct_metering")
        or (isinstance(metering, dict) and metering.get("type", "").lower() in ("ct_meter", "ct_metering"))
    )
    if not is_ct:
        return []

    issues: list[ComplianceIssue] = []

    fuse = requirements.get("ct_pre_mccb_fuse_a")
    if fuse and float(fuse) != 2.0:
        issues.append(
            ComplianceIssue(
                rule="SP_6_8_4",
                severity="warning",
                detail=f"SP §6.8.4: CT pre-MCCB 보호 fuse는 2A 권장 (현재 {fuse}A).",
                measurement={"fuse_a": float(fuse)},
            )
        )

    v_size = requirements.get("ct_voltage_cable_mm2")
    if v_size and float(v_size) < 4.0:
        issues.append(
            ComplianceIssue(
                rule="SP_6_8_4",
                severity="warning",
                detail=f"SP §6.8.4: CT 전압 2차 케이블 4mm² 미만 ({v_size}mm²).",
                measurement={"voltage_cable_mm2": float(v_size)},
            )
        )

    c_size = requirements.get("ct_current_cable_mm2")
    if c_size and float(c_size) < 6.0:
        issues.append(
            ComplianceIssue(
                rule="SP_6_8_4",
                severity="warning",
                detail=f"SP §6.8.4: CT 전류 2차 케이블 6mm² 미만 ({c_size}mm²).",
                measurement={"current_cable_mm2": float(c_size)},
            )
        )

    return issues


# EMA ELISE 필수 12 필드 (sld-drawing-principles.md SG8 기준)
EMA_TITLE_BLOCK_REQUIRED_FIELDS: tuple[str, ...] = (
    "project_name",
    "address",
    "postal_code",
    "kva",
    "voltage",
    "supply_type",
    "drawing_number",
    "lew_name",
    "lew_licence",
    "lew_mobile",
    "client_name",
    "elec_contractor",
)


def check_ema_title_block_completeness(application_info: dict) -> list[ComplianceIssue]:
    """EMA ELISE 제출용 Title Block 12 필수 필드 누락 검증.

    application_info dict는 generator의 ``title_block_kwargs`` 와 동일 키 셋.
    ``elec_contractor`` 는 기본값 'LicenseKaki' 가 있으므로 누락 처리하지 않는다.
    """
    if not isinstance(application_info, dict):
        return []

    missing: list[str] = []
    for field in EMA_TITLE_BLOCK_REQUIRED_FIELDS:
        v = application_info.get(field)
        # 0/None/빈문자열은 누락 — voltage·kVA 0 은 의미상 미지정
        if v in (None, "", 0, "0"):
            missing.append(field)

    if not missing:
        return []
    return [
        ComplianceIssue(
            rule="EMA_TITLE_BLOCK",
            severity="warning",
            detail=(
                f"EMA ELISE 제출용 Title Block 필수 필드 {len(missing)}개 누락: "
                f"{', '.join(missing)}. 제출 시 보완 필요."
            ),
            measurement={"missing_fields": missing},
        )
    ]


def run_all_checks(result, requirements: dict | None = None,
                   application_info: dict | None = None) -> list[ComplianceIssue]:
    """모든 SP/SS 638/EMA 사후 검증 일괄 실행.

    Args:
        result: LayoutResult — 배치 결과 검증
        requirements: 원본 requirements dict (§6.8.4 등 사양 검증)
        application_info: title block 데이터 (EMA 12필드 검증)
    """
    issues: list[ComplianceIssue] = []
    issues.extend(check_sp_6_9_6_ct_immediately_after_breaker(result))
    issues.extend(check_sp_6_1_6_meter_board_outgoing_mcb_label(result))
    if requirements is not None:
        issues.extend(check_sp_6_8_4_ct_metering_cable(requirements))
    if application_info is not None:
        issues.extend(check_ema_title_block_completeness(application_info))
    return issues


# ── Helpers ─────────────────────────────────────────────────────────


def _find_first(items: Iterable, predicate, *, sort_by_y: bool = False):
    matched = [c for c in items if predicate(c)]
    if not matched:
        return None
    if sort_by_y:
        matched.sort(key=lambda c: c.y)
    return matched[0]


def _component_height(comp) -> float:
    """심볼 body 높이를 카탈로그에서 조회. 실패 시 0."""
    try:
        from app.sld.layout.section_base import sym_dims
        _, h, _ = sym_dims(comp.symbol_name)
        return float(h)
    except Exception:
        return 0.0
