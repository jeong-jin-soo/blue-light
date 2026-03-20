#!/usr/bin/env python3
"""
Extract circuit schedule data and SLD topology from DWG files (converted to DXF).
Uses raw DXF parsing since LibreDWG conversion produces non-standard DXF that ezdxf can't read.

Usage:
    cd blue-light-ai
    source .venv/bin/activate
    python scripts/extract_dwg_circuit_data.py
"""

import os
import re
import json
from collections import Counter, defaultdict

DXF_DIR_CONVERTED = "/tmp/dwg_dxf_converted"
DXF_DIR_CLEAN = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                              "data", "sld-info", "slds-dxf")
DWG_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)),
                       "data", "sld-info", "sld-dwg-old")
OUTPUT_FILE = os.path.join(DWG_DIR, "circuit_data_extracted.json")


# ── DXF raw parser ──────────────────────────────────────────────────────

def extract_text_from_dxf_raw(filepath):
    """Parse DXF text file directly to extract TEXT and MTEXT entities."""
    with open(filepath, 'r', errors='replace') as f:
        lines = f.readlines()

    texts = []
    i = 0
    while i < len(lines) - 1:
        code = lines[i].strip()
        value = lines[i + 1].strip()

        if code == '0' and value in ('TEXT', 'MTEXT'):
            entity_type = value
            entity_data = {}
            i += 2
            while i < len(lines) - 1:
                c = lines[i].strip()
                v = lines[i + 1].strip()
                if c == '0':
                    break
                try:
                    code_int = int(c)
                except ValueError:
                    i += 2
                    continue
                # Group code 1 may appear multiple times in MTEXT (continuation)
                if code_int == 1:
                    entity_data[1] = entity_data.get(1, '') + v
                elif code_int == 3:
                    # MTEXT additional text chunks (before code 1)
                    entity_data[1] = entity_data.get(1, '') + v
                else:
                    entity_data[code_int] = v
                i += 2

            text = entity_data.get(1, '')
            try:
                x = float(entity_data.get(10, 0))
                y = float(entity_data.get(20, 0))
            except (ValueError, TypeError):
                x, y = 0, 0
            if text:
                texts.append({'text': text, 'x': x, 'y': y, 'type': entity_type})
        elif code == '0' and value == 'INSERT':
            # Track block inserts for ATTRIB extraction
            i += 2
            entity_data = {}
            while i < len(lines) - 1:
                c = lines[i].strip()
                v = lines[i + 1].strip()
                if c == '0':
                    # Check for ATTRIB entities within this INSERT
                    if v == 'ATTRIB':
                        attrib_data = {}
                        i += 2
                        while i < len(lines) - 1:
                            ac = lines[i].strip()
                            av = lines[i + 1].strip()
                            if ac == '0':
                                break
                            try:
                                attrib_data[int(ac)] = av
                            except ValueError:
                                pass
                            i += 2
                        att_text = attrib_data.get(1, '')
                        if att_text:
                            try:
                                ax = float(attrib_data.get(10, 0))
                                ay = float(attrib_data.get(20, 0))
                            except (ValueError, TypeError):
                                ax, ay = 0, 0
                            texts.append({'text': att_text, 'x': ax, 'y': ay,
                                          'type': f"ATTRIB:{entity_data.get(2, '?')}"})
                    else:
                        break
                else:
                    try:
                        entity_data[int(c)] = v
                    except ValueError:
                        pass
                    i += 2
        else:
            i += 2

    return texts


def extract_text_from_dxf_ezdxf(filepath):
    """Parse clean DXF file using ezdxf library (for properly formatted DXF)."""
    import ezdxf
    texts = []
    try:
        doc = ezdxf.readfile(filepath)
    except Exception:
        from ezdxf import recover
        doc, _ = recover.readfile(filepath)

    msp = doc.modelspace()
    for e in msp:
        if e.dxftype() == 'TEXT':
            texts.append({
                'text': e.dxf.text,
                'x': e.dxf.insert.x,
                'y': e.dxf.insert.y,
                'type': 'TEXT',
            })
        elif e.dxftype() == 'MTEXT':
            texts.append({
                'text': e.text,
                'x': e.dxf.insert.x,
                'y': e.dxf.insert.y,
                'type': 'MTEXT',
            })

    # Also extract ATTRIBs from INSERT entities
    for e in msp:
        if e.dxftype() == 'INSERT':
            for a in e.attribs:
                texts.append({
                    'text': a.dxf.text,
                    'x': a.dxf.insert.x,
                    'y': a.dxf.insert.y,
                    'type': f'ATTRIB:{e.dxf.name}',
                })

    return texts


