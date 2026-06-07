#!/usr/bin/env python3
"""Compare two DXF files: reference vs generated."""

import ezdxf
from collections import Counter, defaultdict
import re

REF_PATH = "/Users/ringo/Projects/blue-light/blue-light-ai/data/sld-info/slds-dxf/I2R-ETR-NLB-SLD-1.dxf"
GEN_PATH = "/Users/ringo/Projects/blue-light/blue-light-ai/output/I2R_ETR_NLB_SLD.dxf"


def load_dxf(path):
    return ezdxf.readfile(path)


def get_block_info(doc):
    """Get block definitions: name -> {entity_count, entity_types}."""
    blocks = {}
    for block in doc.blocks:
        name = block.name
        entities = list(block)
        type_counts = Counter(e.dxftype() for e in entities)
        blocks[name] = {
            "entity_count": len(entities),
            "entity_types": dict(type_counts),
        }
    return blocks


def get_inserts(doc):
    """Get all INSERT entities from modelspace."""
    inserts = defaultdict(list)
    msp = doc.modelspace()
    for e in msp:
        if e.dxftype() == "INSERT":
            name = e.dxf.name
            inserts[name].append({
                "position": (round(e.dxf.insert.x, 2), round(e.dxf.insert.y, 2)),
                "xscale": round(getattr(e.dxf, "xscale", 1.0), 4),
                "yscale": round(getattr(e.dxf, "yscale", 1.0), 4),
                "rotation": round(getattr(e.dxf, "rotation", 0.0), 2),
                "layer": e.dxf.layer,
            })
    return dict(inserts)


def get_texts(doc):
    """Extract all text strings from modelspace."""
    texts = []
    msp = doc.modelspace()
    for e in msp:
        if e.dxftype() == "TEXT":
            texts.append({
                "text": e.dxf.text,
                "position": (round(e.dxf.insert.x, 2), round(e.dxf.insert.y, 2)),
                "layer": e.dxf.layer,
                "height": round(e.dxf.height, 2),
            })
        elif e.dxftype() == "MTEXT":
            raw = e.text  # plain text content
            texts.append({
                "text": raw,
                "position": (round(e.dxf.insert.x, 2), round(e.dxf.insert.y, 2)),
                "layer": e.dxf.layer,
                "height": round(e.dxf.char_height, 2),
            })
    # Also check inside block references (explode INSERTs)
    return texts


def get_all_texts_recursive(doc):
    """Get texts from modelspace including those inside block inserts."""
    texts_direct = []
    texts_in_blocks = []
    msp = doc.modelspace()

    def extract_text_entity(e, prefix=""):
        if e.dxftype() == "TEXT":
            return {"text": e.dxf.text, "source": prefix}
        elif e.dxftype() == "MTEXT":
            return {"text": e.text, "source": prefix}
        return None

    # Direct texts in modelspace
    for e in msp:
        t = extract_text_entity(e, "modelspace")
        if t:
            texts_direct.append(t)

    # Texts inside blocks (check block definitions)
    for block in doc.blocks:
        for e in block:
            t = extract_text_entity(e, f"block:{block.name}")
            if t:
                texts_in_blocks.append(t)

    return texts_direct, texts_in_blocks


def get_entity_counts(doc):
    """Count entities by type in modelspace."""
    msp = doc.modelspace()
    return Counter(e.dxftype() for e in msp)


def get_layer_usage(doc):
    """Count entities per layer in modelspace."""
    msp = doc.modelspace()
    layer_counts = Counter(e.dxf.layer for e in msp)
    return dict(layer_counts)


