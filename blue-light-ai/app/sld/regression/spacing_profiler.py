"""레퍼런스 DXF 간격 프로파일 추출기.

28개 DXF에서 컴포넌트 간 간격을 mm 단위로 추출한다.
각 파일의 E-SLD-FRAME을 A3(420×297mm)에 매핑하여 DU→mm 변환.

Usage:
    python -m app.sld.regression.spacing_profiler
"""

from __future__ import annotations

import json
import logging
import sys
from collections import defaultdict
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

import ezdxf

logger = logging.getLogger(__name__)

_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent  # blue-light-ai/
DXF_DIR = _BASE_DIR / "data" / "sld-info" / "slds-dxf"
OUTPUT_PATH = _BASE_DIR / "data" / "regression" / "reference_spacing_profiles.json"

# A3 landscape dimensions
A3_WIDTH_MM = 420.0
A3_HEIGHT_MM = 297.0


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass
class SpacingProfile:
    """단일 DXF 파일의 간격 프로파일.

    절대 mm 변환 대신 **비율(ratio) 기반**.
    서브회로 간격 = frame 대비 span 비율 → 우리 usable width에서 계산.
    스파인 간격 = frame height 대비 비율 → 우리 usable height에서 계산.
    """
    filename: str
    sld_type: str
    circuits: int = 0

    # 서브회로 수평 비율
    subcircuit_span_ratio: float = 0.0  # span / frame_width
    subcircuit_spacing_per_circuit_ratio: float = 0.0  # span / (n-1) / frame_width

    # 스파인 수직 비율 (섹션 간 gap / frame_height)
    spine_gap_ratios: dict[str, float] = field(default_factory=dict)

    # 서브회로 간격 (mm, usable_width=370mm 기준 환산)
    subcircuit_spacing_mm: float = 0.0

    # 스파인 간격 (mm, usable_height=223mm 기준 환산)
    spine_gaps_mm: dict[str, float] = field(default_factory=dict)

    # Phase A 확장 — 추가 간격 파라미터 (mm)
    extended: dict[str, float] = field(default_factory=dict)

    def to_dict(self) -> dict:
        return asdict(self)


# ---------------------------------------------------------------------------
# DU→mm conversion
# ---------------------------------------------------------------------------

def _compute_du_to_mm(msp) -> tuple[float, float]:
    """E-SLD-FRAME 선으로부터 DU→mm 변환 계수 계산.

    Returns: (mm_per_du_x, mm_per_du_y)
    """
    frame_xs, frame_ys = [], []
    for e in msp:
        if e.dxftype() == "LINE" and e.dxf.layer == "E-SLD-FRAME":
            frame_xs.extend([e.dxf.start.x, e.dxf.end.x])
            frame_ys.extend([e.dxf.start.y, e.dxf.end.y])

    if not frame_xs or not frame_ys:
        # Fallback: 전체 엔티티 범위 사용
        for e in msp:
            try:
                if e.dxftype() == "LINE":
                    frame_xs.extend([e.dxf.start.x, e.dxf.end.x])
                    frame_ys.extend([e.dxf.start.y, e.dxf.end.y])
            except Exception:
                pass

    if not frame_xs or not frame_ys:
        return 0.0, 0.0

    w_du = max(frame_xs) - min(frame_xs)
    h_du = max(frame_ys) - min(frame_ys)

    if w_du < 100 or h_du < 100:
        return 0.0, 0.0

    return A3_WIDTH_MM / w_du, A3_HEIGHT_MM / h_du


# ---------------------------------------------------------------------------
# Component position extraction
# ---------------------------------------------------------------------------

def _extract_component_positions(msp) -> dict[str, list[dict]]:
    """모든 INSERT 엔티티의 위치를 블록 이름별로 수집."""
    positions: dict[str, list[dict]] = defaultdict(list)
    for e in msp:
        if e.dxftype() != "INSERT":
            continue
        name = e.dxf.name
        if name.startswith("*"):
            continue
        positions[name].append({
            "x": e.dxf.insert.x,
            "y": e.dxf.insert.y,
            "scale": getattr(e.dxf, "xscale", 1.0),
            "rotation": getattr(e.dxf, "rotation", 0.0),
        })
    return dict(positions)