def clean_mtext(s):
    """Remove MTEXT formatting codes to get plain text."""
    # Remove common MTEXT formatting
    s = re.sub(r'\\p[^;]*;', '', s)       # paragraph formatting
    s = re.sub(r'\\[fFHWACQT][^;]*;', '', s)  # font/height/width etc
    s = re.sub(r'\{\\[^}]*?;', '', s)     # inline formatting start
    s = re.sub(r'\\P', '\n', s)           # paragraph break
    s = re.sub(r'\\[\\{}]', '', s)        # escaped braces
    s = s.replace('{', '').replace('}', '')
    s = re.sub(r'\\[A-Za-z][0-9.]*;?', '', s)  # remaining format codes
    return s.strip()


# ── Pattern matchers ────────────────────────────────────────────────────

BREAKER_RATING_RE = re.compile(r'\b[BC]?(\d+)\s*A\b', re.I)
CABLE_SIZE_RE = re.compile(r'(\d+(?:\.\d+)?)\s*(?:sq\s*mm|mm[²2]|sqmm)', re.I)
KVA_RE = re.compile(r'(\d+(?:\.\d+)?)\s*kVA', re.I)
KW_RE = re.compile(r'(\d+(?:\.\d+)?)\s*kW', re.I)
VOLTAGE_RE = re.compile(r'(\d{3})\s*V\b')
PHASE_3_RE = re.compile(r'(3[- ]?phase|three[- ]?phase|TPN|3P|TP\b)', re.I)
PHASE_1_RE = re.compile(r'(1[- ]?phase|single[- ]?phase|SPN|SP\b)', re.I)

SUPPLY_PATTERNS = {
    'landlord': re.compile(r'(?:FROM\s+)?LANDLORD', re.I),
    'sp_group': re.compile(r'SP\s+(?:GROUP|POWER)|FROM\s+SP\b', re.I),
    'building_riser': re.compile(r'(?:FROM\s+)?(?:BUILDING\s+)?RISER|MAIN\s+RISER', re.I),
    'sub_main': re.compile(r'(?:FROM\s+)?SUB[- ]?MAIN|FROM\s+MSB|FROM\s+MDB', re.I),
}

BREAKER_TYPE_RE = re.compile(r'\b(MCB|MCCB|ACB|ELCB|RCCB|RCD|RCBO)\b', re.I)
METER_TYPE_RE = re.compile(r'\b(KWH|CT\s+METER|CT\s+METERING|DIRECT\s+METER)', re.I)
ISOLATOR_RE = re.compile(r'(\d+)\s*A\s*(?:SPN|TPN|TP|SP)?\s*ISOLAT', re.I)
BUSBAR_RE = re.compile(r'(?:BUSBAR|BUS\s*BAR)\s*(?:RATED?\s*)?(\d+)\s*A', re.I)
DB_RATING_RE = re.compile(r'(\d+)\s*A\s*(?:TPN|SPN|TP|SP)?\s*(?:DB|DISTRIBUTION|D\.?B\.?)', re.I)
APPROVED_LOAD_RE = re.compile(r'APPROVED\s+LOAD\s*[:=]?\s*(\d+(?:\.\d+)?)\s*kVA', re.I)
LOCATED_RE = re.compile(r'LOCATED\s+(?:IN(?:SIDE)?|AT)\s+(.+)', re.I)
UNIT_RE = re.compile(r'(?:UNIT|#)\s*([A-Z]?\d{1,3}[- ]?\d{0,4}[A-Z]?)', re.I)

# Circuit ID patterns
CIRCUIT_ID_RE = re.compile(r'^[LCP]\d[SPL]\d+$|^C\d+$|^ISO\d+$|^SP\d+$', re.I)

