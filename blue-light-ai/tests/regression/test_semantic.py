"""의미 규칙 테스트.

라벨 정확성, 위상 그룹핑, 케이블 사양.
"""

from __future__ import annotations

import pytest

from .conftest import ALL_CONFIGS, get_layout


class TestBreakerLabels:
    """차단기에 정격 라벨이 있는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_main_breaker_has_rating(self, config_id):
        """메인 차단기에 rating 또는 label이 있어야 한다."""
        result = get_layout(config_id)
        req = ALL_CONFIGS[config_id]

        breaker_types = {"CB_MCCB", "CB_MCB", "CB_ACB"}
        main_breakers = [
            c for c in result.components
            if c.symbol_name in breaker_types and abs(c.rotation) < 1.0
            and c.label_style == "breaker_block"
        ]

        if not main_breakers:
            return  # Some configs may not use breaker_block style

        for mb in main_breakers:
            has_info = mb.rating or mb.label or mb.breaker_type_str
            assert has_info, \
                f"[{config_id}] Main breaker at ({mb.x:.1f}, {mb.y:.1f}) has no rating info"

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_subcircuit_breakers_have_rating(self, config_id):
        """서브회로 차단기에 rating이 있어야 한다."""
        result = get_layout(config_id)
        sub_types = {"MCB", "CB_MCB"}
        subs = [c for c in result.components if c.symbol_name in sub_types]

        if not subs:
            return

        # 최소 하나의 서브회로에 rating이 있어야 함
        rated = [c for c in subs if c.rating or c.label]
        assert len(rated) > 0, \
            f"[{config_id}] No sub-circuit breakers have rating labels"


class TestCircuitIds:
    """서브회로에 회로 ID가 있는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_circuit_ids_assigned(self, config_id):
        """CIRCUIT_ID_BOX 컴포넌트가 존재하거나 circuit_id가 설정되어야 한다."""
        result = get_layout(config_id)

        id_boxes = [c for c in result.components if c.symbol_name == "CIRCUIT_ID_BOX"]
        circuit_ids = [c for c in result.components if c.circuit_id]

        has_ids = len(id_boxes) > 0 or len(circuit_ids) > 0
        assert has_ids, \
            f"[{config_id}] No circuit IDs found in layout"


class TestCableAnnotations:
    """케이블 사양이 포함되어 있는지."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_has_cable_annotations(self, config_id):
        """최소 하나의 컴포넌트에 cable_annotation이 있어야 한다."""
        result = get_layout(config_id)
        cables = [c for c in result.components if c.cable_annotation]

        # Cable annotations are optional for some layouts
        # but at least incoming cable should exist for most
        req = ALL_CONFIGS[config_id]
        has_cable_in_req = any(
            c.get("cable") for c in req.get("sub_circuits", [])
        )

        if has_cable_in_req:
            assert len(cables) > 0, \
                f"[{config_id}] Requirements have cables but no cable annotations in layout"


class TestDbInfo:
    """DB 정보 박스."""

    @pytest.mark.parametrize("config_id", list(ALL_CONFIGS.keys()))
    def test_db_info_box_exists(self, config_id):
        """DB_INFO_BOX 컴포넌트가 존재해야 한다."""
        result = get_layout(config_id)
        db_boxes = [c for c in result.components if c.symbol_name == "DB_INFO_BOX"]
        assert len(db_boxes) > 0, \
            f"[{config_id}] No DB_INFO_BOX found in layout"
