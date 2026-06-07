#!/usr/bin/env python3
"""Test script: Generate SLD from I2R-ETR-NLB Excel file.

This verifies the full pipeline: Excel → Gemini extraction → normalize → layout → PDF/SVG
"""
import asyncio
import json
import os
import sys
from pathlib import Path

# Ensure project root is on sys.path
PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

os.environ.setdefault("GEMINI_API_KEY", "AIzaSyDrV_FG2eMNkpvRNwW3JTDWQnJHQ75eQBw")

EXCEL_PATH = Path("/Users/ringo/Downloads/I2R-ETR-NLB-SLD_Formatted.xlsx")
OUTPUT_DIR = Path("/Users/ringo/Downloads/sld_test_output")


async def main():
    OUTPUT_DIR.mkdir(exist_ok=True)

    # --- Step 1: Read Excel ---
    print(f"[1/5] Reading Excel: {EXCEL_PATH}")
    file_bytes = EXCEL_PATH.read_bytes()
    print(f"       File size: {len(file_bytes):,} bytes")

    # --- Step 2: Extract via Gemini ---
    print("[2/5] Extracting schedule via Gemini AI...")
    from app.sld.schedule_parser import extract_schedule_from_file
    result = await extract_schedule_from_file(file_bytes, EXCEL_PATH.name)

    if not result.get("success"):
        print(f"  ❌ Extraction failed: {result.get('error', result.get('warnings'))}")
        sys.exit(1)

    extracted_data = result["extracted_data"]
    print(f"       ✅ Extraction success")
    print(f"       File type: {result['file_type']}")
    print(f"       Warnings: {result.get('warnings', [])}")

    # Save raw extracted JSON for debugging
    raw_json_path = OUTPUT_DIR / "01_extracted_raw.json"
    with open(raw_json_path, "w") as f:
        json.dump(extracted_data, f, indent=2, ensure_ascii=False)
    print(f"       Raw JSON saved: {raw_json_path}")

    # Check for distribution_boards vs outgoing_circuits
    has_multi_db = bool(extracted_data.get("distribution_boards"))
    has_single = bool(extracted_data.get("outgoing_circuits"))
    print(f"       Multi-DB (distribution_boards): {has_multi_db}")
    print(f"       Single-DB (outgoing_circuits): {has_single}")
    if has_multi_db:
        dbs = extracted_data["distribution_boards"]
        print(f"       Distribution boards: {len(dbs)}")
        for db in dbs:
            name = db.get("name", "?")
            ocs = db.get("outgoing_circuits") or []
            pgs = db.get("protection_groups") or []
            total = len(ocs) + sum(len(pg.get("circuits") or []) for pg in pgs)
            print(f"         - {name}: {total} circuits, {len(pgs)} protection groups")

    # --- Step 3: Normalize via Pydantic ---
    print("[3/5] Normalizing extracted data to generation format...")
    from app.sld.extraction_schema import SldExtractedData, normalize_to_generation_format
    parsed = SldExtractedData.model_validate(extracted_data)
    requirements = normalize_to_generation_format(parsed)

    req_json_path = OUTPUT_DIR / "02_requirements.json"
    with open(req_json_path, "w") as f:
        json.dump(requirements, f, indent=2, ensure_ascii=False, default=str)
    print(f"       ✅ Requirements generated")
    print(f"       Supply type: {requirements.get('supply_type')}")
    print(f"       kVA: {requirements.get('kva')}")
    print(f"       Sub-circuits: {len(requirements.get('sub_circuits', []))}")
    multi_dbs = requirements.get("distribution_boards", [])
    if multi_dbs:
        print(f"       Distribution boards: {len(multi_dbs)}")
        for db in multi_dbs:
            name = db.get("name", "?")
            subs = db.get("sub_circuits", [])
            pgs = db.get("protection_groups", [])
            total = len(subs) + sum(len(pg.get("circuits", [])) for pg in pgs)
            print(f"         - {name}: {total} circuits, {len(pgs)} protection groups")
    print(f"       Requirements saved: {req_json_path}")

    # --- Step 4: Generate SLD ---
    print("[4/5] Generating SLD (PDF + SVG)...")
    application_info = {}
    client_info = extracted_data.get("client_info", {})
    if client_info:
        application_info = {
            "address": client_info.get("address", ""),
            "unit_number": client_info.get("unit_number", ""),
            "drawing_number": client_info.get("drawing_no", ""),
            "owner_name": client_info.get("name", ""),
        }

    from app.sld.generator import SldPipeline
    try:
        _r = SldPipeline().run(requirements, application_info=application_info)
        pdf_bytes, svg_string, dxf_bytes = _r.pdf_bytes, _r.svg_string, _r.dxf_bytes
    except Exception as e:
        print(f"  Generation failed: {e}")
        import traceback
        traceback.print_exc()

        # Save the requirements for debugging
        debug_path = OUTPUT_DIR / "03_debug_requirements.json"
        with open(debug_path, "w") as f:
            json.dump(requirements, f, indent=2, ensure_ascii=False, default=str)
        print(f"       Debug requirements saved: {debug_path}")
        sys.exit(1)

    # --- Step 5: Save outputs ---
    print("[5/5] Saving output files...")

    pdf_path = OUTPUT_DIR / "I2R-ETR-NLB_SLD.pdf"
    pdf_path.write_bytes(pdf_bytes)
    print(f"       ✅ PDF: {pdf_path} ({len(pdf_bytes):,} bytes)")

    svg_path = OUTPUT_DIR / "I2R-ETR-NLB_SLD.svg"
    svg_path.write_text(svg_string, encoding="utf-8")
    print(f"       ✅ SVG: {svg_path} ({len(svg_string):,} chars)")

    if dxf_bytes:
        dxf_path = OUTPUT_DIR / "I2R-ETR-NLB_SLD.dxf"
        dxf_path.write_bytes(dxf_bytes)
        print(f"       ✅ DXF: {dxf_path} ({len(dxf_bytes):,} bytes)")

    print()
    print("=" * 60)
    print("DONE! Open the PDF/SVG to verify the SLD layout.")
    print(f"  Output dir: {OUTPUT_DIR}")
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