# Load description keywords
LOAD_KEYWORDS = {
    'lighting': re.compile(r'L(?:TG|IGHT)|FLUO|LED|HALOGEN|PENDANT|DOWN\s*LIGHT|SPOT|TRACK|T5|T8|PLC', re.I),
    'power': re.compile(r'S/?S/?O|SOCKET|POWER\s*POINT|13A|GPO', re.I),
    'aircon': re.compile(r'AIRCON|AIR\s*CON|A/?C|FCU', re.I),
    'water_heater': re.compile(r'WATER\s*HEAT|W/?H|HEATER|GEYSER|INSTANT', re.I),
    'spare': re.compile(r'^SPARE$', re.I),
    'exhaust_fan': re.compile(r'EXHAUST|EXT?\s*FAN|VENTIL', re.I),
    'emergency': re.compile(r'EMERGE|EXIT|EMERG', re.I),
    'signage': re.compile(r'SIGN|NEON|DISPLAY', re.I),
    'kitchen': re.compile(r'KITCHEN|COOKER|OVEN|FRIDGE|DISHWASH|REFRIGER', re.I),
    'fire_alarm': re.compile(r'FIRE|ALARM|SMOKE|FA\b', re.I),
    'isolator': re.compile(r'ISOLAT|ISO\b', re.I),
}


# ── Main extraction logic ──────────────────────────────────────────────

