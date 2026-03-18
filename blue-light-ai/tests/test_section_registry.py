"""
Section registry 테스트 — SLD 유형별 섹션 시퀀스 검증.

Section 인스턴스 기반 아키텍처의 정확성을 검증:
1. 올바른 시퀀스 반환 (metering → 시퀀스 매핑)
2. Section.execute() → sections_rendered 자동 추적
3. FunctionSection 어댑터 동작
4. 전체 레이아웃 파이프라인 통합
"""

import pytest

from app.sld.layout import compute_layout, Section, FunctionSection, get_section_sequence
from app.sld.layout.section_base import sym_dims, sym_v_pins


# ═══════════════════════════════════════════════════════════════════════════
# 시퀀스 매핑 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestSectionSequenceMapping:
    """metering 유형별 올바른 시퀀스가 반환되는지 검증."""

    def test_ct_meter_sequence(self):
        seq = get_section_sequence({"metering": "ct_meter"})
        names = [s.name for s in seq]
        assert "incoming_supply" in names
        assert "main_breaker" in names
        assert "ct_metering" in names
        assert "ct_pre_mccb_fuse" in names
        # CT meter에서 meter_board는 없어야 함
        assert "meter_board" not in names

    def test_sp_meter_sequence(self):
        seq = get_section_sequence({"metering": "sp_meter"})
        names = [s.name for s in seq]
        assert "meter_board" in names
        assert "main_breaker" in names
        # sp_meter에서 ct_metering은 없어야 함
        assert "ct_metering" not in names

    def test_landlord_meter_sequence(self):
        seq = get_section_sequence({"metering": "landlord_meter"})
        names = [s.name for s in seq]
        assert "meter_board" in names

    def test_direct_supply_sequence(self):
        seq = get_section_sequence({"metering": ""})
        names = [s.name for s in seq]
        assert "incoming_supply" in names
        assert "main_breaker" in names

    def test_no_metering_key(self):
        """metering 키가 없으면 direct supply."""
        seq = get_section_sequence({})
        names = [s.name for s in seq]
        assert "incoming_supply" in names

    def test_all_sections_are_section_instances(self):
        """모든 시퀀스 항목이 Section 인스턴스여야 한다."""
        for metering in ["ct_meter", "sp_meter", "landlord_meter", ""]:
            seq = get_section_sequence({"metering": metering})
            for section in seq:
                assert isinstance(section, Section), (
                    f"metering={metering}: {section} is not a Section instance"
                )

    def test_all_sections_have_names(self):
        """모든 Section에 name이 있어야 한다."""
        for metering in ["ct_meter", "sp_meter", ""]:
            seq = get_section_sequence({"metering": metering})
            for section in seq:
                assert section.name, f"Section without name: {section}"


# ═══════════════════════════════════════════════════════════════════════════
# FunctionSection 어댑터 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestFunctionSection:
    """FunctionSection 어댑터 동작 검증."""

    def test_wraps_function(self):
        called = []

        def my_fn(ctx):
            called.append(True)

        section = FunctionSection("test", my_fn)
        assert section.name == "test"

        # Mock context
        class FakeCtx:
            y = 0.0
            result = type("R", (), {"sections_rendered": {}})()
        ctx = FakeCtx()
        section.place(ctx)
        assert len(called) == 1

    def test_kwargs_forwarding(self):
        received = {}

        def my_fn(ctx, *, skip_gap=False):
            received["skip_gap"] = skip_gap

        section = FunctionSection("test", my_fn, skip_gap=True)
        class FakeCtx:
            y = 0.0
            result = type("R", (), {"sections_rendered": {}})()
        section.place(FakeCtx())
        assert received["skip_gap"] is True

    def test_repr(self):
        section = FunctionSection("main_breaker", lambda ctx: None)
        assert "main_breaker" in repr(section)


