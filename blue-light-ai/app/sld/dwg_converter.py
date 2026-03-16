"""DWG-to-DXF conversion and analysis pipeline.

Provides utilities for converting DWG files to DXF, extracting text from
binary DWG files, and analysing DXF files using ezdxf.

CLI usage::

    python -m app.sld.dwg_converter /path/to/files --output analysis.json
"""

from __future__ import annotations

import json
import logging
import re
import struct
import subprocess
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Configurable paths / constants
# ---------------------------------------------------------------------------

LIBREDWG_PATH = Path("/tmp/libredwg/build/dwg2dxf")
CONVERSION_TIMEOUT = 15  # seconds per file

# Patterns for electrical specification extraction
_RATING_RE = re.compile(
    r"\b(\d+)\s*(A|kA|mA|V|kV|W|kW|MW|VA|kVA|MVA|HP)\b", re.IGNORECASE
)
_CABLE_RE = re.compile(
    r"\b(\d+(?:\.\d+)?)\s*mm[²2]?\b"
    r"|"
    r"\b(\d+/\d+)\s*mm[²2]?\b"
    r"|"
    r"\b(\d+)[Cc]\s*x\s*(\d+(?:\.\d+)?)\s*mm[²2]?\b",
    re.IGNORECASE,
)
_BREAKER_RE = re.compile(
    r"\b(MCB|MCCB|ELCB|RCCB|RCBO|ACB|VCB|ISOLATOR|CONTACTOR)\b", re.IGNORECASE
)
_PHASE_RE = re.compile(
    r"\b(single\s*phase|three\s*phase|3[- ]?phase|1[- ]?phase|TPN|SPN)\b",
    re.IGNORECASE,
)
_SUPPLY_RE = re.compile(
    r"\b(SP\s*GROUP|TNB|MAIN\s*SWITCH|INCOMING|SUPPLY|EMA)\b", re.IGNORECASE
)


# =========================================================================
# 1. DWG → DXF conversion
# =========================================================================

def convert_dwg_to_dxf(
    dwg_path: str | Path,
    output_dir: Optional[str | Path] = None,
) -> Optional[Path]:
    """Convert a DWG file to DXF.

    Strategy:
      1. Try LibreDWG ``dwg2dxf`` (fast, open-source).
      2. Try ODA File Converter if available.

    Returns the path to the converted DXF file, or ``None`` on failure.
    """
    dwg_path = Path(dwg_path)
    if not dwg_path.is_file():
        logger.error("DWG file not found: %s", dwg_path)
        return None

    out_dir = Path(output_dir) if output_dir else dwg_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    dxf_path = out_dir / dwg_path.with_suffix(".dxf").name

    # --- attempt 1: LibreDWG ---
    result = _try_libredwg(dwg_path, dxf_path)
    if result:
        return result

    # --- attempt 2: ODA File Converter ---
    result = _try_oda(dwg_path, out_dir)
    if result:
        return result

    logger.warning("All conversion methods failed for %s", dwg_path.name)
    return None


def _try_libredwg(dwg_path: Path, dxf_path: Path) -> Optional[Path]:
    if not LIBREDWG_PATH.is_file():
        logger.debug("LibreDWG not found at %s", LIBREDWG_PATH)
        return None
    try:
        cmd = [str(LIBREDWG_PATH), "-y", "-o", str(dxf_path), str(dwg_path)]
        subprocess.run(
            cmd,
            timeout=CONVERSION_TIMEOUT,
            capture_output=True,
            check=True,
        )
        if dxf_path.is_file() and dxf_path.stat().st_size > 0:
            logger.info("LibreDWG converted %s", dwg_path.name)
            return dxf_path
    except subprocess.TimeoutExpired:
        logger.warning("LibreDWG timed out on %s", dwg_path.name)
    except subprocess.CalledProcessError as exc:
        logger.debug("LibreDWG failed on %s: %s", dwg_path.name, exc.stderr[:200] if exc.stderr else "")
    return None


def _try_oda(dwg_path: Path, out_dir: Path) -> Optional[Path]:
    """Attempt conversion using ODA File Converter (if installed)."""
    import shutil

    oda_bin = shutil.which("ODAFileConverter")
    if not oda_bin:
        logger.debug("ODA File Converter not found on PATH")
        return None
    try:
        # ODA expects: <input_dir> <output_dir> <version> <type> <recurse> <audit>
        cmd = [
            oda_bin,
            str(dwg_path.parent),
            str(out_dir),
            "ACAD2018",  # target version
            "DXF",
            "0",  # no recursion
            "1",  # audit
            str(dwg_path.name),
        ]
        subprocess.run(cmd, timeout=CONVERSION_TIMEOUT, capture_output=True, check=True)
        candidate = out_dir / dwg_path.with_suffix(".dxf").name
        if candidate.is_file() and candidate.stat().st_size > 0:
            logger.info("ODA converted %s", dwg_path.name)
            return candidate
    except (subprocess.TimeoutExpired, subprocess.CalledProcessError, FileNotFoundError) as exc:
        logger.debug("ODA failed on %s: %s", dwg_path.name, exc)
    return None


