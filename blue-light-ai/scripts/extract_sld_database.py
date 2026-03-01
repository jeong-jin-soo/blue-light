#!/usr/bin/env python3
"""
SLD PDF 일괄 추출 스크립트.

data/sld-info/slds/ 하위 모든 SLD PDF를 Gemini Vision으로 분석하여
정형화된 JSON 데이터베이스를 생성한다.

Usage:
    cd blue-light-ai
    venv/bin/python scripts/extract_sld_database.py
"""

from __future__ import annotations

import base64
import json
import os
import re
import sys
import time
from pathlib import Path

import fitz  # PyMuPDF
import json_repair
from google import genai
from google.genai import types

# ─── Config ───────────────────────────────────────────────────────────
SLD_DIR = Path(__file__).resolve().parent.parent / "data" / "sld-info" / "slds"
OUTPUT_JSON = SLD_DIR.parent / "sld_database.json"
OUTPUT_CSV = SLD_DIR.parent / "sld_database.csv"

GEMINI_MODEL = os.getenv("GEMINI_MODEL", "gemini-3-flash-preview")
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY", "")

# Rate limit: Gemini free tier ~15 RPM
REQUEST_DELAY = 5  # seconds between requests

# ─── Extraction Prompt ────────────────────────────────────────────────
EXTRACTION_PROMPT = """You are an expert electrical engineer specializing in Singapore Single Line Diagrams (SLD).

Analyze this SLD PDF image and extract ALL electrical information into structured JSON.

## What to Extract

### 1. Supply Type & Capacity
- Phase: "single_phase" (230V, L-N) or "three_phase" (400V, L1-L2-L3-N)
- kVA: Approved load capacity (often in title block or near incoming supply text)
- Voltage: 230 or 400

### 2. Main Breaker (Incoming)
- Type: MCB / MCCB / ACB
- Rating in Amps (e.g., 32A, 63A, 100A, 150A, 200A, 300A, 400A, 500A)
- Poles: SPN / DP (single-phase) or TPN / 4P (three-phase)
- Fault rating in kA (e.g., 10kA, 25kA, 35kA, 50kA)
- Trip characteristic: B / C / D (if shown)

### 3. Incoming Cable
- Full cable description as written on the drawing
- Conductor size in mm² (phase conductors)
- Earth conductor size in mm²
- Cable type: PVC / XLPE / PVC/PVC / XLPE/PVC

### 4. ELCB / RCCB (if present)
- Type: ELCB or RCCB
- Rating in Amps
- Poles: 2 (single-phase) or 4 (three-phase)
- Sensitivity in mA (30 / 100 / 300)

### 5. Busbar
- Rating in Amps (e.g., 100A, 200A, 400A)
- Type: COMB (comb busbar) or COPPER (tinned copper)

### 6. Metering Section
- Type: "ct_meter" (with CT) or "sp_meter" (direct/SP meter)
- CT ratio if applicable (e.g., "200/5A")
- Isolator rating
- Whether indicator lights (L1/L2/L3) are present
- Whether ELR (Earth Leakage Relay) is present
- Whether Shunt Trip is present

### 7. Earth Protection
- Whether earth bar / earth conductor is shown

### 8. Outgoing Sub-Circuits (ALL of them)
For EACH sub-circuit branch from the busbar:
- Circuit ID (e.g., L1S1, L2P1, S1, P1)
- Description / load name (e.g., "LED PANEL LIGHT", "13A SOCKET OUTLET")
- Breaker: type, rating, poles, kA, characteristic
- Cable specification (full text as shown)
- Quantity of load points (if shown)
- Load type: lighting / power / aircon / spare / motor / other

### 9. Special Features
- Is there a Sub DB (sub distribution board)?
- Is there an ATS (Automatic Transfer Switch)?
- Is there a Generator?
- Is this a cable extension (no DB, just cable sizing)?
- Number of rows of sub-circuits (1 row or 2 rows)

### 10. Client Info (from title block)
- Client name, address
- LEW name, licence number
- Contractor names

## Output JSON Format
```json
{
  "supply_type": "single_phase|three_phase",
  "kva": <float or null>,
  "voltage": <230|400>,
  "main_breaker": {
    "type": "MCB|MCCB|ACB",
    "rating_a": <int>,
    "poles": "SPN|DP|TPN|4P",
    "ka_rating": <int or null>,
    "characteristic": "B|C|D|null"
  },
  "incoming_cable": {
    "description": "<full text>",
    "size_mm2": "<phase conductor size>",
    "earth_mm2": "<earth conductor size or null>",
    "type": "PVC|XLPE|PVC/PVC|XLPE/PVC"
  },
  "elcb": {
    "type": "ELCB|RCCB|null",
    "rating_a": <int or null>,
    "poles": <2|4|null>,
    "sensitivity_ma": <30|100|300|null>
  },
  "busbar": {
    "rating_a": <int or null>,
    "type": "COMB|COPPER|null"
  },
  "metering": {
    "type": "ct_meter|sp_meter|null",
    "ct_ratio": "<string or null>",
    "isolator_rating_a": <int or null>,
    "has_indicator_lights": <bool>,
    "has_elr": <bool>,
    "has_shunt_trip": <bool>
  },
  "earth_protection": <bool>,
  "sub_circuits": [
    {
      "id": "<circuit ID>",
      "description": "<load name>",
      "breaker_type": "MCB|MCCB",
      "breaker_rating_a": <int>,
      "breaker_poles": "SPN|DP|TPN",
      "breaker_ka": <int or null>,
      "breaker_characteristic": "B|C|D|null",
      "cable": "<full cable spec>",
      "qty": <int or null>,
      "load_type": "lighting|power|aircon|spare|motor|other"
    }
  ],
  "special_features": {
    "has_sub_db": <bool>,
    "has_ats": <bool>,
    "has_generator": <bool>,
    "is_cable_extension": <bool>,
    "sub_circuit_rows": <1|2>
  },
  "client_info": {
    "name": "<string or null>",
    "address": "<string or null>",
    "lew_name": "<string or null>",
    "lew_licence": "<string or null>",
    "contractor": "<string or null>",
    "main_contractor": "<string or null>"
  },
  "notes": "<any additional observations>"
}
```

## Rules
1. Extract ONLY what is explicitly visible on the drawing. Use null for uncertain fields.
2. Normalize all text: uppercase for types (MCB, MCCB), standard poles (SPN, DP, TPN, 4P).
3. For cable extensions without a DB, set sub_circuits to empty array.
4. Count ALL sub-circuits including spares.
5. If kVA is not shown but breaker rating and voltage are known, calculate:
   - Single-phase: kVA = rating × 230 / 1000
   - Three-phase: kVA = rating × 400 × 1.732 / 1000
6. Output ONLY valid JSON. No markdown fences, no explanation."""


