#!/usr/bin/env python3
"""Analyze all DXF files to build a comprehensive symbol block catalog.

Note: ezdxf cannot read native .dwg files. This script analyzes the 28 DXF
template files in slds-dxf/ which are the i2R standard templates converted
from DWG. The original 33 DWG files in sld-dwg-old/ require ODA File Converter
for conversion to DXF format.
"""

import json
import math
from collections import Counter, defaultdict
from pathlib import Path

import ezdxf

DWG_DIR = Path(__file__).parent.parent / "data" / "sld-info" / "sld-dwg-old"
DXF_DIR = Path(__file__).parent.parent / "data" / "sld-info" / "slds-dxf"
OUTPUT = DWG_DIR / "symbol_catalog.json"


def get_entity_bbox(entity):
    """Get approximate bounding box contribution of an entity."""
    try:
        dxftype = entity.dxftype()
        if dxftype == "LINE":
            xs = [entity.dxf.start.x, entity.dxf.end.x]
            ys = [entity.dxf.start.y, entity.dxf.end.y]
            return min(xs), min(ys), max(xs), max(ys)
        elif dxftype == "CIRCLE":
            cx, cy = entity.dxf.center.x, entity.dxf.center.y
            r = entity.dxf.radius
            return cx - r, cy - r, cx + r, cy + r
        elif dxftype == "ARC":
            cx, cy = entity.dxf.center.x, entity.dxf.center.y
            r = entity.dxf.radius
            return cx - r, cy - r, cx + r, cy + r
        elif dxftype == "POINT":
            return entity.dxf.location.x, entity.dxf.location.y, entity.dxf.location.x, entity.dxf.location.y
        elif dxftype in ("TEXT", "MTEXT"):
            ip = entity.dxf.insert if hasattr(entity.dxf, 'insert') else None
            if ip:
                return ip.x, ip.y, ip.x, ip.y
        elif dxftype == "LWPOLYLINE":
            pts = list(entity.get_points(format="xy"))
            if pts:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                return min(xs), min(ys), max(xs), max(ys)
        elif dxftype == "POLYLINE":
            pts = [(v.dxf.location.x, v.dxf.location.y) for v in entity.vertices]
            if pts:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                return min(xs), min(ys), max(xs), max(ys)
        elif dxftype == "ELLIPSE":
            cx, cy = entity.dxf.center.x, entity.dxf.center.y
            mx, my = entity.dxf.major_axis.x, entity.dxf.major_axis.y
            r_major = math.sqrt(mx**2 + my**2)
            return cx - r_major, cy - r_major, cx + r_major, cy + r_major
        elif dxftype in ("SOLID", "TRACE"):
            pts = []
            for attr in ("vtx0", "vtx1", "vtx2", "vtx3"):
                if hasattr(entity.dxf, attr):
                    v = getattr(entity.dxf, attr)
                    pts.append((v.x, v.y))
            if pts:
                xs = [p[0] for p in pts]
                ys = [p[1] for p in pts]
                return min(xs), min(ys), max(xs), max(ys)
        elif dxftype == "SPLINE":
            pts = list(entity.control_points)
            if pts:
                xs = [p.x for p in pts]
                ys = [p.y for p in pts]
                return min(xs), min(ys), max(xs), max(ys)
        elif dxftype == "INSERT":
            ip = entity.dxf.insert
            return ip.x, ip.y, ip.x, ip.y
        elif dxftype in ("ATTDEF", "ATTRIB"):
            ip = entity.dxf.insert if hasattr(entity.dxf, 'insert') else None
            if ip:
                return ip.x, ip.y, ip.x, ip.y
    except Exception:
        pass
    return None


