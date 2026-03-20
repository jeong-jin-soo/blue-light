#!/usr/bin/env python3
"""Build consolidated SLD block library DXF from reference templates.

Extracts the cleanest MCCB, RCCB, DP ISOL, 3P ISOL, and SLD-CT block
definitions from LEW reference DXF templates and saves them into a single
library file at data/templates/symbols/sld_block_library.dxf.

Source files (data/sld-info/slds-dxf/):
  - "63A TPN SLD 14.dxf"     -> MCCB, RCCB, DP ISOL, 3P ISOL
  - "150A TPN SLD 1 DWG.dxf" -> SLD-CT

Usage:
    cd blue-light-ai
    python -m scripts.build_block_library
"""

from __future__ import annotations

import logging
import sys
from pathlib import Path

import ezdxf
from ezdxf import units

logger = logging.getLogger(__name__)

# Project root
_PROJECT_ROOT = Path(__file__).resolve().parent.parent
_SRC_DIR = _PROJECT_ROOT / "data" / "sld-info" / "slds-dxf"
_OUT_PATH = _PROJECT_ROOT / "data" / "templates" / "symbols" / "sld_block_library.dxf"

# Block sources: (source_file, [block_names])
# Chosen for cleanest geometry (fewest extraneous entities per block).
_BLOCK_SOURCES: list[tuple[str, list[str]]] = [
    ("63A TPN SLD 14.dxf", ["MCCB", "RCCB", "DP ISOL", "3P ISOL"]),
    ("150A TPN SLD 1 DWG.dxf", ["SLD-CT"]),
]

# Expected entity counts per block — used for validation
_EXPECTED_ENTITIES: dict[str, dict[str, int]] = {
    "MCCB": {"CIRCLE": 2, "LWPOLYLINE": 1},
    "RCCB": {"CIRCLE": 2, "LINE": 2, "LWPOLYLINE": 1},
    "DP ISOL": {"LINE": 2, "LWPOLYLINE": 1},
    "3P ISOL": {"LINE": 3, "LWPOLYLINE": 1},
    "SLD-CT": {"LINE": 1, "LWPOLYLINE": 1},
}


def build_block_library() -> Path:
    """Extract target blocks from source DXFs into a consolidated library DXF.

    Returns:
        Path to the output sld_block_library.dxf file.

    Raises:
        FileNotFoundError: If a required source DXF is missing.
        ValueError: If a required block is not found in the source DXF.
    """
    # Create output DXF document
    out_doc = ezdxf.new("R2013")
    out_doc.units = units.MM

    imported_blocks: list[str] = []

    for src_filename, block_names in _BLOCK_SOURCES:
        src_path = _SRC_DIR / src_filename
        if not src_path.exists():
            raise FileNotFoundError(f"Source DXF not found: {src_path}")

        src_doc = ezdxf.readfile(str(src_path))
        logger.info("Reading blocks from: %s", src_filename)

        for block_name in block_names:
            if block_name not in src_doc.blocks:
                raise ValueError(
                    f"Block '{block_name}' not found in {src_filename}"
                )

            src_block = src_doc.blocks[block_name]
            src_entities = list(src_block)
            if not src_entities:
                raise ValueError(
                    f"Block '{block_name}' in {src_filename} is empty"
                )

            # Validate entity composition
            entity_counts: dict[str, int] = {}
            for entity in src_entities:
                etype = entity.dxftype()
                entity_counts[etype] = entity_counts.get(etype, 0) + 1

            expected = _EXPECTED_ENTITIES.get(block_name, {})
            if expected and entity_counts != expected:
                logger.warning(
                    "Block '%s' entity mismatch: expected %s, got %s",
                    block_name, expected, entity_counts,
                )

            # Skip if already imported (shouldn't happen with distinct sources)
            if block_name in out_doc.blocks:
                logger.warning("Block '%s' already exists — skipping", block_name)
                continue

            # Create block in output document and copy entities
            new_block = out_doc.blocks.new(name=block_name)
            for entity in src_entities:
                new_block.add_entity(entity.copy())

            imported_blocks.append(block_name)
            logger.info(
                "  Imported '%s': %s",
                block_name,
                ", ".join(f"{v}x {k}" for k, v in entity_counts.items()),
            )

    # Ensure output directory exists
    _OUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    # Save the consolidated library
    out_doc.saveas(str(_OUT_PATH))
    logger.info(
        "Block library saved: %s (%d blocks: %s)",
        _OUT_PATH, len(imported_blocks), ", ".join(imported_blocks),
    )

    return _OUT_PATH


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    try:
        out_path = build_block_library()
        print(f"\nBlock library created: {out_path}")
        print(f"Blocks: {', '.join(b for _, blocks in _BLOCK_SOURCES for b in blocks)}")
    except Exception as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
