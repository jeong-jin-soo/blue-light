#!/usr/bin/env python3
"""
sld-dwg-old/ 디렉토리의 LEW 실제 DWG 도면을 sld_templates 매칭 풀에 추가한다.

기존 ``sld_database.json`` 은 ``data/sld-info/slds/*.pdf`` (사람이 정리한 73개)
만 인덱싱한다. 이 스크립트는 ``circuit_data_extracted.json`` 에서 추출 성공한
LEW DWG 메타를 같은 스키마로 변환해 매칭 풀을 확장한다.

처리 규칙
- ``supply_info.phase`` → "single_phase" / "three_phase"
- ``main_breaker.rating_a`` 가 있는 항목만 (없으면 매칭 의미 없음)
- 출력: ``data/sld-info/sld_database_dwg_extras.json``
- 그 후 import_sld_templates.py 가 두 파일을 모두 읽도록 안내

Usage:
    cd blue-light-ai
    .venv/bin/python -m scripts.index_sld_dwg_old
    .venv/bin/python -m scripts.import_sld_templates  # 확장된 풀로 재임포트
"""

from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

DATA_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info"
EXTRACTED = DATA_DIR / "sld-dwg-old" / "circuit_data_extracted.json"
OUT = DATA_DIR / "sld_database_dwg_extras.json"
CONVERTED_DXF_DIR = DATA_DIR / "sld-dwg-old" / "converted_dxf"


def _normalize_phase(raw: str) -> str:
    if not raw:
        return ""
    r = raw.lower().replace("-", "_")
    if "single" in r or "1_phase" in r or r == "1p":
        return "single_phase"
    if "3_phase" in r or "three" in r or "tpn" in r or r == "3p":
        return "three_phase"
    return ""


def _meter_type(d: dict) -> str:
    mb = d.get("meter_board") or {}
    if mb:
        # KWH meter exists → sp_meter (직접 계량)
        return "sp_meter"
    # CT 계량은 main_breaker.rating_a >= 125A & 3-phase 인 경우 휴리스틱
    main = d.get("main_breaker") or {}
    rating = main.get("rating_a") or 0
    phase = _normalize_phase((d.get("supply_info") or {}).get("phase", ""))
    if phase == "three_phase" and rating and float(rating) >= 125:
        return "ct_meter"
    return "sp_meter"


def _convert_entry(src: dict) -> dict | None:
    """circuit_data_extracted 항목 → sld_database 항목."""
    main = src.get("main_breaker") or {}
    # 실제 추출 형식: rating_a 또는 rating_inferred 또는 breaker_specs 중 최대값
    rating = main.get("rating_a") or main.get("rating_inferred")
    if not rating:
        # breaker_specs 중 가장 큰 등급을 main breaker로 추론
        specs = src.get("breaker_specs") or []
        if specs:
            ratings = [s.get("rating_A") or s.get("rating_a") for s in specs if isinstance(s, dict)]
            ratings = [r for r in ratings if r]
            if ratings:
                rating = max(ratings)

    phase = _normalize_phase((src.get("supply_info") or {}).get("phase", ""))
    if not phase or not rating:
        return None

    elcb_rccb = src.get("elcb_rccb") or []
    elcb = {}
    if elcb_rccb:
        first = elcb_rccb[0]
        if isinstance(first, dict):
            elcb = {
                "type": first.get("type", "RCCB"),
                "rating_a": first.get("rating_a", rating),
                "sensitivity_ma": first.get("sensitivity_ma", 30),
                "poles": first.get("poles", 2 if phase == "single_phase" else 4),
            }

    busbar = src.get("busbar") or {}
    if not busbar.get("rating_a"):
        busbar = {"rating_a": rating}

    circuits_raw = src.get("circuits") or []
    sub_circuits = []
    for c in circuits_raw:
        if not isinstance(c, dict):
            continue
        sub_circuits.append({
            "name": c.get("name") or c.get("description") or "",
            "breaker_type": c.get("breaker_type", "MCB"),
            "breaker_rating": c.get("rating_a") or c.get("breaker_rating") or 0,
            "cable": c.get("cable", ""),
        })

    filename = src["filename"]
    stem = Path(filename).stem
    # 변환된 DXF가 있으면 Vision 참조 이미지 변환에 사용 (PDF 부재 시 fallback).
    dxf_rel = f"sld-dwg-old/converted_dxf/{stem}.dxf"
    dxf_exists = (CONVERTED_DXF_DIR / f"{stem}.dxf").exists()

    return {
        "supply_type": phase,
        "kva": main.get("kva") or 0,  # 있으면 사용
        "voltage": 230 if phase == "single_phase" else 400,
        "main_breaker": {
            "type": main.get("type", "MCCB"),
            "rating_a": rating,
            "poles": main.get("poles", "DP" if phase == "single_phase" else "TPN"),
            "ka_rating": main.get("ka_rating", 10),
        },
        "elcb": elcb,
        "busbar": busbar,
        "sub_circuits": sub_circuits,
        "metering": {"type": _meter_type(src)},
        # PDF 부재가 일반적이므로 filename은 DWG 그대로 두고 dxf_path를 우선 노출.
        # template_matcher.convert_to_image()는 dxf_path가 있으면 그걸로 SVG→PNG 렌더 가능.
        "filename": filename,
        "file_path": dxf_rel if dxf_exists else f"sld-dwg-old/{filename}",
        "source": "lew_dwg_archive",
        "dwg_path": f"sld-dwg-old/{filename}",
        "dxf_path": dxf_rel if dxf_exists else "",
        "circuit_count": len(sub_circuits),
    }


def main() -> int:
    if not EXTRACTED.exists():
        logger.error("Extracted file not found: %s", EXTRACTED)
        return 1

    with EXTRACTED.open(encoding="utf-8") as f:
        raw = json.load(f)

    files = raw.get("files", {})
    if isinstance(files, dict):
        files = list(files.values())

    converted = []
    for src in files:
        entry = _convert_entry(src)
        if entry:
            converted.append(entry)

    OUT.write_text(json.dumps(converted, ensure_ascii=False, indent=2), encoding="utf-8")
    logger.info("Converted %d/%d entries → %s", len(converted), len(files), OUT)
    logger.info("Run `python -m scripts.import_sld_templates` after merging this file.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