def describe_entity(entity):
    """Return a compact description of an entity for block geometry catalog."""
    try:
        dtype = entity.dxftype()
        if dtype == "LINE":
            return {
                "type": "LINE",
                "start": [round(entity.dxf.start.x, 2), round(entity.dxf.start.y, 2)],
                "end": [round(entity.dxf.end.x, 2), round(entity.dxf.end.y, 2)],
            }
        elif dtype == "CIRCLE":
            return {
                "type": "CIRCLE",
                "center": [round(entity.dxf.center.x, 2), round(entity.dxf.center.y, 2)],
                "radius": round(entity.dxf.radius, 2),
            }
        elif dtype == "ARC":
            return {
                "type": "ARC",
                "center": [round(entity.dxf.center.x, 2), round(entity.dxf.center.y, 2)],
                "radius": round(entity.dxf.radius, 2),
                "start_angle": round(getattr(entity.dxf, 'start_angle', 0), 2),
                "end_angle": round(getattr(entity.dxf, 'end_angle', 360), 2),
            }
        elif dtype == "LWPOLYLINE":
            pts = list(entity.get_points(format="xyb"))
            return {
                "type": "LWPOLYLINE",
                "points": [[round(p[0], 2), round(p[1], 2)] for p in pts],
                "bulges": [round(p[2], 4) for p in pts],
                "closed": entity.closed,
            }
        elif dtype in ("TEXT", "MTEXT"):
            ip = entity.dxf.insert if hasattr(entity.dxf, 'insert') else None
            text = getattr(entity.dxf, 'text', '') if dtype == "TEXT" else getattr(entity, 'text', '')
            return {
                "type": dtype,
                "insert": [round(ip.x, 2), round(ip.y, 2)] if ip else None,
                "text": str(text)[:50],
                "height": round(getattr(entity.dxf, 'height', 0), 2),
            }
        elif dtype == "SOLID":
            pts = []
            for attr in ("vtx0", "vtx1", "vtx2", "vtx3"):
                if hasattr(entity.dxf, attr):
                    v = getattr(entity.dxf, attr)
                    pts.append([round(v.x, 2), round(v.y, 2)])
            return {"type": "SOLID", "vertices": pts}
        elif dtype == "INSERT":
            return {
                "type": "INSERT",
                "block_name": entity.dxf.name,
                "insert": [round(entity.dxf.insert.x, 2), round(entity.dxf.insert.y, 2)],
                "xscale": round(getattr(entity.dxf, 'xscale', 1.0), 4),
                "yscale": round(getattr(entity.dxf, 'yscale', 1.0), 4),
                "rotation": round(getattr(entity.dxf, 'rotation', 0.0), 2),
            }
        elif dtype in ("ATTDEF", "ATTRIB"):
            ip = entity.dxf.insert if hasattr(entity.dxf, 'insert') else None
            return {
                "type": dtype,
                "tag": getattr(entity.dxf, 'tag', ''),
                "text": getattr(entity.dxf, 'text', ''),
                "insert": [round(ip.x, 2), round(ip.y, 2)] if ip else None,
            }
        else:
            return {"type": dtype}
    except Exception as e:
        return {"type": entity.dxftype(), "error": str(e)[:50]}


def analyze_block(block):
    """Analyze a block definition."""
    entity_counts = defaultdict(int)
    nested_blocks = []
    entities_detail = []
    min_x = min_y = float('inf')
    max_x = max_y = float('-inf')
    has_geometry = False

    for e in block:
        dtype = e.dxftype()
        entity_counts[dtype] += 1
        if dtype == "INSERT":
            nested_blocks.append(e.dxf.name)
        bbox = get_entity_bbox(e)
        if bbox:
            has_geometry = True
            min_x = min(min_x, bbox[0])
            min_y = min(min_y, bbox[1])
            max_x = max(max_x, bbox[2])
            max_y = max(max_y, bbox[3])
        entities_detail.append(describe_entity(e))

    width = round(max_x - min_x, 2) if has_geometry and min_x != float('inf') else 0
    height = round(max_y - min_y, 2) if has_geometry and min_y != float('inf') else 0

    return {
        "entity_counts": dict(entity_counts),
        "total_entities": sum(entity_counts.values()),
        "nested_blocks": nested_blocks,
        "bbox": {
            "min_x": round(min_x, 2) if has_geometry and min_x != float('inf') else 0,
            "min_y": round(min_y, 2) if has_geometry and min_y != float('inf') else 0,
            "max_x": round(max_x, 2) if has_geometry and max_x != float('inf') else 0,
            "max_y": round(max_y, 2) if has_geometry and max_y != float('inf') else 0,
            "width": width,
            "height": height,
        },
        "entities": entities_detail,
    }


