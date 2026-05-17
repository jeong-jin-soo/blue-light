#!/usr/bin/env python3
"""
Compare DWG-extracted data vs Gemini Vision PDF-extracted data
for the 26 overlapping SLD templates.

Usage:
    python scripts/compare_extractions.py
    (run from the blue-light-ai directory)
"""

import json
import re
import sys
from pathlib import Path
from typing import Any, Optional


# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------
BASE_DIR = Path(__file__).resolve().parent.parent
DWG_DB_PATH = BASE_DIR / "data" / "sld-info" / "sld_database_dwg.json"
PDF_DB_PATH = BASE_DIR / "data" / "sld-info" / "sld_database.json"
OUTPUT_PATH = BASE_DIR / "data" / "sld-info" / "extraction_comparison.json"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def safe_get(d: dict, *keys, default=None) -> Any:
    """Safely traverse nested dicts."""
    val = d
    for k in keys:
        if isinstance(val, dict):
            val = val.get(k)
        else:
            return default
        if val is None:
            return default
    return val


def normalize_text(s: Optional[str]) -> str:
    """Normalize cable/text descriptions for comparison."""
    if s is None:
        return ""
    s = str(s).strip().lower()
    # collapse multiple spaces
    s = re.sub(r"\s+", " ", s)
    # normalize common unit formats: sqmm -> mm², mm2 -> mm²
    s = s.replace("sqmm", "mm²").replace("mm2", "mm²")
    return s


def numeric_equal(a, b, tolerance=0.01) -> bool:
    """Compare two numeric values with tolerance."""
    if a is None and b is None:
        return True
    if a is None or b is None:
        return False
    try:
        return abs(float(a) - float(b)) <= tolerance
    except (ValueError, TypeError):
        return False


def numeric_pct_diff(a, b) -> Optional[float]:
    """Return percentage difference or None."""
    if a is None or b is None:
        return None
    try:
        fa, fb = float(a), float(b)
        if fb == 0:
            return None
        return ((fa - fb) / fb) * 100
    except (ValueError, TypeError):
        return None


def extract_rating_from_filename(filename: str) -> Optional[int]:
    """Extract the amp rating from a filename like '150A TPN SLD 1.pdf'."""
    m = re.match(r"(\d+)A\s", filename)
    if m:
        return int(m.group(1))
    return None


# ---------------------------------------------------------------------------
# Comparison categories
# ---------------------------------------------------------------------------
MATCH = "match"
MINOR_DIFF = "minor_diff"
SIGNIFICANT_DIFF = "significant_diff"
DWG_IMPROVEMENT = "dwg_improvement"
PDF_ONLY = "pdf_only"
BOTH_NULL = "both_null"


def classify_text_diff(dwg_val: Optional[str], pdf_val: Optional[str]) -> str:
    """Classify a text field difference."""
    if dwg_val is None and pdf_val is None:
        return BOTH_NULL
    if dwg_val is not None and pdf_val is None:
        return DWG_IMPROVEMENT
    if dwg_val is None and pdf_val is not None:
        return PDF_ONLY
    if normalize_text(dwg_val) == normalize_text(pdf_val):
        return MATCH
    # Check if normalized forms are similar (formatting only)
    nd = normalize_text(dwg_val)
    np_ = normalize_text(pdf_val)
    # strip all non-alphanumeric for "core content" comparison
    core_d = re.sub(r"[^a-z0-9]", "", nd)
    core_p = re.sub(r"[^a-z0-9]", "", np_)
    if core_d == core_p:
        return MINOR_DIFF
    return SIGNIFICANT_DIFF


def classify_numeric_diff(dwg_val, pdf_val) -> str:
    """Classify a numeric field difference."""
    if dwg_val is None and pdf_val is None:
        return BOTH_NULL
    if dwg_val is not None and pdf_val is None:
        return DWG_IMPROVEMENT
    if dwg_val is None and pdf_val is not None:
        return PDF_ONLY
    if numeric_equal(dwg_val, pdf_val):
        return MATCH
    return SIGNIFICANT_DIFF