def pdf_to_images(pdf_path: Path, dpi: int = 200) -> list[bytes]:
    """Convert PDF pages to PNG images."""
    doc = fitz.open(str(pdf_path))
    images = []
    for page in doc:
        mat = fitz.Matrix(dpi / 72, dpi / 72)
        pix = page.get_pixmap(matrix=mat)
        images.append(pix.tobytes("png"))
    doc.close()
    return images


def extract_single_pdf(client: genai.Client, pdf_path: Path) -> dict:
    """Extract SLD data from a single PDF using Gemini Vision."""
    images = pdf_to_images(pdf_path)

    # Build multimodal content: images + prompt
    parts = []
    for img_bytes in images:
        b64 = base64.b64encode(img_bytes).decode("utf-8")
        parts.append(types.Part.from_bytes(data=img_bytes, mime_type="image/png"))

    parts.append(types.Part.from_text(text=EXTRACTION_PROMPT))

    response = client.models.generate_content(
        model=GEMINI_MODEL,
        contents=[types.Content(parts=parts, role="user")],
        config=types.GenerateContentConfig(
            temperature=0.1,
            max_output_tokens=8192,
        ),
    )

    # Parse JSON from response (robust: json_repair handles Gemini quirks)
    text = response.text.strip()
    # Remove markdown code fences if present
    text = re.sub(r"^```(?:json)?\s*\n?", "", text)
    text = re.sub(r"\n?```\s*$", "", text)
    text = text.strip()

    # json_repair handles trailing commas, unquoted keys, etc.
    data = json_repair.loads(text)
    if not isinstance(data, dict):
        raise ValueError(f"Expected dict, got {type(data).__name__}: {str(data)[:200]}")

    return data


