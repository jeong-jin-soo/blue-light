"""LEW가 자주 누락하는 7가지 항목의 자동 보완.

LEW 대화에서 자주 빠뜨리는 정보를 sld_spec/도메인 지식 기반 기본값으로 보완한다.
보완은 **사용자 명시 값이 없을 때만** 적용되며, 보완된 항목은 ``applied_defaults``
키에 기록되어 어떤 값이 자동으로 들어갔는지 추적 가능하다.

대상 누락 항목 (memory/sld-domain analysis 기반):
  1. CT Ratio                — Metering CT 200/5A (CL1 5VA), Protection CT 5P10 20VA
  2. Incoming Cable 형식      — sld_spec.INCOMING_SPEC[rating]에서 자동
  3. ELCB 감도                — 1상=30mA, 3상>100A=100mA(권장)
  4. Sub-circuit 케이블 크기  — breaker_rating 기준 표준값
  5. DB 명칭 표준화          — 입력 그대로(현장 명칭 존중) — no-op이지만 기록
  6. Multi-row 분기          — 회로 9개+ 시 멀티-row 권장 메타 추가
  7. SPARE circuit_id prefix — name에 'spare' 키워드 → SP* prefix
"""

from __future__ import annotations

import logging
from typing import Tuple

logger = logging.getLogger(__name__)


# Sub-circuit cable defaults는 ``sld_spec.OUTGOING_SPEC`` (SS 638 ampacity 표)을
# 단일 원천으로 사용한다. 별도 매핑 보유 시 80A=25sqmm 처럼 OUTGOING_SPEC=35과
# 어긋나 SS 638 ampacity 미달로 EMA 반려 위험이 있다.


def apply_lew_defaults(requirements: dict) -> Tuple[dict, list[str]]:
    """누락된 LEW 항목을 보완. (보완된_requirements, applied_log) 반환.

    원본 dict는 변경하지 않음 (얕은 복사).
    """
    req = dict(requirements)
    applied: list[str] = []

    _apply_metering_defaults(req, applied)
    _apply_incoming_cable_default(req, applied)
    _apply_elcb_sensitivity_default(req, applied)
    _apply_subcircuit_cable_defaults(req, applied)
    _flag_multi_row_layout(req, applied)
    _normalize_spare_circuit_ids(req, applied)
    # DB name normalization은 LEW 입력 존중 — no-op
    if applied:
        req.setdefault("applied_defaults", []).extend(applied)
        logger.info("LEW defaults applied: %s", applied)
    return req, applied


# ── 1. CT Ratio / metering CT class ─────────────────────────


def _apply_metering_defaults(req: dict, applied: list[str]) -> None:
    metering = req.get("metering")
    is_ct = (
        metering in ("ct_meter", "ct_metering")
        or (isinstance(metering, dict) and metering.get("type", "").lower() in ("ct_meter", "ct_metering"))
    )
    if not is_ct:
        return
    if not req.get("ct_ratio"):
        req["ct_ratio"] = _ct_ratio_from_breaker(req)
        applied.append(f"ct_ratio={req['ct_ratio']} (inferred from main_breaker)")
    if not req.get("metering_ct_class"):
        req["metering_ct_class"] = "CL1 5VA"
        applied.append("metering_ct_class=CL1 5VA")
    if not req.get("protection_ct_class"):
        req["protection_ct_class"] = "5P10 20VA"
        applied.append("protection_ct_class=5P10 20VA")


# 표준 CT 1차 정격 (sg-sld-domain-knowledge.md §5).
# 부하전류 ≥ 80% × CT 1차정격이 되도록 다음 단계 선택.
_STANDARD_CT_PRIMARIES: tuple[int, ...] = (100, 150, 200, 300, 400, 500, 600, 800, 1000, 1200, 1600, 2000)


def _ct_ratio_from_breaker(req: dict) -> str:
    """Main breaker rating 기반 표준 CT ratio 산출.

    SS 638/IEC 61869 권장: CT 1차 정격은 부하의 100% 이상에서 가장 가까운 표준값.
    fallback은 200/5A (가장 흔한 LV 산업용 정격).
    """
    main = req.get("main_breaker") or {}
    if not isinstance(main, dict):
        return "200/5A"
    rating = main.get("rating") or main.get("rating_A") or 0
    try:
        rating = int(rating)
    except (TypeError, ValueError):
        return "200/5A"
    if rating <= 0:
        return "200/5A"
    chosen = next((p for p in _STANDARD_CT_PRIMARIES if p >= rating), _STANDARD_CT_PRIMARIES[-1])
    return f"{chosen}/5A"


# ── 2. Incoming cable format ───────────────────────────────


