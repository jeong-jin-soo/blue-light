#!/usr/bin/env python3
"""
SLD DXF 일괄 추출 스크립트.

data/sld-info/slds-dxf/ 하위 모든 DXF 파일(DWG에서 변환)을 파싱하여
정형화된 JSON 데이터베이스를 생성한다. (AI/API 호출 없이 순수 프로그래밍 방식)

Usage:
    cd blue-light-ai
    .venv/bin/python scripts/extract_sld_database_dwg.py
"""

from __future__ import annotations

import json
import logging
import re
import sys
from pathlib import Path
from typing import Any, Optional

import ezdxf

# ─── Config ───────────────────────────────────────────────────────────
BASE_DIR = Path(__file__).resolve().parent.parent
DXF_DIR = BASE_DIR / "data" / "sld-info" / "slds-dxf"
OUTPUT_JSON = BASE_DIR / "data" / "sld-info" / "sld_database_dwg.json"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)


# ─── MTEXT Cleaning ──────────────────────────────────────────────────

def clean_mtext(raw: str) -> str:
    """Strip DXF MTEXT formatting codes, returning clean text.

    Handles:
      - {\\W0.75;...}  -> inner text
      - {\\W1;...}     -> inner text
      - {\\W0.85;...}  -> inner text
      - {\\W0.65;...}  -> inner text
      - {\\W0.7;...}   -> inner text
      - {\\W0.8;...}   -> inner text
      - {\\W0.95;...}  -> inner text
      - \\C7;           -> colour code (remove)
      - \\A1;           -> alignment (remove)
      - \\Q5;           -> oblique angle (remove)
      - \\P             -> newline
      - \\pxqc;         -> center alignment (remove)
      - \\pxql;         -> left alignment (remove)
    """
    if not raw:
        return ""
    text = raw

    # Extract text from width-factor braces: {\W0.75;content} -> content
    # Must handle nested braces carefully; use a loop
    while True:
        m = re.search(r'\{\\W[\d.]+;([^}]*)\}', text)
        if not m:
            break
        text = text[:m.start()] + m.group(1) + text[m.end():]

    # Remove colour codes like \C7;
    text = re.sub(r'\\C\d+;', '', text)
    # Remove alignment codes
    text = re.sub(r'\\A\d+;', '', text)
    # Remove oblique angle codes
    text = re.sub(r'\\Q\d+;', '', text)
    # Remove paragraph alignment codes (e.g., \pxqc; \pxql; \pxt8; \ptz;)
    text = re.sub(r'\\p[^;]*;', '', text)
    # Remove font/style switches: \Fromans|c134; etc.
    text = re.sub(r'\\F[^;]*;', '', text)
    # Replace \P with newline
    text = text.replace('\\P', '\n')
    # Remove any remaining backslash escapes that are formatting
    text = re.sub(r'\\[a-zA-Z]\d*;?', '', text)
    # Clean up whitespace
    text = text.strip()
    # Remove remaining braces
    text = text.replace('{', '').replace('}', '')
    return text.strip()


# ─── Entity Collection ────────────────────────────────────────────────

def collect_entities(msp) -> tuple[list[dict], list[dict], list[dict]]:
    """Collect TEXT, MTEXT, and INSERT entities from modelspace.

    Returns (text_entities, mtext_entities, insert_entities).
    Each entity is a dict with keys: text/name, x, y, (h).
    """
    texts = []
    mtexts = []
    inserts = []

    for entity in msp:
        etype = entity.dxftype()
        if etype == 'TEXT':
            try:
                texts.append({
                    'text': entity.dxf.text or '',
                    'x': entity.dxf.insert[0],
                    'y': entity.dxf.insert[1],
                    'h': entity.dxf.height,
                })
            except Exception:
                pass
        elif etype == 'MTEXT':
            try:
                raw_text = entity.text or ''
                mtexts.append({
                    'raw': raw_text,
                    'text': clean_mtext(raw_text),
                    'x': entity.dxf.insert[0],
                    'y': entity.dxf.insert[1],
                    'h': entity.dxf.char_height,
                })
            except Exception:
                pass
        elif etype == 'INSERT':
            try:
                inserts.append({
                    'name': entity.dxf.name,
                    'x': entity.dxf.insert[0],
                    'y': entity.dxf.insert[1],
                })
            except Exception:
                pass

    return texts, mtexts, inserts


# ─── Pattern Matching Helpers ─────────────────────────────────────────

# Circuit ID patterns
RE_CIRCUIT_3PHASE = re.compile(r'^L[123](?:S|P|ISO|PP)\d+$')
RE_CIRCUIT_1PHASE = re.compile(r'^[SP]\d+$')

# Breaker rating patterns
RE_BREAKER_RATING = re.compile(r'^([BCD])?(\d{1,4})A$', re.IGNORECASE)
RE_POLES = re.compile(r'^(SPN|DP|TPN|TP&N|4P|2P|3P)$', re.IGNORECASE)
RE_BREAKER_TYPE = re.compile(r'^(MCB|MCCB|ACB|ELCB)$', re.IGNORECASE)
RE_KA = re.compile(r'^(\d+)\s*[kK][aA]$')
RE_TYPE_CHAR = re.compile(r'^TYPE\s+([ABCD])$', re.IGNORECASE)

# Approved load
RE_APPROVED_LOAD = re.compile(
    r'APPROVED\s+LOAD\s*:?\s*([\d.]+)\s*(?:KVA|kVA)',
    re.IGNORECASE
)

# Busbar
RE_BUSBAR = re.compile(
    r'(\d+)A\s+(COMB|COPPER|TINNED COPPER)\s*BUSBAR',
    re.IGNORECASE
)

# Cable spec - matches common cable description patterns
RE_CABLE_SPEC = re.compile(
    r'(?:\d+\s*x\s*(?:\d+C\s+)?[\d.]+(?:mm[²2]|sqmm|mm)\s+'
    r'(?:PVC|XLPE|NEOPRENE))',
    re.IGNORECASE
)

# Load description
RE_LOAD_DESC = re.compile(
    r'^(\d+)\s+Nos?\b\.?\s+(.+)',
    re.IGNORECASE
)
RE_LOAD_DESC_SINGLE = re.compile(
    r'^1\s+No\.?\s+(.+)',
    re.IGNORECASE
)

# RCCB/ELCB patterns
RE_RCCB_COMBINED = re.compile(
    r'(\d+)A\s+(\d+P|DP|2P|4P)\s*\n?\s*RCCB\s*\(?\s*(\d+)\s*m[aA]\s*\)?',
    re.IGNORECASE
)
RE_RCCB_RATING = re.compile(r'RCCB\s*\(?\s*(\d+)\s*m[aA]\s*\)?', re.IGNORECASE)

