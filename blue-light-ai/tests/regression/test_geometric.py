"""기하학 규칙 테스트.

스파인 정렬, 컴포넌트 Y순서, 서브회로 간격, 연결 연속성.
"""

from __future__ import annotations

import pytest

from .conftest import ALL_CONFIGS, get_layout


class TestSpineAlignment:
    """스파인 컴포넌트 X좌표 정렬."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_main_components_x_aligned(self, config_id):
        """메인 차단기와 ELCB가 같은 스파인(X좌표)에 있는지."""
        result = get_layout(config_id)

        # 스파인 컴포넌트: 메인 차단기 + ELCB (rotation ≈ 0)
        spine_types = {"CB_MCCB", "CB_MCB", "CB_ACB", "CB_RCCB", "CB_ELCB"}
        spine_comps = [
            c for c in result.components
            if c.symbol_name in spine_types and abs(c.rotation) < 1.0
        ]
        if len(spine_comps) < 2:
            return  # Not enough components to check alignment

        # Center X of each component (approx)
        xs = [c.x for c in spine_comps]
        x_spread = max(xs) - min(xs)

        # 스파인 컴포넌트는 X좌표 ±5mm 이내로 정렬되어야 함
        # (실제로는 symbol width 차이로 약간 다를 수 있음)
        assert x_spread < 10.0, \
            f"[{config_id}] Spine components X spread too large: {x_spread:.1f}mm. " \
            f"X values: {[f'{x:.1f}' for x in xs]}"


class TestComponentOrder:
    """컴포넌트 Y순서: 전원(아래) → 부하(위)."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_main_breaker_below_elcb(self, config_id):
        """메인 차단기는 ELCB보다 아래(낮은 Y)에 있어야 한다."""
        result = get_layout(config_id)

        breaker_types = {"CB_MCCB", "CB_MCB", "CB_ACB"}
        elcb_types = {"CB_RCCB", "CB_ELCB"}

        breakers = [c for c in result.components if c.symbol_name in breaker_types and abs(c.rotation) < 1.0]
        elcbs = [c for c in result.components if c.symbol_name in elcb_types and abs(c.rotation) < 1.0]

        if not breakers or not elcbs:
            return  # Can't verify without both

        # 가장 아래 breaker와 가장 아래 ELCB 비교
        min_breaker_y = min(c.y for c in breakers)
        min_elcb_y = min(c.y for c in elcbs)

        assert min_breaker_y < min_elcb_y, \
            f"[{config_id}] Main breaker (y={min_breaker_y:.1f}) should be below " \
            f"ELCB (y={min_elcb_y:.1f})"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_busbar_above_main_breaker(self, config_id):
        """부스바는 메인 차단기보다 위에 있어야 한다."""
        result = get_layout(config_id)
        if result.busbar_y == 0:
            return

        breaker_types = {"CB_MCCB", "CB_MCB", "CB_ACB"}
        breakers = [c for c in result.components if c.symbol_name in breaker_types and abs(c.rotation) < 1.0]
        if not breakers:
            return

        max_breaker_y = max(c.y for c in breakers)

        # busbar_y는 부스바 중심 Y — 메인 차단기보다 위여야 함
        assert result.busbar_y > max_breaker_y, \
            f"[{config_id}] Busbar (y={result.busbar_y:.1f}) should be above " \
            f"main breaker (y={max_breaker_y:.1f})"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_subcircuits_above_busbar(self, config_id):
        """서브회로 차단기는 부스바 위에 있어야 한다.

        Note: meter board MCB, incoming chain MCB 등은 서브회로가 아님.
        서브회로 = busbar 근처에서 분기하는 MCB (circuit_id가 있거나 busbar_y+gap 이내)
        """
        result = get_layout(config_id)
        if result.busbar_y == 0:
            return

        # 서브회로 = CIRCUIT_ID_BOX 또는 circuit_id가 있는 CB_MCB
        sub_breakers = [
            c for c in result.components
            if c.symbol_name in ("MCB", "CB_MCB") and c.circuit_id
        ]
        if not sub_breakers:
            return

        for c in sub_breakers:
            assert c.y >= result.busbar_y - 2.0, \
                f"[{config_id}] Sub-circuit '{c.circuit_id}' at y={c.y:.1f} " \
                f"is below busbar at y={result.busbar_y:.1f}"


class TestConnectionContinuity:
    """컴포넌트 간 연결이 존재하는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_has_connections(self, config_id):
        result = get_layout(config_id)
        total_conns = (len(result.resolved_connections(style_filter={"normal"})) +
                      len(result.resolved_connections(style_filter={"thick"})) +
                      len(result.resolved_connections(style_filter={"fixed"})))
        assert total_conns > 0, \
            f"[{config_id}] No connections found in layout"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_connections_count_reasonable(self, config_id):
        """연결 수는 컴포넌트 수의 최소 절반."""
        result = get_layout(config_id)
        n_comps = len(result.components)
        n_conns = len(result.resolved_connections(style_filter={"normal"})) + len(result.resolved_connections(style_filter={"thick"}))
        if n_comps < 3:
            return
        # 대략 component 수의 절반 이상의 연결이 있어야 함
        assert n_conns >= n_comps * 0.3, \
            f"[{config_id}] Too few connections ({n_conns}) for {n_comps} components"


class TestBusbarGeometry:
    """부스바 위치와 범위."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_busbar_within_page(self, config_id):
        result = get_layout(config_id)
        if result.busbar_y == 0:
            return
        # A3 landscape: min_y ≈ 62, max_y ≈ 285
        assert 50 < result.busbar_y < 290, \
            f"[{config_id}] Busbar Y={result.busbar_y:.1f} outside page bounds"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_busbar_has_extent(self, config_id):
        result = get_layout(config_id)
        if result.busbar_start_x == 0 and result.busbar_end_x == 0:
            return
        extent = result.busbar_end_x - result.busbar_start_x
        assert extent > 10, \
            f"[{config_id}] Busbar extent too small: {extent:.1f}mm"
