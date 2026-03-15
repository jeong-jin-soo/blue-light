"""
미터보드 커넥션 갭 회귀 테스트.

버그: validate_connectivity()가 수평 미터보드 커넥션 엔드포인트를
procedural symbol의 stub 포함 핀 위치로 스냅하여 2mm 갭 발생.

DXF 블록은 body edge에 핀이 있고, sections.py의 _place_meter_board_symbols()도
body edge 위치에 커넥션을 생성함. validate_connectivity()가 이를 ±2mm stub
위치로 이동시키면 ISO↔KWH, KWH↔MCB 사이에 갭이 생김.

수정: validate_connectivity()에서 horizontal_pins() 결과에서 stub offset을 제거하여
body edge 위치를 사용하도록 변경.

이 테스트는 수정이 유지되는지 검증함.
"""

import pytest

from app.sld.layout import compute_layout, LayoutResult, PlacedComponent
from app.sld.layout.connectivity import validate_connectivity
from app.sld.layout.models import LayoutConfig


# ---------------------------------------------------------------------------
# 최소 미터보드 생성 요구사항 (single-phase metered)
# ---------------------------------------------------------------------------

SINGLE_PHASE_METERED = {
    "supply_type": "single_phase",
    "kva": 9,
    "voltage": 230,
    "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 30, "poles": 2, "type": "RCCB"},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B", "cable": "2C 1.5sqmm PVC/PVC"},
    ],
}

THREE_PHASE_METERED = {
    "supply_type": "three_phase",
    "kva": 22,
    "voltage": 400,
    "main_breaker": {"type": "MCB", "rating": 32, "poles": "TPN", "fault_kA": 10},
    "busbar_rating": 100,
    "metering": "sp_meter",
    "elcb": {"rating": 40, "sensitivity_ma": 100, "poles": 4},
    "sub_circuits": [
        {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10,
         "breaker_characteristic": "B",
         "cable": "2 x 1C 1.5sqmm PVC + 1.5sqmm PVC CPC IN METAL TRUNKING"},
    ],
}


# ---------------------------------------------------------------------------
# 헬퍼: 미터보드 컴포넌트 및 커넥션 추출
# ---------------------------------------------------------------------------

def _find_meter_board_components(result: LayoutResult) -> dict[str, PlacedComponent]:
    """미터보드 수평 컴포넌트(ISO, KWH, MCB) 찾기.

    미터보드 컴포넌트는 rotation=90.0이고 동일 Y 라인에 배치됨.
    """
    iso = kwh = mcb = None
    for comp in result.components:
        if comp.rotation != 90.0:
            continue
        if comp.symbol_name == "ISOLATOR":
            iso = comp
        elif comp.symbol_name == "KWH_METER":
            kwh = comp
        elif comp.symbol_name == "CB_MCB" and comp.label_style != "breaker_block":
            # 미터보드 MCB (서브서킷 브레이커가 아닌 것)
            if mcb is None:
                mcb = comp

    return {"ISO": iso, "KWH": kwh, "MCB": mcb}


def _find_horizontal_connections_at_y(
    result: LayoutResult, target_y: float, tol: float = 0.5,
) -> list[tuple[tuple[float, float], tuple[float, float]]]:
    """특정 Y 좌표의 수평 커넥션만 필터링."""
    horizontal = []
    for start, end in result.connections:
        dy = abs(start[1] - end[1])
        if dy > 0.5:
            continue  # 수평이 아님
        avg_y = (start[1] + end[1]) / 2
        if abs(avg_y - target_y) < tol:
            horizontal.append((start, end))
    return horizontal


def _get_body_edge_x(comp: PlacedComponent) -> tuple[float, float]:
    """컴포넌트의 body edge X 위치 반환 (left_x, right_x).

    rotation=90 수평 배치 시, comp.x = left edge.
    h_extent는 심볼별로 다름 (height 기준).
    """
    from app.sld.real_symbols import get_real_symbol
    sym = get_real_symbol(comp.symbol_name)
    h_extent = sym.h_extent
    left_x = comp.x
    right_x = comp.x + h_extent
    return left_x, right_x


# ---------------------------------------------------------------------------
# 테스트: 미터보드 커넥션이 body edge에 정렬되는지 검증
# ---------------------------------------------------------------------------