# Main breaker MTEXT patterns
# Handles: "63A TPN\nMCB 10kA", "100A DP\nMCCB 25kA"
RE_MAIN_MCB = re.compile(
    r'(\d+)A\s+(DP|TPN|TP&N|4P|SPN|2P|3P)\s*\n?\s*(MCB|MCCB)\s+(\d+)\s*[kK][aA]',
    re.IGNORECASE
)
# Handles: "63A 4P MCB\n10kA TYPE C"
RE_MAIN_MCB_ALT = re.compile(
    r'(\d+)A\s+(\d+P|DP|TPN|TP&N|SPN)\s+MCB\s*\n?\s*(\d+)\s*[kK][aA]\s+TYPE\s+([ABCD])',
    re.IGNORECASE
)
# Handles: "150A TPN\nMCCB 36kA \nc/w SHUNT TRIP"
RE_MAIN_MCCB_COMPLEX = re.compile(
    r'(\d+)A\s+(TPN|TP&N|4P|DP)\s*\n?\s*(MCCB)\s+(\d+)\s*[kK][aA]',
    re.IGNORECASE
)
# Handles: "150A TPN MCCB (35KA)" — parenthesized kA rating
RE_MAIN_MCCB_PAREN = re.compile(
    r'(\d+)A\s+(TPN|TP&N|4P|DP|SPN|2P|3P)\s+(MCCB|ACB)\s*\((\d+)\s*[kK][aA]\)',
    re.IGNORECASE
)

# Isolator patterns
RE_ISOLATOR = re.compile(
    r'(\d+)A\s+(?:(\d+P|DP|TPN|4P|2P)\s+)?ISOLATOR',
    re.IGNORECASE
)

# DB label
RE_DB_LABEL = re.compile(r'^(\d+)A\s+(?:(DP|TPN)\s+)?DB$', re.IGNORECASE)

# KWH meter
RE_KWH = re.compile(r'K[wW]H\s*(?:METER)?', re.IGNORECASE)

# CT ratio
RE_CT_RATIO = re.compile(r'(\d+)/(\d+)A\s+CT', re.IGNORECASE)

# Supply source
RE_LANDLORD = re.compile(r'(?:FROM\s+)?LANDLORD\s+SUPPLY', re.IGNORECASE)
RE_RISER = re.compile(r'INCOMING\s+FROM\s+RISER', re.IGNORECASE)
RE_SUPPLY_FROM = re.compile(r'SUPPLY\s+FROM', re.IGNORECASE)

# Cable size extraction
RE_CABLE_SIZE = re.compile(
    r'(\d+)\s*x\s*(?:\d+C\s+)?([\d.]+)\s*(?:mm[²2]|sqmm|mm)',
    re.IGNORECASE
)
RE_CABLE_EARTH = re.compile(
    r'\+\s*([\d.]+)\s*(?:mm[²2]|sqmm|mm)',
    re.IGNORECASE
)

# Sub-DB references
RE_SUB_DB = re.compile(r'TO\s+(?:LEVEL|LVL)\s+\d+\s+DB|TO\s+\w+\s+DB', re.IGNORECASE)


def is_circuit_id(text: str) -> bool:
    """Check if text is a circuit ID."""
    t = text.strip()
    return bool(RE_CIRCUIT_3PHASE.match(t) or RE_CIRCUIT_1PHASE.match(t))


def classify_load_type(description: str) -> str:
    """Classify load type from description text."""
    desc_upper = description.upper()
    if any(k in desc_upper for k in ['LIGHT', 'LED', 'EXIT LIGHT', 'EMERGENCY LIGHT', 'FAN POINT']):
        return 'lighting'
    if any(k in desc_upper for k in ['AIRCOND', 'AIRCON', 'A/C', 'VRV', 'FCU', 'CASSETTE']):
        return 'aircon'
    if 'SPARE' in desc_upper:
        return 'spare'
    if any(k in desc_upper for k in ['MOTOR', 'PUMP']):
        return 'motor'
    if any(k in desc_upper for k in [
        'S/S/O', 'SOCKET', 'ISOLATOR', 'OVEN', 'HOB', 'HOOD',
        'DISH WASHER', 'WASHER', 'DRYER', 'HEATER', 'BIDET',
        'CCTV', 'WATER HEATER', '13A', '20A',
    ]):
        return 'power'
    return 'other'


def extract_qty_from_desc(description: str) -> Optional[int]:
    """Extract quantity from load description."""
    m = RE_LOAD_DESC.match(description)
    if m:
        return int(m.group(1))
    m = RE_LOAD_DESC_SINGLE.match(description)
    if m:
        return 1
    return None


def parse_cable_info(cable_text: str) -> dict:
    """Parse cable specification into structured data."""
    if not cable_text:
        return {
            'description': None,
            'size_mm2': None,
            'earth_mm2': None,
            'type': None,
        }

    # Clean newlines
    desc = cable_text.replace('\n', ' ').strip()

    # Extract cable size
    size_mm2 = None
    m = RE_CABLE_SIZE.search(desc)
    if m:
        size_mm2 = m.group(2)

    # Extract earth size
    earth_mm2 = None
    m = RE_CABLE_EARTH.search(desc)
    if m:
        earth_mm2 = m.group(1)

    # Determine cable type
    cable_type = None
    desc_upper = desc.upper()
    if 'XLPE' in desc_upper and 'PVC' in desc_upper:
        cable_type = 'XLPE/PVC'
    elif 'XLPE' in desc_upper:
        cable_type = 'XLPE'
    elif 'NEOPRENE' in desc_upper:
        cable_type = 'NEOPRENE'
    elif 'PVC' in desc_upper:
        cable_type = 'PVC'

    return {
        'description': desc,
        'size_mm2': size_mm2,
        'earth_mm2': earth_mm2,
        'type': cable_type,
    }


# ─── Spatial Helpers ──────────────────────────────────────────────────

def find_nearest_x(target_x: float, candidates: list[dict],
                    x_tolerance: float = 400) -> list[dict]:
    """Find candidates within x_tolerance of target_x."""
    return [c for c in candidates if abs(c['x'] - target_x) < x_tolerance]


def find_nearest_above(target_x: float, target_y: float,
                       candidates: list[dict],
                       x_tolerance: float = 400,
                       y_max_dist: float = 5000) -> Optional[dict]:
    """Find the nearest candidate above (higher Y) and within X tolerance."""
    nearby = [
        c for c in candidates
        if abs(c['x'] - target_x) < x_tolerance
        and c['y'] > target_y
        and (c['y'] - target_y) < y_max_dist
    ]
    if not nearby:
        return None
    return min(nearby, key=lambda c: c['y'] - target_y)


def find_nearest_below(target_x: float, target_y: float,
                       candidates: list[dict],
                       x_tolerance: float = 400,
                       y_max_dist: float = 5000) -> Optional[dict]:
    """Find the nearest candidate below (lower Y) and within X tolerance."""
    nearby = [
        c for c in candidates
        if abs(c['x'] - target_x) < x_tolerance
        and c['y'] < target_y
        and (target_y - c['y']) < y_max_dist
    ]
    if not nearby:
        return None
    return min(nearby, key=lambda c: target_y - c['y'])


# ─── Sub-circuit Breaker Parsing ──────────────────────────────────────

def parse_breaker_stack_text(texts_at_x: list[dict]) -> dict:
    """Parse stacked TEXT entities at the same X position for breaker info.

    Expects a vertical stack (decreasing Y): rating, poles, type, kA
    E.g.: B10A / SPN / MCB / 6kA
    """
    result = {
        'breaker_type': None,
        'breaker_rating_a': None,
        'breaker_poles': None,
        'breaker_ka': None,
        'breaker_characteristic': None,
    }

    for item in texts_at_x:
        t = item['text'].strip()

        # Rating (e.g., B10A, C20A, 32A)
        m = RE_BREAKER_RATING.match(t)
        if m:
            result['breaker_characteristic'] = (m.group(1) or '').upper() or None
            result['breaker_rating_a'] = int(m.group(2))
            continue

        # Poles
        m = RE_POLES.match(t)
        if m:
            result['breaker_poles'] = m.group(1).upper()
            continue

        # Type
        m = RE_BREAKER_TYPE.match(t)
        if m:
            result['breaker_type'] = m.group(1).upper()
            continue

        # kA rating
        m = RE_KA.match(t)
        if m:
            result['breaker_ka'] = int(m.group(1))
            continue

        # TYPE B/C/D
        m = RE_TYPE_CHAR.match(t)
        if m:
            result['breaker_characteristic'] = m.group(1).upper()
            continue

    # Default breaker type based on rating
    if result['breaker_type'] is None and result['breaker_rating_a'] is not None:
        result['breaker_type'] = 'MCCB' if result['breaker_rating_a'] > 63 else 'MCB'

    return result