# =========================================================================
# 2. Binary text extraction from DWG
# =========================================================================

def extract_dwg_text(dwg_path: str | Path) -> dict[str, Any]:
    """Extract text strings from a DWG file via binary scanning.

    Returns a dict with keys:
      - ascii_strings: list[str]
      - utf16_strings: list[str]
      - electrical_specs: dict  (ratings, cable_sizes, breaker_types, phase, supply_source)
    """
    dwg_path = Path(dwg_path)
    if not dwg_path.is_file():
        return {"ascii_strings": [], "utf16_strings": [], "electrical_specs": {}}

    data = dwg_path.read_bytes()

    ascii_strings = _extract_ascii(data)
    utf16_strings = _extract_utf16(data)

    all_text = " ".join(ascii_strings + utf16_strings)
    specs = _extract_electrical_specs(all_text)

    return {
        "ascii_strings": ascii_strings,
        "utf16_strings": utf16_strings,
        "electrical_specs": specs,
    }


def _extract_ascii(data: bytes, min_len: int = 4) -> list[str]:
    """Extract printable ASCII strings of at least *min_len* characters."""
    result: list[str] = []
    current: list[str] = []
    for b in data:
        if 0x20 <= b < 0x7F:
            current.append(chr(b))
        else:
            if len(current) >= min_len:
                result.append("".join(current))
            current = []
    if len(current) >= min_len:
        result.append("".join(current))
    return result


def _extract_utf16(data: bytes, min_len: int = 3) -> list[str]:
    """Extract UTF-16-LE strings (common in DWG for non-ASCII text)."""
    results: list[str] = []
    i = 0
    while i < len(data) - 1:
        chars: list[str] = []
        while i < len(data) - 1:
            code = struct.unpack_from("<H", data, i)[0]
            if 0x20 <= code < 0xFFFE and code != 0xFFFD:
                chars.append(chr(code))
                i += 2
            else:
                break
        if len(chars) >= min_len:
            text = "".join(chars).strip()
            if text and not text.isspace():
                results.append(text)
        i += 2
    # deduplicate while preserving order
    seen: set[str] = set()
    deduped: list[str] = []
    for s in results:
        if s not in seen:
            seen.add(s)
            deduped.append(s)
    return deduped


def _extract_electrical_specs(text: str) -> dict[str, Any]:
    """Parse electrical specifications from combined text."""
    ratings = sorted(set(_RATING_RE.findall(text)), key=lambda t: (t[1].upper(), int(t[0])))
    ratings_str = [f"{v}{u}" for v, u in ratings]

    cable_raw = _CABLE_RE.findall(text)
    cable_sizes: list[str] = []
    for match in cable_raw:
        if match[2] and match[3]:  # NcxMmm² format
            cable_sizes.append(f"{match[2]}Cx{match[3]}mm²")
        elif match[0]:
            cable_sizes.append(f"{match[0]}mm²")
        elif match[1]:
            cable_sizes.append(f"{match[1]}mm²")
    cable_sizes = sorted(set(cable_sizes))

    breaker_types = sorted({m.upper() for m in _BREAKER_RE.findall(text)})
    phase_matches = sorted({m.strip().upper() for m in _PHASE_RE.findall(text)})
    supply_matches = sorted({m.strip().upper() for m in _SUPPLY_RE.findall(text)})

    return {
        "ratings": ratings_str,
        "cable_sizes": cable_sizes,
        "breaker_types": breaker_types,
        "phase": phase_matches,
        "supply_source": supply_matches,
    }


# =========================================================================
# 3. DXF analysis via ezdxf
# =========================================================================

