"""DXF 인제스트 파이프라인.

새 DWG/DXF 파일을 분석하여 블록 라이브러리와 레퍼런스 간격 데이터를
증분 업데이트한다. 이미 분석된 파일은 건너뛰고, 새 파일만 처리한다.

Usage:
    # 전체 스캔 (신규 파일만 처리)
    python -m app.sld.dxf_ingest scan

    # 특정 파일 분석
    python -m app.sld.dxf_ingest scan --file "Sample 1.dwg"

    # 강제 전체 재스캔
    python -m app.sld.dxf_ingest scan --force

    # 현재 라이브러리 현황 출력
    python -m app.sld.dxf_ingest status

    # 블록 상세 조회
    python -m app.sld.dxf_ingest inspect MCCB
"""

from __future__ import annotations

import hashlib
import json
import logging
import math
import shutil
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Paths (relative to blue-light-ai/)
# ---------------------------------------------------------------------------
_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent  # blue-light-ai/
LIBRARY_PATH = _BASE_DIR / "data" / "templates" / "dxf_block_library.json"
SPACING_PATH = _BASE_DIR / "data" / "templates" / "dxf_reference_spacing.json"
DXF_DIR = _BASE_DIR / "data" / "sld-info" / "slds-dxf"
DWG_DIR = _BASE_DIR / "data" / "sld-info" / "slds-dwg"


# ---------------------------------------------------------------------------
# Block extraction helpers
# ---------------------------------------------------------------------------

def _sha256(path: Path) -> str:
    """Compute SHA-256 hex digest of a file."""
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8192), b""):
            h.update(chunk)
    return h.hexdigest()


def _entity_to_dict(entity) -> dict | None:
    """Convert an ezdxf entity to a serializable dict."""
    t = entity.dxftype()

    if t == "LINE":
        s, e = entity.dxf.start, entity.dxf.end
        return {
            "type": "LINE",
            "start": [round(s.x, 4), round(s.y, 4)],
            "end": [round(e.x, 4), round(e.y, 4)],
        }

    if t == "CIRCLE":
        c = entity.dxf.center
        return {
            "type": "CIRCLE",
            "center": [round(c.x, 4), round(c.y, 4)],
            "radius": round(entity.dxf.radius, 4),
        }

    if t == "ARC":
        c = entity.dxf.center
        return {
            "type": "ARC",
            "center": [round(c.x, 4), round(c.y, 4)],
            "radius": round(entity.dxf.radius, 4),
            "start_angle": round(entity.dxf.start_angle, 4),
            "end_angle": round(entity.dxf.end_angle, 4),
        }

    if t == "LWPOLYLINE":
        pts_raw = list(entity.get_points(format="xyseb"))
        points = [[round(p[0], 4), round(p[1], 4)] for p in pts_raw]
        bulges = [round(p[4], 6) for p in pts_raw]
        return {
            "type": "LWPOLYLINE",
            "points": points,
            "bulges": bulges,
            "closed": entity.closed,
        }

    if t == "TEXT":
        ins = entity.dxf.insert
        return {
            "type": "TEXT",
            "text": entity.dxf.text,
            "insert": [round(ins.x, 4), round(ins.y, 4)],
            "height": round(entity.dxf.height, 4) if hasattr(entity.dxf, "height") else 0,
        }

    # Unsupported entity type — skip
    return None


def _compute_bounds(entities: list[dict]) -> dict:
    """Compute bounding box from serialized entities."""
    xs: list[float] = []
    ys: list[float] = []

    for ent in entities:
        t = ent["type"]
        if t == "LINE":
            xs.extend([ent["start"][0], ent["end"][0]])
            ys.extend([ent["start"][1], ent["end"][1]])
        elif t in ("CIRCLE", "ARC"):
            cx, cy, r = ent["center"][0], ent["center"][1], ent["radius"]
            xs.extend([cx - r, cx + r])
            ys.extend([cy - r, cy + r])
        elif t == "LWPOLYLINE":
            for p in ent["points"]:
                xs.append(p[0])
                ys.append(p[1])
        elif t == "TEXT":
            xs.append(ent["insert"][0])
            ys.append(ent["insert"][1])

    if not xs:
        return {"min_x": 0, "min_y": 0, "max_x": 0, "max_y": 0}

    return {
        "min_x": round(min(xs), 4),
        "min_y": round(min(ys), 4),
        "max_x": round(max(xs), 4),
        "max_y": round(max(ys), 4),
    }