def parse_breaker_mtext(text: str) -> dict:
    r"""Parse MTEXT breaker info like '{\W0.75;B20A\PSPN\PMCB\P10kA}'.

    Also handles: 'B20A | SPN | MCB | 10kA' (pipe-delimited)
    and: 'B20A DP\PMCB 6kA' multi-line format
    """
    result = {
        'breaker_type': None,
        'breaker_rating_a': None,
        'breaker_poles': None,
        'breaker_ka': None,
        'breaker_characteristic': None,
    }

    if not text:
        return result

    # Split by newline or pipe
    parts = re.split(r'[\n|]+', text)
    parts = [p.strip() for p in parts if p.strip()]

    for part in parts:
        # Rating
        m = RE_BREAKER_RATING.search(part)
        if m:
            char = (m.group(1) or '').upper() or None
            if result['breaker_characteristic'] is None:
                result['breaker_characteristic'] = char
            result['breaker_rating_a'] = int(m.group(2))

            # Also check for inline poles: "B20A DP" or "32A DP"
            rest = part[m.end():].strip()
            mp = RE_POLES.match(rest)
            if mp:
                result['breaker_poles'] = mp.group(1).upper()
            continue

        # Poles
        mp = RE_POLES.match(part)
        if mp:
            result['breaker_poles'] = mp.group(1).upper()
            continue

        # Type
        mt = RE_BREAKER_TYPE.match(part)
        if mt:
            result['breaker_type'] = mt.group(1).upper()
            continue

        # kA rating (possibly with type)
        mk = re.match(r'(MCB|MCCB)\s+(\d+)\s*[kK][aA]', part, re.IGNORECASE)
        if mk:
            result['breaker_type'] = mk.group(1).upper()
            result['breaker_ka'] = int(mk.group(2))
            continue

        mk = RE_KA.match(part)
        if mk:
            result['breaker_ka'] = int(mk.group(1))
            continue

        # TYPE B/C/D
        mc = RE_TYPE_CHAR.match(part)
        if mc:
            result['breaker_characteristic'] = mc.group(1).upper()
            continue

        # Combined: "MCB 10kA" or "MCCB 25kA"
        mk2 = re.search(r'(MCB|MCCB)\s+(\d+)\s*[kK][aA]', part, re.IGNORECASE)
        if mk2:
            result['breaker_type'] = mk2.group(1).upper()
            result['breaker_ka'] = int(mk2.group(2))
            continue

    if result['breaker_type'] is None and result['breaker_rating_a'] is not None:
        result['breaker_type'] = 'MCCB' if result['breaker_rating_a'] > 63 else 'MCB'

    return result


# ─── Main Extraction Logic ────────────────────────────────────────────

def extract_supply_type(filename: str, all_texts: list[str]) -> tuple[str, int]:
    """Determine supply type and voltage from filename and text content."""
    fn_upper = filename.upper()
    if 'SINGLE PHASE' in fn_upper or 'SINGLE_PHASE' in fn_upper:
        return 'single_phase', 230
    if 'TPN' in fn_upper or 'RYB' in fn_upper:
        return 'three_phase', 400

    # Check text for clues
    for t in all_texts:
        tu = t.upper()
        if 'TPN' in tu or '4P' in tu or 'THREE PHASE' in tu:
            return 'three_phase', 400
        if '3-PHASE' in tu or '3 PHASE' in tu:
            return 'three_phase', 400

    # Check for 3-phase circuit IDs
    for t in all_texts:
        if RE_CIRCUIT_3PHASE.match(t.strip()):
            return 'three_phase', 400

    return 'single_phase', 230


def extract_main_rating_from_filename(filename: str) -> Optional[int]:
    """Extract main rating from filename like '63A TPN SLD 1 DWG.dxf'."""
    m = re.match(r'(\d+)A\s', filename)
    if m:
        return int(m.group(1))
    return None


def extract_kva(all_text_items: list[dict]) -> Optional[float]:
    """Extract approved load kVA from text entities."""
    for item in all_text_items:
        t = item.get('text', '') or item.get('raw', '')
        m = RE_APPROVED_LOAD.search(t)
        if m:
            try:
                return float(m.group(1))
            except ValueError:
                pass
    # Also check cleaned MTEXT
    for item in all_text_items:
        t = item.get('text', '')
        m = RE_APPROVED_LOAD.search(t)
        if m:
            try:
                return float(m.group(1))
            except ValueError:
                pass
    return None


def extract_busbar(all_text_items: list[dict]) -> list[dict]:
    """Extract busbar info; there may be multiple busbars."""
    busbars = []
    seen_positions = set()
    for item in all_text_items:
        t = item.get('text', '')
        m = RE_BUSBAR.search(t)
        if m:
            pos_key = (round(item['x'] / 1000), round(item['y'] / 1000))
            if pos_key not in seen_positions:
                seen_positions.add(pos_key)
                busbars.append({
                    'rating_a': int(m.group(1)),
                    'type': 'COMB' if 'COMB' in m.group(2).upper() else 'COPPER',
                    'x': item['x'],
                    'y': item['y'],
                })
    # Also check for tinned copper busbars in MTEXT
    for item in all_text_items:
        t = item.get('text', '')
        m2 = re.search(r'TINNED COPPER BUSBARS?', t, re.IGNORECASE)
        if m2 and not RE_BUSBAR.search(t):
            pos_key = (round(item['x'] / 1000), round(item['y'] / 1000))
            if pos_key not in seen_positions:
                seen_positions.add(pos_key)
                busbars.append({
                    'rating_a': None,
                    'type': 'COPPER',
                    'x': item['x'],
                    'y': item['y'],
                })
    return busbars