def get_extents(doc):
    """Get drawing bounding box from modelspace entities."""
    msp = doc.modelspace()
    min_x = min_y = float("inf")
    max_x = max_y = float("-inf")
    count = 0
    for e in msp:
        try:
            if e.dxftype() == "INSERT":
                x, y = e.dxf.insert.x, e.dxf.insert.y
                min_x, min_y = min(min_x, x), min(min_y, y)
                max_x, max_y = max(max_x, x), max(max_y, y)
                count += 1
            elif e.dxftype() == "LINE":
                for pt in [e.dxf.start, e.dxf.end]:
                    min_x, min_y = min(min_x, pt.x), min(min_y, pt.y)
                    max_x, max_y = max(max_x, pt.x), max(max_y, pt.y)
                count += 1
            elif e.dxftype() in ("TEXT", "MTEXT"):
                x, y = e.dxf.insert.x, e.dxf.insert.y
                min_x, min_y = min(min_x, x), min(min_y, y)
                max_x, max_y = max(max_x, x), max(max_y, y)
                count += 1
            elif e.dxftype() == "LWPOLYLINE":
                for pt in e.get_points():
                    min_x, min_y = min(min_x, pt[0]), min(min_y, pt[1])
                    max_x, max_y = max(max_x, pt[0]), max(max_y, pt[1])
                count += 1
            elif e.dxftype() == "CIRCLE":
                cx, cy, r = e.dxf.center.x, e.dxf.center.y, e.dxf.radius
                min_x, min_y = min(min_x, cx - r), min(min_y, cy - r)
                max_x, max_y = max(max_x, cx + r), max(max_y, cy + r)
                count += 1
            elif e.dxftype() == "ARC":
                cx, cy = e.dxf.center.x, e.dxf.center.y
                r = e.dxf.radius
                min_x, min_y = min(min_x, cx - r), min(min_y, cy - r)
                max_x, max_y = max(max_x, cx + r), max(max_y, cy + r)
                count += 1
        except Exception:
            pass

    if count == 0:
        return None
    return {
        "min": (round(min_x, 2), round(min_y, 2)),
        "max": (round(max_x, 2), round(max_y, 2)),
        "width": round(max_x - min_x, 2),
        "height": round(max_y - min_y, 2),
        "sampled_entities": count,
    }


def get_layouts(doc):
    """Get layout/paper info."""
    layouts = {}
    for layout in doc.layouts:
        name = layout.name
        info = {"name": name}
        try:
            if hasattr(layout.dxf_layout, "dxf"):
                dxf = layout.dxf_layout.dxf
                if hasattr(dxf, "paper_width"):
                    info["paper_width"] = dxf.paper_width
                if hasattr(dxf, "paper_height"):
                    info["paper_height"] = dxf.paper_height
                if hasattr(dxf, "plot_paper_size"):
                    info["plot_paper_size"] = dxf.plot_paper_size
        except Exception:
            pass
        layouts[name] = info
    return layouts


def normalize_text(t):
    """Normalize text for comparison."""
    t = re.sub(r"\\P|\\p[^;]*;", " ", t)  # MTEXT paragraph breaks
    t = re.sub(r"\\[A-Za-z][^;]*;", "", t)  # MTEXT formatting codes
    t = re.sub(r"\{|\}", "", t)  # braces
    t = re.sub(r"\s+", " ", t).strip()
    return t


def print_separator(title):
    print(f"\n{'='*80}")
    print(f"  {title}")
    print(f"{'='*80}")