def extract_circuit_data(texts, filename):
    """Extract structured circuit data from text entities."""
    result = {
        'filename': filename,
        'supply_info': {},
        'main_breaker': {},
        'elcb_rccb': [],
        'meter_board': {},
        'busbar': {},
        'circuits': [],
        'db_info': {},
        'location_info': {},
        'title_block': {},
        'raw_text_count': len(texts),
    }

    all_text_plain = []
    for t in texts:
        plain = clean_mtext(t['text']) if t['type'] == 'MTEXT' else t['text']
        all_text_plain.append({
            'text': plain,
            'x': t['x'],
            'y': t['y'],
            'type': t['type'],
            'raw': t['text'],
        })

    combined_text = '\n'.join(t['text'] for t in all_text_plain)

    # ── Supply info ─────────────────────────────────────────────────
    for supply_type, pattern in SUPPLY_PATTERNS.items():
        if pattern.search(combined_text):
            result['supply_info']['source'] = supply_type
            break
    else:
        result['supply_info']['source'] = 'unknown'

    # Phase detection
    if PHASE_3_RE.search(combined_text):
        result['supply_info']['phase'] = '3-phase'
    elif PHASE_1_RE.search(combined_text):
        result['supply_info']['phase'] = 'single-phase'
    else:
        # Heuristic: if we see TPN breakers or circuit IDs like L1/L2/L3
        if re.search(r'\bTPN\b', combined_text):
            result['supply_info']['phase'] = '3-phase'
        elif re.search(r'\bL[123][SP]', combined_text):
            result['supply_info']['phase'] = '3-phase'
        else:
            result['supply_info']['phase'] = 'unknown'

    # Voltage
    voltages = VOLTAGE_RE.findall(combined_text)
    if voltages:
        result['supply_info']['voltage'] = list(set(voltages))

    # ── Breaker types and ratings ───────────────────────────────────
    breaker_types = BREAKER_TYPE_RE.findall(combined_text)
    breaker_type_counts = Counter(t.upper() for t in breaker_types)
    result['breaker_type_distribution'] = dict(breaker_type_counts)

    # ── Main breaker detection ──────────────────────────────────────
    for t in all_text_plain:
        txt = t['text'].upper()
        if any(k in txt for k in ['MAIN', 'INCOMING', 'INCOMER']):
            ratings = BREAKER_RATING_RE.findall(txt)
            types = BREAKER_TYPE_RE.findall(txt)
            if ratings:
                result['main_breaker']['rating'] = max(int(r) for r in ratings)
            if types:
                result['main_breaker']['type'] = types[0].upper()

    # If no explicit main breaker found, look for largest breaker
    if not result['main_breaker']:
        all_ratings = [int(r) for r in BREAKER_RATING_RE.findall(combined_text)]
        if all_ratings:
            max_rating = max(all_ratings)
            result['main_breaker']['rating_inferred'] = max_rating

    # ── ELCB/RCCB ──────────────────────────────────────────────────
    for t in all_text_plain:
        txt = t['text'].upper()
        if 'ELCB' in txt or 'RCCB' in txt or 'RCD' in txt:
            ratings = BREAKER_RATING_RE.findall(txt)
            sensitivity = re.findall(r'(\d+)\s*mA', txt, re.I)
            elcb_info = {'type': 'ELCB' if 'ELCB' in txt else ('RCCB' if 'RCCB' in txt else 'RCD')}
            if ratings:
                elcb_info['rating_A'] = int(ratings[0])
            if sensitivity:
                elcb_info['sensitivity_mA'] = int(sensitivity[0])
            if elcb_info not in result['elcb_rccb']:
                result['elcb_rccb'].append(elcb_info)

    # ── Meter board ─────────────────────────────────────────────────
    for t in all_text_plain:
        txt = t['text'].upper()
        if 'METER' in txt:
            meter_match = METER_TYPE_RE.search(txt)
            if meter_match:
                result['meter_board']['meter_type'] = meter_match.group(1).upper()
            iso_match = ISOLATOR_RE.search(txt)
            if iso_match:
                result['meter_board']['isolator_rating'] = int(iso_match.group(1))

    # ── Busbar ──────────────────────────────────────────────────────
    busbar_match = BUSBAR_RE.search(combined_text)
    if busbar_match:
        result['busbar']['rating_A'] = int(busbar_match.group(1))

    # ── DB info ─────────────────────────────────────────────────────
    db_match = DB_RATING_RE.search(combined_text)
    if db_match:
        result['db_info']['rating_A'] = int(db_match.group(1))

    approved_match = APPROVED_LOAD_RE.search(combined_text)
    if approved_match:
        result['db_info']['approved_load_kVA'] = float(approved_match.group(1))

    kva_all = KVA_RE.findall(combined_text)
    if kva_all:
        result['db_info']['kva_mentions'] = sorted(set(float(k) for k in kva_all))

    # ── Location info ───────────────────────────────────────────────
    located_match = LOCATED_RE.search(combined_text)
    if located_match:
        result['location_info']['located'] = located_match.group(1).strip()

    unit_matches = UNIT_RE.findall(combined_text)
    if unit_matches:
        result['location_info']['units'] = list(set(unit_matches))

    # Look for address-like text
    for t in all_text_plain:
        txt = t['text']
        if re.search(r'(?:BLK|BLOCK|LORONG|LOR|ROAD|RD|STREET|ST|AVENUE|AVE|JALAN|JLN|TAMPINES|JURONG|BEDOK|ANG MO KIO|PASIR RIS|BUKIT|TANJONG|GEYLANG|MARINA|ORCHARD|BUGIS|SERANGOON|CLEMENTI|WOODLANDS)', txt, re.I):
            if len(txt) > 5 and len(txt) < 200:
                result['location_info']['address_texts'] = result['location_info'].get('address_texts', [])
                if txt not in result['location_info'].get('address_texts', []):
                    result['location_info']['address_texts'].append(txt)

    # ── Title block info ────────────────────────────────────────────
    # Look for text in typical title block Y-region (usually at bottom)
    all_y = [t['y'] for t in all_text_plain]
    if all_y:
        min_y = min(all_y)
        max_y = max(all_y)
        y_range = max_y - min_y
        # Title block is typically in bottom 20% or has specific keywords
        for t in all_text_plain:
            txt = t['text']
            if re.search(r'(?:DRAWING|DWG|DRG)\s*(?:NO|NUMBER|#)', txt, re.I):
                result['title_block']['drawing_number'] = txt
            if re.search(r'(?:PROJECT|JOB)\s*(?:NAME|TITLE|:)', txt, re.I):
                result['title_block']['project_name'] = txt
            if re.search(r'(?:LICENSED|LEW|L\.E\.W|ELECTRICAL\s+WORKER)', txt, re.I):
                result['title_block']['lew'] = txt
            if re.search(r'(?:DATE|DATED)\s*[:=]?\s*\d', txt, re.I):
                result['title_block']['date'] = txt
            if re.search(r'i2R|BLUE\s*LIGHT|BMET|BOYUTEI', txt, re.I):
                result['title_block']['company'] = txt

    # ── Circuits extraction ─────────────────────────────────────────
    # Group text by Y-coordinate proximity to identify circuit rows
    # Circuit IDs pattern: L1S1, C1, ISO1, etc.
    circuit_ids = []
    circuit_texts_by_y = defaultdict(list)

    for t in all_text_plain:
        txt = t['text'].strip()
        if CIRCUIT_ID_RE.match(txt):
            circuit_ids.append({
                'id': txt,
                'x': t['x'],
                'y': t['y'],
            })
            circuit_texts_by_y[round(t['y'], 0)].append(t)

    # For each circuit ID, find associated breaker info and load description
    # by looking at text near the same X coordinate but different Y
    y_tolerance = 100  # Group texts within this Y range

    for cid in circuit_ids:
        circuit = {'id': cid['id'], 'x': cid['x']}

        # Find texts at same X (within tolerance) at different Y levels
        nearby_texts = []
        for t in all_text_plain:
            if abs(t['x'] - cid['x']) < 200:  # Same column
                nearby_texts.append(t)

        nearby_texts.sort(key=lambda t: -t['y'])

        for t in nearby_texts:
            txt = t['text']
            # Breaker info
            breaker_match = re.search(r'[BC]?(\d+)\s*A', txt)
            if breaker_match and 'breaker_rating' not in circuit:
                circuit['breaker_rating'] = txt.strip()

            # Cable size
            cable_match = CABLE_SIZE_RE.search(txt)
            if cable_match and 'cable_size' not in circuit:
                circuit['cable_text'] = txt.strip()

            # Load description
            for load_type, pattern in LOAD_KEYWORDS.items():
                if pattern.search(txt):
                    circuit['load_type'] = load_type
                    circuit['load_description'] = txt.strip()
                    break

        result['circuits'].append(circuit)

    # ── Also extract breaker specs from MTEXT blocks ────────────────
    breaker_specs = []
    for t in all_text_plain:
        raw = t.get('raw', t['text'])
        plain = t['text']
        # Pattern like "B10A SPN MCB 6kA" or "B20A TPN MCB 10kA"
        spec_match = re.search(r'[BC]?(\d+)\s*A\s*(SPN|TPN|TP|SP)?\s*(MCB|MCCB|ELCB|RCCB)\s*(\d+)\s*kA', plain, re.I)
        if spec_match:
            breaker_specs.append({
                'rating_A': int(spec_match.group(1)),
                'poles': spec_match.group(2).upper() if spec_match.group(2) else 'unknown',
                'type': spec_match.group(3).upper(),
                'kA': int(spec_match.group(4)),
                'x': t['x'],
                'y': t['y'],
            })

    result['breaker_specs'] = breaker_specs

    # ── Cable specifications ────────────────────────────────────────
    cable_specs = []
    for t in all_text_plain:
        txt = t['text']
        if CABLE_SIZE_RE.search(txt) and ('PVC' in txt.upper() or 'XLPE' in txt.upper() or 'CONDUIT' in txt.upper() or 'TRUNKING' in txt.upper()):
            cable_specs.append({
                'text': txt.strip(),
                'x': t['x'],
                'y': t['y'],
            })

    result['cable_specs'] = cable_specs

    # ── All unique breaker ratings ──────────────────────────────────
    all_ratings = sorted(set(int(r) for r in BREAKER_RATING_RE.findall(combined_text)))
    result['all_breaker_ratings_A'] = all_ratings

    # ── All cable sizes ─────────────────────────────────────────────
    all_cables = sorted(set(float(c) for c in CABLE_SIZE_RE.findall(combined_text)))
    result['all_cable_sizes_sqmm'] = all_cables

    # ── Infer DB rating from filename if not found ──────────────────
    if not result['db_info'].get('rating_A'):
        fname_match = re.search(r'(\d+)A', filename)
        if fname_match:
            result['db_info']['rating_A_from_filename'] = int(fname_match.group(1))

    # ── Count total circuits ────────────────────────────────────────
    result['circuit_count'] = len(circuit_ids)

    # ── Collect all load descriptions ───────────────────────────────
    load_types = Counter()
    for t in all_text_plain:
        txt = t['text']
        for load_type, pattern in LOAD_KEYWORDS.items():
            if pattern.search(txt):
                load_types[load_type] += 1
    result['load_type_distribution'] = dict(load_types)

    return result


