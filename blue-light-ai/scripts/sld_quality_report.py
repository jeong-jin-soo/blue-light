#!/usr/bin/env python3
"""SLD 품질 레퍼런스 비교 리포트.

주요 레퍼런스 5개에 대해 SLD 생성 → Vision AI self_review → 갭 리포트.

Usage:
    python scripts/sld_quality_report.py [--output report.md]

Requires: GEMINI_API_KEY 환경변수
"""

from __future__ import annotations

import asyncio
import json
import logging
import sys
import tempfile
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

# blue-light-ai/ 루트를 sys.path에 추가
_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(_ROOT))

logging.basicConfig(level=logging.INFO, format="%(levelname)s: %(message)s")
logger = logging.getLogger(__name__)


# ─── Reference Configurations ────────────────────────────────────

@dataclass
class ReferenceConfig:
    """레퍼런스 SLD 매칭 구성."""
    name: str
    ref_pdf: str  # data/sld-info/slds/ 내 파일명
    requirements: dict


REFERENCES: list[ReferenceConfig] = [
    ReferenceConfig(
        name="63A TPN 15ckt (Direct 3P)",
        ref_pdf="63A TPN SLD 1.pdf",
        requirements={
            "supply_type": "three_phase",
            "metering": "direct",
            "kva": 45,
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "3P", "fault_kA": 10},
            "busbar_rating": 63,
            "elcb": {"rating": 63, "sensitivity_ma": 100},
            "sub_circuits": [
                {"name": f"CKT {i+1}", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2.5", "phase": ["R", "Y", "B"][i % 3]}
                for i in range(15)
            ],
        },
    ),
    ReferenceConfig(
        name="63A TPN 27ckt (Direct 3P, Dense)",
        ref_pdf="63A TPN SLD 14.pdf",
        requirements={
            "supply_type": "three_phase",
            "metering": "direct",
            "kva": 45,
            "main_breaker": {"type": "MCCB", "rating": 63, "poles": "3P", "fault_kA": 10},
            "busbar_rating": 63,
            "elcb": {"rating": 63, "sensitivity_ma": 100},
            "sub_circuits": [
                {"name": f"CKT {i+1}", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2.5", "phase": ["R", "Y", "B"][i % 3]}
                for i in range(27)
            ],
        },
    ),
    ReferenceConfig(
        name="40A Single Phase 6ckt",
        ref_pdf="40A Single Phase DB 1.pdf",
        requirements={
            "supply_type": "single_phase",
            "metering": "sp_meter",
            "kva": 9,
            "main_breaker": {"type": "MCB", "rating": 40, "poles": "DP"},
            "busbar_rating": 40,
            "elcb": {"rating": 40, "sensitivity_ma": 30},
            "sub_circuits": [
                {"name": "Lighting", "breaker_type": "MCB", "breaker_rating": 10, "cable": "1.5", "phase": "L"},
                {"name": "13A S/S/O", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2.5", "phase": "L"},
                {"name": "Aircon", "breaker_type": "MCB", "breaker_rating": 20, "cable": "4.0", "phase": "L"},
                {"name": "Water Heater", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2.5", "phase": "L"},
                {"name": "Cooker", "breaker_type": "MCB", "breaker_rating": 32, "cable": "6.0", "phase": "L"},
                {"name": "Spare", "breaker_type": "MCB", "breaker_rating": 20, "cable": "2.5", "phase": "L"},
            ],
        },
    ),
    ReferenceConfig(
        name="100A TPN 12ckt (Direct 3P)",
        ref_pdf="100A TPN SLD 1.pdf",
        requirements={
            "supply_type": "three_phase",
            "metering": "direct",
            "kva": 69,
            "main_breaker": {"type": "MCCB", "rating": 100, "poles": "3P", "fault_kA": 25},
            "busbar_rating": 100,
            "elcb": {"rating": 100, "sensitivity_ma": 100},
            "sub_circuits": [
                {"name": f"CKT {i+1}", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2.5", "phase": ["R", "Y", "B"][i % 3]}
                for i in range(12)
            ],
        },
    ),
    ReferenceConfig(
        name="150A TPN CT Metering 18ckt",
        ref_pdf="150A TPN SLD 1.pdf",
        requirements={
            "supply_type": "three_phase",
            "metering": "ct_metering",
            "kva": 100,
            "main_breaker": {"type": "MCCB", "rating": 150, "poles": "3P", "fault_kA": 36},
            "busbar_rating": 150,
            "ct_ratio": "200/5A",
            "elcb": {"rating": 150, "sensitivity_ma": 100},
            "sub_circuits": [
                {"name": f"CKT {i+1}", "breaker_type": "MCB", "breaker_rating": 20,
                 "cable": "2.5", "phase": ["R", "Y", "B"][i % 3]}
                for i in range(18)
            ],
        },
    ),
]


# ─── Generation + Review ─────────────────────────────────────────

def generate_sld(config: ReferenceConfig) -> tuple[bytes | None, str | None]:
    """SLD 생성 → (pdf_bytes, svg_string)."""
    from app.sld.generator import SldPipeline

    try:
        result = SldPipeline().run(config.requirements)
        return result.pdf_bytes, result.svg_string
    except Exception as e:
        logger.error("Generation failed for %s: %s", config.name, e)
        return None, None


async def review_sld(pdf_bytes: bytes | None, svg_string: str, api_key: str) -> dict:
    """Vision AI self_review 실행 → 이슈 dict 반환.

    PDF가 있으면 PDF 우선 (SVG→PNG 변환 문제 우회).
    """
    from app.sld.vision_validator import self_review

    # PDF 우선, 없으면 SVG
    if pdf_bytes:
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as f:
            f.write(pdf_bytes)
            review_path = f.name
    else:
        with tempfile.NamedTemporaryFile(suffix=".svg", mode="w", delete=False) as f:
            f.write(svg_string)
            review_path = f.name

    try:
        report = await self_review(review_path, api_key=api_key)
        return {
            "score": report.score,
            "severity": report.severity,
            "issue_count": report.issue_count,
            "summary": report.summary,
            "issues": [
                {
                    "category": issue.category,
                    "severity": issue.severity,
                    "description": issue.description,
                    "root_cause": getattr(issue, "root_cause", ""),
                }
                for issue in report.issues
            ],
        }
    except Exception as e:
        logger.error("Vision review failed: %s", e)
        return {"score": 0.0, "severity": "error", "issues": [], "summary": str(e)}
    finally:
        Path(review_path).unlink(missing_ok=True)
        Path(review_path).with_suffix(".png").unlink(missing_ok=True)


# ─── Report Generation ───────────────────────────────────────────

def generate_report(results: list[dict]) -> str:
    """Markdown 리포트 생성."""
    lines = ["# SLD Quality Report\n"]

    # Summary table
    lines.append("## Summary\n")
    lines.append("| Reference | Score | Severity | Issues |")
    lines.append("|-----------|-------|----------|--------|")
    for r in results:
        lines.append(f"| {r['name']} | {r['score']:.2f} | {r['severity']} | {r['issue_count']} |")
    lines.append("")

    # Issue frequency
    all_categories: Counter = Counter()
    all_issues: list[dict] = []
    for r in results:
        for issue in r.get("issues", []):
            all_categories[issue["category"]] += 1
            all_issues.append({**issue, "reference": r["name"]})

    if all_categories:
        lines.append("## Issue Frequency (by category)\n")
        lines.append("| Category | Count | % |")
        lines.append("|----------|-------|---|")
        total = sum(all_categories.values())
        for cat, count in all_categories.most_common():
            lines.append(f"| {cat} | {count} | {count * 100 // total}% |")
        lines.append("")

    # Detailed issues by reference
    lines.append("## Detailed Issues\n")
    for r in results:
        lines.append(f"### {r['name']} (score: {r['score']:.2f})\n")
        if not r.get("issues"):
            lines.append("No issues found.\n")
            continue
        for issue in r["issues"]:
            sev = issue["severity"]
            icon = {"critical": "X", "warning": "!", "info": "i"}.get(sev, "?")
            lines.append(f"- [{icon}] **{issue['category']}** ({sev}): {issue['description']}")
        lines.append("")

    return "\n".join(lines)


# ─── Main ─────────────────────────────────────────────────────────

def main():
    import argparse

    parser = argparse.ArgumentParser(description="SLD Quality Reference Comparison Report")
    parser.add_argument("--output", "-o", default="SLD_QUALITY_REPORT.md", help="Output file path")
    parser.add_argument("--skip-vision", action="store_true", help="Skip Vision AI (generation only)")
    args = parser.parse_args()

    from app.config import settings
    api_key = settings.gemini_api_key
    if not api_key and not args.skip_vision:
        logger.error("GEMINI_API_KEY required for Vision AI review. Use --skip-vision to skip.")
        sys.exit(1)

    results: list[dict] = []

    for i, config in enumerate(REFERENCES, 1):
        logger.info("━━━ [%d/%d] %s ━━━", i, len(REFERENCES), config.name)

        # Generate
        pdf_bytes, svg_string = generate_sld(config)
        if not svg_string:
            results.append({
                "name": config.name,
                "score": 0.0,
                "severity": "error",
                "issue_count": 0,
                "issues": [],
                "summary": "Generation failed",
            })
            continue

        logger.info("  Generated: PDF=%s, SVG=%d chars",
                     f"{len(pdf_bytes)}B" if pdf_bytes else "N/A", len(svg_string))

        # Vision review
        if args.skip_vision:
            results.append({
                "name": config.name,
                "score": 1.0,
                "severity": "pass",
                "issue_count": 0,
                "issues": [],
                "summary": "Vision review skipped",
            })
        else:
            review = asyncio.run(review_sld(pdf_bytes, svg_string, api_key))
            results.append({
                "name": config.name,
                **review,
            })
            logger.info("  Review: score=%.2f, severity=%s, issues=%d",
                         review["score"], review["severity"], len(review.get("issues", [])))

    # Generate report
    report = generate_report(results)
    output_path = Path(args.output)
    output_path.write_text(report, encoding="utf-8")
    logger.info("\nReport saved to %s", output_path)

    # Print summary
    print(f"\n{'='*60}")
    print(f"  SLD Quality Report — {len(results)} references")
    print(f"{'='*60}")
    avg_score = sum(r["score"] for r in results) / len(results) if results else 0
    print(f"  Average score: {avg_score:.2f}")
    for r in results:
        status = "PASS" if r["severity"] in ("pass", "info") else r["severity"].upper()
        print(f"  [{status:8s}] {r['name']} — score={r['score']:.2f}, issues={r.get('issue_count', 0)}")


if __name__ == "__main__":
    main()