def analyze_file(filepath):
    """Analyze a single DXF file."""
    try:
        doc = ezdxf.readfile(str(filepath))
    except Exception as e:
        return {"error": str(e), "filename": filepath.name}

    msp = doc.modelspace()

    # Modelspace entity summary
    msp_entity_counts = Counter(e.dxftype() for e in msp)

    # Collect all INSERT entities
    inserts = []
    for entity in msp:
        if entity.dxftype() == "INSERT":
            ins = {
                "block_name": entity.dxf.name,
                "x": round(entity.dxf.insert.x, 2),
                "y": round(entity.dxf.insert.y, 2),
                "xscale": round(getattr(entity.dxf, 'xscale', 1.0), 4),
                "yscale": round(getattr(entity.dxf, 'yscale', 1.0), 4),
                "rotation": round(getattr(entity.dxf, 'rotation', 0.0), 2),
            }
            if entity.attribs:
                ins["attribs"] = {}
                for attrib in entity.attribs:
                    ins["attribs"][attrib.dxf.tag] = attrib.dxf.text
            inserts.append(ins)

    # Collect all TEXT/MTEXT for analysis of label patterns
    text_entities = []
    for entity in msp:
        if entity.dxftype() == "TEXT":
            text_entities.append({
                "type": "TEXT",
                "text": entity.dxf.text,
                "x": round(entity.dxf.insert.x, 2),
                "y": round(entity.dxf.insert.y, 2),
                "height": round(entity.dxf.height, 2),
            })
        elif entity.dxftype() == "MTEXT":
            text_entities.append({
                "type": "MTEXT",
                "text": str(entity.text)[:100],
                "x": round(entity.dxf.insert.x, 2),
                "y": round(entity.dxf.insert.y, 2),
                "height": round(getattr(entity.dxf, 'char_height', 0), 2),
            })

    # Analyze block definitions
    block_defs = {}
    for block in doc.blocks:
        name = block.name
        if name.startswith("*"):
            continue
        block_defs[name] = analyze_block(block)

    # Modelspace bounding box
    msp_min_x = msp_min_y = float('inf')
    msp_max_x = msp_max_y = float('-inf')
    for entity in msp:
        bbox = get_entity_bbox(entity)
        if bbox:
            msp_min_x = min(msp_min_x, bbox[0])
            msp_min_y = min(msp_min_y, bbox[1])
            msp_max_x = max(msp_max_x, bbox[2])
            msp_max_y = max(msp_max_y, bbox[3])

    has_msp_bbox = msp_min_x != float('inf')

    return {
        "filename": filepath.name,
        "insert_count": len(inserts),
        "modelspace_summary": {
            "entity_counts": dict(msp_entity_counts),
            "total_entities": sum(msp_entity_counts.values()),
            "text_entity_count": len(text_entities),
            "bbox": {
                "width": round(msp_max_x - msp_min_x, 2) if has_msp_bbox else 0,
                "height": round(msp_max_y - msp_min_y, 2) if has_msp_bbox else 0,
            } if has_msp_bbox else {"width": 0, "height": 0},
        },
        "inserts": inserts,
        "block_definitions": block_defs,
        "unique_blocks_used": sorted(set(i["block_name"] for i in inserts)),
        "text_samples": text_entities[:30],  # Sample of text entities for label pattern analysis
    }


def categorize_block(name):
    """Categorize a block by name pattern."""
    n = name.upper()
    if any(k in n for k in ("MCB", "MCCB")):
        return "breakers"
    if "ELCB" in n or "RCCB" in n or "RCD" in n:
        return "breakers_rcd"
    if "METER" in n or "KWH" in n or "ENERGY" in n:
        return "meters"
    if "ISOLAT" in n or "ISOL" in n:
        return "isolators"
    if "SWITCH" in n and "ISOL" not in n:
        return "switches"
    if "EARTH" in n or "GROUND" in n or "GND" in n:
        return "earth_symbols"
    if "CT" in n and len(n) < 15:
        return "ct_hooks"
    if "CABLE" in n or "WIRE" in n:
        return "cable_annotations"
    if "TITLE" in n or "BORDER" in n or "FRAME" in n or "LOGO" in n:
        return "title_blocks"
    if "FUSE" in n:
        return "fuses"
    if "BUSBAR" in n or "BUS" in n:
        return "busbars"
    if "CONTACTOR" in n or "RELAY" in n:
        return "contactors_relays"
    if "TRANSFORMER" in n or "XFMR" in n:
        return "transformers"
    if "LIGHT" in n or "LAMP" in n or "LED" in n or "LTG" in n:
        return "lighting"
    if "SOCKET" in n or "OUTLET" in n:
        return "sockets"
    if "DB" in n or "DISTRIBUTION" in n or "PANEL" in n:
        return "distribution_boards"
    if "ARROW" in n or "SYMBOL" in n or "SIGN" in n:
        return "misc_symbols"
    if "A$" in n or name.startswith("_"):
        return "autocad_internal"
    if "SS" == n:
        return "selector_switch"
    if "EF" == n:
        return "earth_fault"
    if "DP" in n:
        return "isolators"
    if "3P" in n:
        return "three_phase_devices"
    return "uncategorized"