def _find_spine_components(positions: dict) -> dict[str, float]:
    """스파인 위의 주요 컴포넌트 Y좌표 추출.

    Returns: {"main_breaker_y": ..., "rccb_y": ..., "isolator_y": ..., "busbar_y": ...}
    """
    result: dict[str, float] = {}

    # Main breaker: scale이 가장 큰 MCCB (서브회로 MCCBs보다 큼)
    mccbs = positions.get("MCCB", [])
    if mccbs:
        upright = [m for m in mccbs if abs(m["rotation"]) < 1.0]
        if upright:
            # 가장 낮은 Y의 upright MCCB = 메인 차단기 (전원 가까운 쪽)
            sorted_by_y = sorted(upright, key=lambda m: m["y"])
            # 서브회로 MCCB와 구분: Y가 가장 낮은 것이 메인
            main_y = sorted_by_y[0]["y"]
            result["main_breaker_y"] = main_y

            # 서브회로 Y: 가장 많은 MCCB가 있는 Y 레벨
            if len(upright) >= 3:
                from collections import Counter
                y_bins = Counter(round(m["y"] / 200) * 200 for m in upright)
                most_common_bin = y_bins.most_common(1)[0][0]
                subckt_group = [m for m in upright if abs(round(m["y"] / 200) * 200 - most_common_bin) < 1]
                if subckt_group:
                    result["subcircuit_y"] = sum(m["y"] for m in subckt_group) / len(subckt_group)

    # RCCB
    rccbs = positions.get("RCCB", [])
    if rccbs:
        upright_rccbs = [r for r in rccbs if abs(r["rotation"]) < 1.0]
        if upright_rccbs:
            # 가장 낮은 Y의 RCCB
            result["rccb_y"] = min(r["y"] for r in upright_rccbs)

    # Isolator (DP ISOL or 3P ISOL)
    for iso_name in ("DP ISOL", "3P ISOL"):
        isos = positions.get(iso_name, [])
        if isos:
            result["isolator_y"] = min(i["y"] for i in isos)
            break

    return result


# ---------------------------------------------------------------------------
# Spacing extraction
# ---------------------------------------------------------------------------

def _extract_subcircuit_ratios(
    positions: dict, frame_width_du: float,
) -> tuple[float, float, int]:
    """서브회로 비율 추출.

    Returns: (span_ratio, spacing_per_circuit_ratio, circuit_count)
    """
    mccbs = positions.get("MCCB", [])
    upright = [m for m in mccbs if abs(m["rotation"]) < 1.0]
    if len(upright) < 3 or frame_width_du <= 0:
        return 0, 0, len(upright)

    # Y 그룹핑: 가장 많은 MCCBs가 있는 Y 레벨 = 서브회로 행
    from collections import Counter
    y_bins = Counter(round(m["y"] / 300) * 300 for m in upright)
    largest_bin = y_bins.most_common(1)[0][0]
    subckt = [m for m in upright if abs(round(m["y"] / 300) * 300 - largest_bin) < 1]

    if len(subckt) < 3:
        return 0, 0, len(subckt)

    sorted_x = sorted(m["x"] for m in subckt)
    total_span = sorted_x[-1] - sorted_x[0]
    n = len(subckt)

    span_ratio = total_span / frame_width_du
    spacing_ratio = span_ratio / (n - 1) if n > 1 else 0

    return round(span_ratio, 4), round(spacing_ratio, 6), n


def _extract_spine_gap_ratios(
    spine_comps: dict[str, float], frame_height_du: float,
) -> dict[str, float]:
    """스파인 컴포넌트 간 Y간격 비율 추출 (gap / frame_height)."""
    if frame_height_du <= 0:
        return {}

    ratios: dict[str, float] = {}
    pairs = [
        ("isolator_y", "main_breaker_y", "isolator_to_breaker"),
        ("main_breaker_y", "rccb_y", "breaker_to_rccb"),
        ("rccb_y", "subcircuit_y", "rccb_to_busbar"),
    ]

    for from_key, to_key, gap_name in pairs:
        if from_key in spine_comps and to_key in spine_comps:
            gap_du = spine_comps[to_key] - spine_comps[from_key]
            if gap_du > 0:
                ratios[gap_name] = round(gap_du / frame_height_du, 4)

    return ratios


