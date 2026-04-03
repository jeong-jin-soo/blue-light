#!/usr/bin/env python3
"""DXF 블록 라이브러리 JSON → sld_block_library.dxf 통합.

dxf_block_library.json의 모든 블록(extracted + custom)을 하나의
DXF 파일에 블록 정의로 기록한다. 기존 레퍼런스 DXF에서 추출된 블록은
원본 DXF에서 직접 복사하고, 커스텀 블록은 JSON 엔티티에서 생성한다.

Usage:
    python scripts/rebuild_block_library_dxf.py
    # → data/templates/symbols/sld_block_library.dxf 교체
"""

import json
import math
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import ezdxf

LIB_JSON = Path(__file__).resolve().parent.parent / "data" / "templates" / "dxf_block_library.json"
OUTPUT_DXF = Path(__file__).resolve().parent.parent / "data" / "templates" / "symbols" / "sld_block_library.dxf"

# Reference DXFs to import extracted blocks from
REF_DXFS = [
    Path(__file__).resolve().parent.parent / "data" / "sld-info" / "slds-dxf" / "150A TPN SLD 1 DWG.dxf",
    Path(__file__).resolve().parent.parent / "data" / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf",
    Path(__file__).resolve().parent.parent / "data" / "sld-info" / "slds-dxf" / "63A TPN SLD 14.dxf",
]


def _add_custom_block_from_json(doc: ezdxf.document.Drawing, name: str, block_def: dict) -> None:
    """Create a DXF block from JSON entity definitions."""
    if name in [b.name for b in doc.blocks]:
        return  # already exists

    block = doc.blocks.new(name=name)
    for ent in block_def.get("entities", []):
        etype = ent.get("type", "")
        if etype == "LINE":
            block.add_line(tuple(ent["start"]), tuple(ent["end"]))
        elif etype == "CIRCLE":
            block.add_circle(tuple(ent["center"]), ent["radius"])
        elif etype == "ARC":
            block.add_arc(
                center=tuple(ent["center"]),
                radius=ent["radius"],
                start_angle=ent["start_angle"],
                end_angle=ent["end_angle"],
            )
        elif etype == "LWPOLYLINE":
            pts = ent.get("points", [])
            bulges = ent.get("bulges", [])
            closed = ent.get("closed", False)
            if pts:
                lwp = block.add_lwpolyline(pts, close=closed)
                for i, b in enumerate(bulges):
                    if b != 0 and i < len(pts):
                        lwp[i] = (*pts[i], 0, 0, b)
        elif etype == "TEXT":
            block.add_text(
                ent.get("text", ""),
                dxfattribs={
                    "insert": tuple(ent.get("insert", [0, 0])),
                    "height": ent.get("height", 100),
                },
            )


def main():
    with open(LIB_JSON) as f:
        lib = json.load(f)

    # Create new DXF document
    doc = ezdxf.new("R2010")

    # Step 1: Import extracted blocks from reference DXFs
    extracted_blocks = set(lib.get("blocks", {}).keys())
    imported = set()

    for ref_path in REF_DXFS:
        if not ref_path.exists():
            print(f"  WARN: {ref_path.name} not found, skipping")
            continue

        ref_doc = ezdxf.readfile(str(ref_path))
        for block in ref_doc.blocks:
            name = block.name
            if name.startswith("*") or name.startswith("A$C") or name.startswith("_"):
                continue
            if name in extracted_blocks and name not in imported:
                new_block = doc.blocks.new(name=name)
                for entity in block:
                    new_block.add_entity(entity.copy())
                imported.add(name)
                print(f"  IMPORTED {name} from {ref_path.name}")

    # Step 2: Add custom blocks from JSON definitions
    for name, block_def in lib.get("custom_blocks", {}).items():
        if name in imported:
            print(f"  SKIP {name} (already imported from DXF)")
            continue
        _add_custom_block_from_json(doc, name, block_def)
        print(f"  CREATED {name} from JSON definition")

    # Save
    doc.saveas(str(OUTPUT_DXF))
    total = len(imported) + len(lib.get("custom_blocks", {}))
    print(f"\nSaved {OUTPUT_DXF.name}: {len(imported)} imported + {len(lib.get('custom_blocks', {}))} custom = {total} blocks")


if __name__ == "__main__":
    main()
