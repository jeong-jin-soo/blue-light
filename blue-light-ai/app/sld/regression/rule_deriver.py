"""범용 규칙 도출기.

reference_specs.json에서 패턴을 분석하여 universal_rules.json을 생성한다.
각 규칙은 28개 레퍼런스에서 관찰된 불변(invariant) 패턴이며,
evidence_count로 규칙의 강도를 표현한다.

Usage:
    python -m app.sld.regression.rule_deriver
"""

from __future__ import annotations

import logging
import sys
from collections import Counter, defaultdict
from pathlib import Path

from .rules import ReferenceDatabase, ReferenceSpec, Rule, RuleSet

logger = logging.getLogger(__name__)


def derive_rules(db: ReferenceDatabase) -> RuleSet:
    """레퍼런스 DB에서 범용 규칙을 도출한다."""
    rules: list[Rule] = []

    # SLD 유형별 그룹핑
    by_type: dict[str, list[ReferenceSpec]] = defaultdict(list)
    for spec in db.specs:
        by_type[spec.sld_type].append(spec)

    # ── A. 구조 규칙 (Structural) ──

    for sld_type, specs in by_type.items():
        if sld_type in ("unknown",):
            continue

        # A1: 필수 블록
        all_block_sets = [set(s.block_counts.keys()) for s in specs if s.block_counts]
        if all_block_sets:
            common = set.intersection(*all_block_sets)
            if common:
                rules.append(Rule(
                    name=f"required_blocks_{sld_type}",
                    description=f"{sld_type} SLD에서 모든 레퍼런스에 공통으로 존재하는 블록",
                    category="structural",
                    applies_to=[sld_type],
                    params={"required_blocks": sorted(common)},
                    evidence_count=len(specs),
                ))

        # A2: RCCB 존재 여부
        rccb_count = sum(1 for s in specs if s.has_rccb)
        if rccb_count > 0:
            rules.append(Rule(
                name=f"rccb_presence_{sld_type}",
                description=f"{sld_type}에서 RCCB 존재 비율: {rccb_count}/{len(specs)}",
                category="structural",
                applies_to=[sld_type],
                params={
                    "required": rccb_count == len(specs),
                    "ratio": round(rccb_count / len(specs), 2),
                },
                evidence_count=rccb_count,
            ))

        # A3: 아이솔레이터 존재 여부
        iso_count = sum(1 for s in specs if s.has_isolator)
        if iso_count > 0:
            rules.append(Rule(
                name=f"isolator_presence_{sld_type}",
                description=f"{sld_type}에서 아이솔레이터 존재 비율: {iso_count}/{len(specs)}",
                category="structural",
                applies_to=[sld_type],
                params={
                    "required": iso_count == len(specs),
                    "ratio": round(iso_count / len(specs), 2),
                },
                evidence_count=iso_count,
            ))

        # A4: 서브회로 개수 범위
        counts = [s.total_subcircuits for s in specs if s.total_subcircuits > 0]
        if counts:
            rules.append(Rule(
                name=f"subcircuit_count_range_{sld_type}",
                description=f"{sld_type} 서브회로 개수 범위: {min(counts)}~{max(counts)}",
                category="structural",
                applies_to=[sld_type],
                params={"min": min(counts), "max": max(counts)},
                evidence_count=len(counts),
            ))

        # A5: DB 수 범위
        db_counts = [s.num_dbs for s in specs]
        if db_counts:
            rules.append(Rule(
                name=f"num_dbs_range_{sld_type}",
                description=f"{sld_type} DB 수 범위: {min(db_counts)}~{max(db_counts)}",
                category="structural",
                applies_to=[sld_type],
                params={"min": min(db_counts), "max": max(db_counts)},
                evidence_count=len(db_counts),
            ))

    # A6: CT 계측 전용 규칙
    ct_specs = by_type.get("ct_metering_3phase", [])
    if ct_specs:
        ct_counts = [s.block_counts.get("SLD-CT", 0) for s in ct_specs]
        rules.append(Rule(
            name="ct_blocks_required",
            description=f"CT 계측 SLD에 SLD-CT 블록 필수: {min(ct_counts)}~{max(ct_counts)}개",
            category="structural",
            applies_to=["ct_metering_3phase"],
            params={"min_count": min(ct_counts), "max_count": max(ct_counts)},
            evidence_count=len(ct_specs),
        ))

        fuse_count = sum(1 for s in ct_specs if s.has_fuse)
        rules.append(Rule(
            name="ct_fuse_required",
            description=f"CT 계측 SLD에 2A FUSE 존재: {fuse_count}/{len(ct_specs)}",
            category="structural",
            applies_to=["ct_metering_3phase"],
            params={"required": fuse_count == len(ct_specs)},
            evidence_count=fuse_count,
        ))

    # ── B. 순서 규칙 (Order) ──

    for sld_type, specs in by_type.items():
        if sld_type in ("unknown",):
            continue

        # B1: 스파인 컴포넌트 순서 패턴
        orderings = []
        for spec in specs:
            for order in spec.spine_orders:
                if len(order) >= 2:
                    orderings.append(order)
        if orderings:
            # 가장 빈번한 패턴 찾기
            order_strs = ["|".join(o) for o in orderings]
            most_common = Counter(order_strs).most_common(3)
            rules.append(Rule(
                name=f"spine_order_{sld_type}",
                description=f"{sld_type} 스파인 컴포넌트 Y순서 패턴 (전원→부하)",
                category="order",
                applies_to=[sld_type],
                params={
                    "observed_orders": [o.split("|") for o, _ in most_common],
                    "frequencies": [c for _, c in most_common],
                    "total_samples": len(orderings),
                },
                evidence_count=len(orderings),
            ))

    # B2: 서브회로는 항상 스파인 상단 (높은 Y)
    _derive_subcircuit_position_rule(rules, by_type)

    # ── C. 간격 규칙 (Spacing) ──

    for sld_type, specs in by_type.items():
        if sld_type in ("unknown",):
            continue

        # C1: 서브회로 X간격 범위
        all_spacings = []
        for spec in specs:
            for row in spec.subcircuit_rows:
                all_spacings.extend(row.x_spacings)
        if all_spacings:
            # 이상치 제거 (median의 3배 이상 = 위상 그룹 분리기)
            sorted_sp = sorted(all_spacings)
            median = sorted_sp[len(sorted_sp) // 2]
            regular = [s for s in sorted_sp if s < median * 3.0]
            if regular:
                rules.append(Rule(
                    name=f"subcircuit_x_spacing_{sld_type}",
                    description=f"{sld_type} 서브회로 X간격 (DU): {min(regular):.0f}~{max(regular):.0f}",
                    category="spacing",
                    applies_to=[sld_type],
                    params={
                        "min_du": round(min(regular), 1),
                        "max_du": round(max(regular), 1),
                        "median_du": round(sorted(regular)[len(regular) // 2], 1),
                        "samples": len(regular),
                    },
                    evidence_count=len([s for s in specs if s.subcircuit_rows]),
                ))

    # ── D. 의미 규칙 (Semantic) ──

    # D1: 위상 라벨 존재
    phase_label_counts = defaultdict(int)
    phase_label_totals = defaultdict(int)
    for spec in db.specs:
        if spec.sld_type in ("unknown",):
            continue
        phase_label_totals[spec.sld_type] += 1
        if spec.has_phase_labels:
            phase_label_counts[spec.sld_type] += 1

    for sld_type in phase_label_totals:
        count = phase_label_counts[sld_type]
        total = phase_label_totals[sld_type]
        if count > 0:
            rules.append(Rule(
                name=f"phase_labels_{sld_type}",
                description=f"{sld_type}에서 위상 라벨 존재: {count}/{total}",
                category="semantic",
                applies_to=[sld_type],
                params={"ratio": round(count / total, 2)},
                evidence_count=count,
            ))

    # D2: 정격 라벨 존재
    rating_count = sum(1 for s in db.specs if s.has_rating_labels and s.sld_type != "unknown")
    total_non_unknown = sum(1 for s in db.specs if s.sld_type != "unknown")
    if rating_count > 0:
        rules.append(Rule(
            name="rating_labels_universal",
            description=f"전체 SLD에서 정격 라벨 존재: {rating_count}/{total_non_unknown}",
            category="semantic",
            applies_to=["*"],
            params={"ratio": round(rating_count / total_non_unknown, 2)},
            evidence_count=rating_count,
        ))

    # D3: 케이블 사양 존재
    cable_count = sum(1 for s in db.specs if s.has_cable_annotations and s.sld_type != "unknown")
    if cable_count > 0:
        rules.append(Rule(
            name="cable_annotations_universal",
            description=f"전체 SLD에서 케이블 사양 존재: {cable_count}/{total_non_unknown}",
            category="semantic",
            applies_to=["*"],
            params={"ratio": round(cable_count / total_non_unknown, 2)},
            evidence_count=cable_count,
        ))

    # Build RuleSet
    return RuleSet(
        rules=rules,
        meta={
            "source_files": len(db.specs),
            "total_rules": len(rules),
            "categories": dict(Counter(r.category for r in rules)),
        },
    )


def _derive_subcircuit_position_rule(
    rules: list[Rule],
    by_type: dict[str, list[ReferenceSpec]],
):
    """서브회로가 스파인 컴포넌트보다 높은 Y에 위치하는지 검증."""
    evidence = 0
    total = 0
    for sld_type, specs in by_type.items():
        if sld_type in ("unknown", "cable_extension"):
            continue
        for spec in specs:
            if not spec.subcircuit_rows or not spec.spine_components:
                continue
            total += 1
            max_spine_y = max(c.y for c in spec.spine_components)
            min_subckt_y = min(r.y for r in spec.subcircuit_rows)
            if min_subckt_y > max_spine_y:
                evidence += 1

    if total > 0:
        rules.append(Rule(
            name="subcircuit_above_spine",
            description=f"서브회로는 스파인 컴포넌트보다 높은 Y에 위치: {evidence}/{total}",
            category="order",
            applies_to=["direct_metering_3phase", "ct_metering_3phase", "direct_metering_1phase"],
            params={"ratio": round(evidence / total, 2)},
            evidence_count=evidence,
        ))


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    db = ReferenceDatabase.load()
    logger.info("Loaded %d reference specs", len(db.specs))

    ruleset = derive_rules(db)
    ruleset.save()

    logger.info("\nDerived %d rules:", len(ruleset.rules))
    for cat in ["structural", "order", "spacing", "semantic"]:
        cat_rules = ruleset.by_category(cat)
        logger.info("\n  [%s] (%d rules)", cat.upper(), len(cat_rules))
        for r in cat_rules:
            logger.info("    %-45s evidence=%d  %s", r.name, r.evidence_count, r.params)


if __name__ == "__main__":
    main()