def infer_drawing_type(result):
    """Infer what type of SLD this drawing represents."""
    fname = result["filename"].upper()
    blocks_used = set(b.upper() for b in result["unique_blocks_used"])

    if "CABLE EXTENSION" in fname:
        return "cable_extension"
    if "SINGLE PHASE" in fname:
        return "single_phase_db"
    if "TPN" in fname:
        return "three_phase_tpn"

    # Infer from content
    if any("RCCB" in b for b in blocks_used):
        return "distribution_board_with_rcd"
    if any("MCCB" in b for b in blocks_used):
        return "distribution_board"
    return "unknown"


def main():
    dwg_files = sorted(DXF_DIR.glob("*.dxf")) + sorted(DWG_DIR.glob("*.dxf"))
    print(f"Found {len(dwg_files)} DXF files")

    all_results = []
    errors = []

    for i, f in enumerate(dwg_files):
        print(f"  [{i+1}/{len(dwg_files)}] {f.name[:60]}...")
        result = analyze_file(f)
        if "error" in result:
            errors.append(result)
            print(f"    ERROR: {result['error'][:80]}")
        else:
            all_results.append(result)
            ms = result["modelspace_summary"]
            print(f"    -> {result['insert_count']} inserts, {len(result['block_definitions'])} block defs, "
                  f"{ms['total_entities']} msp entities, drawing {ms['bbox']['width']:.0f}x{ms['bbox']['height']:.0f}")

    # ===== Cross-file frequency table =====
    block_usage = defaultdict(lambda: {
        "file_count": 0,
        "total_instances": 0,
        "files": [],
        "sizes": [],
        "scales": [],
        "rotations": set(),
        "has_nested": False,
        "nested_refs": [],
        "entity_types": defaultdict(int),
        "attrib_tags": set(),
        "sample_attribs": {},
        "insertion_positions": [],
    })

    for result in all_results:
        fname = result["filename"]
        blocks_in_file = set()

        for ins in result["inserts"]:
            bname = ins["block_name"]
            bu = block_usage[bname]
            bu["total_instances"] += 1
            bu["scales"].append((ins["xscale"], ins["yscale"]))
            bu["rotations"].add(ins["rotation"])
            bu["insertion_positions"].append({"file": fname, "x": ins["x"], "y": ins["y"]})
            if "attribs" in ins:
                for tag in ins["attribs"]:
                    bu["attrib_tags"].add(tag)
                if not bu["sample_attribs"]:
                    bu["sample_attribs"] = ins["attribs"]
            blocks_in_file.add(bname)

        for bname in blocks_in_file:
            bu = block_usage[bname]
            bu["file_count"] += 1
            bu["files"].append(fname)

        for bname, bdef in result["block_definitions"].items():
            bu = block_usage[bname]
            if bdef["bbox"]["width"] > 0 or bdef["bbox"]["height"] > 0:
                bu["sizes"].append((bdef["bbox"]["width"], bdef["bbox"]["height"]))
            if bdef["nested_blocks"]:
                bu["has_nested"] = True
                bu["nested_refs"].extend(bdef["nested_blocks"])
            for etype, count in bdef["entity_counts"].items():
                bu["entity_types"][etype] += count

    # Build frequency table
    frequency_table = []
    for bname, bu in sorted(block_usage.items(), key=lambda x: -x[1]["total_instances"]):
        avg_w = round(sum(s[0] for s in bu["sizes"]) / len(bu["sizes"]), 2) if bu["sizes"] else 0
        avg_h = round(sum(s[1] for s in bu["sizes"]) / len(bu["sizes"]), 2) if bu["sizes"] else 0

        # Compute typical scale
        unique_scales = list(set(bu["scales"]))

        entry = {
            "block_name": bname,
            "category": categorize_block(bname),
            "file_count": bu["file_count"],
            "total_instances": bu["total_instances"],
            "avg_size_mm": {"width": avg_w, "height": avg_h},
            "entity_types": dict(bu["entity_types"]),
            "rotations_used": sorted(bu["rotations"]),
            "unique_scales": [{"xscale": s[0], "yscale": s[1]} for s in unique_scales[:10]],
            "has_nested_blocks": bu["has_nested"],
            "nested_block_refs": sorted(set(bu["nested_refs"])) if bu["nested_refs"] else [],
            "attrib_tags": sorted(bu["attrib_tags"]),
            "sample_attribs": bu["sample_attribs"],
            "files_using": sorted(bu["files"]),
        }
        frequency_table.append(entry)

    # Identify special groups
    i2r_blocks = [e for e in frequency_table if "i2r" in e["block_name"].lower()]
    standard_symbols = [e for e in frequency_table
                        if e["category"] in ("breakers", "breakers_rcd", "meters", "isolators",
                                              "earth_symbols", "earth_fault", "ct_hooks", "fuses",
                                              "selector_switch", "switches")]
    complex_assemblies = [e for e in frequency_table if e["has_nested_blocks"]]

    # Category summary
    category_summary = defaultdict(lambda: {"block_count": 0, "total_instances": 0, "block_names": []})
    for entry in frequency_table:
        cat = entry["category"]
        cs = category_summary[cat]
        cs["block_count"] += 1
        cs["total_instances"] += entry["total_instances"]
        cs["block_names"].append(entry["block_name"])

    # ===== Per-file detail =====
    per_file = []
    for result in all_results:
        per_file.append({
            "filename": result["filename"],
            "drawing_type": infer_drawing_type(result),
            "insert_count": result["insert_count"],
            "modelspace_summary": result["modelspace_summary"],
            "unique_blocks_used": result["unique_blocks_used"],
            "inserts": result["inserts"],
            "block_definitions": result["block_definitions"],
            "text_samples": result["text_samples"],
        })

    # ===== Drawing type distribution =====
    drawing_types = Counter(pf["drawing_type"] for pf in per_file)

    # ===== Naming pattern analysis =====
    naming_patterns = defaultdict(list)
    for entry in frequency_table:
        name = entry["block_name"]
        # Extract prefix pattern
        parts = name.split()
        if len(parts) > 1:
            naming_patterns[f"multi-word: '{parts[0]} ...'"].append(name)
        elif "_" in name:
            prefix = name.split("_")[0]
            naming_patterns[f"underscore: '{prefix}_*'"].append(name)
        elif name.startswith("A$"):
            naming_patterns["autocad_anonymous: 'A$*'"].append(name)
        elif name.startswith("_"):
            naming_patterns["autocad_internal: '_*'"].append(name)
        else:
            naming_patterns[f"simple: '{name}'"].append(name)

    catalog = {
        "metadata": {
            "description": "Symbol block catalog from i2R SLD DXF template files",
            "source_directory": str(DXF_DIR),
            "note": "ezdxf cannot read native .dwg files. These are DXF conversions of the i2R templates. "
                    "The 33 DWG files in sld-dwg-old/ would need ODA File Converter for analysis.",
            "dwg_files_not_analyzed": [f.name for f in sorted(DWG_DIR.glob("*.dwg"))],
        },
        "summary": {
            "total_files_analyzed": len(all_results),
            "total_files_errored": len(errors),
            "total_unique_blocks": len(frequency_table),
            "total_insert_instances": sum(e["total_instances"] for e in frequency_table),
            "i2r_specific_blocks_count": len(i2r_blocks),
            "standard_electrical_symbols_count": len(standard_symbols),
            "complex_assemblies_count": len(complex_assemblies),
            "drawing_type_distribution": dict(drawing_types),
        },
        "category_summary": {k: v for k, v in sorted(category_summary.items(), key=lambda x: -x[1]["total_instances"])},
        "naming_patterns": {k: v for k, v in sorted(naming_patterns.items())},
        "frequency_table": frequency_table,
        "standard_electrical_symbols": [
            {"block_name": e["block_name"], "category": e["category"],
             "instances": e["total_instances"], "files": e["file_count"],
             "size_mm": e["avg_size_mm"], "entity_types": e["entity_types"]}
            for e in standard_symbols
        ],
        "complex_assemblies": [
            {"block_name": e["block_name"], "nested_refs": e["nested_block_refs"],
             "total_entities": sum(e["entity_types"].values())}
            for e in complex_assemblies
        ],
        "i2r_specific_blocks": [e["block_name"] for e in i2r_blocks],
        "per_file_detail": per_file,
        "errors": errors,
    }

    with open(OUTPUT, "w") as f:
        json.dump(catalog, f, indent=2, default=str)

    # ===== Print summary =====
    print(f"\n{'='*70}")
    print(f"SYMBOL BLOCK CATALOG — {OUTPUT}")
    print(f"{'='*70}")
    print(f"Files analyzed: {len(all_results)} (errors: {len(errors)})")
    print(f"Unique blocks:  {len(frequency_table)}")
    print(f"Total INSERT instances: {sum(e['total_instances'] for e in frequency_table)}")

    print(f"\n--- Drawing Type Distribution ---")
    for dt, count in drawing_types.most_common():
        print(f"  {dt:30s}: {count}")

    print(f"\n--- Category Summary ---")
    for cat, cs in sorted(category_summary.items(), key=lambda x: -x[1]["total_instances"]):
        print(f"  {cat:25s}: {cs['block_count']:3d} blocks, {cs['total_instances']:5d} instances  {cs['block_names']}")

    print(f"\n--- Block Frequency Table (all {len(frequency_table)} blocks) ---")
    print(f"  {'Block Name':40s} {'Files':>5s} {'Inst':>5s} {'Size (WxH mm)':>16s} {'Category':20s} {'Entities'}")
    print(f"  {'-'*40} {'-'*5} {'-'*5} {'-'*16} {'-'*20} {'-'*30}")
    for entry in frequency_table:
        ent_str = ", ".join(f"{k}:{v}" for k, v in sorted(entry["entity_types"].items()))
        print(f"  {entry['block_name']:40s} {entry['file_count']:5d} {entry['total_instances']:5d} "
              f"{entry['avg_size_mm']['width']:7.1f}x{entry['avg_size_mm']['height']:<7.1f} "
              f"{entry['category']:20s} {ent_str}")

    print(f"\n--- Standard Electrical Symbols ({len(standard_symbols)}) ---")
    for e in standard_symbols:
        print(f"  {e['block_name']:20s} cat={e['category']:15s} "
              f"size={e['avg_size_mm']['width']:.1f}x{e['avg_size_mm']['height']:.1f}mm  "
              f"in {e['file_count']} files, {e['total_instances']} instances")

    print(f"\n--- Complex Assemblies (nested blocks: {len(complex_assemblies)}) ---")
    for e in complex_assemblies:
        print(f"  {e['block_name']} -> {e['nested_block_refs']}")

    print(f"\n--- i2R-Specific Blocks ({len(i2r_blocks)}) ---")
    for e in i2r_blocks:
        print(f"  {e['block_name']}")
    if not i2r_blocks:
        print("  (none found — i2R branding likely in TEXT entities, not block names)")

    print(f"\n--- Naming Patterns ---")
    for pattern, names in sorted(naming_patterns.items()):
        print(f"  {pattern}: {names}")

    # Files with no blocks (symbols drawn inline)
    no_block_files = [pf for pf in per_file if pf["insert_count"] == 0]
    if no_block_files:
        print(f"\n--- Files With NO Block References ({len(no_block_files)}) ---")
        print("  (These files draw symbols as raw geometry, not reusable blocks)")
        for pf in no_block_files:
            ms = pf["modelspace_summary"]
            print(f"  {pf['filename']:50s} {ms['total_entities']:4d} entities, "
                  f"drawing size {ms['bbox']['width']:.0f}x{ms['bbox']['height']:.0f}")


if __name__ == "__main__":
    main()