class TestMeterBoardConnectionAlignment:
    """validate_connectivity() 이후 미터보드 커넥션 갭 없음을 검증."""

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_iso_kwh_connection_no_gap(self, requirements: dict):
        """ISO 오른쪽 → KWH 왼쪽 커넥션이 body edge에 정확히 맞는지 확인.

        회귀 조건: validate_connectivity()가 stub offset(±2mm)만큼
        엔드포인트를 이동시켜 갭이 생기면 실패.
        """
        result = compute_layout(requirements)
        comps = _find_meter_board_components(result)

        assert comps["ISO"] is not None, "ISOLATOR 컴포넌트를 찾을 수 없음"
        assert comps["KWH"] is not None, "KWH_METER 컴포넌트를 찾을 수 없음"

        iso_left, iso_right = _get_body_edge_x(comps["ISO"])
        kwh_left, kwh_right = _get_body_edge_x(comps["KWH"])

        mb_y = comps["ISO"].y  # 미터보드 Y 좌표

        # 미터보드 Y 라인의 수평 커넥션 수집
        h_conns = _find_horizontal_connections_at_y(result, mb_y)
        assert len(h_conns) > 0, f"미터보드 Y={mb_y}에 수평 커넥션 없음"

        # ISO right → KWH left 커넥션 찾기
        iso_kwh_conn = None
        for start, end in h_conns:
            # 방향 무관: 어느 쪽이 ISO right이고 어느 쪽이 KWH left인지
            s_x, e_x = min(start[0], end[0]), max(start[0], end[0])
            if (abs(s_x - iso_right) < 1.0 or abs(e_x - iso_right) < 1.0) and \
               (abs(s_x - kwh_left) < 1.0 or abs(e_x - kwh_left) < 1.0):
                iso_kwh_conn = (start, end)
                break

        assert iso_kwh_conn is not None, (
            f"ISO({iso_right:.1f}) → KWH({kwh_left:.1f}) 커넥션을 찾을 수 없음. "
            f"수평 커넥션: {[(f'{s[0]:.1f}→{e[0]:.1f}') for s, e in h_conns]}"
        )

        # 엔드포인트가 body edge에 정확히 맞는지 검증 (±0.5mm 허용)
        conn_left_x = min(iso_kwh_conn[0][0], iso_kwh_conn[1][0])
        conn_right_x = max(iso_kwh_conn[0][0], iso_kwh_conn[1][0])

        assert abs(conn_left_x - iso_right) < 0.5, (
            f"ISO→KWH 커넥션 왼쪽 끝({conn_left_x:.2f})이 "
            f"ISO body edge({iso_right:.2f})에서 {abs(conn_left_x - iso_right):.2f}mm 벗어남 — "
            f"stub offset으로 인한 갭 발생 가능"
        )
        assert abs(conn_right_x - kwh_left) < 0.5, (
            f"ISO→KWH 커넥션 오른쪽 끝({conn_right_x:.2f})이 "
            f"KWH body edge({kwh_left:.2f})에서 {abs(conn_right_x - kwh_left):.2f}mm 벗어남 — "
            f"stub offset으로 인한 갭 발생 가능"
        )

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_kwh_mcb_connection_no_gap(self, requirements: dict):
        """KWH 오른쪽 → MCB 왼쪽 커넥션이 body edge에 정확히 맞는지 확인.

        회귀 조건: validate_connectivity()가 stub offset만큼
        엔드포인트를 이동시키면 실패.
        """
        result = compute_layout(requirements)
        comps = _find_meter_board_components(result)

        assert comps["KWH"] is not None, "KWH_METER 컴포넌트를 찾을 수 없음"
        assert comps["MCB"] is not None, "미터보드 MCB 컴포넌트를 찾을 수 없음"

        kwh_left, kwh_right = _get_body_edge_x(comps["KWH"])
        mcb_left, mcb_right = _get_body_edge_x(comps["MCB"])

        mb_y = comps["KWH"].y

        h_conns = _find_horizontal_connections_at_y(result, mb_y)
        assert len(h_conns) > 0, f"미터보드 Y={mb_y}에 수평 커넥션 없음"

        # KWH right → MCB left 커넥션 찾기
        kwh_mcb_conn = None
        for start, end in h_conns:
            s_x, e_x = min(start[0], end[0]), max(start[0], end[0])
            if (abs(s_x - kwh_right) < 1.0 or abs(e_x - kwh_right) < 1.0) and \
               (abs(s_x - mcb_left) < 1.0 or abs(e_x - mcb_left) < 1.0):
                kwh_mcb_conn = (start, end)
                break

        assert kwh_mcb_conn is not None, (
            f"KWH({kwh_right:.1f}) → MCB({mcb_left:.1f}) 커넥션을 찾을 수 없음. "
            f"수평 커넥션: {[(f'{s[0]:.1f}→{e[0]:.1f}') for s, e in h_conns]}"
        )

        conn_left_x = min(kwh_mcb_conn[0][0], kwh_mcb_conn[1][0])
        conn_right_x = max(kwh_mcb_conn[0][0], kwh_mcb_conn[1][0])

        assert abs(conn_left_x - kwh_right) < 0.5, (
            f"KWH→MCB 커넥션 왼쪽 끝({conn_left_x:.2f})이 "
            f"KWH body edge({kwh_right:.2f})에서 {abs(conn_left_x - kwh_right):.2f}mm 벗어남 — "
            f"stub offset으로 인한 갭 발생 가능"
        )
        assert abs(conn_right_x - mcb_left) < 0.5, (
            f"KWH→MCB 커넥션 오른쪽 끝({conn_right_x:.2f})이 "
            f"MCB body edge({mcb_left:.2f})에서 {abs(conn_right_x - mcb_left):.2f}mm 벗어남 — "
            f"stub offset으로 인한 갭 발생 가능"
        )

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_validate_connectivity_preserves_meter_board_endpoints(
        self, requirements: dict,
    ):
        """validate_connectivity() 전후로 미터보드 수평 커넥션 엔드포인트 불변 검증.

        validate_connectivity()가 이미 정확한 body edge 위치에 있는
        엔드포인트를 stub 위치로 이동시키지 않는지 직접 확인.
        """
        result = compute_layout(requirements)
        comps = _find_meter_board_components(result)
        assert comps["ISO"] is not None

        mb_y = comps["ISO"].y

        # validate_connectivity() 호출 전 엔드포인트 스냅샷
        h_conns_before = _find_horizontal_connections_at_y(result, mb_y)
        endpoints_before = []
        for start, end in h_conns_before:
            endpoints_before.append((start[0], end[0]))

        # validate_connectivity() 재호출 — compute_layout이 이미 호출했지만
        # 추가 호출이 엔드포인트를 변경하지 않아야 함 (멱등성)
        config = LayoutConfig()
        snapped = validate_connectivity(result, config)

        h_conns_after = _find_horizontal_connections_at_y(result, mb_y)
        endpoints_after = []
        for start, end in h_conns_after:
            endpoints_after.append((start[0], end[0]))

        # 미터보드 수평 커넥션 수 동일
        assert len(endpoints_before) == len(endpoints_after), (
            f"validate_connectivity() 재호출 후 미터보드 커넥션 수 변경: "
            f"{len(endpoints_before)} → {len(endpoints_after)}"
        )

        # 각 엔드포인트 좌표 불변 (±0.1mm)
        for i, (before, after) in enumerate(zip(endpoints_before, endpoints_after)):
            assert abs(before[0] - after[0]) < 0.1, (
                f"커넥션 #{i} 시작점 X가 변경됨: {before[0]:.2f} → {after[0]:.2f} "
                f"(validate_connectivity가 stub 위치로 스냅)"
            )
            assert abs(before[1] - after[1]) < 0.1, (
                f"커넥션 #{i} 끝점 X가 변경됨: {before[1]:.2f} → {after[1]:.2f} "
                f"(validate_connectivity가 stub 위치로 스냅)"
            )