def _auto_pins(bounds: dict, entities: list[dict]) -> dict:
    """Derive connection pins from block bounds.

    Heuristic: top/bottom pins are at the horizontal center,
    left/right pins are at the vertical center.
    """
    cx = round((bounds["min_x"] + bounds["max_x"]) / 2, 4)
    cy = round((bounds["min_y"] + bounds["max_y"]) / 2, 4)
    return {
        "top": [cx, bounds["max_y"]],
        "bottom": [cx, bounds["min_y"]],
        "left": [bounds["min_x"], cy],
        "right": [bounds["max_x"], cy],
    }


# ---------------------------------------------------------------------------
# SLD type classification
# ---------------------------------------------------------------------------

def _classify_sld_type(filename: str, block_names: set[str]) -> str:
    """Classify SLD type from filename and block composition."""
    name = filename.upper()

    if "SLD-CT" in block_names:
        return "ct_metering_3phase"
    if "CABLE EXTENSION" in name or "CABLE_EXTENSION" in name:
        return "cable_extension"
    if "SP " in name or "SINGLE" in name or " SP " in name:
        return "direct_metering_1phase"
    if "TPN" in name or "3P" in name:
        return "direct_metering_3phase"
    return "unknown"


# ---------------------------------------------------------------------------
# Spacing measurement
# ---------------------------------------------------------------------------