def extract_rccb_info(all_text_items: list[dict],
                      texts: list[dict]) -> list[dict]:
    """Extract RCCB/ELCB information.

    Handles both combined MTEXT ('63A 4P\\nRCCB (30mA)')
    and split TEXT entities ('100A 4P' / 'RCCB (30mA)').
    Returns a list of RCCB entries with positions.
    """
    rccbs = []
    seen_positions = set()

    # Combined MTEXT patterns
    for item in all_text_items:
        t = item.get('text', '')
        m = RE_RCCB_COMBINED.search(t)
        if m:
            pos_key = (round(item['x'] / 500), round(item['y'] / 500))
            if pos_key not in seen_positions:
                seen_positions.add(pos_key)
                rccbs.append({
                    'type': 'RCCB',
                    'rating_a': int(m.group(1)),
                    'poles': _parse_poles_int(m.group(2)),
                    'sensitivity_ma': int(m.group(3)),
                    'x': item['x'],
                    'y': item['y'],
                })

    # Split TEXT patterns: find 'RCCB (30mA)' or 'RCCB 10MA' entries
    for item in texts:
        t = item.get('text', '').strip()
        m = RE_RCCB_RATING.match(t)
        if m:
            pos_key = (round(item['x'] / 500), round(item['y'] / 500))
            if pos_key not in seen_positions:
                sensitivity = int(m.group(1))
                # Look for rating above: e.g., '100A 4P' or '63A 4P'
                rating_a = None
                poles = None
                for t2 in texts:
                    if (abs(t2['x'] - item['x']) < 400 and
                            t2['y'] > item['y'] and
                            (t2['y'] - item['y']) < 600):
                        rm = re.match(r'(\d+)A\s+(\d+P|DP|4P|2P|TPN)', t2['text'].strip(), re.IGNORECASE)
                        if rm:
                            rating_a = int(rm.group(1))
                            poles = _parse_poles_int(rm.group(2))
                            break
                seen_positions.add(pos_key)
                rccbs.append({
                    'type': 'RCCB',
                    'rating_a': rating_a,
                    'poles': poles,
                    'sensitivity_ma': sensitivity,
                    'x': item['x'],
                    'y': item['y'],
                })

    return rccbs


def _parse_poles_int(poles_str: str) -> Optional[int]:
    """Convert poles string to integer: '4P'->4, 'DP'->2, 'TPN'->4, 'SPN'->1, '2P'->2."""
    if not poles_str:
        return None
    p = poles_str.upper().strip()
    if p == 'DP' or p == '2P':
        return 2
    if p in ('TPN', 'TP&N', '4P'):
        return 4
    if p == 'SPN':
        return 1
    if p == '3P':
        return 3
    m = re.match(r'(\d+)P', p)
    if m:
        return int(m.group(1))
    return None


def _parse_poles_str(poles_str: str) -> Optional[str]:
    """Normalize poles to standard string format."""
    if not poles_str:
        return None
    p = poles_str.upper().strip()
    if p in ('DP', '2P'):
        return 'DP'
    if p in ('TPN', 'TP&N', '4P'):
        return 'TPN'
    if p in ('SPN', '1P'):
        return 'SPN'
    if p == '3P':
        return 'TPN'
    return p


def _compute_main_drawing_x_range(all_text_items: list[dict],
                                   busbar_y: Optional[float]) -> tuple[float, float]:
    """Compute the X range of the main drawing area (excluding title block).

    Title block text is typically at far-right X positions.
    We use the busbar and nearby entities to determine the main drawing area.
    """
    if busbar_y is not None:
        # Entities near the busbar Y are reliable indicators of drawing area
        near_busbar = [
            item for item in all_text_items
            if abs(item['y'] - busbar_y) < 5000
        ]
        if near_busbar:
            x_values = [item['x'] for item in near_busbar]
            return min(x_values) - 2000, max(x_values) + 2000

    # Fallback: use middle 80% of all X values to exclude title block outliers
    if all_text_items:
        x_values = sorted(item['x'] for item in all_text_items)
        n = len(x_values)
        lo = x_values[int(n * 0.1)]
        hi = x_values[int(n * 0.9)]
        return lo - 2000, hi + 2000

    return -float('inf'), float('inf')


