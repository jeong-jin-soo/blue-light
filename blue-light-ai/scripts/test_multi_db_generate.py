#!/usr/bin/env python3
"""Generate SLD from previously extracted JSON (skip Gemini call)."""
import json
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(PROJECT_ROOT))

OUTPUT_DIR = Path("/Users/ringo/Downloads/sld_test_output")
RAW_JSON_PATH = OUTPUT_DIR / "01_extracted_raw.json"


def main():
    # --- Load extracted data ---
    print("[1/4] Loading extracted data...")
    with open(RAW_JSON_PATH) as f:
        extracted_data = json.load(f)

    dbs = extracted_data.get("distribution_boards") or []
    print(f"       Distribution boards: {len(dbs)}")
    for db in dbs:
        name = db.get("name", "?")
        ocs = db.get("outgoing_circuits") or []
        pgs = db.get("protection_groups") or []
        total = len(ocs) + sum(len(pg.get("circuits") or []) for pg in pgs)
        print(f"         - {name}: {total} circuits, {len(pgs)} protection groups")

    # --- Normalize ---
    print("[2/4] Normalizing to generation format...")
    from app.sld.extraction_schema import SldExtractedData, normalize_to_generation_format
    parsed = SldExtractedData.model_validate(extracted_data)
    requirements = normalize_to_generation_format(parsed)

    req_json_path = OUTPUT_DIR / "02_requirements.json"
    with open(req_json_path, "w") as f:
        json.dump(requirements, f, indent=2, ensure_ascii=False, default=str)

    print(f"       Supply type: {requirements.get('supply_type')}")
    print(f"       kVA: {requirements.get('kva')}")
    print(f"       Main breaker: {requirements.get('main_breaker', {}).get('type')} {requirements.get('main_breaker', {}).get('rating')}A")
    print(f"       Sub-circuits (direct): {len(requirements.get('sub_circuits', []))}")
    multi_dbs = requirements.get("distribution_boards") or []
    if multi_dbs:
        print(f"       Distribution boards: {len(multi_dbs)}")
        for db in multi_dbs:
            name = db.get("name", "?")
            subs = db.get("sub_circuits") or []
            pgs = db.get("protection_groups") or []
            total = len(subs) + sum(len(pg.get("circuits") or []) for pg in pgs)
            print(f"         - {name}: {total} circuits, {len(pgs)} protection groups")

    # --- Generate ---
    print("[3/4] Generating SLD (PDF + SVG + DXF)...")
    client_info = extracted_data.get("client_info") or {}
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
        sys.exit(1)

    # --- Save ---
    print("[4/4] Saving output files...")
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
    print("DONE! Open the PDF to verify the SLD layout:")
    print(f"  open \"{pdf_path}\"")
    print("=" * 60)


if __name__ == "__main__":
    main()
