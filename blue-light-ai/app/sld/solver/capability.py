"""솔버 적용 가능성 판정.

CP-SAT 솔버(`app.sld.solver`)는 단일 보드 테넌트 SLD의 표준 토폴로지
(sp_meter / ct_meter / non_meter)만 모델링한다.  requirements dict 에
솔버가 아직 표현하지 못하는 키가 있으면 절차적 엔진(v3)으로 폴백해야
한다.  이 모듈은 그 판정을 한 곳에 모은다 — scenario.py 가 새 키를
지원하게 되면 여기서 해당 가드를 제거하는 것이 롤아웃 절차다.
"""

from __future__ import annotations

_SUPPORTED_METERING = {"sp_meter", "ct_meter", "non_meter"}

# 솔버 place 모델이 단일 행(등간격 X)만 지원 — 벤치마크 검증 상한.
_MAX_SUB_CIRCUITS = 24


def solver_can_handle(requirements: dict) -> tuple[bool, str]:
    """(적용 가능 여부, 불가 사유) 반환.

    사유 문자열은 로그·진단용이며 적용 가능하면 빈 문자열.
    """
    req = requirements or {}

    dbs = req.get("distribution_boards") or []
    if len(dbs) > 1:
        return False, "multi-DB (distribution_boards > 1)"

    if req.get("is_cable_extension"):
        return False, "cable extension topology"

    if req.get("protection_groups") or any(
        (db or {}).get("protection_groups") for db in dbs
    ):
        return False, "per-phase protection_groups"

    if req.get("post_elcb_mcb"):
        return False, "post-ELCB MCB (RCCB+MCB serial)"

    supply_source = str(req.get("supply_source") or "").lower()
    if supply_source and supply_source not in ("landlord", "sp_powergrid", "building_riser"):
        return False, f"unsupported supply_source '{supply_source}'"

    # metering 은 dict({"type": ...}) 또는 레거시 문자열("direct" 등) 둘 다 온다.
    _m = req.get("metering")
    if isinstance(_m, str):
        metering = _m or "sp_meter"
    else:
        metering = ((_m or {}).get("type") or "sp_meter")
    if metering not in _SUPPORTED_METERING:
        return False, f"unsupported metering type '{metering}'"

    subs = req.get("sub_circuits") or []
    if not subs:
        return False, "no sub_circuits"
    if len(subs) > _MAX_SUB_CIRCUITS:
        return False, f"{len(subs)} sub_circuits > {_MAX_SUB_CIRCUITS} (single-row limit)"

    # scenario.py:177 — unit isolator 는 RCCB 카탈로그 placeholder 를 쓰지만
    # 이는 non_meter 스파인 전용.  서브회로 단의 ISOLATOR 심볼은 미지원.
    for sc in subs:
        sc_type = str((sc or {}).get("type") or "").upper()
        if "ISOL" in sc_type:
            return False, "ISOLATOR sub-circuit symbol not in solver catalog"

    return True, ""
