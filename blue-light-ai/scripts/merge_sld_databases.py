#!/usr/bin/env python3
"""
SLD 데이터베이스 머지 스크립트.

DWG 추출분(26건)으로 PDF 추출분을 교체하고,
PDF-only 항목(47건)은 그대로 유지하여 통합 sld_database.json 생성.

머지 전략:
  기존 sld_database.json (73건)
    ├─ DWG 매칭 26건 → DWG 추출분으로 교체 (source: "dwg_parsed")
    └─ PDF only 47건 → 그대로 유지 (source: "pdf_gemini" 태깅)
  결과: sld_database.json (73건, 26건 교체)

Usage:
    cd blue-light-ai
    python scripts/merge_sld_databases.py
"""

import json
import logging
import shutil
import sys
from datetime import datetime
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info"
PDF_DB = DATA_DIR / "sld_database.json"
DWG_DB = DATA_DIR / "sld_database_dwg.json"
OUTPUT_DB = DATA_DIR / "sld_database.json"
BACKUP_DB = DATA_DIR / "sld_database_backup.json"


def main():
    logger.info("=== SLD 데이터베이스 머지 시작 ===")

    # ── 입력 파일 확인 ──
    if not PDF_DB.exists():
        logger.error(f"PDF DB 없음: {PDF_DB}")
        sys.exit(1)
    if not DWG_DB.exists():
        logger.error(f"DWG DB 없음: {DWG_DB}")
        sys.exit(1)

    # ── 로드 ──
    with open(PDF_DB, "r", encoding="utf-8") as f:
        pdf_entries = json.load(f)
    with open(DWG_DB, "r", encoding="utf-8") as f:
        dwg_entries = json.load(f)

    logger.info(f"PDF DB: {len(pdf_entries)}건")
    logger.info(f"DWG DB: {len(dwg_entries)}건")

    # ── DWG 인덱스 (filename → entry) ──
    dwg_index: dict[str, dict] = {}
    for entry in dwg_entries:
        fn = entry.get("filename", "")
        if fn:
            dwg_index[fn] = entry

    # ── 머지 ──
    merged = []
    replaced = 0
    kept_pdf = 0

    for pdf_entry in pdf_entries:
        fn = pdf_entry.get("filename", "")
        if fn in dwg_index:
            # DWG 추출분으로 교체
            dwg_entry = dwg_index[fn]
            # source 필드 확인 (이미 dwg_parsed로 설정되어 있어야 함)
            if "source" not in dwg_entry:
                dwg_entry["source"] = "dwg_parsed"
            merged.append(dwg_entry)
            replaced += 1
            logger.debug(f"  교체: {fn}")
        else:
            # PDF-only 항목 유지 + source 태깅
            if "source" not in pdf_entry:
                pdf_entry["source"] = "pdf_gemini"
            merged.append(pdf_entry)
            kept_pdf += 1

    logger.info(f"\n머지 결과:")
    logger.info(f"  DWG로 교체: {replaced}건")
    logger.info(f"  PDF 유지:   {kept_pdf}건")
    logger.info(f"  합계:       {len(merged)}건")

    # ── 검증 ──
    assert len(merged) == len(pdf_entries), (
        f"머지 후 건수 불일치: {len(merged)} vs {len(pdf_entries)}"
    )

    # source 분포 확인
    source_counts: dict[str, int] = {}
    for entry in merged:
        src = entry.get("source", "unknown")
        source_counts[src] = source_counts.get(src, 0) + 1

    logger.info(f"\n  source 분포:")
    for src, cnt in sorted(source_counts.items()):
        logger.info(f"    {src}: {cnt}건")

    # DWG 항목 필드 품질
    dwg_items = [e for e in merged if e.get("source") == "dwg_parsed"]
    kva_filled = sum(1 for e in dwg_items if e.get("kva") is not None)
    mb_filled = sum(
        1 for e in dwg_items
        if isinstance(e.get("main_breaker"), dict) and e["main_breaker"].get("rating_a")
    )
    sc_total = sum(len(e.get("sub_circuits", [])) for e in dwg_items)

    logger.info(f"\n  DWG 항목 품질:")
    logger.info(f"    kVA 채워진 항목:  {kva_filled}/{len(dwg_items)}")
    logger.info(f"    Main breaker 있음: {mb_filled}/{len(dwg_items)}")
    logger.info(f"    서브회로 합계:     {sc_total}개")

    # ── 백업 + 저장 ──
    if OUTPUT_DB.exists():
        backup_name = f"sld_database_backup_{datetime.now().strftime('%Y%m%d_%H%M%S')}.json"
        backup_path = DATA_DIR / backup_name
        shutil.copy2(OUTPUT_DB, backup_path)
        logger.info(f"\n기존 DB 백업: {backup_path}")

    with open(OUTPUT_DB, "w", encoding="utf-8") as f:
        json.dump(merged, f, indent=2, ensure_ascii=False)

    logger.info(f"머지된 DB 저장: {OUTPUT_DB}")
    logger.info("=== 머지 완료 ===")


if __name__ == "__main__":
    main()