def extract_main_breaker(all_text_items: list[dict],
                         busbar_y: Optional[float],
                         filename: str) -> dict:
    """Extract main breaker from text below the busbar.

    Main breaker is identified by being BELOW busbar Y level (lower Y value)
    and matching main breaker text patterns in MTEXT.

    Selection strategy (in priority order):
    1. Match breaker patterns below busbar, prefer highest rating (= main breaker)
    2. If no match, use filename-derived rating (more reliable than random fallback)
    3. Last resort: search entire drawing (excluding title block area)
    """
    result = {
        'type': None,
        'rating_a': None,
        'poles': None,
        'ka_rating': None,
        'characteristic': None,
    }

    # Determine the expected main rating from filename for validation
    fn_rating = extract_main_rating_from_filename(filename)

    main_breaker_candidates = []

    for item in all_text_items:
        t = item.get('text', '')
        y = item['y']
        x = item['x']

        # Skip items above busbar (those are sub-circuit breakers)
        if busbar_y is not None and y > busbar_y:
            continue

        # Try matching main breaker patterns
        # Pattern: "63A TPN\nMCB 10KA\nTYPE B"
        m = RE_MAIN_MCB.search(t)
        if m:
            main_breaker_candidates.append({
                'rating_a': int(m.group(1)),
                'poles': _parse_poles_str(m.group(2)),
                'type': m.group(3).upper(),
                'ka_rating': int(m.group(4)),
                'characteristic': None,
                'y': y,
                'x': x,
                'text': t,
            })
            continue

        # Pattern: "63A 4P MCB\n10kA TYPE C"
        m = RE_MAIN_MCB_ALT.search(t)
        if m:
            main_breaker_candidates.append({
                'rating_a': int(m.group(1)),
                'poles': _parse_poles_str(m.group(2)),
                'type': 'MCB',
                'ka_rating': int(m.group(3)),
                'characteristic': m.group(4).upper(),
                'y': y,
                'x': x,
                'text': t,
            })
            continue

        # Pattern: "150A TPN\nMCCB 36kA \nc/w SHUNT TRIP"
        m = RE_MAIN_MCCB_COMPLEX.search(t)
        if m:
            main_breaker_candidates.append({
                'rating_a': int(m.group(1)),
                'poles': _parse_poles_str(m.group(2)),
                'type': m.group(3).upper(),
                'ka_rating': int(m.group(4)),
                'characteristic': None,
                'y': y,
                'x': x,
                'text': t,
            })
            continue

        # Pattern: "150A TPN MCCB (35KA)" — parenthesized kA rating
        m = RE_MAIN_MCCB_PAREN.search(t)
        if m:
            main_breaker_candidates.append({
                'rating_a': int(m.group(1)),
                'poles': _parse_poles_str(m.group(2)),
                'type': m.group(3).upper(),
                'ka_rating': int(m.group(4)),
                'characteristic': None,
                'y': y,
                'x': x,
                'text': t,
            })
            continue

    # Also look for separate TEXT entities for main breaker below busbar
    # Pattern like separate lines: "100A 4P" / "MCCB 25kA" / "TYPE B"
    # These are stacked at same X, below busbar
    if busbar_y is not None:
        below_busbar = [
            item for item in all_text_items
            if item['y'] < busbar_y and (busbar_y - item['y']) < 5000
        ]
        # Group by X proximity
        x_groups: dict[int, list[dict]] = {}
        for item in below_busbar:
            x_key = round(item['x'] / 500)
            x_groups.setdefault(x_key, []).append(item)

        for x_key, group in x_groups.items():
            has_rating = False
            has_type = False
            rating_a = None
            poles = None
            breaker_type = None
            ka = None
            char = None

            for item in group:
                t = item.get('text', '').strip()
                # Rating + poles: "100A 4P" or "63A TPN"
                rm = re.match(r'(\d+)A\s+(DP|TPN|TP&N|4P|SPN|2P|3P)', t, re.IGNORECASE)
                if rm:
                    rating_a = int(rm.group(1))
                    poles = _parse_poles_str(rm.group(2))
                    has_rating = True
                    continue
                # Type: "MCCB 25kA" or "MCB 10kA"
                tm = re.match(r'(MCB|MCCB)\s+(\d+)\s*[kK][aA]', t, re.IGNORECASE)
                if tm:
                    breaker_type = tm.group(1).upper()
                    ka = int(tm.group(2))
                    has_type = True
                    continue
                # Also just "MCCB" alone
                tm2 = re.match(r'^(MCB|MCCB)\s*$', t, re.IGNORECASE)
                if tm2:
                    breaker_type = tm2.group(1).upper()
                    has_type = True
                    continue
                # kA alone
                km = RE_KA.match(t)
                if km:
                    ka = int(km.group(1))
                    continue
                # TYPE B/C/D
                cm = RE_TYPE_CHAR.match(t)
                if cm:
                    char = cm.group(1).upper()
                    continue

            if has_rating and has_type and rating_a is not None:
                # Determine Y as the average of the group
                avg_y = sum(i['y'] for i in group) / len(group)
                avg_x = sum(i['x'] for i in group) / len(group)
                main_breaker_candidates.append({
                    'rating_a': rating_a,
                    'poles': poles,
                    'type': breaker_type or 'MCB',
                    'ka_rating': ka,
                    'characteristic': char,
                    'y': avg_y,
                    'x': avg_x,
                    'text': f'{rating_a}A {poles} {breaker_type} {ka}kA',
                })

    if not main_breaker_candidates:
        # Fallback: search entire drawing for main breaker patterns,
        # but exclude title block area (far-right X, low Y)
        drawing_x_min, drawing_x_max = _compute_main_drawing_x_range(
            all_text_items, busbar_y
        )
        for item in all_text_items:
            t = item.get('text', '')
            x = item['x']
            # Skip entities outside the main drawing area (likely title block)
            if x < drawing_x_min or x > drawing_x_max:
                continue
            m = RE_MAIN_MCB.search(t)
            if m:
                main_breaker_candidates.append({
                    'rating_a': int(m.group(1)),
                    'poles': _parse_poles_str(m.group(2)),
                    'type': m.group(3).upper(),
                    'ka_rating': int(m.group(4)),
                    'characteristic': None,
                    'y': item['y'],
                    'x': x,
                    'text': t,
                })
            m2 = RE_MAIN_MCB_ALT.search(t)
            if m2:
                main_breaker_candidates.append({
                    'rating_a': int(m2.group(1)),
                    'poles': _parse_poles_str(m2.group(2)),
                    'type': 'MCB',
                    'ka_rating': int(m2.group(3)),
                    'characteristic': m2.group(4).upper(),
                    'y': item['y'],
                    'x': x,
                    'text': t,
                })
            # Also try parenthesized MCCB pattern in fallback
            m3 = RE_MAIN_MCCB_PAREN.search(t)
            if m3:
                main_breaker_candidates.append({
                    'rating_a': int(m3.group(1)),
                    'poles': _parse_poles_str(m3.group(2)),
                    'type': m3.group(3).upper(),
                    'ka_rating': int(m3.group(4)),
                    'characteristic': None,
                    'y': item['y'],
                    'x': x,
                    'text': t,
                })

    if not main_breaker_candidates:
        # Last resort: use filename rating
        if fn_rating:
            supply_type, _ = extract_supply_type(filename, [])
            result['rating_a'] = fn_rating
            result['type'] = 'MCCB' if fn_rating > 63 else 'MCB'
            result['poles'] = 'DP' if supply_type == 'single_phase' else 'TPN'
        return result

    # --- Selection strategy ---
    # The main breaker is typically the highest-rated breaker below the busbar.
    # When multiple candidates exist, prefer:
    # 1. The candidate whose rating matches the filename rating (strongest signal)
    # 2. The candidate with the highest rating (main breaker > sub-circuit breakers)
    # 3. Among ties, the one closest to (but below) the busbar

    # If we have a filename rating, strongly prefer candidates matching it
    if fn_rating:
        fn_matches = [c for c in main_breaker_candidates
                      if c['rating_a'] == fn_rating]
        if fn_matches:
            main_breaker_candidates = fn_matches

    # Pick the candidate with the highest rating (main breaker has highest rating)
    best = max(main_breaker_candidates, key=lambda c: c['rating_a'])

    # Check for TYPE characteristic in nearby text
    if best['characteristic'] is None:
        for item in all_text_items:
            t = item.get('text', '').strip()
            m = RE_TYPE_CHAR.match(t)
            if m and abs(item['y'] - best['y']) < 800:
                best['characteristic'] = m.group(1).upper()
                break

    result['type'] = best['type']
    result['rating_a'] = best['rating_a']
    result['poles'] = best['poles']
    result['ka_rating'] = best['ka_rating']
    result['characteristic'] = best['characteristic']

    return result


def extract_isolator(all_text_items: list[dict]) -> Optional[dict]:
    """Extract main isolator info."""
    for item in all_text_items:
        t = item.get('text', '')
        m = RE_ISOLATOR.search(t)
        if m:
            return {
                'rating_a': int(m.group(1)),
                'poles': _parse_poles_str(m.group(2)) if m.group(2) else None,
                'x': item['x'],
                'y': item['y'],
            }
    return None


def extract_metering(all_text_items: list[dict]) -> dict:
    """Extract metering section info."""
    result = {
        'type': None,
        'ct_ratio': None,
        'isolator_rating_a': None,
        'has_indicator_lights': False,
        'has_elr': False,
        'has_shunt_trip': False,
    }

    # Check for KWH meter
    has_kwh = False
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'KWH' in t:
            has_kwh = True
            if 'SP' in t:
                result['type'] = 'sp_meter'

    # Check for CT ratio
    for item in all_text_items:
        t = item.get('text', '')
        m = RE_CT_RATIO.search(t)
        if m:
            result['ct_ratio'] = f"{m.group(1)}/{m.group(2)}A"
            result['type'] = 'ct_meter'
            break

    if result['type'] is None and has_kwh:
        result['type'] = 'sp_meter'

    # Check for indicator lights
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'INDICATING LIGHT' in t:
            result['has_indicator_lights'] = True
            break

    # Check for shunt trip
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'SHUNT TRIP' in t:
            result['has_shunt_trip'] = True
            break

    # Check for ELR
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'ELR' in t or 'EARTH LEAKAGE RELAY' in t:
            result['has_elr'] = True
            break

    # Isolator rating (main isolator)
    isolator = extract_isolator(all_text_items)
    if isolator:
        result['isolator_rating_a'] = isolator['rating_a']

    return result


def extract_incoming_cable(all_text_items: list[dict],
                           busbar_y: Optional[float]) -> dict:
    """Extract incoming cable spec (below busbar, near meter board area)."""
    # Incoming cable is typically below busbar and near the metering section
    cable_candidates = []
    for item in all_text_items:
        t = item.get('text', '')
        if RE_CABLE_SPEC.search(t):
            # Prefer cables below the busbar (incoming)
            if busbar_y is not None and item['y'] < busbar_y:
                cable_candidates.append(item)
            elif busbar_y is None:
                cable_candidates.append(item)

    if not cable_candidates:
        # Fallback: take any cable near the bottom
        for item in all_text_items:
            t = item.get('text', '')
            if RE_CABLE_SPEC.search(t):
                cable_candidates.append(item)

    if not cable_candidates:
        return parse_cable_info(None)

    # Pick the one closest to the bottom (lowest Y = closest to meter board)
    # But not the sub-circuit cables (which are at the top)
    best = min(cable_candidates, key=lambda c: c['y'])
    return parse_cable_info(best.get('text', ''))


