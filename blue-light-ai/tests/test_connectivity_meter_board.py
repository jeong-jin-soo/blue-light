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


class TestVerticalSpineConnectionAlignment:
    """수직 스파인 MCB/ELCB 커넥션이 body edge에 정렬되는지 검증.

    버그: validate_connectivity()가 수직 컴포넌트의 vertical_pins() 결과
    (stub 포함 위치)로 커넥션을 스냅하여 2mm 갭 발생.

    수정: connectivity.py에서 vertical_pins() 결과의 stub offset을 제거하여
    body edge 위치를 사용하도록 변경.
    """

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_main_mcb_bottom_connection_at_body_edge(self, requirements: dict):
        """메인 MCB 하단 커넥션이 body bottom edge에 맞는지 확인."""
        from app.sld.real_symbols import get_real_symbol

        result = compute_layout(requirements)

        # 수직 메인 MCB 찾기 (rotation=0)
        main_mcb = None
        for comp in result.components:
            if comp.symbol_name == "CB_MCB" and comp.rotation == 0:
                main_mcb = comp
                break

        assert main_mcb is not None, "수직 메인 MCB를 찾을 수 없음"

        sym = get_real_symbol("CB_MCB")
        stub = getattr(sym, "_stub", 2.0)
        body_bottom = main_mcb.y  # body starts at placement y

        # MCB body bottom에 닿는 수직 커넥션 찾기
        found = False
        for start, end in result.connections:
            if abs(start[0] - end[0]) > 1.0:
                continue  # 수평 커넥션 스킵
            for pt in [start, end]:
                if abs(pt[1] - body_bottom) < 0.5 and abs(pt[0] - (main_mcb.x + 2.5)) < 3:
                    found = True
                    # stub 위치가 아닌 body edge에 있어야 함
                    assert abs(pt[1] - body_bottom) < 0.5, (
                        f"MCB 하단 커넥션({pt[1]:.2f})이 body edge({body_bottom:.2f})에서 벗어남"
                    )
                    # stub 위치(body_bottom - stub)에 있으면 안 됨
                    assert abs(pt[1] - (body_bottom - stub)) > 0.5, (
                        f"MCB 하단 커넥션({pt[1]:.2f})이 stub 위치({body_bottom - stub:.2f})에 있음 — 갭 발생"
                    )

        assert found, f"MCB body bottom({body_bottom:.2f}) 근처 수직 커넥션을 찾을 수 없음"

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_elcb_busbar_connection_at_body_edge(self, requirements: dict):
        """ELCB/RCCB 상단 → 버스바 커넥션이 body top edge에서 시작하는지 확인."""
        from app.sld.real_symbols import get_real_symbol

        result = compute_layout(requirements)

        # 수직 ELCB/RCCB 찾기 (rotation=0)
        main_elcb = None
        for comp in result.components:
            if comp.symbol_name in ("CB_ELCB", "CB_RCCB") and comp.rotation == 0:
                main_elcb = comp
                break

        assert main_elcb is not None, "수직 ELCB/RCCB를 찾을 수 없음"

        sym = get_real_symbol(main_elcb.symbol_name)
        stub = getattr(sym, "_stub", 2.0)
        raw_pins = sym.vertical_pins(main_elcb.x, main_elcb.y)
        body_top = raw_pins["top"][1] - stub

        # ELCB body top 근처 수직 커넥션 찾기
        spine_x = raw_pins["top"][0]
        found = False
        for start, end in result.connections:
            if abs(start[0] - end[0]) > 1.0:
                continue  # 수평 커넥션 스킵
            for pt in [start, end]:
                if abs(pt[1] - body_top) < 1.0 and abs(pt[0] - spine_x) < 3:
                    found = True
                    assert abs(pt[1] - body_top) < 0.5, (
                        f"ELCB 상단 커넥션({pt[1]:.2f})이 body edge({body_top:.2f})에서 벗어남"
                    )
                    # stub 위치에 있으면 안 됨
                    stub_top = raw_pins["top"][1]
                    assert abs(pt[1] - stub_top) > 0.5, (
                        f"ELCB 상단 커넥션({pt[1]:.2f})이 stub 위치({stub_top:.2f})에 있음 — 갭 발생"
                    )

        assert found, f"ELCB body top({body_top:.2f}) 근처 수직 커넥션을 찾을 수 없음"

    @pytest.mark.parametrize("requirements", [
        pytest.param(SINGLE_PHASE_METERED, id="1ph_metered"),
        pytest.param(THREE_PHASE_METERED, id="3ph_metered"),
    ])
    def test_spine_connections_are_correct_without_validate_connectivity(
        self, requirements: dict,
    ):
        """v2 아키텍처: validate_connectivity 없이도 스파인 커넥션이 정확한지 검증.

        validate_connectivity가 파이프라인에서 제거되었으므로 (대각선/브랜치 좌표를
        잘못 스냅하는 문제), sections.py가 처음부터 정확한 핀 좌표를 생성해야 함.
        """
        result = compute_layout(requirements)

        # 스파인 X 좌표 찾기
        spine_x = None
        for comp in result.components:
            if comp.symbol_name == "CB_MCB" and comp.rotation == 0:
                spine_x = comp.x + 2.5
                break
        assert spine_x is not None

        # 수직 커넥션 확인: 스파인 커넥션의 X 좌표가 일관적인지
        vertical_conns = []
        for s, e in result.connections:
            if abs(s[0] - e[0]) < 1.0 and abs(s[0] - spine_x) < 3:
                vertical_conns.append((s, e))

        assert len(vertical_conns) > 0, "스파인 수직 커넥션이 하나도 없음"

        # 모든 수직 스파인 커넥션의 X가 spine_x ± 1mm 이내
        for s, e in vertical_conns:
            assert abs(s[0] - spine_x) < 1.5, (
                f"스파인 커넥션 start X ({s[0]:.2f})가 spine_x ({spine_x:.2f})에서 벗어남"
            )
            assert abs(e[0] - spine_x) < 1.5, (
                f"스파인 커넥션 end X ({e[0]:.2f})가 spine_x ({spine_x:.2f})에서 벗어남"
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