def build_csv_row(filename: str, data: dict) -> dict:
    """Flatten extracted data into a CSV-friendly row."""
    mb = data.get("main_breaker") or {}
    cable = data.get("incoming_cable") or {}
    elcb = data.get("elcb") or {}
    busbar = data.get("busbar") or {}
    metering = data.get("metering") or {}
    special = data.get("special_features") or {}
    client = data.get("client_info") or {}
    circuits = data.get("sub_circuits") or []

    # Count MCBs by rating
    mcb_counts = {}
    for sc in circuits:
        rating = sc.get("breaker_rating_a")
        if rating:
            mcb_counts[f"{rating}A"] = mcb_counts.get(f"{rating}A", 0) + 1

    return {
        "filename": filename,
        "supply_type": data.get("supply_type"),
        "kva": data.get("kva"),
        "voltage": data.get("voltage"),
        "main_breaker_type": mb.get("type"),
        "main_breaker_rating_a": mb.get("rating_a"),
        "main_breaker_poles": mb.get("poles"),
        "main_breaker_ka": mb.get("ka_rating"),
        "main_breaker_char": mb.get("characteristic"),
        "incoming_cable": cable.get("description"),
        "cable_size_mm2": cable.get("size_mm2"),
        "cable_type": cable.get("type"),
        "elcb_type": elcb.get("type"),
        "elcb_rating_a": elcb.get("rating_a"),
        "elcb_sensitivity_ma": elcb.get("sensitivity_ma"),
        "busbar_rating_a": busbar.get("rating_a"),
        "busbar_type": busbar.get("type"),
        "metering_type": metering.get("type"),
        "ct_ratio": metering.get("ct_ratio"),
        "total_sub_circuits": len(circuits),
        "mcb_breakdown": json.dumps(mcb_counts) if mcb_counts else None,
        "has_sub_db": special.get("has_sub_db"),
        "has_ats": special.get("has_ats"),
        "is_cable_extension": special.get("is_cable_extension"),
        "sub_circuit_rows": special.get("sub_circuit_rows"),
        "client_name": client.get("name"),
        "address": client.get("address"),
    }


def main():
    if not GEMINI_API_KEY:
        # Try loading from .env
        env_path = Path(__file__).resolve().parent.parent / ".env"
        if env_path.exists():
            for line in env_path.read_text().splitlines():
                if line.startswith("GEMINI_API_KEY="):
                    os.environ["GEMINI_API_KEY"] = line.split("=", 1)[1].strip()
                    break

    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("ERROR: GEMINI_API_KEY not set")
        sys.exit(1)

    client = genai.Client(api_key=api_key)

    # Collect all PDFs
    pdfs = sorted(SLD_DIR.glob("*.pdf"))
    print(f"Found {len(pdfs)} SLD PDFs in {SLD_DIR}")

    # Load existing progress (resume support)
    database: dict[str, dict] = {}
    if OUTPUT_JSON.exists():
        existing = json.loads(OUTPUT_JSON.read_text())
        if isinstance(existing, list):
            for entry in existing:
                if "filename" in entry:
                    database[entry["filename"]] = entry
        print(f"  Loaded {len(database)} existing entries (resume mode)")

    total = len(pdfs)
    errors = []

    for i, pdf_path in enumerate(pdfs, 1):
        filename = pdf_path.name

        # Skip already extracted
        if filename in database:
            print(f"[{i}/{total}] SKIP (already done): {filename}")
            continue

        print(f"[{i}/{total}] Extracting: {filename} ... ", end="", flush=True)
        try:
            data = extract_single_pdf(client, pdf_path)
            data["filename"] = filename
            data["file_path"] = f"slds/{filename}"
            database[filename] = data
            print("OK")

            # Save progress after each successful extraction
            db_list = list(database.values())
            OUTPUT_JSON.write_text(
                json.dumps(db_list, indent=2, ensure_ascii=False), encoding="utf-8"
            )

        except Exception as e:
            error_msg = f"{type(e).__name__}: {e}"
            print(f"ERROR - {error_msg}")
            errors.append({"filename": filename, "error": error_msg})

        # Rate limiting
        if i < total:
            time.sleep(REQUEST_DELAY)

    # Final save
    db_list = list(database.values())
    OUTPUT_JSON.write_text(
        json.dumps(db_list, indent=2, ensure_ascii=False), encoding="utf-8"
    )
    print(f"\nJSON database saved: {OUTPUT_JSON} ({len(db_list)} entries)")

    # Generate CSV
    if db_list:
        import csv

        csv_rows = [build_csv_row(d["filename"], d) for d in db_list]
        fieldnames = csv_rows[0].keys()
        with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
            writer = csv.DictWriter(f, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(csv_rows)
        print(f"CSV database saved: {OUTPUT_CSV}")

    # Error report
    if errors:
        print(f"\n⚠ {len(errors)} errors:")
        for err in errors:
            print(f"  - {err['filename']}: {err['error']}")

    # Summary
    print(f"\n{'='*60}")
    print(f"Total PDFs: {total}")
    print(f"Extracted:  {len(db_list)}")
    print(f"Errors:     {len(errors)}")
    print(f"{'='*60}")


if __name__ == "__main__":
    main()