def extract_sub_circuit_cables(all_text_items: list[dict],
                               busbar_y: Optional[float]) -> list[dict]:
    """Extract sub-circuit cable specs (above busbar)."""
    cables = []
    for item in all_text_items:
        t = item.get('text', '')
        if RE_CABLE_SPEC.search(t):
            if busbar_y is not None and item['y'] > busbar_y:
                cables.append(item)
    return cables


def _find_nearest_breaker_column(
    cid_x: float,
    breaker_columns: list[dict],
) -> Optional[dict]:
    """Find the nearest breaker column for a circuit ID.

    Breaker columns are at specific X positions that serve a range of circuit IDs.
    A breaker column covers all circuit IDs up to halfway to the next breaker column.
    """
    if not breaker_columns:
        return None

    # Sort breaker columns by X
    sorted_cols = sorted(breaker_columns, key=lambda c: c['x'])

    # Find the nearest column
    best = None
    best_dist = float('inf')
    for col in sorted_cols:
        dist = abs(col['x'] - cid_x)
        if dist < best_dist:
            best_dist = dist
            best = col

    return best


def extract_sub_circuits(
    texts: list[dict],
    mtexts: list[dict],
    inserts: list[dict],
    busbars: list[dict],
    all_text_items: list[dict],
) -> list[dict]:
    """Extract all sub-circuits by finding circuit IDs and associating
    breaker info, load descriptions, and cable specs spatially.

    Uses a column-based approach: breaker specs often serve multiple circuits.
    We first build breaker columns, then assign each circuit to its nearest column.
    """

    # Find all circuit ID entities
    circuit_ids = []
    for item in texts:
        t = item['text'].strip()
        if is_circuit_id(t):
            circuit_ids.append({
                'id': t,
                'x': item['x'],
                'y': item['y'],
            })
    # Also check MTEXT for circuit IDs (some files use MTEXT for IDs)
    for item in mtexts:
        t = item['text'].strip()
        if is_circuit_id(t):
            circuit_ids.append({
                'id': t,
                'x': item['x'],
                'y': item['y'],
            })

    if not circuit_ids:
        return []

    # Find breaker rating texts/mtexts near circuit IDs
    # Determine the Y band for circuit IDs (they should be at similar Y)
    circuit_y_values = [c['y'] for c in circuit_ids]
    circuit_y_min = min(circuit_y_values)
    circuit_y_max = max(circuit_y_values)

    # Busbar Y (if available) — breakers are between busbar and descriptions
    busbar_max_y_val = max((b['y'] for b in busbars), default=0)

    # Breaker texts can be ABOVE or BELOW circuit IDs (varies by file layout)
    # Use a wide band: from busbar Y up to well above circuit IDs
    breaker_zone_y_min = min(busbar_max_y_val, circuit_y_min) - 500
    breaker_zone_y_max = circuit_y_max + 4000

    # Load descriptions are above circuit IDs (higher Y) — typically above
    # breakers too, but not always. Use circuit Y as the floor.
    desc_zone_y_min = circuit_y_max + 200  # just above circuit IDs

    # Collect breaker-rated TEXT entities in the breaker zone
    breaker_texts = []
    for item in texts:
        if breaker_zone_y_min < item['y'] < breaker_zone_y_max + 2000:
            t = item['text'].strip()
            if (RE_BREAKER_RATING.match(t) or RE_POLES.match(t) or
                    RE_BREAKER_TYPE.match(t) or RE_KA.match(t) or
                    RE_TYPE_CHAR.match(t)):
                breaker_texts.append(item)

    # Collect breaker-rated MTEXT entities (combined format)
    # Use re.MULTILINE to match per-line anchors in multi-line cleaned text
    # Allow trailing whitespace since some MTEXT entries have trailing spaces
    re_breaker_ml = re.compile(r'^([BCD])?(\d{1,4})A\s*$', re.IGNORECASE | re.MULTILINE)
    breaker_mtexts = []
    for item in mtexts:
        t = item['text'].strip()
        t_upper = t.upper()
        if breaker_zone_y_min < item['y'] < breaker_zone_y_max + 2000:
            # Multi-line MTEXT with breaker specs: B10A\nSPN\nMCB\n10kA
            has_rating = bool(re_breaker_ml.search(t))
            has_type = ('MCB' in t_upper or 'MCCB' in t_upper)
            has_poles = ('SPN' in t_upper or 'DP' in t_upper or
                         'TPN' in t_upper or '4P' in t_upper)
            has_ka = bool(re.search(r'\d+\s*kA', t, re.IGNORECASE))
            if has_rating and (has_type or has_ka or has_poles):
                breaker_mtexts.append(item)

    # Build breaker columns from TEXT entities:
    # Group breaker texts by X proximity (within 200mm = same column)
    breaker_text_columns = []
    if breaker_texts:
        sorted_bt = sorted(breaker_texts, key=lambda t: t['x'])
        current_group = [sorted_bt[0]]
        for bt in sorted_bt[1:]:
            if abs(bt['x'] - current_group[-1]['x']) < 200:
                current_group.append(bt)
            else:
                avg_x = sum(t['x'] for t in current_group) / len(current_group)
                parsed = parse_breaker_stack_text(current_group)
                parsed['x'] = avg_x
                breaker_text_columns.append(parsed)
                current_group = [bt]
        # Last group
        avg_x = sum(t['x'] for t in current_group) / len(current_group)
        parsed = parse_breaker_stack_text(current_group)
        parsed['x'] = avg_x
        breaker_text_columns.append(parsed)

    # Build breaker columns from MTEXT entities
    breaker_mtext_columns = []
    for bm in breaker_mtexts:
        parsed = parse_breaker_mtext(bm['text'])
        parsed['x'] = bm['x']
        breaker_mtext_columns.append(parsed)

    # Merge: prefer MTEXT columns (more complete), add TEXT columns
    all_breaker_columns = breaker_mtext_columns + breaker_text_columns

    # Collect load description TEXT/MTEXT entities (above breaker zone)
    desc_texts = []
    for item in texts:
        t = item['text'].strip()
        if item['y'] > desc_zone_y_min:
            if (RE_LOAD_DESC.match(t) or RE_LOAD_DESC_SINGLE.match(t) or
                    'SPARE' in t.upper() or 'TO ' in t.upper() or
                    'LIGHTING' in t.upper() or 'S/S/O' in t.upper() or
                    'SOCKET' in t.upper() or 'ISOLATOR' in t.upper() or
                    'BIDET' in t.upper() or 'HEATER' in t.upper() or
                    'AIRCOND' in t.upper() or 'FAN' in t.upper() or
                    'PUMP' in t.upper() or 'WATERPROOF' in t.upper()):
                desc_texts.append(item)
    for item in mtexts:
        t = item['text'].strip()
        if item['y'] > desc_zone_y_min:
            if (RE_LOAD_DESC.match(t) or RE_LOAD_DESC_SINGLE.match(t) or
                    'SPARE' in t.upper() or
                    'INDUSTRIAL SOCKET' in t.upper() or
                    'CEE-FORM' in t.upper() or
                    'LIGHTING' in t.upper()):
                desc_texts.append(item)

    # Cable specs above busbar
    busbar_max_y = max((b['y'] for b in busbars), default=0)
    sub_cables = extract_sub_circuit_cables(all_text_items, busbar_max_y - 1000)

    # Determine the typical spacing between circuit IDs to calibrate tolerances
    cid_x_sorted = sorted(set(round(c['x']) for c in circuit_ids))
    if len(cid_x_sorted) >= 2:
        spacings = [cid_x_sorted[i+1] - cid_x_sorted[i]
                    for i in range(len(cid_x_sorted) - 1)]
        typical_spacing = sorted(spacings)[len(spacings) // 2]  # median
    else:
        typical_spacing = 800

    # Description tolerance: slightly less than half the typical spacing
    desc_x_tolerance = max(typical_spacing * 0.6, 400)

    # Now, for each circuit ID, find associated data
    sub_circuits = []
    for cid in circuit_ids:
        circuit = {
            'id': cid['id'],
            'description': None,
            'breaker_type': None,
            'breaker_rating_a': None,
            'breaker_poles': None,
            'breaker_ka': None,
            'breaker_characteristic': None,
            'cable': None,
            'qty': None,
            'load_type': 'other',
        }

        # 1. Find breaker info — use nearest breaker column
        best_col = _find_nearest_breaker_column(cid['x'], all_breaker_columns)
        if best_col:
            for key in ['breaker_type', 'breaker_rating_a', 'breaker_poles',
                        'breaker_ka', 'breaker_characteristic']:
                if best_col.get(key) is not None:
                    circuit[key] = best_col[key]

        # 2. Find load description above circuit ID, at similar X
        best_desc = None
        best_desc_dist = float('inf')
        for desc in desc_texts:
            dx = abs(desc['x'] - cid['x'])
            if dx < desc_x_tolerance:
                dy = desc['y'] - cid['y']
                if 0 < dy < 15000 and dx < best_desc_dist:
                    best_desc = desc
                    best_desc_dist = dx

        if best_desc:
            desc_text = best_desc.get('text', '').strip()
            circuit['description'] = desc_text
            circuit['qty'] = extract_qty_from_desc(desc_text)
            circuit['load_type'] = classify_load_type(desc_text)

        # 3. Find cable spec — wider tolerance since cables span multiple circuits
        best_cable = None
        best_cable_dist = float('inf')
        for cable in sub_cables:
            dx = abs(cable['x'] - cid['x'])
            if dx < max(typical_spacing * 3, 2000) and dx < best_cable_dist:
                best_cable = cable
                best_cable_dist = dx

        if best_cable:
            cable_text = best_cable.get('text', '').replace('\n', ' ').strip()
            circuit['cable'] = cable_text

        sub_circuits.append(circuit)

    # Deduplicate by id (in case we picked up from both TEXT and MTEXT)
    seen_ids = set()
    unique_circuits = []
    for sc in sub_circuits:
        if sc['id'] not in seen_ids:
            seen_ids.add(sc['id'])
            unique_circuits.append(sc)

    # Sort by circuit ID
    def circuit_sort_key(sc):
        cid = sc['id']
        # Extract phase prefix and number
        m = re.match(r'(L[123])?(S|P|ISO|PP)(\d+)', cid)
        if m:
            phase = m.group(1) or ''
            ctype = m.group(2)
            num = int(m.group(3))
            type_order = {'S': 0, 'P': 1, 'ISO': 2, 'PP': 3}.get(ctype, 4)
            phase_order = {'L1': 0, 'L2': 1, 'L3': 2, '': 0}.get(phase, 3)
            return (type_order, num, phase_order)
        return (99, 0, cid)

    unique_circuits.sort(key=circuit_sort_key)
    return unique_circuits


def extract_special_features(all_text_items: list[dict],
                             filename: str,
                             busbars: list[dict],
                             circuit_ids: list[str]) -> dict:
    """Extract special features."""
    result = {
        'has_sub_db': False,
        'has_ats': False,
        'has_generator': False,
        'is_cable_extension': False,
        'sub_circuit_rows': 1,
    }

    # Cable extension detection
    fn_upper = filename.upper()
    if 'CABLE EXTENSION' in fn_upper:
        result['is_cable_extension'] = True

    # Sub-DB detection
    for item in all_text_items:
        t = item.get('text', '').upper()
        if RE_SUB_DB.search(t):
            result['has_sub_db'] = True
            break

    # ATS detection
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'ATS' in t or 'AUTOMATIC TRANSFER' in t:
            result['has_ats'] = True
            break

    # Generator detection
    for item in all_text_items:
        t = item.get('text', '').upper()
        if 'GENERATOR' in t or 'GENSET' in t:
            result['has_generator'] = True
            break

    # Sub-circuit rows: determined by number of busbars or Y-spread of circuits
    if len(busbars) >= 2:
        # Check if busbars are at significantly different X positions
        busbar_x_values = sorted(set(round(b['x'] / 3000) for b in busbars))
        if len(busbar_x_values) >= 2:
            result['sub_circuit_rows'] = 2
        else:
            result['sub_circuit_rows'] = len(busbars)

    # Also check if there are multiple distinct groups of circuit IDs
    if circuit_ids:
        # If we have both S and P type circuits, and they're in different
        # X ranges, it's likely 2 rows
        s_circuits = [c for c in circuit_ids if 'S' in c and 'ISO' not in c]
        p_circuits = [c for c in circuit_ids if 'P' in c and 'PP' not in c]
        if s_circuits and p_circuits:
            result['sub_circuit_rows'] = 2

    return result


def extract_notes(all_text_items: list[dict]) -> str:
    """Extract notes from text entities (supply source, locations, etc.)."""
    notes_parts = []

    for item in all_text_items:
        t = item.get('text', '').strip()
        tu = t.upper()

        if RE_LANDLORD.search(tu):
            notes_parts.append('FROM LANDLORD SUPPLY')
        elif RE_RISER.search(tu):
            notes_parts.append('INCOMING FROM RISER')
        elif RE_SUPPLY_FROM.search(tu):
            notes_parts.append(t)

        # Isolator location notes
        if 'LOCATED' in tu and 'ISOLATOR' not in tu and 'METER' not in tu:
            if 'INSIDE' in tu or 'PREMISES' in tu:
                notes_parts.append(t)

    # Deduplicate
    seen = set()
    unique_notes = []
    for n in notes_parts:
        n_clean = n.strip()
        if n_clean and n_clean.upper() not in seen:
            seen.add(n_clean.upper())
            unique_notes.append(n_clean)

    return '; '.join(unique_notes) if unique_notes else None


# ─── Main File Extraction ─────────────────────────────────────────────

def extract_single_dxf(dxf_path: Path) -> dict:
    """Extract SLD data from a single DXF file."""
    filename = dxf_path.name
    base_name = filename.replace(' DWG.dxf', '')

    doc = ezdxf.readfile(str(dxf_path))
    msp = doc.modelspace()

    texts, mtexts, inserts = collect_entities(msp)

    # Build a combined list of all text items for global searches
    all_text_items = []
    for t in texts:
        all_text_items.append({
            'text': t['text'],
            'x': t['x'],
            'y': t['y'],
            'h': t['h'],
            'source': 'TEXT',
        })
    for m in mtexts:
        all_text_items.append({
            'text': m['text'],
            'raw': m['raw'],
            'x': m['x'],
            'y': m['y'],
            'h': m['h'],
            'source': 'MTEXT',
        })

    all_plain_texts = [item.get('text', '') for item in all_text_items]

    # 1. Supply type & voltage
    supply_type, voltage = extract_supply_type(filename, all_plain_texts)

    # 2. kVA
    kva = extract_kva(all_text_items)

    # If kVA not found, calculate from main rating
    fn_rating = extract_main_rating_from_filename(filename)
    if kva is None and fn_rating is not None:
        if supply_type == 'single_phase':
            kva = round(fn_rating * 230 / 1000, 3)
        else:
            kva = round(fn_rating * 400 * 1.732 / 1000, 3)

    # 3. Busbars
    busbars = extract_busbar(all_text_items)

    # Find the highest busbar Y for spatial reference
    busbar_y_max = max((b['y'] for b in busbars), default=None)

    # 4. Main breaker
    main_breaker = extract_main_breaker(all_text_items, busbar_y_max, filename)

    # 5. RCCB/ELCB
    rccb_list = extract_rccb_info(all_text_items, texts)

    # Pick the "main" RCCB (lowest Y = closest to meter board)
    elcb_info = {
        'type': None,
        'rating_a': None,
        'poles': None,
        'sensitivity_ma': None,
    }
    if rccb_list:
        # If there are RCCBs below busbar, prefer those (main RCCB)
        main_rccbs = [r for r in rccb_list if busbar_y_max and r['y'] < busbar_y_max]
        if not main_rccbs:
            main_rccbs = rccb_list
        best_rccb = min(main_rccbs, key=lambda r: r['y'])
        elcb_info = {
            'type': best_rccb['type'],
            'rating_a': best_rccb['rating_a'],
            'poles': best_rccb['poles'],
            'sensitivity_ma': best_rccb['sensitivity_ma'],
        }

    # 6. Busbar (pick the primary/first one)
    busbar_info = {'rating_a': None, 'type': None}
    if busbars:
        busbar_info = {
            'rating_a': busbars[0]['rating_a'],
            'type': busbars[0]['type'],
        }

    # 7. Metering
    metering = extract_metering(all_text_items)
    # If isolator not found in metering, check main breaker rating
    if metering['isolator_rating_a'] is None and fn_rating:
        isolator = extract_isolator(all_text_items)
        if isolator:
            metering['isolator_rating_a'] = isolator['rating_a']

    # 8. Incoming cable
    incoming_cable = extract_incoming_cable(all_text_items, busbar_y_max)

    # 9. Sub-circuits
    sub_circuits = extract_sub_circuits(
        texts, mtexts, inserts, busbars, all_text_items
    )

    # 10. Special features
    circuit_ids = [sc['id'] for sc in sub_circuits]
    special_features = extract_special_features(
        all_text_items, filename, busbars, circuit_ids
    )

    # 11. Earth protection
    earth_protection = any(
        'E' == item.get('text', '').strip() or
        'EARTH' in item.get('text', '').upper()
        for item in all_text_items
    )
    if not earth_protection:
        # Check for E symbol in MTEXT
        earth_protection = any(
            item.get('raw', '') == '{\\W0.75;E}' or
            item.get('raw', '') == '{\\W1;E}'
            for item in all_text_items
            if item.get('source') == 'MTEXT'
        )

    # 12. Notes
    notes = extract_notes(all_text_items)

    # Build result
    result = {
        'supply_type': supply_type,
        'kva': kva,
        'voltage': voltage,
        'main_breaker': main_breaker,
        'incoming_cable': incoming_cable,
        'elcb': elcb_info,
        'busbar': busbar_info,
        'metering': metering,
        'earth_protection': earth_protection,
        'sub_circuits': sub_circuits,
        'special_features': special_features,
        'notes': notes,
        'source': 'dwg_parsed',
        'filename': f'{base_name}.pdf',
        'file_path': f'slds/{base_name}.pdf',
        'dwg_path': f'slds-dwg/{base_name} DWG.dwg',
        'dxf_path': f'slds-dxf/{filename}',
    }

    return result


# ─── Main ─────────────────────────────────────────────────────────────

def main():
    if not DXF_DIR.exists():
        log.error(f"DXF directory not found: {DXF_DIR}")
        sys.exit(1)

    dxf_files = sorted(DXF_DIR.glob("*.dxf"))
    total = len(dxf_files)
    log.info(f"Found {total} DXF files in {DXF_DIR}")

    # Load existing progress (resume support)
    database: dict[str, dict] = {}
    if OUTPUT_JSON.exists():
        try:
            existing = json.loads(OUTPUT_JSON.read_text(encoding='utf-8'))
            if isinstance(existing, list):
                for entry in existing:
                    dxf_path = entry.get('dxf_path', '')
                    if dxf_path:
                        key = Path(dxf_path).name
                        database[key] = entry
            log.info(f"  Loaded {len(database)} existing entries (resume mode)")
        except Exception as e:
            log.warning(f"  Could not load existing output: {e}")

    errors = []
    extracted_count = 0
    skipped_count = 0

    for i, dxf_path in enumerate(dxf_files, 1):
        filename = dxf_path.name

        # Skip already extracted
        if filename in database:
            log.info(f"[{i}/{total}] SKIP (already done): {filename}")
            skipped_count += 1
            continue

        log.info(f"[{i}/{total}] Extracting: {filename}")
        try:
            data = extract_single_dxf(dxf_path)
            database[filename] = data
            extracted_count += 1

            sc_count = len(data.get('sub_circuits', []))
            mb = data.get('main_breaker', {})
            log.info(
                f"  -> {data['supply_type']}, {data['kva']}kVA, "
                f"{sc_count} sub-circuits, "
                f"main={mb.get('rating_a')}A {mb.get('type')}"
            )

            # Save progress after each successful extraction
            db_list = list(database.values())
            OUTPUT_JSON.write_text(
                json.dumps(db_list, indent=2, ensure_ascii=False),
                encoding='utf-8',
            )

        except Exception as e:
            error_msg = f"{type(e).__name__}: {e}"
            log.error(f"  ERROR: {error_msg}")
            errors.append({'filename': filename, 'error': error_msg})

    # Final save
    db_list = list(database.values())
    OUTPUT_JSON.write_text(
        json.dumps(db_list, indent=2, ensure_ascii=False),
        encoding='utf-8',
    )

    # Summary
    log.info("")
    log.info("=" * 60)
    log.info(f"Total DXF files:  {total}")
    log.info(f"Newly extracted:  {extracted_count}")
    log.info(f"Skipped (resume): {skipped_count}")
    log.info(f"Errors:           {len(errors)}")
    log.info(f"Total in DB:      {len(db_list)}")
    log.info(f"Output: {OUTPUT_JSON}")
    log.info("=" * 60)

    if errors:
        log.warning(f"\n{len(errors)} errors occurred:")
        for err in errors:
            log.warning(f"  - {err['filename']}: {err['error']}")

    # Print quick stats for each file
    log.info("\n--- Per-file Summary ---")
    for entry in sorted(db_list, key=lambda e: e.get('filename', '')):
        fn = entry.get('filename', '?')
        st = entry.get('supply_type', '?')
        kva = entry.get('kva', '?')
        sc = len(entry.get('sub_circuits', []))
        mb = entry.get('main_breaker', {})
        sf = entry.get('special_features', {})
        log.info(
            f"  {fn}: {st}, {kva}kVA, {sc} circuits, "
            f"main={mb.get('rating_a','?')}A {mb.get('type','?')}, "
            f"sub_db={sf.get('has_sub_db','?')}, "
            f"cable_ext={sf.get('is_cable_extension','?')}"
        )

    return len(errors)


if __name__ == "__main__":
    exit_code = main()
    sys.exit(min(exit_code, 1) if exit_code else 0)
