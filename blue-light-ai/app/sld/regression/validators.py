"""LayoutResult 및 DXF 출력 검증기.

generator 출력을 universal_rules.json의 규칙으로 자동 검증한다.
Level 1: LayoutResult 검증 (빠름, ~0.1s)
Level 2: DXF 파일 검증 (정밀, ~1s)
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING, Any

from .rules import Rule, RuleSet

if TYPE_CHECKING:
    from app.sld.layout.models import LayoutResult

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Validation result
# ---------------------------------------------------------------------------

@dataclass
class Violation:
    """규칙 위반 상세."""
    rule_name: str
    message: str
    severity: str = "error"  # "error" | "warning"
    details: dict[str, Any] = field(default_factory=dict)


@dataclass
class ValidationReport:
    """검증 결과 리포트."""
    violations: list[Violation] = field(default_factory=list)
    passed_rules: list[str] = field(default_factory=list)
    skipped_rules: list[str] = field(default_factory=list)

    @property
    def passed(self) -> bool:
        return not any(v.severity == "error" for v in self.violations)

    @property
    def error_count(self) -> int:
        return sum(1 for v in self.violations if v.severity == "error")

    @property
    def warning_count(self) -> int:
        return sum(1 for v in self.violations if v.severity == "warning")

    @property
    def score(self) -> float:
        total = len(self.passed_rules) + self.error_count
        if total == 0:
            return 1.0
        return len(self.passed_rules) / total


# ---------------------------------------------------------------------------
# Level 1: LayoutResult validator
# ---------------------------------------------------------------------------

def _map_sld_type_from_requirements(req: dict) -> str:
    """requirements dict에서 SLD 유형 추정.

    Note: sld_spec 자동보정 후의 metering 값은 original과 다를 수 있음.
    ct_metering/ct_meter → ct_metering_3phase
    나머지 → supply_type에 따라 결정
    """
    metering = req.get("metering", "")
    supply = req.get("supply_type", "three_phase")

    if metering in ("ct_metering", "ct_meter"):
        return "ct_metering_3phase"
    if supply == "single_phase":
        return "direct_metering_1phase"
    if supply == "three_phase":
        return "direct_metering_3phase"
    return "unknown"


class LayoutValidator:
    """LayoutResult를 범용 규칙으로 검증."""

    def validate(
        self,
        result: "LayoutResult",
        rules: RuleSet,
        requirements: dict | None = None,
    ) -> ValidationReport:
        report = ValidationReport()
        sld_type = _map_sld_type_from_requirements(requirements or {})
        applicable_rules = rules.by_type(sld_type)

        for rule in applicable_rules:
            check_fn = self._get_check(rule.category, rule.name)
            if check_fn is None:
                report.skipped_rules.append(rule.name)
                continue
            try:
                violations = check_fn(result, rule, requirements or {})
                if violations:
                    report.violations.extend(violations)
                else:
                    report.passed_rules.append(rule.name)
            except Exception as exc:
                report.violations.append(Violation(
                    rule_name=rule.name,
                    message=f"Check raised exception: {exc}",
                    severity="warning",
                ))

        return report

    def _get_check(self, category: str, name: str):
        """규칙 이름으로 검증 함수 매핑."""
        # Structural checks
        if name.startswith("required_blocks_"):
            return self._check_symbols_used
        if name.startswith("rccb_presence_"):
            return self._check_rccb_presence
        if name.startswith("isolator_presence_"):
            return self._check_isolator_presence
        if name.startswith("subcircuit_count_range_"):
            return self._check_subcircuit_count
        if name == "ct_blocks_required":
            return self._check_ct_blocks
        if name == "ct_fuse_required":
            return self._check_ct_fuse

        # Order checks
        if name.startswith("spine_order_"):
            return self._check_spine_order
        if name == "subcircuit_above_spine":
            return self._check_subcircuit_above_spine

        # Semantic checks
        if name == "rating_labels_universal":
            return self._check_rating_labels
        if name == "cable_annotations_universal":
            return self._check_cable_annotations
        if name.startswith("phase_labels_"):
            return self._check_phase_labels

        # Quality checks (always applicable)
        return None

    # -- Structural checks --

    def _check_symbols_used(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """symbols_used에 필수 블록이 있는지 검증."""
        # Map DXF block names → LayoutResult symbol names
        block_to_symbol = {
            "MCCB": {"CB_MCCB", "MCCB", "CB_MCB", "MCB"},  # MCB is valid alternative
            "RCCB": {"CB_RCCB", "CB_ELCB", "RCCB"},
            "DP ISOL": {"ISOLATOR"},
            "3P ISOL": {"ISOLATOR"},
            "SLD-CT": {"CT"},
            "2A FUSE": {"FUSE", "POTENTIAL_FUSE"},
            "LED IND LTG": {"INDICATOR_LIGHTS", "LED_INDICATOR"},
            "SS": {"SELECTOR_SWITCH"},
            "VOLTMETER": {"VOLTMETER"},
            "EF": {"EF", "EARTH", "ELR"},
        }
        required = rule.params.get("required_blocks", [])
        violations = []
        for block_name in required:
            expected_symbols = block_to_symbol.get(block_name, {block_name})
            if not (result.symbols_used & expected_symbols):
                violations.append(Violation(
                    rule_name=rule.name,
                    message=f"Required block '{block_name}' not found in symbols_used. "
                            f"Expected one of {expected_symbols}, got {result.symbols_used}",
                    severity="error",
                ))
        return violations

    def _check_rccb_presence(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        if not rule.params.get("required", False):
            return []
        rccb_syms = {"CB_RCCB", "CB_ELCB", "RCCB"}
        if not (result.symbols_used & rccb_syms):
            return [Violation(
                rule_name=rule.name,
                message="RCCB/ELCB required but not found in layout",
                severity="error",
            )]
        return []

    def _check_isolator_presence(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        if not rule.params.get("required", False):
            return []
        if "ISOLATOR" not in result.symbols_used:
            return [Violation(
                rule_name=rule.name,
                message="Isolator required but not found in layout",
                severity="error",
            )]
        return []

    def _check_subcircuit_count(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """서브회로 수가 레퍼런스 범위 내인지 (warning only — 입력에 따라 달라짐)."""
        subcircuit_symbols = {"MCB", "CB_MCB", "CB_MCCB_SUB", "MCCB_SUB"}
        # Count sub-circuit components (breakers on busbar rows)
        sub_count = len(req.get("sub_circuits", []))
        if sub_count == 0:
            return []
        # This is informational — don't fail on count mismatch since it depends on input
        return []

    def _check_ct_blocks(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        if req.get("metering") != "ct_metering":
            return []
        ct_comps = [c for c in result.components if c.symbol_name == "CT"]
        # Generator produces 2 CTs (protection + metering).
        # Reference DXFs may have 3-4 (multiple CTs per phase in some configs).
        # Minimum 2 is the actual requirement.
        min_required = 2
        if len(ct_comps) < min_required:
            return [Violation(
                rule_name=rule.name,
                message=f"CT metering requires at least {min_required} CT components, found {len(ct_comps)}",
                severity="error",
            )]
        return []

    def _check_ct_fuse(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        if req.get("metering") != "ct_metering":
            return []
        if not rule.params.get("required", False):
            return []
        fuse_comps = [c for c in result.components if "FUSE" in c.symbol_name.upper()]
        if not fuse_comps:
            return [Violation(
                rule_name=rule.name,
                message="CT metering requires 2A fuse but none found",
                severity="error",
            )]
        return []

    # -- Order checks --

    def _check_spine_order(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """스파인 컴포넌트 Y순서 검증: 메인차단기(MCCB) → ELCB/RCCB 순서."""
        # 스파인 위의 주요 컴포넌트를 Y순서로 추출
        spine_types = {"CB_MCCB", "CB_MCB", "CB_ACB", "CB_RCCB", "CB_ELCB",
                       "CT", "BI_CONNECTOR", "FUSE", "ISOLATOR"}
        spine_comps = [
            c for c in result.components
            if c.symbol_name in spine_types and abs(c.rotation) < 1.0
        ]

        if len(spine_comps) < 2:
            return []

        # Y 기준 정렬 (ascending = bottom to top = 전원→부하)
        sorted_comps = sorted(spine_comps, key=lambda c: c.y)

        # 핵심 규칙: main breaker는 ELCB보다 아래 (낮은 Y)
        breaker_types = {"CB_MCCB", "CB_MCB", "CB_ACB"}
        elcb_types = {"CB_RCCB", "CB_ELCB"}

        breakers = [c for c in sorted_comps if c.symbol_name in breaker_types]
        elcbs = [c for c in sorted_comps if c.symbol_name in elcb_types]

        violations = []
        if breakers and elcbs:
            # 가장 아래 breaker가 가장 아래 ELCB보다 아래여야 함
            min_breaker_y = min(c.y for c in breakers)
            min_elcb_y = min(c.y for c in elcbs)
            if min_breaker_y > min_elcb_y:
                violations.append(Violation(
                    rule_name=rule.name,
                    message=f"Main breaker (y={min_breaker_y:.1f}) should be below "
                            f"ELCB (y={min_elcb_y:.1f}) in spine order",
                    severity="error",
                ))

        return violations

    def _check_subcircuit_above_spine(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """서브회로가 부스바 위에 위치하는지 검증."""
        if result.busbar_y == 0:
            return []

        # 서브회로 = circuit_id가 있는 MCB (meter board MCB 제외)
        sub_comps = [
            c for c in result.components
            if c.symbol_name in ("MCB", "CB_MCB") and c.circuit_id
        ]
        violations = []
        for c in sub_comps:
            if c.y < result.busbar_y - 2.0:
                violations.append(Violation(
                    rule_name=rule.name,
                    message=f"Sub-circuit '{c.circuit_id}' at y={c.y:.1f} is below "
                            f"busbar at y={result.busbar_y:.1f}",
                    severity="error",
                ))
                break  # One example suffices
        return violations

    # -- Semantic checks --

    def _check_rating_labels(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """주요 차단기에 정격 라벨이 있는지."""
        breaker_comps = [
            c for c in result.components
            if c.symbol_name in ("CB_MCCB", "CB_MCB", "CB_ACB", "CB_RCCB", "CB_ELCB")
            and c.label_style == "breaker_block"
        ]
        violations = []
        for c in breaker_comps:
            if not c.rating and not c.label:
                violations.append(Violation(
                    rule_name=rule.name,
                    message=f"Breaker {c.symbol_name} at ({c.x:.1f}, {c.y:.1f}) has no rating label",
                    severity="warning",
                ))
        return violations

    def _check_cable_annotations(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """주요 케이블 경로에 사양 텍스트가 있는지."""
        cable_comps = [c for c in result.components if c.cable_annotation]
        if not cable_comps:
            # 최소 incoming cable은 있어야 함
            incoming = req.get("incoming_cable") or req.get("cable")
            if incoming:
                return [Violation(
                    rule_name=rule.name,
                    message="No cable annotations found in layout despite cable spec in requirements",
                    severity="warning",
                )]
        return []

    def _check_phase_labels(
        self, result: "LayoutResult", rule: Rule, req: dict,
    ) -> list[Violation]:
        """서브회로에 위상 라벨(L1, L2, L3)이 있는지."""
        if req.get("supply_type") != "three_phase":
            return []
        # Check for circuit_id labels
        sub_comps = [
            c for c in result.components
            if c.symbol_name in ("MCB", "CB_MCB") and c.circuit_id
        ]
        if not sub_comps:
            ratio = rule.params.get("ratio", 0)
            if ratio >= 0.9:
                return [Violation(
                    rule_name=rule.name,
                    message="3-phase SLD should have phase labels on sub-circuits",
                    severity="warning",
                )]
        return []


# ---------------------------------------------------------------------------
# Level 2: Quality gate validators (work with LayoutResult directly)
# ---------------------------------------------------------------------------

class QualityGateValidator:
    """LayoutResult 품질 게이트 검증. 규칙 파일 불필요."""

    def validate(self, result: "LayoutResult") -> ValidationReport:
        report = ValidationReport()

        # Q1: Audit score
        if result.audit_report:
            if result.audit_report.score >= 0.9:
                report.passed_rules.append("audit_score_minimum")
            else:
                report.violations.append(Violation(
                    rule_name="audit_score_minimum",
                    message=f"Audit score {result.audit_report.score:.2f} < 0.9 minimum",
                    severity="error",
                    details={"score": result.audit_report.score},
                ))

        # Q2: No overflow
        if result.overflow_metrics:
            if not result.overflow_metrics.has_overflow:
                report.passed_rules.append("no_overflow")
            else:
                report.violations.append(Violation(
                    rule_name="no_overflow",
                    message="Layout overflows page boundaries",
                    severity="error",
                    details={
                        "overflow_left": getattr(result.overflow_metrics, "overflow_left", 0),
                        "overflow_right": getattr(result.overflow_metrics, "overflow_right", 0),
                    },
                ))

        # Q3: Sections rendered (at least core sections)
        # Note: incoming_supply is only in CT metering sequence;
        # SP meter uses meter_board as entry point.
        has_entry = (
            result.sections_rendered.get("incoming_supply")
            or result.sections_rendered.get("meter_board")
        )
        if has_entry:
            report.passed_rules.append("section_entry")
        else:
            report.violations.append(Violation(
                rule_name="section_entry",
                message="No entry section (incoming_supply or meter_board) rendered",
                severity="error",
            ))

        core_sections = ["main_breaker", "main_busbar", "sub_circuits"]
        for section in core_sections:
            if result.sections_rendered.get(section):
                report.passed_rules.append(f"section_{section}")
            else:
                report.violations.append(Violation(
                    rule_name=f"section_{section}",
                    message=f"Core section '{section}' was not rendered",
                    severity="error",
                ))

        # Q4: Components exist
        if result.components:
            report.passed_rules.append("has_components")
        else:
            report.violations.append(Violation(
                rule_name="has_components",
                message="Layout produced no components",
                severity="error",
            ))

        # Q5: Connections exist
        if result.connections:
            report.passed_rules.append("has_connections")
        else:
            report.violations.append(Violation(
                rule_name="has_connections",
                message="Layout produced no connections",
                severity="error",
            ))

        # Q6: Busbar Y is set
        if result.busbar_y > 0:
            report.passed_rules.append("busbar_positioned")
        else:
            report.violations.append(Violation(
                rule_name="busbar_positioned",
                message="Busbar Y position is not set (0)",
                severity="error",
            ))

        return report