def classify_exact_diff(dwg_val, pdf_val) -> str:
    """Classify an exact-match field difference."""
    if dwg_val is None and pdf_val is None:
        return BOTH_NULL
    if dwg_val is not None and pdf_val is None:
        return DWG_IMPROVEMENT
    if dwg_val is None and pdf_val is not None:
        return PDF_ONLY
    # Normalize strings for exact comparison
    dv = str(dwg_val).strip().lower() if dwg_val is not None else ""
    pv = str(pdf_val).strip().lower() if pdf_val is not None else ""
    if dv == pv:
        return MATCH
    # Check minor variations (e.g., "TPN" vs "4P", "DP" vs "2P")
    pole_aliases = {
        "dp": {"2p", "dp"},
        "2p": {"2p", "dp"},
        "spn": {"spn", "sp", "1p"},
        "sp": {"spn", "sp", "1p"},
        "1p": {"spn", "sp", "1p"},
        "tpn": {"tpn", "4p", "tp+n"},
        "4p": {"tpn", "4p", "tp+n"},
        "tp+n": {"tpn", "4p", "tp+n"},
        "tp": {"tp", "3p"},
        "3p": {"tp", "3p"},
    }
    if dv in pole_aliases and pv in pole_aliases.get(dv, set()):
        return MINOR_DIFF
    return SIGNIFICANT_DIFF


# ---------------------------------------------------------------------------
# Compare a single file
# ---------------------------------------------------------------------------
def compare_entry(dwg: dict, pdf: dict, filename: str) -> dict:
    """Compare one DWG entry against its PDF counterpart."""
    results = {
        "matches": [],
        "minor_diffs": [],
        "diffs": [],
        "dwg_improvements": [],
        "pdf_only": [],
        "warnings": [],
    }

    def record(field: str, category: str, detail: str):
        if category == MATCH:
            results["matches"].append({"field": field, "detail": detail})
        elif category == MINOR_DIFF:
            results["minor_diffs"].append({"field": field, "detail": detail})
        elif category == SIGNIFICANT_DIFF:
            results["diffs"].append({"field": field, "detail": detail})
        elif category == DWG_IMPROVEMENT:
            results["dwg_improvements"].append({"field": field, "detail": detail})
        elif category == PDF_ONLY:
            results["pdf_only"].append({"field": field, "detail": detail})
        # BOTH_NULL — skip, not interesting

    # 1. supply_type
    dv, pv = dwg.get("supply_type"), pdf.get("supply_type")
    cat = classify_exact_diff(dv, pv)
    record("supply_type", cat, f"DWG: {dv}  PDF: {pv}")

    # 2. kva
    dv, pv = dwg.get("kva"), pdf.get("kva")
    cat = classify_numeric_diff(dv, pv)
    pct = numeric_pct_diff(dv, pv)
    pct_str = f" ({pct:+.1f}%)" if pct is not None and not numeric_equal(dv, pv) else ""
    record("kva", cat, f"DWG: {dv}  PDF: {pv}{pct_str}")

    # 3. voltage
    dv, pv = dwg.get("voltage"), pdf.get("voltage")
    cat = classify_exact_diff(dv, pv)
    record("voltage", cat, f"DWG: {dv}  PDF: {pv}")

    # 4. main_breaker.type
    dv, pv = safe_get(dwg, "main_breaker", "type"), safe_get(pdf, "main_breaker", "type")
    cat = classify_exact_diff(dv, pv)
    record("main_breaker.type", cat, f"DWG: {dv}  PDF: {pv}")

    # 5. main_breaker.rating_a
    dv, pv = safe_get(dwg, "main_breaker", "rating_a"), safe_get(pdf, "main_breaker", "rating_a")
    cat = classify_numeric_diff(dv, pv)
    record("main_breaker.rating_a", cat, f"DWG: {dv}  PDF: {pv}")

    # Sanity check: main_breaker.rating_a vs filename
    fname_rating = extract_rating_from_filename(filename)
    dwg_rating = safe_get(dwg, "main_breaker", "rating_a")
    if fname_rating and dwg_rating:
        try:
            if float(dwg_rating) < fname_rating * 0.5:
                results["warnings"].append(
                    f"DWG main_breaker.rating_a={dwg_rating} seems low for "
                    f"filename '{filename}' (expected ~{fname_rating}A)"
                )
        except (ValueError, TypeError):
            pass

    # 6. main_breaker.poles
    dv, pv = safe_get(dwg, "main_breaker", "poles"), safe_get(pdf, "main_breaker", "poles")
    cat = classify_exact_diff(dv, pv)
    record("main_breaker.poles", cat, f"DWG: {dv}  PDF: {pv}")

    # 7. main_breaker.ka_rating
    dv, pv = safe_get(dwg, "main_breaker", "ka_rating"), safe_get(pdf, "main_breaker", "ka_rating")
    cat = classify_numeric_diff(dv, pv)
    record("main_breaker.ka_rating", cat, f"DWG: {dv}  PDF: {pv}")

    # 8. incoming_cable.description
    dv = safe_get(dwg, "incoming_cable", "description")
    pv = safe_get(pdf, "incoming_cable", "description")
    cat = classify_text_diff(dv, pv)
    record("incoming_cable.description", cat, f"DWG: {dv}  PDF: {pv}")

    # 9. elcb.type
    dv, pv = safe_get(dwg, "elcb", "type"), safe_get(pdf, "elcb", "type")
    cat = classify_exact_diff(dv, pv)
    record("elcb.type", cat, f"DWG: {dv}  PDF: {pv}")

    # 10. elcb.rating_a
    dv, pv = safe_get(dwg, "elcb", "rating_a"), safe_get(pdf, "elcb", "rating_a")
    cat = classify_numeric_diff(dv, pv)
    record("elcb.rating_a", cat, f"DWG: {dv}  PDF: {pv}")

    # 11. busbar.rating_a
    dv, pv = safe_get(dwg, "busbar", "rating_a"), safe_get(pdf, "busbar", "rating_a")
    cat = classify_numeric_diff(dv, pv)
    record("busbar.rating_a", cat, f"DWG: {dv}  PDF: {pv}")

    # 12. metering.type
    dv, pv = safe_get(dwg, "metering", "type"), safe_get(pdf, "metering", "type")
    cat = classify_exact_diff(dv, pv)
    record("metering.type", cat, f"DWG: {dv}  PDF: {pv}")

    # 13 & 14. sub_circuits
    dwg_sc = dwg.get("sub_circuits") or []
    pdf_sc = pdf.get("sub_circuits") or []
    count_cat = classify_numeric_diff(len(dwg_sc), len(pdf_sc))
    record("sub_circuits.count", count_cat, f"DWG: {len(dwg_sc)}  PDF: {len(pdf_sc)}")

    max_sc = max(len(dwg_sc), len(pdf_sc))
    for i in range(max_sc):
        d_sc = dwg_sc[i] if i < len(dwg_sc) else {}
        p_sc = pdf_sc[i] if i < len(pdf_sc) else {}

        prefix = f"sub_circuits[{i}]"

        # breaker_type
        dv, pv = d_sc.get("breaker_type"), p_sc.get("breaker_type")
        cat = classify_exact_diff(dv, pv)
        record(f"{prefix}.breaker_type", cat, f"DWG: {dv}  PDF: {pv}")

        # breaker_rating_a
        dv, pv = d_sc.get("breaker_rating_a"), p_sc.get("breaker_rating_a")
        cat = classify_numeric_diff(dv, pv)
        record(f"{prefix}.breaker_rating_a", cat, f"DWG: {dv}  PDF: {pv}")

        # cable
        dv, pv = d_sc.get("cable"), p_sc.get("cable")
        cat = classify_text_diff(dv, pv)
        record(f"{prefix}.cable", cat, f"DWG: {dv}  PDF: {pv}")

    return results