# ═══════════════════════════════════════════════════════════════════════════
# Section.execute() 자동 추적 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestSectionExecute:
    """Section.execute()가 sections_rendered를 자동 추적하는지 검증."""

    def test_execute_tracks_rendered(self):
        """place()에서 Y가 변하면 sections_rendered에 기록."""
        class MySection(Section):
            name = "test_section"
            def place(self, ctx):
                ctx.y += 10  # Y 변경 → 렌더링 됨

        class FakeCtx:
            y = 0.0
            result = type("R", (), {"sections_rendered": {}})()

        ctx = FakeCtx()
        section = MySection()
        section.execute(ctx)
        assert ctx.result.sections_rendered.get("test_section") is True

    def test_execute_no_track_when_y_unchanged(self):
        """place()에서 Y 불변이면 sections_rendered에 기록하지 않음."""
        class SkipSection(Section):
            name = "skipped_section"
            def place(self, ctx):
                pass  # Y 변경 없음 → skip

        class FakeCtx:
            y = 100.0
            result = type("R", (), {"sections_rendered": {}})()

        ctx = FakeCtx()
        section = SkipSection()
        section.execute(ctx)
        assert "skipped_section" not in ctx.result.sections_rendered

    def test_execute_does_not_override_existing(self):
        """place() 내부에서 sections_rendered를 직접 설정한 경우 덮어쓰지 않음."""
        class ManualSection(Section):
            name = "manual_section"
            def place(self, ctx):
                ctx.result.sections_rendered["manual_section"] = True
                # Y는 변경하지 않지만, 내부에서 직접 추적

        class FakeCtx:
            y = 0.0
            result = type("R", (), {"sections_rendered": {}})()

        ctx = FakeCtx()
        section = ManualSection()
        section.execute(ctx)
        # 내부에서 직접 설정했으므로 유지
        assert ctx.result.sections_rendered.get("manual_section") is True


# ═══════════════════════════════════════════════════════════════════════════
# 통합 테스트: Section 기반 파이프라인
# ═══════════════════════════════════════════════════════════════════════════

class TestSectionPipelineIntegration:
    """Section 기반 아키텍처가 실제 SLD 생성에서 정상 동작하는지 검증."""

    CT_METERING_3PH = {
        "supply_type": "three_phase", "kva": 100,
        "main_breaker": {"type": "mccb", "rating_A": 400, "poles": "3P", "fault_kA": 25},
        "busbar_rating": 400, "metering": "ct_metering", "ct_ratio": "400/5",
        "elcb": {"rating": 63, "sensitivity_ma": 30},
        "sub_circuits": [
            {"name": "L", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
            {"name": "P", "breaker_type": "mcb", "breaker_rating": 32, "cable": "4mm2", "phase": "L2"},
            {"name": "A", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L3"},
        ],
    }

    SP_METER_1PH = {
        "supply_type": "single_phase", "kva": 15,
        "main_breaker": {"type": "mcb", "rating_A": 63, "poles": "SP"},
        "busbar_rating": 63, "metering": "sp_meter",
        "sub_circuits": [
            {"name": "L", "breaker_type": "mcb", "breaker_rating": 20, "cable": "2.5mm2", "phase": "L1"},
        ],
    }

    def test_ct_meter_sections_rendered(self):
        """CT meter SLD에서 필수 섹션이 렌더링됨."""
        result = compute_layout(self.CT_METERING_3PH)
        rendered = result.sections_rendered
        assert rendered.get("incoming_supply") is True
        assert rendered.get("main_breaker") is True
        assert rendered.get("ct_metering_section") is True

    def test_sp_meter_sections_rendered(self):
        """SP meter SLD에서 meter_board가 렌더링됨."""
        result = compute_layout(self.SP_METER_1PH)
        rendered = result.sections_rendered
        assert rendered.get("meter_board") is True
        assert rendered.get("main_breaker") is True

    def test_audit_still_passes(self):
        """Section 아키텍처 전환 후에도 audit 11/11 PASS."""
        for req in [self.CT_METERING_3PH, self.SP_METER_1PH]:
            result = compute_layout(req)
            report = result.audit_report
            assert report is not None
            assert report.failed == 0, (
                f"audit 실패: {[c.principle_id for c in report.results if not c.passed]}"
            )


# ═══════════════════════════════════════════════════════════════════════════
# sym_dims / sym_v_pins 헬퍼 테스트
# ═══════════════════════════════════════════════════════════════════════════

class TestSymbolHelpers:
    """section_base의 심볼 조회 헬퍼 검증."""

    def test_sym_dims_returns_tuple(self):
        w, h, stub = sym_dims("CB_MCB")
        assert w > 0
        assert h > 0
        assert stub > 0

    def test_sym_v_pins_has_top_bottom(self):
        pins = sym_v_pins("CB_MCB", 100, 50)
        assert "top" in pins
        assert "bottom" in pins
        assert pins["top"][1] > pins["bottom"][1]  # top Y > bottom Y