def main():
    print("Loading DXF files...")
    ref_doc = load_dxf(REF_PATH)
    gen_doc = load_dxf(GEN_PATH)
    print(f"  Reference: {REF_PATH}")
    print(f"  Generated: {GEN_PATH}")

    # =========================================================================
    # A. Block Definitions
    # =========================================================================
    print_separator("A. BLOCK DEFINITIONS")

    ref_blocks = get_block_info(ref_doc)
    gen_blocks = get_block_info(gen_doc)

    ref_names = set(ref_blocks.keys())
    gen_names = set(gen_blocks.keys())

    # Filter out internal blocks (starting with *)
    ref_user = {n for n in ref_names if not n.startswith("*")}
    gen_user = {n for n in gen_names if not n.startswith("*")}

    print(f"\n  Reference: {len(ref_user)} user blocks, {len(ref_names - ref_user)} internal")
    print(f"  Generated: {len(gen_user)} user blocks, {len(gen_names - gen_user)} internal")

    print(f"\n  --- Reference blocks ---")
    for name in sorted(ref_user):
        info = ref_blocks[name]
        types_str = ", ".join(f"{k}:{v}" for k, v in sorted(info["entity_types"].items()))
        print(f"    {name:40s}  entities={info['entity_count']:3d}  [{types_str}]")

    print(f"\n  --- Generated blocks ---")
    for name in sorted(gen_user):
        info = gen_blocks[name]
        types_str = ", ".join(f"{k}:{v}" for k, v in sorted(info["entity_types"].items()))
        print(f"    {name:40s}  entities={info['entity_count']:3d}  [{types_str}]")

    only_ref = ref_user - gen_user
    only_gen = gen_user - ref_user
    common = ref_user & gen_user

    if only_ref:
        print(f"\n  ONLY in reference ({len(only_ref)}):")
        for n in sorted(only_ref):
            print(f"    - {n}")
    if only_gen:
        print(f"\n  ONLY in generated ({len(only_gen)}):")
        for n in sorted(only_gen):
            print(f"    - {n}")
    if common:
        print(f"\n  Common blocks ({len(common)}):")
        for n in sorted(common):
            r, g = ref_blocks[n], gen_blocks[n]
            match = "MATCH" if r["entity_count"] == g["entity_count"] else "DIFF"
            print(f"    {n:40s}  ref={r['entity_count']:3d}  gen={g['entity_count']:3d}  [{match}]")

    # =========================================================================
    # B. INSERT Entities (Block Usage)
    # =========================================================================
    print_separator("B. INSERT ENTITIES (Block Usage)")

    ref_inserts = get_inserts(ref_doc)
    gen_inserts = get_inserts(gen_doc)

    all_insert_names = sorted(set(ref_inserts.keys()) | set(gen_inserts.keys()))

    print(f"\n  {'Block Name':40s}  {'Ref Count':>10s}  {'Gen Count':>10s}  {'Diff':>6s}")
    print(f"  {'-'*40}  {'-'*10}  {'-'*10}  {'-'*6}")
    for name in all_insert_names:
        rc = len(ref_inserts.get(name, []))
        gc = len(gen_inserts.get(name, []))
        diff = gc - rc
        diff_str = f"+{diff}" if diff > 0 else str(diff) if diff != 0 else "="
        print(f"  {name:40s}  {rc:10d}  {gc:10d}  {diff_str:>6s}")

    # Detailed comparison for key blocks
    key_blocks = ["MCCB", "RCCB", "DP_ISOL", "DP ISOL", "ISOLATOR", "SP_MCBH", "MCB"]
    # Find actual matching names
    all_names_lower = {n.lower(): n for n in all_insert_names}

    for key in ["MCCB", "RCCB", "DP_ISOL", "DP ISOL", "ISOLATOR", "MCB", "SP_MCBH", "METER",
                 "CT", "ELCB", "BUSBAR", "EARTH", "NEUTRAL"]:
        matches = [n for n in all_insert_names if key.lower() in n.lower()]
        if matches:
            print(f"\n  --- Detail: *{key}* blocks ---")
            for name in matches:
                print(f"\n    [{name}]")
                if name in ref_inserts:
                    print(f"      Reference ({len(ref_inserts[name])}):")
                    for ins in ref_inserts[name]:
                        print(f"        pos={ins['position']}  scale=({ins['xscale']},{ins['yscale']})  rot={ins['rotation']}  layer={ins['layer']}")
                else:
                    print(f"      Reference: NOT PRESENT")
                if name in gen_inserts:
                    print(f"      Generated ({len(gen_inserts[name])}):")
                    for ins in gen_inserts[name]:
                        print(f"        pos={ins['position']}  scale=({ins['xscale']},{ins['yscale']})  rot={ins['rotation']}  layer={ins['layer']}")
                else:
                    print(f"      Generated: NOT PRESENT")

    # =========================================================================
    # C. Text Content Comparison
    # =========================================================================
    print_separator("C. TEXT CONTENT COMPARISON")

    ref_texts_direct, ref_texts_blocks = get_all_texts_recursive(ref_doc)
    gen_texts_direct, gen_texts_blocks = get_all_texts_recursive(gen_doc)

    print(f"\n  Reference: {len(ref_texts_direct)} direct texts, {len(ref_texts_blocks)} texts in blocks")
    print(f"  Generated: {len(gen_texts_direct)} direct texts, {len(gen_texts_blocks)} texts in blocks")

    # Normalize and compare direct texts
    ref_normalized = {}  # normalized -> original
    gen_normalized = {}
    for t in ref_texts_direct:
        norm = normalize_text(t["text"]).lower()
        if norm:
            ref_normalized[norm] = t["text"]
    for t in gen_texts_direct:
        norm = normalize_text(t["text"]).lower()
        if norm:
            gen_normalized[norm] = t["text"]

    ref_set = set(ref_normalized.keys())
    gen_set = set(gen_normalized.keys())

    only_in_ref = ref_set - gen_set
    only_in_gen = gen_set - ref_set
    common_texts = ref_set & gen_set

    print(f"\n  Common texts (normalized): {len(common_texts)}")
    print(f"  Only in reference: {len(only_in_ref)}")
    print(f"  Only in generated: {len(only_in_gen)}")

    if only_in_ref:
        print(f"\n  --- Texts ONLY in reference (missing from generated) ---")
        for norm in sorted(only_in_ref):
            orig = ref_normalized[norm]
            print(f"    \"{orig}\"")

    if only_in_gen:
        print(f"\n  --- Texts ONLY in generated (extra) ---")
        for norm in sorted(only_in_gen):
            orig = gen_normalized[norm]
            print(f"    \"{orig}\"")

    # Special focus: busbar, circuit, ratings, phase labels
    categories = {
        "Busbar labels": r"bus|bar|db\d",
        "Circuit descriptions": r"circuit|ckt|c/|lighting|socket|aircon|spare|water|heater",
        "Ratings": r"\d+\s*a\b|\d+\s*at|\d+\s*kw|\d+\s*mm",
        "Phase labels": r"^[lrby][123]$|phase|l1|l2|l3|red|yellow|blue|neutral|earth",
    }
    for cat_name, pattern in categories.items():
        ref_match = {n: ref_normalized[n] for n in ref_set if re.search(pattern, n, re.I)}
        gen_match = {n: gen_normalized[n] for n in gen_set if re.search(pattern, n, re.I)}
        print(f"\n  --- {cat_name} ---")
        print(f"    Reference ({len(ref_match)}):")
        for n in sorted(ref_match):
            marker = " " if n in gen_set else " [MISSING]"
            print(f"      \"{ref_match[n]}\"{marker}")
        print(f"    Generated ({len(gen_match)}):")
        for n in sorted(gen_match):
            marker = " " if n in ref_set else " [EXTRA]"
            print(f"      \"{gen_match[n]}\"{marker}")

    # =========================================================================
    # D. Geometry Statistics
    # =========================================================================
    print_separator("D. GEOMETRY STATISTICS")

    ref_counts = get_entity_counts(ref_doc)
    gen_counts = get_entity_counts(gen_doc)
    all_types = sorted(set(ref_counts.keys()) | set(gen_counts.keys()))

    print(f"\n  {'Entity Type':20s}  {'Reference':>10s}  {'Generated':>10s}  {'Diff':>8s}")
    print(f"  {'-'*20}  {'-'*10}  {'-'*10}  {'-'*8}")
    ref_total = gen_total = 0
    for etype in all_types:
        rc = ref_counts.get(etype, 0)
        gc = gen_counts.get(etype, 0)
        ref_total += rc
        gen_total += gc
        diff = gc - rc
        diff_str = f"+{diff}" if diff > 0 else str(diff) if diff != 0 else "="
        print(f"  {etype:20s}  {rc:10d}  {gc:10d}  {diff_str:>8s}")
    print(f"  {'TOTAL':20s}  {ref_total:10d}  {gen_total:10d}  {gen_total-ref_total:>+8d}")

    # Layer usage
    print(f"\n  --- Layer Usage ---")
    ref_layers = get_layer_usage(ref_doc)
    gen_layers = get_layer_usage(gen_doc)
    all_layers = sorted(set(ref_layers.keys()) | set(gen_layers.keys()))

    print(f"\n  {'Layer':30s}  {'Reference':>10s}  {'Generated':>10s}")
    print(f"  {'-'*30}  {'-'*10}  {'-'*10}")
    for layer in all_layers:
        rc = ref_layers.get(layer, 0)
        gc = gen_layers.get(layer, 0)
        print(f"  {layer:30s}  {rc:10d}  {gc:10d}")

    # =========================================================================
    # E. Overall Structure
    # =========================================================================
    print_separator("E. OVERALL STRUCTURE")

    # Layouts
    ref_layouts = get_layouts(ref_doc)
    gen_layouts = get_layouts(gen_doc)
    print(f"\n  --- Layouts ---")
    print(f"  Reference layouts: {list(ref_layouts.keys())}")
    for name, info in ref_layouts.items():
        print(f"    {name}: {info}")
    print(f"  Generated layouts: {list(gen_layouts.keys())}")
    for name, info in gen_layouts.items():
        print(f"    {name}: {info}")

    # Extents
    ref_ext = get_extents(ref_doc)
    gen_ext = get_extents(gen_doc)
    print(f"\n  --- Drawing Extents (Bounding Box) ---")
    if ref_ext:
        print(f"  Reference: min={ref_ext['min']}  max={ref_ext['max']}  size={ref_ext['width']} x {ref_ext['height']}")
    if gen_ext:
        print(f"  Generated: min={gen_ext['min']}  max={gen_ext['max']}  size={gen_ext['width']} x {gen_ext['height']}")

    # DXF version
    print(f"\n  --- DXF Version ---")
    print(f"  Reference: {ref_doc.dxfversion}  ({ref_doc.acad_release})")
    print(f"  Generated: {gen_doc.dxfversion}  ({gen_doc.acad_release})")

    # Total entities
    print(f"\n  --- Total Modelspace Entities ---")
    print(f"  Reference: {ref_total}")
    print(f"  Generated: {gen_total}")

    print(f"\n{'='*80}")
    print(f"  COMPARISON COMPLETE")
    print(f"{'='*80}")


if __name__ == "__main__":
    main()
