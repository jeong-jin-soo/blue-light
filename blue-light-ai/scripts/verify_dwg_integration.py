#!/usr/bin/env python3
"""
DWG 통합 검증 스크립트.

다음 항목을 검증:
1. sld_database.json: 26건 source=dwg_parsed 확인
2. DWG 항목: kva, sub_circuits, main_breaker 등 데이터 품질
3. MySQL sld_templates: DWG 항목 존재 확인
4. find_similar_templates() 호출 → DWG 항목이 결과에 포함
5. convert_to_image() → PNG 생성 확인

Usage:
    cd blue-light-ai
    venv/bin/python scripts/verify_dwg_integration.py
"""

import json
import logging
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info"
PASS = "✅"
FAIL = "❌"
WARN = "⚠️"

results = []


def check(name: str, ok: bool, detail: str = ""):
    status = PASS if ok else FAIL
    msg = f"  {status} {name}"
    if detail:
        msg += f" — {detail}"
    results.append((name, ok, detail))
    print(msg)
    return ok


def main():
    all_ok = True
    print("\n" + "=" * 60)
    print("  DWG Integration Verification")
    print("=" * 60 + "\n")

    # ── 1. sld_database.json 검증 ──
    print("[1] sld_database.json")
    db_path = DATA_DIR / "sld_database.json"
    if not db_path.exists():
        check("sld_database.json exists", False)
        sys.exit(1)

    with open(db_path) as f:
        db = json.load(f)

    check("Total entries", len(db) == 73, f"{len(db)}건")
    all_ok &= len(db) == 73

    dwg_entries = [e for e in db if e.get("source") == "dwg_parsed"]
    pdf_entries = [e for e in db if e.get("source") == "pdf_gemini"]
    check("DWG entries", len(dwg_entries) == 26, f"{len(dwg_entries)}건")
    check("PDF entries", len(pdf_entries) == 47, f"{len(pdf_entries)}건")
    all_ok &= len(dwg_entries) == 26 and len(pdf_entries) == 47

    # ── 2. DWG 항목 데이터 품질 ──
    print("\n[2] DWG Data Quality")
    kva_ok = sum(1 for e in dwg_entries if e.get("kva") is not None)
    check("kVA present", kva_ok >= 23, f"{kva_ok}/26")  # 3 cable extensions may have null

    mb_ok = sum(
        1 for e in dwg_entries
        if isinstance(e.get("main_breaker"), dict) and e["main_breaker"].get("rating_a")
    )
    check("Main breaker rating", mb_ok == 26, f"{mb_ok}/26")
    all_ok &= mb_ok == 26

    sc_total = sum(len(e.get("sub_circuits", [])) for e in dwg_entries)
    check("Sub-circuits total", sc_total > 400, f"{sc_total}개")

    # DWG 필드 존재 확인
    dwg_path_ok = sum(1 for e in dwg_entries if e.get("dwg_path"))
    dxf_path_ok = sum(1 for e in dwg_entries if e.get("dxf_path"))
    check("dwg_path present", dwg_path_ok == 26, f"{dwg_path_ok}/26")
    check("dxf_path present", dxf_path_ok == 26, f"{dxf_path_ok}/26")

    # ── 3. MySQL 검증 ──
    print("\n[3] MySQL sld_templates")
    try:
        from app.config import settings
        from app.db.connection import get_db

        with get_db() as conn:
            with conn.cursor() as cursor:
                cursor.execute("SELECT COUNT(*) AS cnt FROM sld_templates")
                total = cursor.fetchone()["cnt"]

                cursor.execute("""
                    SELECT COUNT(*) AS cnt FROM sld_templates
                    WHERE JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.source')) = 'dwg_parsed'
                """)
                dwg_db_count = cursor.fetchone()["cnt"]

                cursor.execute("""
                    SELECT COUNT(*) AS cnt FROM sld_templates
                    WHERE JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.source')) = 'pdf_gemini'
                """)
                pdf_db_count = cursor.fetchone()["cnt"]

        check("DB total", total == 73, f"{total}건")
        check("DB dwg_parsed", dwg_db_count == 26, f"{dwg_db_count}건")
        check("DB pdf_gemini", pdf_db_count == 47, f"{pdf_db_count}건")
        all_ok &= total == 73 and dwg_db_count == 26

    except Exception as e:
        check("MySQL connection", False, str(e))
        all_ok = False

    # ── 4. find_similar_templates 테스트 ──
    print("\n[4] find_similar_templates()")
    try:
        from app.sld.template_matcher import find_similar_templates

        # 3상 63A — DWG 항목이 여러 개 있으므로 결과에 포함되어야 함
        results_3ph = find_similar_templates(
            {"supply_type": "three_phase", "kva": 45.0, "circuit_count": 8},
            limit=5,
        )
        check("3ph 45kVA results", len(results_3ph) > 0, f"{len(results_3ph)}건")

        # 결과에 DWG 항목이 포함되는지
        dwg_in_results = any(r.get("source") == "dwg_parsed" for r in results_3ph)
        check("DWG in results", dwg_in_results, "source=dwg_parsed 포함")
        all_ok &= dwg_in_results

        # 1상 테스트
        results_1ph = find_similar_templates(
            {"supply_type": "single_phase", "kva": 7.0},
            limit=3,
        )
        check("1ph 7kVA results", len(results_1ph) > 0, f"{len(results_1ph)}건")

        if results_3ph:
            top = results_3ph[0]
            has_meta = "source" in top and "dwg_path" in top and "dxf_path" in top
            check("Metadata fields", has_meta, f"source={top.get('source')}")

    except Exception as e:
        check("Template matching", False, str(e))
        all_ok = False

    # ── 5. convert_to_image 테스트 ──
    print("\n[5] convert_to_image()")
    try:
        from app.sld.template_matcher import convert_to_image

        # DWG 항목의 PDF로 이미지 변환 테스트
        test_pdf = str(DATA_DIR / "slds" / "63A TPN SLD 1.pdf")
        img = convert_to_image(test_pdf)
        check("PDF→PNG", img is not None and Path(img).exists(), str(img) if img else "None")
        all_ok &= img is not None

    except Exception as e:
        check("Image conversion", False, str(e))
        all_ok = False

    # ── Summary ──
    print("\n" + "=" * 60)
    passed = sum(1 for _, ok, _ in results if ok)
    failed = sum(1 for _, ok, _ in results if not ok)
    total_checks = len(results)
    status = PASS if all_ok else FAIL
    print(f"  {status} {passed}/{total_checks} checks passed, {failed} failed")
    print("=" * 60 + "\n")

    sys.exit(0 if all_ok else 1)


if __name__ == "__main__":
    main()