# ── Summary generation ──────────────────────────────────────────────────

def generate_summary(all_results):
    """Generate aggregate summary statistics."""
    summary = {
        'total_files': len(all_results),
        'successful_extractions': sum(1 for r in all_results if r.get('raw_text_count', 0) > 0),
    }

    # DB rating distribution
    db_ratings = Counter()
    for r in all_results:
        rating = r.get('db_info', {}).get('rating_A') or r.get('db_info', {}).get('rating_A_from_filename')
        if rating:
            db_ratings[f"{rating}A"] += 1
        else:
            db_ratings['unknown'] += 1
    summary['db_rating_distribution'] = dict(db_ratings.most_common())

    # Supply source distribution
    supply_sources = Counter()
    for r in all_results:
        src = r.get('supply_info', {}).get('source', 'unknown')
        supply_sources[src] += 1
    summary['supply_source_distribution'] = dict(supply_sources.most_common())

    # Phase distribution
    phase_types = Counter()
    for r in all_results:
        phase = r.get('supply_info', {}).get('phase', 'unknown')
        phase_types[phase] += 1
    summary['phase_distribution'] = dict(phase_types.most_common())

    # Circuit count distribution
    circuit_counts = []
    for r in all_results:
        cc = r.get('circuit_count', 0)
        if cc > 0:
            circuit_counts.append(cc)
    if circuit_counts:
        summary['circuit_count_stats'] = {
            'min': min(circuit_counts),
            'max': max(circuit_counts),
            'avg': round(sum(circuit_counts) / len(circuit_counts), 1),
            'total_files_with_circuits': len(circuit_counts),
        }

    # Load type distribution (aggregate)
    agg_load = Counter()
    for r in all_results:
        for lt, cnt in r.get('load_type_distribution', {}).items():
            agg_load[lt] += cnt
    summary['load_type_distribution'] = dict(agg_load.most_common())

    # Common breaker ratings
    all_breaker_ratings = Counter()
    for r in all_results:
        for spec in r.get('breaker_specs', []):
            all_breaker_ratings[f"{spec['rating_A']}A {spec.get('type', '')}"] += 1
        # Also from raw ratings
        for rating in r.get('all_breaker_ratings_A', []):
            all_breaker_ratings[f"{rating}A"] += 1
    summary['common_breaker_ratings'] = dict(all_breaker_ratings.most_common(20))

    # Common cable sizes
    all_cables = Counter()
    for r in all_results:
        for cs in r.get('all_cable_sizes_sqmm', []):
            all_cables[f"{cs}sqmm"] += 1
    summary['common_cable_sizes'] = dict(all_cables.most_common(15))

    # Breaker type distribution (MCB vs MCCB vs ELCB etc)
    breaker_types = Counter()
    for r in all_results:
        for bt, cnt in r.get('breaker_type_distribution', {}).items():
            breaker_types[bt] += cnt
    summary['breaker_type_distribution'] = dict(breaker_types.most_common())

    # ELCB/RCCB usage
    elcb_count = sum(1 for r in all_results if r.get('elcb_rccb'))
    summary['files_with_elcb_rccb'] = elcb_count

    # Meter board types
    meter_types = Counter()
    for r in all_results:
        mt = r.get('meter_board', {}).get('meter_type')
        if mt:
            meter_types[mt] += 1
    summary['meter_type_distribution'] = dict(meter_types.most_common())

    return summary