# ---------------------------------------------------------------------------
# Phase A: Extended parameter extraction
# ---------------------------------------------------------------------------

def _extract_spine_component_gap(spine_comps: dict[str, float], mm_per_du_y: float) -> float | None:
    """스파인 컴포넌트 간 평균 간격 (mm).

    인접한 스파인 컴포넌트 쌍의 Y 간격 중앙값.
    """
    ys = sorted(spine_comps.values())
    if len(ys) < 2 or mm_per_du_y <= 0:
        return None
    gaps = [(ys[i + 1] - ys[i]) * mm_per_du_y for i in range(len(ys) - 1)]
    gaps = [g for g in gaps if 1.0 < g < 50.0]  # 이상치 제거
    if not gaps:
        return None
    gaps.sort()
    return round(gaps[len(gaps) // 2], 2)


def _extract_busbar_to_breaker_gap(
    spine_comps: dict[str, float], positions: dict, mm_per_du_y: float,
) -> float | None:
    """부스바 Y → 가장 가까운 서브회로 브레이커 Y 간격 (mm).

    subcircuit_y가 spine_comps에 있으면 사용, 없으면 MCCB Y 그룹의 가장 높은 레벨.
    """
    if mm_per_du_y <= 0:
        return None

    subckt_y = spine_comps.get("subcircuit_y")
    main_y = spine_comps.get("main_breaker_y")
    rccb_y = spine_comps.get("rccb_y")

    # busbar는 subcircuit_y 바로 아래에 위치
    # busbar_to_breaker = subcircuit_y - (rccb_y or main_breaker_y 중 더 높은 것)
    # 대신 간단히: subcircuit_y가 있고 해당 DXF에서 busbar-breaker 간격을 직접 측정
    if subckt_y is None:
        return None

    # MCCBs 중 subcircuit 행에 속하는 것들의 Y와 busbar(== subcircuit_y) 간 차이
    mccbs = positions.get("MCCB", [])
    upright = [m for m in mccbs if abs(m["rotation"]) < 1.0]
    if not upright:
        return None

    from collections import Counter
    y_bins = Counter(round(m["y"] / 300) * 300 for m in upright)
    largest_bin = y_bins.most_common(1)[0][0]
    subckt_mccbs = [m for m in upright if abs(round(m["y"] / 300) * 300 - largest_bin) < 1]
    if not subckt_mccbs:
        return None

    avg_subckt_y = sum(m["y"] for m in subckt_mccbs) / len(subckt_mccbs)
    # busbar는 subcircuit MCCB보다 약간 아래 — 여기서는 subcircuit_y를 busbar Y로 근사
    # busbar_to_breaker = avg_subckt_mccb_y - subcircuit_y (busbar)
    gap_du = avg_subckt_y - subckt_y
    gap_mm = abs(gap_du) * mm_per_du_y
    if 3.0 < gap_mm < 30.0:
        return round(gap_mm, 2)
    return None


def _extract_row_spacing(positions: dict, mm_per_du_y: float) -> float | None:
    """다열 회로 행간 간격 (mm).

    MCCB Y 좌표를 클러스터링하여 행 간 간격 추출.
    """
    if mm_per_du_y <= 0:
        return None

    mccbs = positions.get("MCCB", [])
    upright = [m for m in mccbs if abs(m["rotation"]) < 1.0]
    if len(upright) < 6:
        return None  # 행이 2개 이상이려면 최소 6개 MCCB

    from collections import Counter
    y_bins = Counter(round(m["y"] / 300) * 300 for m in upright)

    if len(y_bins) < 2:
        return None  # 단일 행

    # 행 중심 Y 값 추출
    row_ys = sorted(y_bins.keys())
    gaps = [(row_ys[i + 1] - row_ys[i]) * mm_per_du_y for i in range(len(row_ys) - 1)]
    gaps = [g for g in gaps if 20.0 < g < 120.0]  # 합리적 범위
    if not gaps:
        return None
    gaps.sort()
    return round(gaps[len(gaps) // 2], 2)


def _extract_label_char_height(msp, mm_per_du_y: float) -> float | None:
    """TEXT/MTEXT 엔티티의 char_height 중앙값 (mm)."""
    if mm_per_du_y <= 0:
        return None

    heights: list[float] = []
    for e in msp:
        dxf_type = e.dxftype()
        if dxf_type == "TEXT":
            h = getattr(e.dxf, "height", 0)
            if h > 0:
                heights.append(h * mm_per_du_y)
        elif dxf_type == "MTEXT":
            h = getattr(e.dxf, "char_height", 0)
            if h > 0:
                heights.append(h * mm_per_du_y)

    # 비정상 크기 필터 (0.5mm ~ 10mm)
    heights = [h for h in heights if 0.5 < h < 10.0]
    if not heights:
        return None
    heights.sort()
    return round(heights[len(heights) // 2], 2)


def _extract_isolator_to_db_gap(
    spine_comps: dict[str, float], mm_per_du_y: float,
) -> float | None:
    """아이솔레이터 → 메인 브레이커 간격 (mm).

    isolator_y와 main_breaker_y 사이 거리.
    """
    iso_y = spine_comps.get("isolator_y")
    main_y = spine_comps.get("main_breaker_y")
    if iso_y is None or main_y is None or mm_per_du_y <= 0:
        return None
    gap_mm = abs(main_y - iso_y) * mm_per_du_y
    if 5.0 < gap_mm < 40.0:
        return round(gap_mm, 2)
    return None


# ---------------------------------------------------------------------------
# Main profiler
# ---------------------------------------------------------------------------

    # Usable dimensions for mm conversion (A3 landscape minus margins/title block)
USABLE_WIDTH_MM = 370.0   # 420 - 2*25mm margins
USABLE_HEIGHT_MM = 223.0  # 297 - 62mm(title) - 12mm(top margin)


def extract_profile(dxf_path: Path) -> SpacingProfile | None:
    """단일 DXF에서 간격 프로파일 추출 (비율 기반)."""
    try:
        doc = ezdxf.readfile(str(dxf_path))
    except Exception as e:
        logger.error("Failed to read %s: %s", dxf_path.name, e)
        return None

    msp = doc.modelspace()

    # 프레임 크기 (DU)
    frame_xs, frame_ys = [], []
    for e in msp:
        if e.dxftype() == "LINE" and e.dxf.layer == "E-SLD-FRAME":
            frame_xs.extend([e.dxf.start.x, e.dxf.end.x])
            frame_ys.extend([e.dxf.start.y, e.dxf.end.y])
    if not frame_xs:
        logger.warning("  %s: no E-SLD-FRAME, skipping", dxf_path.name)
        return None

    frame_w = max(frame_xs) - min(frame_xs)
    frame_h = max(frame_ys) - min(frame_ys)

    # SLD 유형 분류
    from app.sld.regression.extractor import classify_sld_type
    block_names = set()
    for e in msp:
        if e.dxftype() == "INSERT" and not e.dxf.name.startswith("*"):
            block_names.add(e.dxf.name)
    sld_type = classify_sld_type(dxf_path.name, block_names)

    # 컴포넌트 위치
    positions = _extract_component_positions(msp)

    # 서브회로 비율
    span_ratio, spacing_ratio, circuits = _extract_subcircuit_ratios(positions, frame_w)

    # 스파인 간격 비율
    spine_comps = _find_spine_components(positions)
    spine_gap_ratios = _extract_spine_gap_ratios(spine_comps, frame_h)

    # 비율 → mm 환산 (usable 영역 기준)
    subcircuit_spacing_mm = round(spacing_ratio * USABLE_WIDTH_MM, 2) if spacing_ratio else 0
    spine_gaps_mm = {
        k: round(v * USABLE_HEIGHT_MM, 2)
        for k, v in spine_gap_ratios.items()
    }

    # Phase A: DU→mm 변환 계수
    mm_x, mm_y = _compute_du_to_mm(msp)

    # Phase A: 확장 파라미터 추출
    extended: dict[str, float] = {}

    scg = _extract_spine_component_gap(spine_comps, mm_y)
    if scg is not None:
        extended["spine_component_gap"] = scg

    btb = _extract_busbar_to_breaker_gap(spine_comps, positions, mm_y)
    if btb is not None:
        extended["busbar_to_breaker_gap"] = btb

    itd = _extract_isolator_to_db_gap(spine_comps, mm_y)
    if itd is not None:
        extended["isolator_to_db_gap"] = itd

    rs = _extract_row_spacing(positions, mm_y)
    if rs is not None:
        extended["row_spacing"] = rs

    lch = _extract_label_char_height(msp, mm_y)
    if lch is not None:
        extended["label_char_height_raw"] = lch  # 원본 DXF 크기 (참고용, 직접 적용 안 함)

    return SpacingProfile(
        filename=dxf_path.name,
        sld_type=sld_type,
        circuits=circuits,
        subcircuit_span_ratio=span_ratio,
        subcircuit_spacing_per_circuit_ratio=spacing_ratio,
        spine_gap_ratios=spine_gap_ratios,
        subcircuit_spacing_mm=subcircuit_spacing_mm,
        spine_gaps_mm=spine_gaps_mm,
        extended=extended,
    )


def extract_all_profiles(dxf_dir: Path | None = None) -> dict:
    """28개 DXF 전수 프로파일 추출 → JSON 저장."""
    dxf_dir = dxf_dir or DXF_DIR

    profiles: dict[str, Any] = {}
    type_profiles: dict[str, list[SpacingProfile]] = defaultdict(list)

    for dxf_file in sorted(dxf_dir.glob("*.dxf")):
        profile = extract_profile(dxf_file)
        if profile:
            profiles[profile.filename] = profile.to_dict()
            type_profiles[profile.sld_type].append(profile)
            logger.info(
                "  OK %-40s span=%.3f subckt=%.1fmm (%d circuits) spine=%s",
                profile.filename, profile.subcircuit_span_ratio,
                profile.subcircuit_spacing_mm, profile.circuits,
                profile.spine_gaps_mm,
            )

    # 유형별 집계
    aggregated: dict[str, dict] = {}
    for sld_type, profs in type_profiles.items():
        sub_spacings = [p.subcircuit_spacing_mm for p in profs if p.subcircuit_spacing_mm > 0]
        spine_keys = set()
        for p in profs:
            spine_keys.update(p.spine_gaps_mm.keys())

        agg: dict[str, Any] = {"count": len(profs)}
        if sub_spacings:
            sorted_ss = sorted(sub_spacings)
            agg["subcircuit_spacing_mm"] = {
                "min": round(min(sub_spacings), 1),
                "max": round(max(sub_spacings), 1),
                "median": round(sorted_ss[len(sorted_ss) // 2], 1),
            }

        for key in sorted(spine_keys):
            vals = [p.spine_gaps_mm.get(key, 0) for p in profs if p.spine_gaps_mm.get(key, 0) > 0]
            if vals:
                sorted_v = sorted(vals)
                agg[f"spine_{key}_mm"] = {
                    "min": round(min(vals), 1),
                    "max": round(max(vals), 1),
                    "median": round(sorted_v[len(sorted_v) // 2], 1),
                }

        # Phase A: 확장 파라미터 집계
        ext_keys: set[str] = set()
        for p in profs:
            ext_keys.update(p.extended.keys())
        for key in sorted(ext_keys):
            vals = [p.extended.get(key, 0) for p in profs if p.extended.get(key, 0) > 0]
            if vals:
                sorted_v = sorted(vals)
                agg[f"ext_{key}"] = {
                    "min": round(min(vals), 1),
                    "max": round(max(vals), 1),
                    "median": round(sorted_v[len(sorted_v) // 2], 1),
                }

        aggregated[sld_type] = agg

    # Save
    result = {
        "_meta": {
            "total_files": len(profiles),
            "conversion": "per-file DU→mm via E-SLD-FRAME → A3(420x297mm)",
        },
        "profiles": profiles,
        "aggregated": aggregated,
    }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    logger.info("\nSaved %d profiles to %s", len(profiles), OUTPUT_PATH)

    return result


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    result = extract_all_profiles()

    print(f"\n=== Aggregated Spacing (mm) by SLD Type ===")
    for sld_type, agg in result.get("aggregated", {}).items():
        print(f"\n{sld_type} ({agg['count']} files):")
        for key, val in agg.items():
            if key != "count":
                print(f"  {key}: {val}")


if __name__ == "__main__":
    main()