def analyze_dxf(dxf_path: str | Path) -> dict[str, Any]:
    """Perform a structured analysis of a DXF file using ezdxf.

    Returns a dict with:
      - file: str
      - blocks: list[dict]   (name, entity_count)
      - texts: list[dict]    (text, x, y, layer)
      - layers: list[str]
      - entity_counts: dict[str, int]
      - electrical_specs: dict
    """
    import ezdxf

    dxf_path = Path(dxf_path)
    try:
        doc = ezdxf.readfile(str(dxf_path))
    except Exception as exc:
        logger.error("Failed to read DXF %s: %s", dxf_path.name, exc)
        return {"file": str(dxf_path), "error": str(exc)}

    # --- blocks ---
    blocks: list[dict[str, Any]] = []
    for block in doc.blocks:
        if block.name.startswith("*"):
            continue  # skip anonymous blocks
        blocks.append({
            "name": block.name,
            "entity_count": len(list(block)),
        })

    # --- texts with coordinates ---
    texts: list[dict[str, Any]] = []
    msp = doc.modelspace()
    for entity in msp:
        dxftype = entity.dxftype()
        if dxftype == "TEXT":
            ins = entity.dxf.insert
            texts.append({
                "text": entity.dxf.text,
                "x": round(ins.x, 2),
                "y": round(ins.y, 2),
                "layer": entity.dxf.layer,
            })
        elif dxftype == "MTEXT":
            ins = entity.dxf.insert
            texts.append({
                "text": entity.text,  # plain text content
                "x": round(ins.x, 2),
                "y": round(ins.y, 2),
                "layer": entity.dxf.layer,
            })

    # --- layers ---
    layers = sorted(layer.dxf.name for layer in doc.layers)

    # --- entity counts ---
    entity_counts: dict[str, int] = {}
    for entity in msp:
        t = entity.dxftype()
        entity_counts[t] = entity_counts.get(t, 0) + 1

    # --- electrical specs from all text ---
    all_text = " ".join(t["text"] for t in texts)
    specs = _extract_electrical_specs(all_text)

    return {
        "file": str(dxf_path),
        "blocks": blocks,
        "texts": texts,
        "layers": layers,
        "entity_counts": entity_counts,
        "electrical_specs": specs,
    }


# =========================================================================
# 4. Batch processing
# =========================================================================

def batch_analyze(
    input_dir: str | Path,
    output_json: Optional[str | Path] = None,
) -> list[dict[str, Any]]:
    """Process all .dwg and .dxf files in *input_dir*.

    For DWG files: attempt conversion to DXF, then analyse; fall back to
    binary text extraction if conversion fails.

    For DXF files: analyse directly.

    Returns a list of analysis result dicts. Optionally writes them to
    *output_json*.
    """
    input_dir = Path(input_dir)
    if not input_dir.is_dir():
        logger.error("Input directory not found: %s", input_dir)
        return []

    dwg_files = sorted(input_dir.glob("*.dwg"))
    dxf_files = sorted(input_dir.glob("*.dxf"))

    # Track DXF files that came from conversion so we don't double-count
    converted_dxf_names: set[str] = set()
    results: list[dict[str, Any]] = []
    total = len(dwg_files) + len(dxf_files)
    idx = 0

    # --- process DWG files ---
    for dwg in dwg_files:
        idx += 1
        logger.info("[%d/%d] Processing DWG: %s", idx, total, dwg.name)

        dxf_path = convert_dwg_to_dxf(dwg)
        if dxf_path and dxf_path.is_file():
            converted_dxf_names.add(dxf_path.name)
            analysis = analyze_dxf(dxf_path)
            analysis["source"] = "dwg_converted"
            analysis["source_file"] = str(dwg)
        else:
            # Fall back to binary extraction
            analysis = extract_dwg_text(dwg)
            analysis["source"] = "dwg_binary"
            analysis["source_file"] = str(dwg)
            analysis["file"] = str(dwg)

        results.append(analysis)

    # --- process DXF files (skip those that were just converted) ---
    for dxf in dxf_files:
        if dxf.name in converted_dxf_names:
            continue
        idx += 1
        logger.info("[%d/%d] Processing DXF: %s", idx, total, dxf.name)
        analysis = analyze_dxf(dxf)
        analysis["source"] = "dxf_direct"
        results.append(analysis)

    # --- save results ---
    if output_json:
        out_path = Path(output_json)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(results, f, indent=2, ensure_ascii=False, default=str)
        logger.info("Results saved to %s", out_path)

    return results


# =========================================================================
# CLI entry point
# =========================================================================

def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(
        description="DWG/DXF conversion and analysis pipeline",
    )
    parser.add_argument(
        "input_dir",
        help="Directory containing .dwg and/or .dxf files",
    )
    parser.add_argument(
        "--output", "-o",
        default=None,
        help="Path to output JSON file (default: print summary to stdout)",
    )
    parser.add_argument(
        "--verbose", "-v",
        action="store_true",
        help="Enable verbose logging",
    )
    args = parser.parse_args()

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s  %(message)s",
    )

    results = batch_analyze(args.input_dir, args.output)

    # Print summary
    print(f"\nProcessed {len(results)} file(s)")
    for r in results:
        src = r.get("source", "?")
        fname = Path(r.get("file", r.get("source_file", "?"))).name
        specs = r.get("electrical_specs", {})
        n_ratings = len(specs.get("ratings", []))
        n_breakers = len(specs.get("breaker_types", []))
        error = r.get("error", "")
        status = f"ERROR: {error}" if error else f"ratings={n_ratings} breakers={n_breakers}"
        print(f"  [{src}] {fname}: {status}")


if __name__ == "__main__":
    main()