def _apply_incoming_cable_default(req: dict, applied: list[str]) -> None:
    if req.get("incoming_cable"):
        return
    main = req.get("main_breaker") or {}
    if not isinstance(main, dict):
        return  # 잘못된 형식은 generate_sld의 입력 검증이 별도로 잡는다
    rating = main.get("rating") or main.get("rating_A") or 0
    try:
        rating = int(rating)
    except (TypeError, ValueError):
        rating = 0
    if not rating:
        return

    supply = req.get("supply_type", "")
    try:
        from app.sld.layout.models import format_cable_spec
        from app.sld.sld_spec import INCOMING_SPEC, INCOMING_SPEC_3PHASE, _CPC_SIZE
    except Exception:
        return

    # 3상 우선 조회 — 단상/3상 32~100A는 양쪽 spec이 다르다.
    spec = None
    if supply == "three_phase":
        spec = INCOMING_SPEC_3PHASE.get(rating) or INCOMING_SPEC.get(rating)
    else:
        spec = INCOMING_SPEC.get(rating) or INCOMING_SPEC_3PHASE.get(rating)
    if not spec:
        return

    # spec.cable_size는 "16 + 16mmsq E" / "70" / "95" 등 비정형. 수치만 추출해
    # format_cable_spec()이 정규형으로 출력하도록 dict로 위임.
    import re
    nums = [float(x) for x in re.findall(r"[\d.]+", spec.cable_size or "")]
    if not nums:
        return
    phase_size = nums[0]
    cpc_size = nums[1] if len(nums) >= 2 else _CPC_SIZE.get(phase_size, phase_size)

    # cores 결정 — "4 X 1 CORE" → cores=1, count=4 ; "1 X 4 CORE" → cores=4, count=1
    cores_raw = (spec.cable_cores or "").upper()
    if cores_raw.startswith("1 X 4"):
        count, cores = 1, 4
    elif cores_raw.startswith("4 X 1"):
        count, cores = 4, 1
    else:
        # 단상 기본: 2C
        count, cores = 2, 1

    cable_dict = {
        "count": count,
        "cores": cores,
        "size_mm2": str(int(phase_size)) if phase_size == int(phase_size) else str(phase_size),
        "type": spec.cable_type or "PVC/PVC",
        "cpc_mm2": str(int(cpc_size)) if cpc_size == int(cpc_size) else str(cpc_size),
        "method": getattr(spec, "method", "") or "CABLE TRAY",
    }
    req["incoming_cable"] = format_cable_spec(cable_dict)
    applied.append(f"incoming_cable inferred from {rating}A {supply or 'unknown'} spec")


# ── 3. ELCB sensitivity ────────────────────────────────────


def _apply_elcb_sensitivity_default(req: dict, applied: list[str]) -> None:
    """1상에서만 30mA를 자동 보완.

    3상은 100/300mA 모두 가능하므로 lew_defaults에서 강제하지 않고
    ``sld_spec._validate_elcb`` 에 위임한다 (그쪽이 부하 유형까지 고려).
    """
    elcb = req.get("elcb")
    if not isinstance(elcb, dict):
        return
    if elcb.get("sensitivity_ma"):
        return
    supply = req.get("supply_type", "")
    if supply == "single_phase":
        elcb["sensitivity_ma"] = 30
        applied.append("elcb.sensitivity_ma=30mA (1상 SS 638 의무)")


# ── 4. Sub-circuit cables ──────────────────────────────────


def _apply_subcircuit_cable_defaults(req: dict, applied: list[str]) -> None:
    circuits = req.get("sub_circuits")
    if not isinstance(circuits, list):
        return

    try:
        from app.sld.layout.models import format_cable_spec
        from app.sld.sld_spec import lookup_outgoing_cable_spec
    except Exception:
        return

    supply = req.get("supply_type", "")
    default_poles = "TPN" if supply == "three_phase" else "SPN"

    filled = 0
    for sc in circuits:
        if not isinstance(sc, dict) or sc.get("cable"):
            continue
        rating = sc.get("breaker_rating") or 0
        try:
            rating = int(rating)
        except (TypeError, ValueError):
            rating = 0
        if not rating:
            continue

        sc_poles = (sc.get("breaker_poles") or "").upper() or default_poles
        try:
            cable_dict = lookup_outgoing_cable_spec(
                rating, poles=sc_poles, method="METAL TRUNKING"
            )
            sc["cable"] = format_cable_spec(cable_dict)
        except Exception:
            continue
        filled += 1
    if filled:
        applied.append(f"sub_circuits cables inferred ({filled} circuits)")


# ── 6. Multi-row hint ──────────────────────────────────────


def _flag_multi_row_layout(req: dict, applied: list[str]) -> None:
    circuits = req.get("sub_circuits") or []
    if len(circuits) >= 9:
        # 메타 플래그 — engine이 활용
        req.setdefault("layout_hints", {})["multi_row_recommended"] = True
        applied.append(f"multi_row_recommended (circuits={len(circuits)})")


# ── 7. SPARE circuit_id prefix ─────────────────────────────


def _normalize_spare_circuit_ids(req: dict, applied: list[str]) -> None:
    circuits = req.get("sub_circuits")
    if not isinstance(circuits, list):
        return
    flagged = 0
    for sc in circuits:
        if not isinstance(sc, dict):
            continue
        name = (sc.get("name") or "").lower()
        if "spare" not in name:
            continue
        # 이미 SP* prefix면 패스
        cur = (sc.get("circuit_id") or "").upper()
        if cur.startswith("SP"):
            continue
        sc["is_spare"] = True
        flagged += 1
    if flagged:
        applied.append(f"spare circuits flagged ({flagged})")