class TestMeterBoardSupplyConnection:
    """미터보드 MCB → 공급 라인 커넥션 검증."""

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_mcb_right_supply_connection_no_gap(self, requirements: dict):
        """MCB 오른쪽 → 공급 라인 커넥션이 MCB body edge에서 시작하는지 확인."""
        result = compute_layout(requirements)
        comps = _find_meter_board_components(result)

        assert comps["MCB"] is not None, "미터보드 MCB 컴포넌트를 찾을 수 없음"

        mcb_left, mcb_right = _get_body_edge_x(comps["MCB"])
        mb_y = comps["MCB"].y

        h_conns = _find_horizontal_connections_at_y(result, mb_y)

        # MCB right에서 시작하는 커넥션 (공급 라인 방향)
        mcb_exit_conn = None
        for start, end in h_conns:
            left_x = min(start[0], end[0])
            right_x = max(start[0], end[0])
            # MCB 오른쪽 edge에서 시작하여 오른쪽으로 가는 커넥션
            if abs(left_x - mcb_right) < 0.5 and right_x > mcb_right + 5:
                mcb_exit_conn = (start, end)
                break

        assert mcb_exit_conn is not None, (
            f"MCB 오른쪽({mcb_right:.1f})에서 시작하는 공급 라인 커넥션을 찾을 수 없음"
        )

        conn_left_x = min(mcb_exit_conn[0][0], mcb_exit_conn[1][0])
        assert abs(conn_left_x - mcb_right) < 0.5, (
            f"MCB→공급 커넥션 시작점({conn_left_x:.2f})이 "
            f"MCB body edge({mcb_right:.2f})에서 {abs(conn_left_x - mcb_right):.2f}mm 벗어남"
        )