# ---------------------------------------------------------------------------
# Console output
# ---------------------------------------------------------------------------
ICONS = {
    MATCH: "\u2713 MATCH",
    MINOR_DIFF: "~ MINOR",
    SIGNIFICANT_DIFF: "\u2717 DIFFER",
    DWG_IMPROVEMENT: "+ DWG_ONLY",
    PDF_ONLY: "- PDF_ONLY",
}


def print_file_result(filename: str, result: dict):
    """Print comparison results for one file."""
    print(f"\n--- {filename} ---")

    all_items = []
    for item in result["matches"]:
        all_items.append((item["field"], MATCH, item["detail"]))
    for item in result["minor_diffs"]:
        all_items.append((item["field"], MINOR_DIFF, item["detail"]))
    for item in result["diffs"]:
        all_items.append((item["field"], SIGNIFICANT_DIFF, item["detail"]))
    for item in result["dwg_improvements"]:
        all_items.append((item["field"], DWG_IMPROVEMENT, item["detail"]))
    for item in result["pdf_only"]:
        all_items.append((item["field"], PDF_ONLY, item["detail"]))

    # Sort by field name for consistent ordering
    # Group: top-level fields first, then sub_circuits by index
    def sort_key(x):
        field = x[0]
        if field.startswith("sub_circuits["):
            m = re.match(r"sub_circuits\[(\d+)\]\.(.+)", field)
            if m:
                return (1, int(m.group(1)), m.group(2))
        return (0, 0, field)

    all_items.sort(key=sort_key)

    for field, category, detail in all_items:
        icon = ICONS[category]
        # Indent sub-circuit fields
        indent = "    " if field.startswith("sub_circuits[") else "  "
        print(f"{indent}{field:40s} {icon:15s} -- {detail}")

    for w in result.get("warnings", []):
        print(f"  !! WARNING: {w}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
def main():
    # Load data
    if not DWG_DB_PATH.exists():
        print(f"ERROR: DWG database not found at {DWG_DB_PATH}")
        sys.exit(1)
    if not PDF_DB_PATH.exists():
        print(f"ERROR: PDF database not found at {PDF_DB_PATH}")
        sys.exit(1)

    with open(DWG_DB_PATH) as f:
        dwg_entries = json.load(f)
    with open(PDF_DB_PATH) as f:
        pdf_entries = json.load(f)

    # Build lookup by filename
    dwg_by_name = {e["filename"]: e for e in dwg_entries}
    pdf_by_name = {e["filename"]: e for e in pdf_entries}

    # Find overlapping filenames
    overlap = sorted(set(dwg_by_name.keys()) & set(pdf_by_name.keys()))

    print("=" * 60)
    print("=== SLD Extraction Comparison: DWG vs PDF (Gemini) ===")
    print("=" * 60)
    print(f"DWG entries: {len(dwg_entries)}")
    print(f"PDF entries: {len(pdf_entries)}")
    print(f"Found {len(overlap)} overlapping entries")

    # Compare each overlapping file
    all_results = {}
    totals = {
        "fields_compared": 0,
        "perfect_match": 0,
        "minor_diff": 0,
        "significant_diff": 0,
        "dwg_improvement": 0,
        "pdf_only": 0,
    }

    for filename in overlap:
        result = compare_entry(dwg_by_name[filename], pdf_by_name[filename], filename)
        all_results[filename] = result
        print_file_result(filename, result)

        totals["perfect_match"] += len(result["matches"])
        totals["minor_diff"] += len(result["minor_diffs"])
        totals["significant_diff"] += len(result["diffs"])
        totals["dwg_improvement"] += len(result["dwg_improvements"])
        totals["pdf_only"] += len(result["pdf_only"])

    totals["fields_compared"] = (
        totals["perfect_match"]
        + totals["minor_diff"]
        + totals["significant_diff"]
        + totals["dwg_improvement"]
        + totals["pdf_only"]
    )

    # Count files with DWG improvements / PDF-only
    files_with_dwg_imp = sum(1 for r in all_results.values() if r["dwg_improvements"])
    files_with_pdf_only = sum(1 for r in all_results.values() if r["pdf_only"])

    # Collect all warnings
    all_warnings = []
    for fn, r in all_results.items():
        for w in r.get("warnings", []):
            all_warnings.append(f"{fn}: {w}")

    # Print summary
    N = totals["fields_compared"]
    print("\n" + "=" * 60)
    print("--- Summary ---")
    print("=" * 60)
    print(f"Total files compared: {len(overlap)}")
    print(f"Fields compared:      {N}")
    if N > 0:
        print(
            f"  Perfect match:      {totals['perfect_match']:>4d}/{N}"
            f"  ({totals['perfect_match']/N*100:.1f}%)"
        )
        print(
            f"  Minor diff:         {totals['minor_diff']:>4d}/{N}"
            f"  ({totals['minor_diff']/N*100:.1f}%)"
            f"  (formatting only)"
        )
        print(
            f"  Significant diff:   {totals['significant_diff']:>4d}/{N}"
            f"  ({totals['significant_diff']/N*100:.1f}%)"
            f"  (actual data difference)"
        )
    print()
    print(
        f"DWG improvements (DWG has data, PDF is null): "
        f"{totals['dwg_improvement']} fields across {files_with_dwg_imp} files"
    )
    print(
        f"PDF-only data (PDF has data, DWG is null):    "
        f"{totals['pdf_only']} fields across {files_with_pdf_only} files"
    )

    if all_warnings:
        print(f"\n!! WARNINGS ({len(all_warnings)}):")
        for w in all_warnings:
            print(f"  - {w}")

    # Save JSON summary
    json_output = {
        "summary": {
            "total_files": len(overlap),
            "fields_compared": N,
            "perfect_match": totals["perfect_match"],
            "minor_diff": totals["minor_diff"],
            "significant_diff": totals["significant_diff"],
            "dwg_improvements": totals["dwg_improvement"],
            "pdf_only_data": totals["pdf_only"],
            "files_with_dwg_improvements": files_with_dwg_imp,
            "files_with_pdf_only": files_with_pdf_only,
            "warnings": all_warnings,
        },
        "files": {},
    }

    for filename, result in all_results.items():
        json_output["files"][filename] = {
            "matches": result["matches"],
            "minor_diffs": result["minor_diffs"],
            "diffs": result["diffs"],
            "dwg_improvements": result["dwg_improvements"],
            "pdf_only": result["pdf_only"],
            "warnings": result.get("warnings", []),
        }

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_PATH, "w") as f:
        json.dump(json_output, f, indent=2, ensure_ascii=False)

    print(f"\nJSON summary saved to: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