def _measure_spacing(dxf_path: Path) -> dict | None:
    """Measure component spacing from model space INSERT entities.

    Returns a spacing profile dict, or None if insufficient data.
    """
    import ezdxf

    doc = ezdxf.readfile(str(dxf_path))
    msp = doc.modelspace()

    # Collect all INSERT entities (non-anonymous blocks)
    inserts: list[dict] = []
    for entity in msp:
        if entity.dxftype() != "INSERT":
            continue
        bname = entity.dxf.name
        if bname.startswith("*"):
            continue
        x = entity.dxf.insert.x
        y = entity.dxf.insert.y
        sx = entity.dxf.xscale if hasattr(entity.dxf, "xscale") else 1.0
        rot = entity.dxf.rotation if hasattr(entity.dxf, "rotation") else 0.0
        inserts.append({"name": bname, "x": x, "y": y, "scale": round(sx, 4), "rotation": round(rot, 1)})

    if not inserts:
        return None

    # --- Sub-circuit spacing ---
    # Find the dominant Y level for sub-circuit breakers (vertical MCCB/MCB, most common Y)
    mccb_inserts = [i for i in inserts if i["name"] == "MCCB" and i["rotation"] == 0.0]
    subckt_profile: dict[str, Any] = {}

    if len(mccb_inserts) >= 3:
        # Group by Y (round to nearest 50 DU)
        y_groups: dict[int, list[dict]] = {}
        for m in mccb_inserts:
            key = round(m["y"] / 50) * 50
            y_groups.setdefault(key, []).append(m)

        # Largest Y-group = sub-circuit row
        largest_group = max(y_groups.values(), key=len)
        if len(largest_group) >= 3:
            xs = sorted(m["x"] for m in largest_group)
            gaps = [round(xs[i + 1] - xs[i], 2) for i in range(len(xs) - 1)]
            # Filter out large gaps (e.g., phase group separators)
            median_gap = sorted(gaps)[len(gaps) // 2]
            regular_gaps = [g for g in gaps if g < median_gap * 2.5]
            if regular_gaps:
                subckt_profile = {
                    "subcircuit_x_spacing_du": {
                        "min": round(min(regular_gaps), 2),
                        "max": round(max(regular_gaps), 2),
                        "median": round(sorted(regular_gaps)[len(regular_gaps) // 2], 2),
                        "samples": len(regular_gaps),
                    },
                    "subcircuit_mccb_scale": largest_group[0]["scale"],
                    "subcircuit_y_du": round(largest_group[0]["y"], 2),
                }

    # --- Spine components (CT metering) ---
    spine_profile: dict[str, Any] = {}
    ct_inserts = [i for i in inserts if i["name"] == "SLD-CT"]
    if ct_inserts:
        # Find MCCB closest to CT (spine MCCB)
        ct_ys = sorted(set(round(c["y"]) for c in ct_inserts))
        spine_mccbs = [
            m for m in mccb_inserts
            if any(abs(m["x"] - c["x"]) < 500 for c in ct_inserts)
        ]
        if spine_mccbs:
            spine_mccb = min(spine_mccbs, key=lambda m: m["y"])
            components = [{"name": "MCCB", "y_du": round(spine_mccb["y"], 2), "scale": spine_mccb["scale"]}]
            for ct in sorted(ct_inserts, key=lambda c: c["y"]):
                components.append({
                    "name": "SLD-CT",
                    "y_du": round(ct["y"], 2),
                    "scale": ct["scale"],
                    "rotation": ct["rotation"],
                })

            # Compute gaps
            gaps_named: dict[str, float] = {}
            for i in range(len(components) - 1):
                key = f"{components[i]['name'].lower()}_to_{components[i + 1]['name'].lower()}"
                # De-duplicate key names
                if key in gaps_named:
                    key = f"{key}_{i}"
                gaps_named[key] = round(components[i + 1]["y_du"] - components[i]["y_du"], 2)

            spine_profile = {
                "spine_components": components,
                "spine_gaps_du": gaps_named,
            }

    # --- Main breaker scale ---
    main_scales: dict[str, float] = {}
    # Rotated MCCB = main breaker or horizontal placement
    for m in mccb_inserts:
        if m["scale"] not in [i["scale"] for i in largest_group] if subckt_profile else True:
            if m not in (largest_group if subckt_profile else []):
                main_scales.setdefault("main_mccb_scale", m["scale"])

    # --- Busbar Y ---
    # Look for long horizontal LINE as busbar
    busbar_y = None
    for entity in msp:
        if entity.dxftype() == "LINE":
            s, e = entity.dxf.start, entity.dxf.end
            if abs(s.y - e.y) < 1 and abs(e.x - s.x) > 10000:
                busbar_y = round(s.y, 2)
                break

    # --- RCCB scale ---
    rccb_inserts = [i for i in inserts if i["name"] == "RCCB"]
    rccb_scales = list({i["scale"] for i in rccb_inserts})

    # Combine profile
    block_names = {i["name"] for i in inserts}
    sld_type = _classify_sld_type(dxf_path.name, block_names)

    profile: dict[str, Any] = {
        "sld_type": sld_type,
        "source_file": dxf_path.name,
        "total_inserts": len(inserts),
    }
    profile.update(subckt_profile)
    profile.update(spine_profile)
    profile.update(main_scales)
    if busbar_y is not None:
        profile["busbar_y_du"] = busbar_y
    if rccb_scales:
        profile["rccb_scales"] = sorted(rccb_scales)

    # Scale usage summary
    scale_usage: dict[str, list] = {}
    for i in inserts:
        scale_usage.setdefault(i["name"], []).append(i["scale"])
    profile["scale_usage"] = {
        name: dict(Counter(scales).most_common())
        for name, scales in scale_usage.items()
    }

    return profile


# ---------------------------------------------------------------------------
# DxfIngestPipeline
# ---------------------------------------------------------------------------

class DxfIngestPipeline:
    """DXF 파일 분석 → 블록 라이브러리 + 간격 데이터 증분 업데이트."""

    def __init__(
        self,
        library_path: Path = LIBRARY_PATH,
        spacing_path: Path = SPACING_PATH,
        dxf_dir: Path = DXF_DIR,
        dwg_dir: Path = DWG_DIR,
    ):
        self.library_path = library_path
        self.spacing_path = spacing_path
        self.dxf_dir = dxf_dir
        self.dwg_dir = dwg_dir

    # -- Public API --

    def scan(
        self,
        target_file: str | None = None,
        force: bool = False,
    ) -> dict:
        """메인 스캔. 반환값: {"scanned": int, "new_blocks": int, "updated_blocks": int}."""
        import ezdxf

        library = self._load_or_init(self.library_path, _init_library)
        spacing = self._load_or_init(self.spacing_path, _init_spacing)

        files = self._discover_files(target_file)
        stats = {"scanned": 0, "skipped": 0, "new_blocks": 0, "updated_blocks": 0}

        for dxf_path in files:
            file_hash = _sha256(dxf_path)

            # Skip already-processed files (unless --force)
            if not force and self._already_processed(library, dxf_path.name, file_hash):
                stats["skipped"] += 1
                continue

            print(f"[SCAN] {dxf_path.name} ...")
            stats["scanned"] += 1

            try:
                doc = ezdxf.readfile(str(dxf_path))
            except Exception as exc:
                print(f"  ⚠ 읽기 실패: {exc}")
                continue

            # Step 1: Extract blocks
            new_blocks = self._extract_blocks(doc, dxf_path.name)
            merge_stats = self._merge_blocks(library, new_blocks, dxf_path.name)
            stats["new_blocks"] += merge_stats["new"]
            stats["updated_blocks"] += merge_stats["existing"]

            # Step 2: Measure spacing
            try:
                spacing_profile = _measure_spacing(dxf_path)
                if spacing_profile:
                    self._merge_spacing(spacing, spacing_profile)
            except Exception as exc:
                print(f"  ⚠ 간격 측정 실패: {exc}")

            # Step 3: Update manifest
            block_names_found = list(new_blocks.keys())
            block_names_set = set(block_names_found)
            sld_type = _classify_sld_type(dxf_path.name, block_names_set)

            library["_processed_files"][dxf_path.name] = {
                "sha256": file_hash,
                "processed_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S"),
                "blocks_found": block_names_found,
                "sld_type": sld_type,
            }

            print(f"  → 블록 {len(new_blocks)}개 (신규 {merge_stats['new']}, 기존 {merge_stats['existing']}), 유형: {sld_type}")

        # Update meta
        library["_meta"]["last_updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        library["_meta"]["total_source_files"] = len(library["_processed_files"])
        library["_meta"]["total_blocks"] = len(library["blocks"])

        spacing["_meta"]["last_updated"] = library["_meta"]["last_updated"]

        self._save(self.library_path, library)
        self._save(self.spacing_path, spacing)

        total = stats["scanned"] + stats["skipped"]
        print(f"\n✅ 완료: {stats['scanned']}/{total} 파일 처리, "
              f"블록 {stats['new_blocks']} 신규 / {stats['updated_blocks']} 갱신")
        print(f"  📦 {self.library_path.name}: {library['_meta']['total_blocks']}개 블록")
        print(f"  📏 {self.spacing_path.name}: {len(spacing['profiles'])}개 프로파일")

        return stats

    def status(self) -> None:
        """현재 라이브러리 현황 출력."""
        library = self._load_or_init(self.library_path, _init_library)

        total_files = library["_meta"].get("total_source_files", 0)
        total_blocks = len(library["blocks"])
        total_custom = len(library.get("custom_blocks", {}))
        last_updated = library["_meta"].get("last_updated", "N/A")

        print("DXF Block Library Status")
        print("=" * 40)
        print(f"  마지막 갱신: {last_updated}")
        print(f"  처리된 파일: {total_files}개")
        print(f"  등록 블록: {total_blocks}개")
        print(f"  커스텀 블록: {total_custom}개")
        print()

        if library["blocks"]:
            print("  블록별 현황:")
            for name in sorted(library["blocks"].keys()):
                blk = library["blocks"][name]
                src_count = blk.get("source_count", 1)
                h = blk.get("height_du", 0)
                ent_count = len(blk.get("entities", []))
                print(f"    {name:20s} | {src_count:2d}/{total_files} 파일 | "
                      f"{h:8.2f} DU | {ent_count:2d} ent")

        if library.get("custom_blocks"):
            print("\n  커스텀 블록:")
            for name, blk in library["custom_blocks"].items():
                h = blk.get("height_du", 0)
                print(f"    {name:20s} | {h:8.2f} DU | (수동 등록)")

        # Unprocessed DXF files
        all_dxf = set(f.name for f in self.dxf_dir.glob("*.dxf"))
        processed = set(library["_processed_files"].keys())
        unprocessed = all_dxf - processed
        if unprocessed:
            print(f"\n  ⚠ 미처리 파일: {len(unprocessed)}개")
            for f in sorted(unprocessed):
                print(f"    - {f}")
        else:
            print(f"\n  ✅ 미처리 파일: 0개")

    def inspect(self, block_name: str) -> None:
        """블록 상세 조회."""
        library = self._load_or_init(self.library_path, _init_library)

        # Search in blocks and custom_blocks
        blk = library["blocks"].get(block_name) or library.get("custom_blocks", {}).get(block_name)
        if not blk:
            print(f"❌ 블록 '{block_name}' 을 찾을 수 없습니다.")
            print(f"  등록된 블록: {', '.join(sorted(library['blocks'].keys()))}")
            custom = library.get("custom_blocks", {})
            if custom:
                print(f"  커스텀 블록: {', '.join(sorted(custom.keys()))}")
            return

        is_custom = block_name in library.get("custom_blocks", {})

        print(f"Block: {block_name}" + (" (커스텀)" if is_custom else ""))
        print(f"  Size: {blk.get('width_du', 0):.2f} × {blk.get('height_du', 0):.2f} DU")
        print(f"  Source: {blk.get('source_file', blk.get('source', 'N/A'))}")
        if not is_custom:
            print(f"  발견 파일 수: {blk.get('source_count', 1)}개")

        # Entities
        entities = blk.get("entities", [])
        type_counts = Counter(e["type"] for e in entities)
        type_str = ", ".join(f"{t}:{c}" for t, c in sorted(type_counts.items()))
        print(f"  Entities: {len(entities)} ({type_str})")

        # Bounds
        bounds = blk.get("bounds")
        if bounds:
            print(f"  Bounds: ({bounds['min_x']:.2f}, {bounds['min_y']:.2f}) → "
                  f"({bounds['max_x']:.2f}, {bounds['max_y']:.2f})")

        # Pins
        pins = blk.get("pins", {})
        if pins:
            print(f"  Pins:")
            for pname, pos in pins.items():
                print(f"    {pname}: [{pos[0]:.2f}, {pos[1]:.2f}]")

        # Scale usage from spacing profiles
        spacing = self._load_or_init(self.spacing_path, _init_spacing)
        for prof_name, prof in spacing["profiles"].items():
            usage = prof.get("scale_usage", {}).get(block_name)
            if usage:
                print(f"  Scale usage ({prof_name}):")
                for scale_val, count in usage.items():
                    print(f"    scale={scale_val} × {count}회")

        # Entity details
        if entities:
            print(f"\n  Entity details:")
            for ent in entities:
                t = ent["type"]
                if t == "LINE":
                    print(f"    LINE ({ent['start'][0]:.1f},{ent['start'][1]:.1f}) → "
                          f"({ent['end'][0]:.1f},{ent['end'][1]:.1f})")
                elif t == "CIRCLE":
                    print(f"    CIRCLE center=({ent['center'][0]:.1f},{ent['center'][1]:.1f}) "
                          f"r={ent['radius']:.2f}")
                elif t == "ARC":
                    print(f"    ARC center=({ent['center'][0]:.1f},{ent['center'][1]:.1f}) "
                          f"r={ent['radius']:.2f} {ent['start_angle']:.1f}°→{ent['end_angle']:.1f}°")
                elif t == "LWPOLYLINE":
                    bulges = [b for b in ent.get("bulges", []) if abs(b) > 0.0001]
                    print(f"    LWPOLY {len(ent['points'])} pts"
                          + (f", bulges={[round(b, 3) for b in bulges]}" if bulges else "")
                          + (", closed" if ent.get("closed") else ""))
                elif t == "TEXT":
                    print(f"    TEXT \"{ent['text']}\" at ({ent['insert'][0]:.1f},{ent['insert'][1]:.1f})")

    def register_custom_blocks(self) -> dict:
        """KWH_METER, EARTH 등 DXF 블록이 없는 심볼을 커스텀 블록으로 등록.

        DXF model space의 loose entity를 기반으로 정규화된 좌표(origin 0,0)로 등록.
        Returns: {"registered": int, "skipped": int}
        """
        library = self._load_or_init(self.library_path, _init_library)
        custom = library.setdefault("custom_blocks", {})
        stats = {"registered": 0, "skipped": 0}

        for name, block_def in _CUSTOM_BLOCK_DEFS.items():
            if name in custom:
                print(f"  ⏭ {name} 이미 등록됨 — 건너뜀")
                stats["skipped"] += 1
                continue
            custom[name] = block_def
            stats["registered"] += 1
            print(f"  ✅ {name} 커스텀 블록 등록 ({block_def['width_du']:.1f}×{block_def['height_du']:.1f} DU)")

        library["_meta"]["last_updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        self._save(self.library_path, library)
        print(f"\n완료: {stats['registered']}개 등록, {stats['skipped']}개 건너뜀")
        return stats

    def cleanup_library(self) -> dict:
        """A$C* 등 AutoCAD 내부 블록을 라이브러리에서 제거.

        Returns: {"removed": list[str]}
        """
        library = self._load_or_init(self.library_path, _init_library)
        removed = []

        to_remove = [name for name in library["blocks"] if name.startswith("A$C")]
        for name in to_remove:
            del library["blocks"][name]
            removed.append(name)
            print(f"  🗑 {name} 제거 (AutoCAD 내부 블록)")

        # Also clean up from _processed_files block lists
        for fname, finfo in library["_processed_files"].items():
            finfo["blocks_found"] = [
                b for b in finfo.get("blocks_found", [])
                if not b.startswith("A$C")
            ]

        library["_meta"]["total_blocks"] = len(library["blocks"])
        library["_meta"]["last_updated"] = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S")
        self._save(self.library_path, library)
        print(f"\n완료: {len(removed)}개 블록 제거")
        return {"removed": removed}

    # -- Internal --

    def _extract_blocks(self, doc, source_filename: str) -> dict[str, dict]:
        """Extract all named, non-empty blocks from an ezdxf document."""
        result: dict[str, dict] = {}

        for block in doc.blocks:
            name = block.name
            # Skip anonymous blocks, standard blocks, and AutoCAD internals
            if name.startswith("*") or name.startswith("_") or name.startswith("A$C"):
                continue

            raw_entities = list(block)
            if not raw_entities:
                continue

            entities = [e for e in (_entity_to_dict(ent) for ent in raw_entities) if e is not None]
            if not entities:
                continue

            bounds = _compute_bounds(entities)
            width = round(bounds["max_x"] - bounds["min_x"], 4)
            height = round(bounds["max_y"] - bounds["min_y"], 4)

            result[name] = {
                "source_file": source_filename,
                "source_count": 1,
                "width_du": width,
                "height_du": height,
                "bounds": bounds,
                "entities": entities,
                "pins": _auto_pins(bounds, entities),
            }

        return result

    def _merge_blocks(
        self,
        library: dict,
        new_blocks: dict[str, dict],
        source_filename: str,
    ) -> dict[str, int]:
        """Merge new blocks into library. Returns {"new": N, "existing": M}."""
        stats = {"new": 0, "existing": 0}

        for name, block_def in new_blocks.items():
            existing = library["blocks"].get(name)
            if not existing:
                library["blocks"][name] = block_def
                stats["new"] += 1
            else:
                # Compare geometry: height_du within 1%
                if existing["height_du"] > 0 and abs(
                    existing["height_du"] - block_def["height_du"]
                ) / existing["height_du"] < 0.01:
                    existing["source_count"] = existing.get("source_count", 1) + 1
                elif existing["height_du"] == 0 and block_def["height_du"] == 0:
                    existing["source_count"] = existing.get("source_count", 1) + 1
                else:
                    # Geometry differs — register as variant
                    variant_name = f"{name}__{Path(source_filename).stem.replace(' ', '_')}"
                    library["blocks"][variant_name] = block_def
                    print(f"  ⚠ {name} 치수 상이 ({existing['height_du']:.1f} vs {block_def['height_du']:.1f} DU) "
                          f"→ {variant_name}")
                stats["existing"] += 1

        return stats

    def _merge_spacing(self, spacing: dict, profile: dict) -> None:
        """Merge a spacing profile into the spacing data."""
        sld_type = profile.get("sld_type", "unknown")
        source_file = profile.get("source_file", "unknown")

        existing = spacing["profiles"].get(sld_type)
        if existing:
            # Append source file
            sources = existing.setdefault("source_files", [])
            if source_file not in sources:
                sources.append(source_file)

            # Update spacing stats (keep min/max)
            for key in ("subcircuit_x_spacing_du",):
                new_val = profile.get(key)
                old_val = existing.get(key)
                if isinstance(new_val, dict) and isinstance(old_val, dict):
                    old_val["min"] = min(old_val.get("min", float("inf")), new_val.get("min", float("inf")))
                    old_val["max"] = max(old_val.get("max", 0), new_val.get("max", 0))
                    old_val["samples"] = old_val.get("samples", 0) + new_val.get("samples", 0)
                elif new_val is not None and old_val is None:
                    existing[key] = new_val

            # Merge scale_usage
            for bname, usage in profile.get("scale_usage", {}).items():
                old_usage = existing.setdefault("scale_usage", {}).setdefault(bname, {})
                for scale_val, count in usage.items():
                    sv = str(scale_val)
                    old_usage[sv] = old_usage.get(sv, 0) + count

            # Merge spine data if richer
            if "spine_components" in profile and (
                "spine_components" not in existing
                or len(profile["spine_components"]) > len(existing["spine_components"])
            ):
                existing["spine_components"] = profile["spine_components"]
                existing["spine_gaps_du"] = profile.get("spine_gaps_du", {})
        else:
            # New profile
            spacing["profiles"][sld_type] = {
                "source_files": [source_file],
                **{k: v for k, v in profile.items() if k not in ("sld_type", "source_file")},
            }

    def _already_processed(self, library: dict, filename: str, file_hash: str) -> bool:
        """Check if file was already processed with same hash."""
        entry = library["_processed_files"].get(filename)
        return entry is not None and entry.get("sha256") == file_hash

    def _discover_files(self, target_file: str | None = None) -> list[Path]:
        """Find DXF files to scan. Auto-convert DWG if needed."""
        # Auto-convert DWG → DXF
        if self.dwg_dir.exists():
            for dwg in self.dwg_dir.glob("*.dwg"):
                dxf_target = self.dxf_dir / dwg.with_suffix(".dxf").name
                if not dxf_target.exists():
                    # Try dwg2dxf conversion
                    if shutil.which("dwg2dxf"):
                        print(f"[CONVERT] {dwg.name} → DXF")
                        try:
                            subprocess.run(
                                ["dwg2dxf", str(dwg)],
                                check=True,
                                capture_output=True,
                            )
                            # dwg2dxf outputs to same directory as input
                            converted = dwg.with_suffix(".dxf")
                            if converted.exists():
                                shutil.move(str(converted), str(dxf_target))
                        except subprocess.CalledProcessError as exc:
                            print(f"  ⚠ 변환 실패: {exc}")

        if target_file:
            target = self.dxf_dir / target_file
            if not target.exists():
                # Try with .dxf extension
                target = self.dxf_dir / (Path(target_file).stem + ".dxf")
            return [target] if target.exists() else []

        return sorted(self.dxf_dir.glob("*.dxf"))

    @staticmethod
    def _load_or_init(path: Path, init_fn) -> dict:
        """Load JSON file, or initialize with factory function."""
        if path.exists():
            with open(path, encoding="utf-8") as f:
                return json.load(f)
        return init_fn()

    @staticmethod
    def _save(path: Path, data: dict) -> None:
        """Save JSON with pretty formatting."""
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)


# ---------------------------------------------------------------------------
# Custom block definitions (symbols that don't exist as DXF block defs)
# ---------------------------------------------------------------------------

_CUSTOM_BLOCK_DEFS: dict[str, dict] = {
    "KWH_METER": {
        "source": "150A TPN SLD 1 DWG.dxf (model space LWPOLYLINE)",
        "description": "KWH meter — rectangle with KWH text. Extracted from model space loose entity and normalized to origin.",
        "width_du": 1067.6,
        "height_du": 533.2,
        "bounds": {"min_x": 0, "min_y": 0, "max_x": 1067.6, "max_y": 533.2},
        "entities": [
            {
                "type": "LWPOLYLINE",
                "points": [[0.0, 533.2], [1067.6, 533.2], [1067.6, 0.0], [0.0, 0.0]],
                "bulges": [0.0, 0.0, 0.0, 0.0],
                "closed": True,
            },
            {
                "type": "TEXT",
                "text": "KWH",
                "insert": [320.0, 310.0],
                "height": 200.0,
            },
        ],
        "pins": {
            "top": [533.8, 533.2],
            "bottom": [533.8, 0.0],
            "left": [0.0, 266.6],
            "right": [1067.6, 266.6],
        },
    },
    "EARTH": {
        "source": "Standard IEC 60617 earth/ground symbol — 3 horizontal lines of decreasing width + vertical stem",
        "description": "Earth/ground symbol. No DXF block exists; defined from IEC standard.",
        "width_du": 600.0,
        "height_du": 600.0,
        "bounds": {"min_x": 0, "min_y": 0, "max_x": 600.0, "max_y": 600.0},
        "entities": [
            # Vertical stem from top to first horizontal line
            {"type": "LINE", "start": [300.0, 600.0], "end": [300.0, 360.0]},
            # First horizontal line (full width)
            {"type": "LINE", "start": [0.0, 360.0], "end": [600.0, 360.0]},
            # Second horizontal line (2/3 width)
            {"type": "LINE", "start": [100.0, 210.0], "end": [500.0, 210.0]},
            # Third horizontal line (1/3 width)
            {"type": "LINE", "start": [200.0, 60.0], "end": [400.0, 60.0]},
        ],
        "pins": {
            "top": [300.0, 600.0],
        },
    },
}


def _init_library() -> dict:
    """Create empty library structure."""
    return {
        "_meta": {
            "version": 1,
            "last_updated": "",
            "total_source_files": 0,
            "total_blocks": 0,
        },
        "_processed_files": {},
        "blocks": {},
        "custom_blocks": {},
    }


def _init_spacing() -> dict:
    """Create empty spacing structure."""
    return {
        "_meta": {
            "version": 1,
            "last_updated": "",
        },
        "profiles": {},
    }


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    """CLI entry point for ``python -m app.sld.dxf_ingest``."""
    import argparse

    parser = argparse.ArgumentParser(
        prog="dxf_ingest",
        description="DXF 인제스트 파이프라인: 블록 추출 + 간격 측정",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # scan
    p_scan = sub.add_parser("scan", help="DXF 파일 스캔 (신규 파일만 처리)")
    p_scan.add_argument("--file", "-f", help="특정 파일만 분석")
    p_scan.add_argument("--force", action="store_true", help="전체 재스캔")

    # status
    sub.add_parser("status", help="현재 라이브러리 현황")

    # inspect
    p_inspect = sub.add_parser("inspect", help="블록 상세 조회")
    p_inspect.add_argument("block_name", help="블록 이름 (예: MCCB)")

    # register-custom
    sub.add_parser("register-custom", help="KWH_METER/EARTH 등 커스텀 블록 등록")

    # cleanup
    sub.add_parser("cleanup", help="A$C* 등 AutoCAD 내부 블록 제거")

    args = parser.parse_args()
    pipeline = DxfIngestPipeline()

    if args.command == "scan":
        pipeline.scan(target_file=args.file, force=args.force)
    elif args.command == "status":
        pipeline.status()
    elif args.command == "inspect":
        pipeline.inspect(args.block_name)
    elif args.command == "register-custom":
        pipeline.register_custom_blocks()
    elif args.command == "cleanup":
        pipeline.cleanup_library()


if __name__ == "__main__":
    main()