# ── Main ────────────────────────────────────────────────────────────────

def process_file(fpath, display_name, use_ezdxf=False):
    """Process a single DXF file and return extraction result."""
    if use_ezdxf:
        texts = extract_text_from_dxf_ezdxf(fpath)
    else:
        texts = extract_text_from_dxf_raw(fpath)
    result = extract_circuit_data(texts, display_name)
    return result


def print_result(result):
    """Print key findings for a file."""
    phase = result['supply_info'].get('phase', '?')
    source = result['supply_info'].get('source', '?')
    ccount = result.get('circuit_count', 0)
    db_rating = result.get('db_info', {}).get('rating_A') or \
                result.get('db_info', {}).get('rating_A_from_filename', '?')
    print(f"  Phase: {phase}, Source: {source}, Circuits: {ccount}, DB: {db_rating}A")
    if result.get('elcb_rccb'):
        print(f"  ELCB/RCCB: {result['elcb_rccb']}")
    if result.get('breaker_specs'):
        specs_summary = Counter()
        for s in result['breaker_specs']:
            specs_summary[f"{s['rating_A']}A {s.get('poles','')} {s['type']} {s['kA']}kA"] += 1
        print(f"  Breaker specs: {dict(specs_summary)}")


def main():
    all_results = []

    # ── Source 1: LibreDWG-converted DXF files (from sld-dwg-old/) ──────
    if os.path.isdir(DXF_DIR_CONVERTED):
        dxf_files = sorted(f for f in os.listdir(DXF_DIR_CONVERTED) if f.endswith('.dxf'))
        print(f"\n{'='*80}")
        print(f"SOURCE 1: LibreDWG-converted DWG files ({len(dxf_files)} files)")
        print(f"{'='*80}")

        for fname in dxf_files:
            fpath = os.path.join(DXF_DIR_CONVERTED, fname)
            dwg_name = fname.replace('.dxf', '.dwg')
            print(f"\nProcessing: {dwg_name}")
            try:
                result = process_file(fpath, dwg_name, use_ezdxf=False)
                result['source_dir'] = 'sld-dwg-old'
                all_results.append(result)
                print(f"  Extracted {result['raw_text_count']} text entities")
                print_result(result)
            except Exception as ex:
                print(f"  ERROR: {ex}")
                all_results.append({'filename': dwg_name, 'error': str(ex), 'source_dir': 'sld-dwg-old'})
    else:
        print(f"WARNING: Converted DXF dir not found: {DXF_DIR_CONVERTED}")

    # ── Source 2: Clean DXF files (from slds-dxf/) ──────────────────────
    if os.path.isdir(DXF_DIR_CLEAN):
        clean_dxf_files = sorted(f for f in os.listdir(DXF_DIR_CLEAN) if f.endswith('.dxf'))
        print(f"\n{'='*80}")
        print(f"SOURCE 2: Clean DXF template files ({len(clean_dxf_files)} files)")
        print(f"{'='*80}")

        for fname in clean_dxf_files:
            fpath = os.path.join(DXF_DIR_CLEAN, fname)
            print(f"\nProcessing: {fname}")
            try:
                result = process_file(fpath, fname, use_ezdxf=True)
                result['source_dir'] = 'slds-dxf'
                all_results.append(result)
                print(f"  Extracted {result['raw_text_count']} text entities")
                print_result(result)
            except Exception as ex:
                print(f"  ERROR: {ex}")
                all_results.append({'filename': fname, 'error': str(ex), 'source_dir': 'slds-dxf'})

    # Generate summary
    summary = generate_summary(all_results)

    # Save output
    output = {
        'extraction_date': '2026-03-16',
        'total_files': len(all_results),
        'summary': summary,
        'files': all_results,
    }

    with open(OUTPUT_FILE, 'w', encoding='utf-8') as f:
        json.dump(output, f, indent=2, ensure_ascii=False)

    print(f"\n{'='*80}")
    print(f"Results saved to: {OUTPUT_FILE}")
    print(f"{'='*80}")

    # Print summary
    print(f"\n{'='*80}")
    print("EXTRACTION SUMMARY")
    print(f"{'='*80}")
    print(f"\nTotal files: {summary['total_files']}")
    print(f"Successful extractions: {summary['successful_extractions']}")

    print(f"\n--- DB Rating Distribution ---")
    for k, v in summary['db_rating_distribution'].items():
        print(f"  {k}: {v} files")

    print(f"\n--- Supply Source Distribution ---")
    for k, v in summary['supply_source_distribution'].items():
        print(f"  {k}: {v} files")

    print(f"\n--- Phase Distribution ---")
    for k, v in summary['phase_distribution'].items():
        print(f"  {k}: {v} files")

    if summary.get('circuit_count_stats'):
        stats = summary['circuit_count_stats']
        print(f"\n--- Circuit Count Stats ---")
        print(f"  Min: {stats['min']}, Max: {stats['max']}, Avg: {stats['avg']}")
        print(f"  Files with circuits: {stats['total_files_with_circuits']}")

    print(f"\n--- Load Type Distribution (aggregate) ---")
    for k, v in summary['load_type_distribution'].items():
        print(f"  {k}: {v} mentions")

    print(f"\n--- Common Breaker Ratings ---")
    for k, v in list(summary['common_breaker_ratings'].items())[:15]:
        print(f"  {k}: {v} occurrences")

    print(f"\n--- Common Cable Sizes ---")
    for k, v in summary['common_cable_sizes'].items():
        print(f"  {k}: {v} occurrences")

    print(f"\n--- Breaker Type Distribution ---")
    for k, v in summary['breaker_type_distribution'].items():
        print(f"  {k}: {v} mentions")

    print(f"\n--- Meter Types ---")
    for k, v in summary['meter_type_distribution'].items():
        print(f"  {k}: {v} files")

    print(f"\nFiles with ELCB/RCCB: {summary['files_with_elcb_rccb']}")


if __name__ == '__main__':
    main()
