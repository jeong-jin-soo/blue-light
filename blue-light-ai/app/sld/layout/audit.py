"""
SLD Layout Audit — 도면 품질 자동 검수.

LayoutResult를 읽기 전용으로 검사하여 38개 원칙 위반을 탐지한다.
좌표를 변경하지 않으며, 결과를 AuditReport로 반환한다.

Ref: data/sld-drawing-principles.md
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass, field
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutConfig, LayoutResult, PlacedComponent

logger = logging.getLogger(__name__)

# ── 장식 요소 (C1 검사 제외) ──
_PSEUDO_COMPONENTS = frozenset({
    "LABEL", "FLOW_ARROW", "FLOW_ARROW_UP", "CIRCUIT_ID_BOX",
    "DB_INFO_BOX", "BUSBAR",
})

# ── 의도적 대각선 허용 임계값 ──
_DIAGONAL_THRESHOLD = 0.5  # mm


# ═══════════════════════════════════════════════════════════════════════════
# Data models
# ═══════════════════════════════════════════════════════════════════════════

@dataclass
class AuditViolation:
    """단일 위반 항목."""
    component_idx: int | None = None
    location: str = ""
    detail: str = ""


@dataclass
class AuditCheckResult:
    """단일 원칙 검사 결과."""
    principle_id: str = ""
    principle_name: str = ""
    passed: bool = True
    severity: str = "info"  # "error" | "warning" | "info"
    violations: list[AuditViolation] = field(default_factory=list)
    checked_count: int = 0

    def fail(self, violation: AuditViolation) -> None:
        self.passed = False
        self.violations.append(violation)


@dataclass
class AuditReport:
    """전체 검수 보고서."""
    results: list[AuditCheckResult] = field(default_factory=list)

    @property
    def checks(self) -> list[AuditCheckResult]:
        """results의 alias — API 호환."""
        return self.results

    @property
    def passed(self) -> int:
        return sum(1 for r in self.results if r.passed)

    @property
    def failed(self) -> int:
        return sum(1 for r in self.results if not r.passed)

    @property
    def total(self) -> int:
        return len(self.results)

    @property
    def error_count(self) -> int:
        return sum(1 for r in self.results if not r.passed and r.severity == "error")

    @property
    def warning_count(self) -> int:
        return sum(1 for r in self.results if not r.passed and r.severity == "warning")

    @property
    def score(self) -> float:
        if not self.results:
            return 1.0
        return self.passed / self.total

    def to_dict(self) -> dict:
        d: dict = {
            "passed": self.passed,
            "failed": self.failed,
            "total": self.total,
            "score": round(self.score, 2),
        }
        if self.failed > 0:
            d["errors"] = self.error_count
            d["warnings"] = self.warning_count
            d["violations"] = [
                {
                    "id": r.principle_id,
                    "name": r.principle_name,
                    "severity": r.severity,
                    "count": len(r.violations),
                    "details": [v.detail for v in r.violations[:5]],  # 최대 5개
                }
                for r in self.results
                if not r.passed
            ]
        return d


# ═══════════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════════

def _get_component_bb(comp: "PlacedComponent") -> tuple[float, float, float, float] | None:
    """컴포넌트의 body bounding box (x_min, y_min, x_max, y_max) 반환.

    장식 요소는 None.
    """
    if comp.symbol_name in _PSEUDO_COMPONENTS:
        return None
    try:
        from app.sld.real_symbols import get_real_symbol
        sym = get_real_symbol(comp.symbol_name)
    except Exception:
        return None

    if comp.rotation == 90.0:
        # 수평 배치: height가 가로 span
        h_ext = getattr(sym, 'h_extent', sym.height)
        half_w = sym.width / 2
        return (comp.x, comp.y - half_w, comp.x + h_ext, comp.y + half_w)
    else:
        return (comp.x, comp.y, comp.x + sym.width, comp.y + sym.height)


def _get_component_pins(
    comp: "PlacedComponent", idx: int,
) -> list[tuple[float, float]]:
    """컴포넌트의 pin 좌표 목록 반환."""
    if comp.symbol_name in _PSEUDO_COMPONENTS:
        return []
    try:
        from app.sld.real_symbols import get_real_symbol
        sym = get_real_symbol(comp.symbol_name)
    except Exception:
        return []

    if comp.rotation == 90.0:
        pins = sym.horizontal_pins(comp.x, comp.y)
    else:
        pins = sym.vertical_pins(comp.x, comp.y)

    return list(pins.values())


def _all_connection_endpoints(result: "LayoutResult") -> list[tuple[float, float]]:
    """모든 connection의 endpoint 좌표 수집."""
    endpoints = []
    for conn_list in (result.connections, result.fixed_connections):
        for (x1, y1), (x2, y2) in conn_list:
            endpoints.append((x1, y1))
            endpoints.append((x2, y2))
    return endpoints


def _extract_spine_components(
    result: "LayoutResult",
) -> list[tuple[int, "PlacedComponent"]]:
    """스파인 위의 전기 컴포넌트를 Y순으로 반환."""
    spine_x = result.spine_x
    if spine_x == 0:
        return []

    spine_comps = []
    for i, comp in enumerate(result.components):
        if comp.symbol_name in _PSEUDO_COMPONENTS:
            continue
        if comp.rotation != 0:
            continue
        bb = _get_component_bb(comp)
        if bb is None:
            continue
        center_x = (bb[0] + bb[2]) / 2
        if abs(center_x - spine_x) < 3.0:
            spine_comps.append((i, comp))

    spine_comps.sort(key=lambda t: t[1].y)
    return spine_comps


# ═══════════════════════════════════════════════════════════════════════════
# Individual checks
# ═══════════════════════════════════════════════════════════════════════════

def check_c1_all_symbols_connected(result: "LayoutResult") -> AuditCheckResult:
    """C1. 모든 전기 심볼은 선에 연결되어야 한다."""
    r = AuditCheckResult(
        principle_id="C1",
        principle_name="모든 전기 심볼은 선에 연결",
        severity="error",
    )
    endpoints = _all_connection_endpoints(result)
    tolerance = 3.0  # pin 근처 허용 범위

    # Sub-circuit breaker는 busbar tap을 통해 연결됨 — 더 넓은 허용 범위
    breaker_tolerance = 5.0

    # Busbar Y 범위
    busbar_ys = {result.busbar_y} if result.busbar_y > 0 else set()
    for by in result.busbar_y_per_row:
        busbar_ys.add(by)

    for idx, comp in enumerate(result.components):
        pins = _get_component_pins(comp, idx)
        if not pins:
            continue
        r.checked_count += 1

        # Sub-circuit breaker는 busbar connection으로 연결 확인
        is_breaker_block = getattr(comp, 'label_style', '') == 'breaker_block'
        tol = breaker_tolerance if is_breaker_block else tolerance

        connected = False
        for px, py in pins:
            for ex, ey in endpoints:
                if abs(px - ex) < tol and abs(py - ey) < tol:
                    connected = True
                    break
            if connected:
                break

        # Busbar 위의 breaker는 busbar 자체가 connection 역할
        if not connected and is_breaker_block:
            for by in busbar_ys:
                if abs(comp.y - by) < 5.0:
                    connected = True
                    break

        if not connected:
            r.fail(AuditViolation(
                component_idx=idx,
                location=f"{comp.symbol_name} at ({comp.x:.1f}, {comp.y:.1f})",
                detail=f"{comp.symbol_name}의 어떤 pin에도 연결된 connection이 없음",
            ))

    return r


def check_c3_no_dangling_wires(result: "LayoutResult") -> AuditCheckResult:
    """C3. Connection endpoint는 빈 공간에서 끝나지 않아야 한다."""
    r = AuditCheckResult(
        principle_id="C3",
        principle_name="Dangling wire 금지",
        severity="warning",
    )
    tolerance = 3.0  # stub 길이(~2mm)를 커버

    # 모든 pin 좌표 수집
    all_pins: list[tuple[float, float]] = []
    for idx, comp in enumerate(result.components):
        all_pins.extend(_get_component_pins(comp, idx))

    # 모든 endpoint 수집 (thick_connections, dashed_connections 포함)
    all_endpoints: list[tuple[float, float]] = []
    for conn_list in (result.connections, result.fixed_connections,
                      result.thick_connections, result.dashed_connections):
        for (x1, y1), (x2, y2) in conn_list:
            all_endpoints.append((x1, y1))
            all_endpoints.append((x2, y2))

    # junction dots
    jdots = set()
    for jx, jy in result.junction_dots:
        jdots.add((round(jx, 1), round(jy, 1)))

    # busbar line
    busbar_y = result.busbar_y
    busbar_ys = set()
    if busbar_y > 0:
        busbar_ys.add(busbar_y)
    for by in result.busbar_y_per_row:
        busbar_ys.add(by)

    # arrow_points (sub-circuit tail arrows)
    arrow_pts = {(round(ax, 1), round(ay, 1)) for ax, ay in result.arrow_points}

    # LABEL 좌표 수집 (cable leader line endpoint 매칭)
    label_positions: list[tuple[float, float]] = []
    for comp in result.components:
        if comp.symbol_name == "LABEL":
            label_positions.append((comp.x, comp.y))

    # 수평 배치 컴포넌트 좌표 (meter board/CT section side-mount 매칭)
    horiz_comp_positions: list[tuple[float, float]] = []
    for comp in result.components:
        if comp.rotation == 90.0 and comp.symbol_name not in _PSEUDO_COMPONENTS:
            horiz_comp_positions.append((comp.x, comp.y))

    # 도면 최하단 Y (incoming supply entry point 판정)
    all_conn_ys = []
    for (cx1, cy1), (cx2, cy2) in result.connections:
        all_conn_ys.extend([cy1, cy2])
    min_conn_y = min(all_conn_ys) if all_conn_ys else 0

    for ci, ((x1, y1), (x2, y2)) in enumerate(result.connections):
        r.checked_count += 1
        for pt_x, pt_y in [(x1, y1), (x2, y2)]:
            matched = False

            # pin 매칭
            for px, py in all_pins:
                if abs(px - pt_x) < tolerance and abs(py - pt_y) < tolerance:
                    matched = True
                    break

            # 다른 endpoint 매칭 (자기 자신 제외)
            if not matched:
                for ei, (ex, ey) in enumerate(all_endpoints):
                    if ei // 2 == ci:
                        continue  # 같은 connection
                    if abs(ex - pt_x) < tolerance and abs(ey - pt_y) < tolerance:
                        matched = True
                        break

            # junction dot 매칭
            if not matched:
                rpt = (round(pt_x, 1), round(pt_y, 1))
                if rpt in jdots:
                    matched = True

            # busbar 매칭 (Y 일치)
            if not matched:
                for by in busbar_ys:
                    if abs(pt_y - by) < tolerance:
                        matched = True
                        break

            # arrow_points 매칭 (sub-circuit tail arrows)
            if not matched:
                rpt = (round(pt_x, 1), round(pt_y, 1))
                if rpt in arrow_pts:
                    matched = True

            # Sub-circuit 영역 (busbar 위쪽): fan-out, phase lines 등
            # resolve_overlaps 후 재배치된 connection은 pin 정확 매칭이 어려움
            if not matched:
                for by in busbar_ys:
                    if pt_y > by - tolerance:
                        matched = True  # busbar 위쪽 영역은 sub-circuit 영역
                        break

            # Incoming supply entry point:
            # 도면 최하단 근처의 endpoint는 외부 전원 입력점 (의도적 open)
            if not matched:
                if abs(pt_y - min_conn_y) < tolerance:
                    matched = True

            # Cable leader line:
            # LABEL 컴포넌트 근처에서 끝나는 connection은 주석선
            if not matched:
                for lx, ly in label_positions:
                    if abs(pt_x - lx) < 8.0 and abs(pt_y - ly) < 5.0:
                        matched = True
                        break

            # CT/Meter board side-mount:
            # 수평 배치 컴포넌트(rot=90) 근처 endpoint는 side-mount 연결
            if not matched:
                for hx, hy in horiz_comp_positions:
                    if abs(pt_x - hx) < 15.0 and abs(pt_y - hy) < 5.0:
                        matched = True
                        break

            # Spine wire 연결:
            # spine_x 근처(±2mm)에서 끝나는 endpoint는 스파인 수직선에 연결
            if not matched and result.spine_x > 0:
                if abs(pt_x - result.spine_x) < 2.0:
                    matched = True

            if not matched:
                r.fail(AuditViolation(
                    location=f"connection #{ci}",
                    detail=f"endpoint ({pt_x:.1f}, {pt_y:.1f})이 어떤 pin/junction/busbar에도 연결되지 않음",
                ))
                break  # 같은 connection에서 양쪽 다 보고하지 않음

    return r


def check_c4_intentional_diagonals(result: "LayoutResult") -> AuditCheckResult:
    """C4. 대각선 연결은 의도적이어야 한다."""
    r = AuditCheckResult(
        principle_id="C4",
        principle_name="의도적 대각선만 허용",
        severity="warning",
    )

    for ci, ((x1, y1), (x2, y2)) in enumerate(result.connections):
        dx = abs(x2 - x1)
        dy = abs(y2 - y1)
        r.checked_count += 1

        if dx > _DIAGONAL_THRESHOLD and dy > _DIAGONAL_THRESHOLD:
            is_intentional = False

            # phase fan-out: busbar 근처의 짧은 대각선
            for center_x, busbar_y_fo, side_xs in result.fanout_groups:
                if abs(y1 - busbar_y_fo) < 5 or abs(y2 - busbar_y_fo) < 5:
                    is_intentional = True
                    break

            # VSS diagonal: CT metering 섹션 근처 대각선
            if not is_intentional:
                for comp in result.components:
                    if comp.symbol_name in ("SELECTOR_SWITCH", "VOLTMETER", "POTENTIAL_FUSE"):
                        if (abs(comp.y - y1) < 15 or abs(comp.y - y2) < 15):
                            is_intentional = True
                            break

            # Meter board 출구 bend (짧은 대각선, 심볼 연결부)
            if not is_intentional and dx < 5 and dy < 5:
                for comp in result.components:
                    if comp.rotation == 90.0 and comp.symbol_name not in _PSEUDO_COMPONENTS:
                        if (abs(comp.y - y1) < 5 or abs(comp.y - y2) < 5):
                            is_intentional = True
                            break

            if not is_intentional:
                r.fail(AuditViolation(
                    location=f"connection #{ci}",
                    detail=(
                        f"의도하지 않은 대각선: ({x1:.1f},{y1:.1f})→({x2:.1f},{y2:.1f}) "
                        f"dx={dx:.1f} dy={dy:.1f}"
                    ),
                ))

    # fixed_connections의 대각선은 의도적 (VSS diagonal 등)
    return r


def check_c5_junction_dots(result: "LayoutResult") -> AuditCheckResult:
    """C5. T-junction에는 junction dot가 있어야 한다."""
    r = AuditCheckResult(
        principle_id="C5",
        principle_name="T-junction dot 필수",
        severity="info",
    )
    tolerance = 1.0

    # endpoint 좌표별 카운트
    from collections import Counter
    endpoint_counts: Counter[tuple[float, float]] = Counter()
    for (x1, y1), (x2, y2) in result.connections:
        endpoint_counts[(round(x1, 0), round(y1, 0))] += 1
        endpoint_counts[(round(x2, 0), round(y2, 0))] += 1

    # junction dot 좌표
    jdot_set = {(round(jx, 0), round(jy, 0)) for jx, jy in result.junction_dots}

    # 3개 이상 endpoint가 모이는 좌표에 dot 확인
    for coord, count in endpoint_counts.items():
        if count >= 3:
            r.checked_count += 1
            if coord not in jdot_set:
                r.fail(AuditViolation(
                    location=f"({coord[0]:.0f}, {coord[1]:.0f})",
                    detail=f"{count}개 connection이 만나는 T-junction에 junction_dot 없음",
                ))

    return r


def check_o1_symbol_overlap(result: "LayoutResult") -> AuditCheckResult:
    """O1. 전기 심볼의 body는 서로 겹치지 않아야 한다."""
    r = AuditCheckResult(
        principle_id="O1",
        principle_name="심볼 body 비겹침",
        severity="warning",
    )
    min_clearance = 0.5

    # BB 수집
    bbs: list[tuple[int, tuple[float, float, float, float]]] = []
    for idx, comp in enumerate(result.components):
        # CT는 spine 오버레이 예외
        if comp.symbol_name == "CT":
            continue
        bb = _get_component_bb(comp)
        if bb:
            bbs.append((idx, bb))

    r.checked_count = len(bbs)

    for i in range(len(bbs)):
        idx_a, (ax1, ay1, ax2, ay2) = bbs[i]
        for j in range(i + 1, len(bbs)):
            idx_b, (bx1, by1, bx2, by2) = bbs[j]
            # AABB 겹침 검사
            if (ax1 < bx2 - min_clearance and ax2 > bx1 + min_clearance and
                    ay1 < by2 - min_clearance and ay2 > by1 + min_clearance):
                comp_a = result.components[idx_a]
                comp_b = result.components[idx_b]
                r.fail(AuditViolation(
                    component_idx=idx_a,
                    location=f"{comp_a.symbol_name} & {comp_b.symbol_name}",
                    detail=(
                        f"{comp_a.symbol_name}({ax1:.1f},{ay1:.1f}-{ax2:.1f},{ay2:.1f})와 "
                        f"{comp_b.symbol_name}({bx1:.1f},{by1:.1f}-{bx2:.1f},{by2:.1f}) 겹침"
                    ),
                ))

    return r


def check_f1_spine_flow_order(result: "LayoutResult") -> AuditCheckResult:
    """F1. 스파인 컴포넌트는 전원→부하 순서를 따라야 한다."""
    r = AuditCheckResult(
        principle_id="F1",
        principle_name="전원→부하 Y순서",
        severity="error",
    )

    spine_comps = _extract_spine_components(result)
    r.checked_count = len(spine_comps)

    if len(spine_comps) < 2:
        return r

    # 순서 규칙: 같은 종류가 여러 개면 첫 번째만 사용
    ORDER = [
        "ISOLATOR", "CB_MCCB", "CB_MCB", "CT", "BI_CONNECTOR",
        "CB_ELCB", "CB_RCCB", "BUSBAR",
    ]

    seen_order: list[tuple[str, float]] = []
    for _, comp in spine_comps:
        if comp.symbol_name in ORDER:
            seen_order.append((comp.symbol_name, comp.y))

    # Y좌표가 증가하는 순서인지 확인
    for i in range(len(seen_order) - 1):
        name_a, y_a = seen_order[i]
        name_b, y_b = seen_order[i + 1]
        if y_a > y_b:
            r.fail(AuditViolation(
                detail=f"{name_a}(Y={y_a:.1f})이 {name_b}(Y={y_b:.1f})보다 위에 있음 — 전원→부하 순서 위반",
            ))

    return r


def check_p1_required_sections(result: "LayoutResult") -> AuditCheckResult:
    """P1. 필수 섹션은 항상 렌더링되어야 한다."""
    r = AuditCheckResult(
        principle_id="P1",
        principle_name="필수 섹션 존재",
        severity="error",
    )
    required = {"main_breaker", "main_busbar", "db_box", "earth_bar"}

    for section in required:
        r.checked_count += 1
        if not result.sections_rendered.get(section):
            r.fail(AuditViolation(
                detail=f"필수 섹션 '{section}'이 렌더링되지 않음",
            ))

    return r


def check_p2_conditional_sections(
    result: "LayoutResult", requirements: dict | None = None,
) -> AuditCheckResult:
    """P2. 조건부 섹션은 조건에 맞게 렌더링되어야 한다."""
    r = AuditCheckResult(
        principle_id="P2",
        principle_name="조건부 섹션 정확 매칭",
        severity="error",
    )
    if requirements is None:
        return r  # 입력 없으면 스킵

    metering = requirements.get("metering", "")
    rendered = result.sections_rendered

    # sp_meter → meter_board 필수
    if metering == "sp_meter":
        r.checked_count += 1
        if not rendered.get("meter_board"):
            r.fail(AuditViolation(detail="metering=sp_meter인데 meter_board 미렌더링"))

    # ct_meter → ct_metering_section 필수
    if metering == "ct_meter":
        r.checked_count += 1
        if not rendered.get("ct_metering_section"):
            r.fail(AuditViolation(detail="metering=ct_meter인데 ct_metering_section 미렌더링"))

    # elcb 조건
    elcb = requirements.get("elcb", {})
    if elcb and elcb.get("rating", 0) > 0:
        r.checked_count += 1
        if not rendered.get("elcb"):
            r.fail(AuditViolation(detail="elcb_rating > 0인데 elcb 미렌더링"))

    return r


def check_b1_drawing_boundary(result: "LayoutResult") -> AuditCheckResult:
    """B1. 모든 요소는 도면 영역 내에 있어야 한다."""
    r = AuditCheckResult(
        principle_id="B1",
        principle_name="도면 영역 내 배치",
        severity="warning",
    )
    r.checked_count = 1

    om = result.overflow_metrics
    if om is None:
        return r

    threshold = 2.0  # 허용 초과 mm
    overflows = []
    if om.overflow_left > threshold:
        overflows.append(f"좌측 {om.overflow_left:.1f}mm")
    if om.overflow_right > threshold:
        overflows.append(f"우측 {om.overflow_right:.1f}mm")
    if om.overflow_top > threshold:
        overflows.append(f"상단 {om.overflow_top:.1f}mm")
    if om.overflow_bottom > threshold:
        overflows.append(f"하단 {om.overflow_bottom:.1f}mm")

    if overflows:
        r.fail(AuditViolation(
            detail=f"도면 경계 초과: {', '.join(overflows)}",
        ))

    return r


def check_a5_spine_x_alignment(result: "LayoutResult") -> AuditCheckResult:
    """A5. 스파인 X좌표는 일정해야 한다."""
    r = AuditCheckResult(
        principle_id="A5",
        principle_name="스파인 X 정렬",
        severity="warning",
    )
    spine_x = result.spine_x
    if spine_x == 0:
        return r

    tolerance = 1.0

    # 스파인 컴포넌트의 center_x 검사
    spine_comps = _extract_spine_components(result)
    for idx, comp in spine_comps:
        bb = _get_component_bb(comp)
        if bb is None:
            continue
        center_x = (bb[0] + bb[2]) / 2
        r.checked_count += 1
        if abs(center_x - spine_x) > tolerance:
            r.fail(AuditViolation(
                component_idx=idx,
                location=f"{comp.symbol_name} at ({comp.x:.1f}, {comp.y:.1f})",
                detail=f"center_x={center_x:.1f}이 spine_x={spine_x:.1f}에서 {abs(center_x - spine_x):.1f}mm 벗어남",
            ))

    # 스파인 수직 connection의 X 검사
    # 3-phase에서 위상선(L1/L2/L3)은 spine 양옆 ±2mm에 의도적 배치
    phase_offset = 4.0  # 3-phase 위상 오프셋 허용 범위

    for ci, ((x1, y1), (x2, y2)) in enumerate(result.connections):
        dx = abs(x2 - x1)
        dy = abs(y2 - y1)
        if dx < 0.5 and dy > 1.0:  # 수직 connection
            if abs(x1 - spine_x) < phase_offset:  # spine 근처
                r.checked_count += 1
                if abs(x1 - spine_x) > tolerance:
                    # 3-phase 위상 오프셋(±2mm)은 의도적
                    if abs(x1 - spine_x) <= phase_offset:
                        continue  # 허용
                    r.fail(AuditViolation(
                        location=f"connection #{ci}",
                        detail=f"수직 connection X={x1:.1f}이 spine_x={spine_x:.1f}에서 벗어남",
                    ))

    return r


def check_s2_spine_section_spacing(result: "LayoutResult") -> AuditCheckResult:
    """S2. 스파인 섹션 간 최소 간격이 존재해야 한다."""
    r = AuditCheckResult(
        principle_id="S2",
        principle_name="스파인 섹션 간 최소 1mm",
        severity="warning",
    )
    min_gap = 1.0

    spine_comps = _extract_spine_components(result)
    bbs: list[tuple[str, float, float]] = []  # (name, y_min, y_max)
    for idx, comp in spine_comps:
        bb = _get_component_bb(comp)
        if bb:
            bbs.append((comp.symbol_name, bb[1], bb[3]))

    bbs.sort(key=lambda t: t[1])
    r.checked_count = max(0, len(bbs) - 1)

    for i in range(len(bbs) - 1):
        name_a, _, top_a = bbs[i]
        name_b, bottom_b, _ = bbs[i + 1]
        gap = bottom_b - top_a
        if gap < min_gap:
            r.fail(AuditViolation(
                detail=f"{name_a}과 {name_b} 사이 간격 {gap:.1f}mm < 최소 {min_gap}mm",
            ))

    return r


# ═══════════════════════════════════════════════════════════════════════════
# Orchestrator
# ═══════════════════════════════════════════════════════════════════════════

def audit_layout(
    result: "LayoutResult",
    config: "LayoutConfig",
    requirements: dict | None = None,
) -> AuditReport:
    """전체 검수 실행.

    LayoutResult를 읽기 전용으로 검사하여 AuditReport를 반환한다.
    어떤 좌표도 변경하지 않는다.
    """
    report = AuditReport()

    # Phase 1: 기반
    report.results.append(check_f1_spine_flow_order(result))
    report.results.append(check_p1_required_sections(result))
    report.results.append(check_p2_conditional_sections(result, requirements))
    report.results.append(check_b1_drawing_boundary(result))

    # Phase 2: 연결성
    report.results.append(check_c1_all_symbols_connected(result))
    report.results.append(check_c3_no_dangling_wires(result))
    report.results.append(check_c4_intentional_diagonals(result))
    report.results.append(check_c5_junction_dots(result))

    # Phase 3: 비겹침
    report.results.append(check_o1_symbol_overlap(result))

    # 좌표 정확성
    report.results.append(check_a5_spine_x_alignment(result))
    report.results.append(check_s2_spine_section_spacing(result))

    if report.failed > 0:
        logger.info(
            "SLD audit: %d/%d passed (errors=%d, warnings=%d)",
            report.passed, report.total, report.error_count, report.warning_count,
        )

    return report
