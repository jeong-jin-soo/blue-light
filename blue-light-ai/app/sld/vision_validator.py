"""Vision AI SLD 검증기.

생성된 SLD를 Gemini Vision으로 시각적 품질 검증.
두 가지 모드:
  1. Self-Review: 레퍼런스 없이 시각적 문제 탐지
  2. Reference Compare: 레퍼런스와 구조 비교

두 가지 처리 경로:
  - 경로 2 (서비스): 문제 → 파라미터 조정 → 재생성 (최대 3회)
  - 경로 3 (개발): 문제 → 개발자 리포트 → 코드 수정 → 룰 테스트 추가

Usage:
    # Self-Review (CLI)
    python -m app.sld.vision_validator --svg output/test.svg

    # Reference Compare
    python -m app.sld.vision_validator --svg output/test.svg --ref data/sld-info/slds/ref.pdf
"""

from __future__ import annotations

import json
import logging
import os
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Domain context — 오탐 방지
# ---------------------------------------------------------------------------

_ACCEPTED_PATTERNS_PATH = Path(__file__).resolve().parents[2] / "data" / "templates" / "vision_accepted_patterns.json"


def _load_accepted_patterns() -> list[dict]:
    """Load Singapore SLD accepted patterns from JSON file."""
    try:
        with open(_ACCEPTED_PATTERNS_PATH) as f:
            data = json.load(f)
        return data.get("accepted_patterns", [])
    except (FileNotFoundError, json.JSONDecodeError) as e:
        logger.warning("Failed to load accepted patterns: %s", e)
        return []


def _build_domain_context() -> str:
    """Build domain context section for Vision AI prompt.

    Loads accepted patterns from JSON and formats as prompt instructions.
    This prevents Gemini from flagging Singapore-standard conventions as issues.
    """
    patterns = _load_accepted_patterns()
    if not patterns:
        return ""

    lines = [
        "",
        "## Singapore SLD Domain Rules (Do NOT flag these as issues)",
        "",
        "This is a Singapore SLD following SP Group, SS 638, and local LEW conventions.",
        "The following are NORMAL and CORRECT — do NOT report them as problems:",
        "",
    ]
    for p in patterns:
        lines.append(f"- **{p['id']}**: {p['reason']}")

    lines.append("")
    lines.append("If you encounter any of the above patterns, skip them silently. "
                 "Only report genuine visual defects.")
    lines.append("")

    return "\n".join(lines)


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass
class VisionIssue:
    """Vision AI가 발견한 단일 문제."""
    category: str       # overlap, gap, symbol, label, spacing, missing, clipping
    description: str    # "Main breaker label overlaps ELCB symbol"
    severity: str       # critical, warning, info
    location: str = ""  # spine_main_breaker, subcircuit_row_1
    suggestion: str = ""  # "Increase spine_component_gap" (legacy)
    root_cause: str = ""  # WHY: "earth bar Y from wrong reference point"
    fix: str = ""         # HOW: "Calculate earth bar Y from per-DB box bottom"


@dataclass
class VisionReport:
    """Vision AI 검증 결과."""
    issues: list[VisionIssue] = field(default_factory=list)
    severity: str = "pass"     # pass, warning, fail
    score: float = 1.0         # 0.0 ~ 1.0
    summary: str = ""
    adjustments: dict[str, Any] = field(default_factory=dict)  # 경로 2: 파라미터 조정 제안
    matched_sections: list[str] = field(default_factory=list)  # 레퍼런스 비교 시 일치 섹션
    raw_response: str = ""     # Gemini 원본 응답 (디버깅용)

    @property
    def has_critical(self) -> bool:
        return any(i.severity == "critical" for i in self.issues)

    @property
    def issue_count(self) -> int:
        return len(self.issues)

    def to_dict(self) -> dict:
        d = asdict(self)
        del d["raw_response"]  # 큰 문자열 제외
        return d


# ---------------------------------------------------------------------------
# SVG → PNG conversion
# ---------------------------------------------------------------------------

def svg_to_png(svg_path: str | Path, dpi: int = 150) -> str:
    """SVG 파일을 PNG로 변환. PyMuPDF 사용.

    Returns: PNG 파일 경로 (svg_path와 같은 디렉토리, .png 확장자)
    """
    import fitz

    svg_path = Path(svg_path)
    png_path = svg_path.with_suffix(".png")

    doc = fitz.open(str(svg_path))
    page = doc.load_page(0)
    mat = fitz.Matrix(dpi / 72, dpi / 72)
    pix = page.get_pixmap(matrix=mat)
    pix.save(str(png_path))
    doc.close()

    logger.info("SVG → PNG: %s (%dx%d)", png_path.name, pix.width, pix.height)
    return str(png_path)


def pdf_to_png(pdf_path: str | Path, dpi: int = 150) -> str:
    """PDF 파일을 PNG로 변환. PyMuPDF 사용."""
    import fitz

    pdf_path = Path(pdf_path)
    png_path = pdf_path.with_suffix(".png")

    doc = fitz.open(str(pdf_path))
    page = doc.load_page(0)
    mat = fitz.Matrix(dpi / 72, dpi / 72)
    pix = page.get_pixmap(matrix=mat)
    pix.save(str(png_path))
    doc.close()

    return str(png_path)


def _crop_png(png_path: str, bbox_mm: tuple[float, float, float, float],
              page_w_mm: float = 420.0, page_h_mm: float = 297.0,
              dpi: int = 200) -> bytes:
    """PNG 이미지에서 mm 좌표 영역을 크롭하여 bytes로 반환.

    bbox_mm: (x_min, y_min, x_max, y_max) in mm coordinates (SLD coordinate system).
    SLD 좌표: Y는 아래(0)→위(297). PNG 좌표: Y는 위(0)→아래(height).
    """
    from PIL import Image
    import io

    img = Image.open(png_path)
    img_w, img_h = img.size

    # mm → pixel 변환
    px_per_mm_x = img_w / page_w_mm
    px_per_mm_y = img_h / page_h_mm

    x1 = int(bbox_mm[0] * px_per_mm_x)
    x2 = int(bbox_mm[2] * px_per_mm_x)
    # Y축 반전: SLD y=0(bottom) → PNG y=height
    y1 = int((page_h_mm - bbox_mm[3]) * px_per_mm_y)  # top in PNG
    y2 = int((page_h_mm - bbox_mm[1]) * px_per_mm_y)  # bottom in PNG

    # Clamp
    x1, x2 = max(0, x1), min(img_w, x2)
    y1, y2 = max(0, y1), min(img_h, y2)

    cropped = img.crop((x1, y1, x2, y2))
    buf = io.BytesIO()
    cropped.save(buf, format="PNG")
    return buf.getvalue()


def _get_detail_crop_regions(layout_result) -> list[dict]:
    """LayoutResult에서 정밀 검사가 필요한 영역을 자동 추출.

    작은 심볼이 밀집된 영역만 선택 — 전체 비교에서 놓칠 가능성이 높은 곳.
    """
    regions = []
    margin = 15.0  # mm

    # 1. CT metering (작은 hook 심볼)
    ct_names = {'CT', 'ELR', 'KWH_METER', 'SELECTOR_SWITCH', 'AMMETER',
                'VOLTMETER', 'BI_CONNECTOR', 'POTENTIAL_FUSE', 'INDICATOR_LIGHTS'}
    ct_comps = [(c.x, c.y) for c in layout_result.components if c.symbol_name in ct_names]
    if ct_comps:
        xs = [x for x, y in ct_comps]
        ys = [y for x, y in ct_comps]
        # Tight crop around CT hooks only — focused on hook shape/overlap
        regions.append({
            "name": "CT_HOOKS",
            "bbox": (min(xs) - margin, min(ys) - margin / 2, max(xs) + margin, max(ys) + margin / 2),
            "dpi": 400,
            "prompt_focus": """Examine the REFERENCE (Image 2) FIRST:
1. How many CT hook symbols are on the vertical spine line? What SHAPE are they (circles, half-ovals, flat hooks)?
2. What is their SIZE relative to other symbols?
3. Are all hooks clearly SEPARATED with gaps between them?

Now examine the GENERATED (Image 1):
4. Same questions — shape, size, separation.
5. Do ANY hooks overlap or touch each other in the generated?
6. Compare CT ratio labels, ELR label, ammeter/voltmeter range labels.""",
        })

    # BI Connector area — focused on left/right structure
    bi_comps = [(c.x, c.y) for c in layout_result.components if c.symbol_name == 'BI_CONNECTOR']
    if bi_comps:
        bi_x, bi_y = bi_comps[0]
        regions.append({
            "name": "BI_CONNECTOR",
            "bbox": (bi_x - 30, bi_y - 25, bi_x + 30, bi_y + 25),
            "dpi": 400,
            "prompt_focus": """Examine the REFERENCE (Image 2) FIRST:
1. Does the BI connector have a HORIZONTAL line passing through it (left to right)?
2. What components/connections are on the LEFT side of the BI connector horizontal line?
3. What components/connections are on the RIGHT side of the BI connector horizontal line?

Now examine the GENERATED (Image 1):
4. Does the BI connector have the same horizontal line structure?
5. Are the same components on left and right sides?
6. Report ANY structural difference in how the BI connector connects to surrounding components.""",
        })

    # 2. MSB↔DB2 연결 (feeder connection)
    if hasattr(layout_result, 'db_box_ranges') and len(layout_result.db_box_ranges) >= 2:
        r0 = layout_result.db_box_ranges[0]
        r1 = layout_result.db_box_ranges[1]
        # 두 DB 사이 영역
        x_min = r0.get('busbar_end_x', 180) - 20
        x_max = r1.get('busbar_start_x', 220) + 20
        y_min = min(r0.get('db_box_start_y', 80), r1.get('db_box_start_y', 80)) - margin
        y_max = max(r0.get('busbar_y_row', 170), r1.get('busbar_y_row', 130)) + margin
        regions.append({
            "name": "FEEDER_CONNECTION",
            "bbox": (x_min, y_min, x_max, y_max),
            "prompt_focus": "Feeder cable connection between MSB and DB2. Check: how the cable routes from MSB busbar to DB2 incoming, feeder MCB symbol, cable labels, 'SUPPLY FROM MSB' text, and connection line path. Compare route and layout with reference.",
        })

    return regions


_CROP_COMPARE_TEMPLATE = """\
You are an expert Singapore SLD inspector doing a DETAIL COMPARISON of a specific section.

**Image 1 = GENERATED SLD (cropped region)**
**Image 2 = REFERENCE SLD (same region cropped)**

## Focus Area: {region_name}

{prompt_focus}

## CRITICAL: Analyze the REFERENCE (Image 2) FIRST
Study the reference crop thoroughly, then check if the generated crop matches.

Report differences in:
1. Symbol SHAPE and SIZE (are symbols the same shape? same proportional size?)
2. Symbol POSITION (relative to each other and to the spine line)
3. Connection line ROUTING (same path? same direction?)
4. Label TEXT and POSITION
5. OVERLAP or CLIPPING issues

## Response Format (JSON only)
```json
{{
  "issues": [
    {{
      "category": "symbol|label|spacing|connection|overlap",
      "description": "What is different",
      "severity": "critical|warning|info",
      "location": "specific component",
      "root_cause": "Why this difference likely exists in the code",
      "fix": "Specific action to fix"
    }}
  ],
  "severity": "pass|warning|fail",
  "score": 0.0-1.0,
  "summary": "One-line summary"
}}
```
"""


# ---------------------------------------------------------------------------
# Gemini Vision API call
# ---------------------------------------------------------------------------

_SELF_REVIEW_TEMPLATE = """\
You are an expert Singapore SLD (Single Line Diagram) quality inspector.
Inspect the SLD image below and report ALL visual issues as JSON.
{domain_context}
## Inspection Checklist (check EVERY item)

### CRITICAL — Check these FIRST (these are the most common problems)
1. **CLIPPING/OVERFLOW**: Are ANY components, labels, lines, or symbols cut off or extending beyond the drawing border? Check ALL four edges. If the top of the diagram shows circuit labels or conductor lines that appear truncated or go beyond the border line, this is CRITICAL.
2. **SYMBOL OVERLAP**: Are any electrical symbols overlapping each other? Check CT hooks, breakers, and other spine components. Symbols must not share the same space.
3. **LINE THROUGH SYMBOL**: Do any connection lines pass through the body of a symbol (through MCB arc, MCCB box, RCCB, isolator)? Subcircuit breaker symbols must NOT have vertical lines passing through them.
4. **EMPTY SPACE**: Is there excessive unused space in any area while other areas are cramped? The content should use the drawing area proportionally.

### VISUAL QUALITY
5. **LABEL OVERLAP**: Labels overlapping other labels, cable spec text overlapping load names
6. **GAP**: Broken spine connections, disconnected busbar-to-subcircuit lines, missing connection segments
7. **SYMBOL**: Deformed circuit breaker arcs, missing CT hooks, broken earth symbol, incomplete symbols
8. **SPACING**: Abnormally tight or wide gaps between components, uneven subcircuit spacing
9. **LABEL**: Missing breaker ratings, truncated cable specs, missing phase labels (L1/L2/L3), missing poles info
10. **MISSING**: Expected sections not present. An SLD should have (bottom→top): incoming supply → meter board or isolator → main breaker → ELCB/RCCB → busbar → subcircuit breakers → DB box → earth bar

### MULTI-DB (if multiple distribution boards are present)
11. **DB LAYOUT**: Are multiple DBs placed side-by-side horizontally with clear separation?
12. **FEEDER CONNECTION**: Is the feeder cable from parent DB to child DB clearly drawn with correct labeling?
13. **PROTECTION GROUPS**: If per-phase RCCB groups exist, are they correctly placed with separate busbars?

## Response Format (JSON only)

```json
{{
  "issues": [
    {{
      "category": "overlap|gap|symbol|label|spacing|missing",
      "description": "Specific problem description",
      "severity": "critical|warning|info",
      "location": "section or area name",
      "suggestion": "How to fix (parameter name if applicable)"
    }}
  ],
  "severity": "pass|warning|fail",
  "score": 0.0-1.0,
  "summary": "One-line summary",
  "adjustments": {{
    "parameter_name": suggested_value
  }}
}}
```

Rules:
- "critical" = rendering error that makes the SLD unusable (broken connections, missing mandatory sections)
- "warning" = visual quality issue (overlaps, tight spacing) that should be fixed
- "info" = minor cosmetic issue
- severity "fail" if ANY critical issue exists, "warning" if warnings but no critical, "pass" if clean
- score: 1.0 = perfect, 0.8+ = acceptable, 0.5-0.8 = needs work, <0.5 = major problems
- adjustments: suggest layout parameter changes (e.g., {{"spine_component_gap": 8.0, "horizontal_spacing": 30}})
- Be strict and thorough. Do not miss issues.
"""


def _build_self_review_prompt() -> str:
    """Build self-review prompt with domain context injected."""
    return _SELF_REVIEW_TEMPLATE.format(domain_context=_build_domain_context())

_REFERENCE_COMPARE_TEMPLATE = """\
You are an expert Singapore SLD (Single Line Diagram) inspector.

**Image 1 = GENERATED SLD (to be evaluated)**
**Image 2 = REFERENCE SLD (the correct answer)**

## CRITICAL: Reference-First Analysis

**You MUST analyze the REFERENCE (Image 2) FIRST.** The reference is the ground truth.
Then check if EVERY element in the reference exists and is correct in the generated SLD.

Do NOT start from the generated SLD — you will miss elements that are in the reference but absent in the generated.

**Process:**
1. Study the REFERENCE image thoroughly — identify every component, connection, label, and structure
2. For each element found in the reference, check: does the generated SLD have it? Is it the same shape, size, position, and label?
3. Report ANY difference, no matter how small

{domain_context}
## 14-Section Full Comparison (bottom → top, power → load)

For EACH section, first describe what the REFERENCE shows, then check if the GENERATED matches.

| # | Section | What to check in REFERENCE first |
|---|---------|----------------------------------|
| 1 | INCOMING SUPPLY | Supply label, AC symbol, phase lines |
| 2 | INCOMING CABLE | Cable spec text, tick mark position |
| 3 | METER BOARD (if sp_meter) | Dashed box, ISO→KWH→MCB layout |
| 4 | UNIT ISOLATOR (if non-meter) | Symbol type, label position, rating |
| 5 | OUTGOING CABLE | Cable spec between isolator and DB |
| 6 | CT PRE-MCCB FUSE (if ct_meter) | 2A fuse + indicator lights |
| 7 | MAIN BREAKER | Symbol type (MCB/MCCB/ACB), rating, poles, kA |
| 8 | CT METERING (if ct_meter) | CT hook SHAPE (small flat half-oval, NOT circles/rings), hook SIZE relative to other symbols, ELR, ASS/Ammeter, VSS/Voltmeter, KWH meter. BI CONNECTOR structure: rectangular box with HORIZONTAL line passing through (left↔right). LEFT of BI = MCB→RCCB→busbar→subcircuits. RIGHT of BI = spare/feeder to other DBs. |
| 9 | ELCB/RCCB | Symbol, rating, sensitivity (mA) |
| 10 | INTERNAL CABLE | Cable spec text |
| 11 | MAIN BUSBAR | Name (BUSBAR/COMB BAR), rating, DB info box |
| 12 | CIRCUIT BRANCHES | Breaker symbols per circuit, phase groups, labels, cable leader lines |
| 13 | DB BOX | Dashed box size, DB name text |
| 14 | EARTH BAR | Symbol, conductor label |

## Multi-DB Specific (if reference has multiple distribution boards)

| Check | What to look for in REFERENCE |
|-------|-------------------------------|
| DB LAYOUT | How many DBs? Side by side? Relative sizes? |
| FEEDER CONNECTION | How does parent DB connect to child DB? Cable route, labels, feeder MCB position |
| PROTECTION GROUPS | Per-phase RCCB groups? How are they laid out? Spacing between groups? |
| SYMBOL SIZES | Are CT hooks small (half-oval) or large? Compare sizes relative to other components |

## MANDATORY Pre-Check: Visual Defects (do this FIRST, BEFORE section comparison)

**You MUST check ALL of these in the GENERATED image BEFORE comparing sections. If ANY defect is found, report it as CRITICAL and set score below 0.5. Do NOT give score 1.0 if any of these exist:**

1. **CLIPPING/TRUNCATION**: Look at ALL FOUR edges of the generated image. Are ANY labels, lines, symbols, or text cut off at the drawing border? Even partially truncated text counts. Check especially: top edge (subcircuit labels), bottom edge (supply labels, earth bars), left edge (incoming cable), right edge (DB2 connections).
2. **EMPTY SPACE IMBALANCE**: Is there a large empty area (>20mm gap with nothing) in the generated SLD while other areas are cramped? Especially check if the earth bar is far away from the rest of the diagram with empty space between them.
3. **SYMBOL OVERLAP**: Are any electrical symbols overlapping each other? CT hooks, breakers, RCCB symbols touching or overlapping.
4. **LINE THROUGH SYMBOL**: Do connection lines pass through the body of breaker symbols (MCB arc, MCCB box, RCCB)? NOTE: BI Connector (rectangular box on spine) is ALLOWED to have the spine line pass through it — this is normal.
5. **BROKEN CONNECTIONS**: Are there visible gaps in the spine line or connections that should be continuous?
6. **LABEL OVERLAP**: Are text labels overlapping each other or overlapping symbols, making them unreadable?
7. **OVERALL PROPORTION**: Compare the space usage between generated and reference. If the generated is much more cramped or spread out, report it.

**CRITICAL RULE**: If the generated SLD has visual defects (clipping, overlaps, broken lines), it does NOT match the reference regardless of section content. Set severity="fail" and score below 0.5.

## Important Rules

- In subcircuits, ISOLATOR circuits (ISOL1, ISOL2...) use MCB symbols + DP_ISOL_DEVICE at conductor end. This is NORMAL.
- Subcircuit breaker labels: same-spec groups show ONE label per group. This is NORMAL.
- SPARE circuits have no cable spec. This is NORMAL.
- Report ONLY actual differences between generated and reference.
- For each difference, state which section, what the reference shows, and what the generated shows.
- Be STRICT about visual quality. Do not say "match" if there are visible problems.

## Response Format (JSON only)

For EACH difference, provide a complete analysis with root cause and fix:

```json
{{
  "issues": [
    {{
      "category": "structure|symbol|label|spacing|missing|extra|clipping|overlap",
      "description": "Section X: reference shows Y but generated shows Z",
      "severity": "critical|warning|info",
      "location": "section name",
      "root_cause": "WHY this difference exists. Analyze the likely code/data cause. Examples: 'label text generation missing LOCKED prefix', 'earth bar Y position calculated from wrong reference point', 'vertical spacing too tight in compact mode', 'fanout horizontal line not clipped to avoid symbol body'",
      "fix": "SPECIFIC action to fix. Examples: 'Add LOCKED prefix to KWH meter label in CT metering section', 'Calculate earth bar Y from per-DB box bottom, not global db_box_start_y', 'Increase spine_component_gap from 2.0 to 3.0mm', 'Clip fanout line segments to avoid intersecting MCB bounding boxes'"
    }}
  ],
  "severity": "pass|warning|fail",
  "score": 0.0-1.0,
  "summary": "One-line summary of comparison result",
  "matched_sections": ["list of sections that match perfectly"],
  "adjustments": {{}}
}}
```

Rules for root_cause and fix:
- root_cause must explain the TECHNICAL reason, not just restate the difference
- fix must be a SPECIFIC action that a developer can implement
- For layout issues, suggest specific parameter changes with values
- For label issues, identify which text generation logic needs to change
- For structural issues, describe what the code should do differently
"""


def _build_reference_compare_prompt() -> str:
    """Build reference compare prompt with domain context injected."""
    return _REFERENCE_COMPARE_TEMPLATE.format(domain_context=_build_domain_context())


async def _call_gemini_vision(
    image_bytes: bytes,
    prompt: str,
    *,
    reference_bytes: bytes | None = None,
    api_key: str | None = None,
) -> str:
    """Call Gemini Vision API with one or two images.

    Returns raw JSON string from Gemini.
    """
    from google import genai
    from google.genai import types

    from app.config import settings

    resolved_key = api_key or settings.gemini_api_key
    if not resolved_key:
        raise ValueError("Gemini API key not configured (GEMINI_API_KEY)")

    client = genai.Client(api_key=resolved_key)

    parts = [types.Part.from_text(text=prompt)]
    parts.append(types.Part.from_bytes(data=image_bytes, mime_type="image/png"))
    if reference_bytes:
        parts.append(types.Part.from_bytes(data=reference_bytes, mime_type="image/png"))

    response = await client.aio.models.generate_content(
        model=settings.gemini_model,
        contents=[types.Content(parts=parts)],
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            temperature=0.0,
        ),
    )
    return response.text


def _filter_false_positives(issues: list[VisionIssue]) -> tuple[list[VisionIssue], list[VisionIssue]]:
    """Filter out known false positives using accepted patterns.

    Returns: (real_issues, filtered_issues)
    """
    import re

    patterns = _load_accepted_patterns()
    if not patterns:
        return issues, []

    compiled = []
    for p in patterns:
        try:
            compiled.append((p["id"], re.compile(p["pattern"], re.IGNORECASE)))
        except re.error:
            logger.warning("Invalid regex in accepted pattern '%s'", p.get("id"))

    real = []
    filtered = []
    for issue in issues:
        text = f"{issue.description} {issue.suggestion}"
        matched = False
        for pid, regex in compiled:
            if regex.search(text):
                logger.info("Filtered false positive '%s': %s", pid, issue.description[:60])
                matched = True
                break
        if matched:
            filtered.append(issue)
        else:
            real.append(issue)

    return real, filtered


def _parse_vision_response(raw: str) -> VisionReport:
    """Parse Gemini JSON response into VisionReport.

    Applies false positive filtering as a post-processing defense layer.
    """
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        return VisionReport(
            issues=[VisionIssue(
                category="error", description="Failed to parse Vision AI response",
                severity="warning",
            )],
            severity="warning", score=0.5,
            summary="Vision AI response parse error",
            raw_response=raw,
        )

    all_issues = [
        VisionIssue(
            category=i.get("category", "unknown"),
            description=i.get("description", ""),
            severity=i.get("severity", "info"),
            location=i.get("location", ""),
            suggestion=i.get("suggestion", ""),
            root_cause=i.get("root_cause", ""),
            fix=i.get("fix", ""),
        )
        for i in data.get("issues", [])
    ]

    # Double defense: filter false positives even if Gemini ignores prompt instructions
    real_issues, filtered = _filter_false_positives(all_issues)
    if filtered:
        logger.info("Filtered %d false positive(s) from %d total issues",
                     len(filtered), len(all_issues))

    # Recalculate severity based on filtered issues
    has_critical = any(i.severity == "critical" for i in real_issues)
    has_warning = any(i.severity == "warning" for i in real_issues)
    if has_critical:
        severity = "fail"
    elif has_warning:
        severity = "warning"
    elif real_issues:
        severity = "pass"  # only info-level
    else:
        severity = "pass"

    # Adjust score upward if false positives were removed
    original_score = data.get("score", 1.0)
    if filtered and all_issues:
        score_boost = (len(filtered) / len(all_issues)) * 0.2
        adjusted_score = min(1.0, original_score + score_boost)
    else:
        adjusted_score = original_score

    return VisionReport(
        issues=real_issues,
        severity=severity,
        score=adjusted_score,
        summary=data.get("summary", ""),
        adjustments=data.get("adjustments", {}),
        matched_sections=data.get("matched_sections", []),
        raw_response=raw,
    )


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

async def _self_review_once(
    svg_or_png_path: str | Path,
    *,
    api_key: str | None = None,
) -> VisionReport:
    """Self-Review: 생성된 SLD 이미지의 시각적 품질 검증.

    레퍼런스 불필요. 이미지만 보고 문제를 탐지.
    """
    path = Path(svg_or_png_path)
    if path.suffix.lower() == ".svg":
        png_path = svg_to_png(path, dpi=200)
    else:
        png_path = str(path)

    with open(png_path, "rb") as f:
        image_bytes = f.read()

    logger.info("Vision Self-Review: %s (%d bytes)", path.name, len(image_bytes))
    raw = await _call_gemini_vision(image_bytes, _build_self_review_prompt(), api_key=api_key)
    report = _parse_vision_response(raw)
    logger.info("Vision result: severity=%s score=%.2f issues=%d",
               report.severity, report.score, report.issue_count)
    return report


SELF_REVIEW_TRIALS = 3  # Majority voting to handle Gemini non-determinism


async def self_review(
    svg_or_png_path: str | Path,
    *,
    api_key: str | None = None,
) -> VisionReport:
    """Self-Review with majority voting.

    Gemini Vision은 같은 이미지에 대해 비결정적 결과를 반환할 수 있다.
    3회 실행하여 과반이 critical이면 가장 많은 이슈를 찾은 결과를 반환.
    """
    reports: list[VisionReport] = []
    for trial in range(SELF_REVIEW_TRIALS):
        try:
            r = await _self_review_once(svg_or_png_path, api_key=api_key)
            reports.append(r)
            logger.info("Self-review trial %d/%d: score=%.2f critical=%d total=%d",
                       trial + 1, SELF_REVIEW_TRIALS, r.score,
                       sum(1 for i in r.issues if i.severity == "critical"),
                       r.issue_count)
        except Exception as e:
            logger.warning("Self-review trial %d failed: %s", trial + 1, e)

    if not reports:
        return VisionReport(severity="fail", score=0.0, summary="All self-review trials failed")

    # Majority voting: if ≥2 of 3 trials find critical issues, use the strictest result
    critical_counts = [sum(1 for i in r.issues if i.severity == "critical") for r in reports]
    has_critical_majority = sum(1 for c in critical_counts if c > 0) >= (SELF_REVIEW_TRIALS // 2 + 1)

    if has_critical_majority:
        # Return the trial with the most critical issues (strictest)
        strictest = max(reports, key=lambda r: sum(1 for i in r.issues if i.severity == "critical"))
        logger.info("Majority voting: %d/%d trials found critical issues → using strictest (%d issues)",
                   sum(1 for c in critical_counts if c > 0), SELF_REVIEW_TRIALS, strictest.issue_count)
        return strictest
    else:
        # No critical majority — but if ANY trial found critical, use the middle score
        if any(c > 0 for c in critical_counts):
            # At least one trial found issues — merge unique critical issues into best report
            best = min(reports, key=lambda r: r.score)
            logger.info("No majority but %d/%d trials found critical — using lowest score (%.2f)",
                       sum(1 for c in critical_counts if c > 0), SELF_REVIEW_TRIALS, best.score)
            return best

        # All clean — return the one with most issues (conservative)
        return max(reports, key=lambda r: r.issue_count)


async def reference_compare(
    generated_path: str | Path,
    reference_path: str | Path,
    *,
    api_key: str | None = None,
    layout_result: Any = None,
) -> VisionReport:
    """Reference Compare: 생성 결과와 레퍼런스 비교.

    3단계 검증:
      Step 1: Self-review (시각적 결함 감지)
      Step 2: 전체 비교 (14섹션)
      Step 3: 영역별 크롭 비교 (작은 심볼 정밀 검사)
    """
    gen_path = Path(generated_path)
    ref_path = Path(reference_path)

    # Convert to PNG if needed
    _dpi = 200  # Higher DPI for better detail detection (was 150)
    if gen_path.suffix.lower() == ".svg":
        gen_png = svg_to_png(gen_path, dpi=_dpi)
    else:
        gen_png = str(gen_path)

    if ref_path.suffix.lower() == ".pdf":
        ref_png = pdf_to_png(ref_path, dpi=_dpi)
    elif ref_path.suffix.lower() == ".svg":
        ref_png = svg_to_png(ref_path, dpi=_dpi)
    else:
        ref_png = str(ref_path)

    with open(gen_png, "rb") as f:
        gen_bytes = f.read()
    with open(ref_png, "rb") as f:
        ref_bytes = f.read()

    # Step 1: Self-review first (catch visual defects before comparison)
    logger.info("Vision Reference Compare: Step 1 - Self-review of generated SLD")
    self_report = await self_review(generated_path, api_key=api_key)

    # Step 2: Reference comparison
    logger.info("Vision Reference Compare: Step 2 - Comparing %s vs %s", gen_path.name, ref_path.name)

    # Re-read gen_png (self_review may have created it)
    _gen_png_path = Path(gen_png)
    if not _gen_png_path.exists():
        gen_png = svg_to_png(gen_path, dpi=_dpi) if gen_path.suffix.lower() == ".svg" else str(gen_path)
    with open(gen_png, "rb") as f:
        gen_bytes = f.read()

    raw = await _call_gemini_vision(
        gen_bytes, _build_reference_compare_prompt(),
        reference_bytes=ref_bytes, api_key=api_key,
    )
    compare_report = _parse_vision_response(raw)

    # Step 3: Merge — self-review critical issues override compare score
    if self_report.has_critical:
        # Self-review found defects: inject them into compare report
        for issue in self_report.issues:
            if issue.severity == "critical":
                # Avoid duplicates
                if not any(i.description == issue.description for i in compare_report.issues):
                    compare_report.issues.insert(0, issue)

        # Force severity/score down
        compare_report.severity = "fail"
        compare_report.score = min(compare_report.score, 0.3)
        compare_report.summary = (
            f"[Self-review: {sum(1 for i in self_report.issues if i.severity=='critical')} critical defects] "
            + compare_report.summary
        )
        logger.warning("Self-review found %d critical defects — compare score capped at %.2f",
                       sum(1 for i in self_report.issues if i.severity == "critical"),
                       compare_report.score)

    # Step 3: Detail crop comparison (small symbol regions)
    if layout_result is not None:
        crop_regions = _get_detail_crop_regions(layout_result)
        if crop_regions:
            logger.info("Vision Reference Compare: Step 3 - %d detail crop region(s)", len(crop_regions))

            # Determine reference page dimensions (for crop coordinate mapping)
            ref_page_w, ref_page_h = 420.0, 297.0  # A3 default
            try:
                import fitz as _fitz
                _rdoc = _fitz.open(str(ref_path))
                _rpage = _rdoc.load_page(0)
                ref_page_w = _rpage.rect.width / 72 * 25.4
                ref_page_h = _rpage.rect.height / 72 * 25.4
                _rdoc.close()
            except Exception:
                pass

            # Pre-generate high-DPI PNGs if any region needs it
            _hi_dpi = max((r.get("dpi", _dpi) for r in crop_regions), default=_dpi)
            if _hi_dpi > _dpi:
                _hi_gen_png = svg_to_png(gen_path, dpi=_hi_dpi) if gen_path.suffix.lower() == ".svg" else gen_png
                _hi_ref_png = pdf_to_png(ref_path, dpi=_hi_dpi) if ref_path.suffix.lower() == ".pdf" else ref_png
            else:
                _hi_gen_png, _hi_ref_png = gen_png, ref_png

            for region in crop_regions:
                try:
                    _rdpi = region.get("dpi", _dpi)
                    _use_gen = _hi_gen_png if _rdpi > _dpi else gen_png
                    _use_ref = _hi_ref_png if _rdpi > _dpi else ref_png
                    gen_crop = _crop_png(_use_gen, region["bbox"], dpi=_rdpi)
                    ref_crop = _crop_png(_use_ref, region["bbox"],
                                        page_w_mm=ref_page_w, page_h_mm=ref_page_h,
                                        dpi=_rdpi)

                    prompt = _CROP_COMPARE_TEMPLATE.format(
                        region_name=region["name"],
                        prompt_focus=region["prompt_focus"],
                    )

                    raw_crop = await _call_gemini_vision(
                        gen_crop, prompt,
                        reference_bytes=ref_crop, api_key=api_key,
                    )
                    crop_report = _parse_vision_response(raw_crop)

                    # Merge crop issues into main report
                    for issue in crop_report.issues:
                        issue.location = f"[DETAIL:{region['name']}] {issue.location}"
                        # Avoid duplicates (same description already in main report)
                        if not any(issue.description in existing.description
                                  for existing in compare_report.issues):
                            compare_report.issues.append(issue)

                    logger.info("Crop %s: score=%.2f issues=%d",
                               region["name"], crop_report.score, crop_report.issue_count)

                except Exception as crop_err:
                    logger.warning("Crop comparison failed for %s: %s", region["name"], crop_err)

            # Recalculate severity after crop additions
            if any(i.severity == "critical" for i in compare_report.issues):
                compare_report.severity = "fail"
                compare_report.score = min(compare_report.score, 0.4)

    return compare_report


# ---------------------------------------------------------------------------
# 경로 2: 자동 재생성 루프
# ---------------------------------------------------------------------------

def apply_adjustments(requirements: dict, adjustments: dict) -> dict:
    """Vision AI 제안 파라미터를 requirements에 적용.

    지원하는 조정:
    - horizontal_spacing: 서브회로 간격
    - spine_component_gap: 스파인 컴포넌트 간 간격
    - vertical_spacing: 수직 간격
    """
    import copy
    req = copy.deepcopy(requirements)

    # layout_overrides로 전달 (generator가 LayoutConfig에 적용)
    overrides = req.get("layout_overrides", {})
    for key in ("horizontal_spacing", "spine_component_gap", "vertical_spacing",
                "busbar_margin", "row_spacing"):
        if key in adjustments:
            overrides[key] = adjustments[key]

    if overrides:
        req["layout_overrides"] = overrides
        logger.info("Applied Vision adjustments: %s", overrides)

    return req


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

async def _main():
    import argparse

    parser = argparse.ArgumentParser(description="Vision AI SLD Validator")
    parser.add_argument("--svg", help="SVG file to validate")
    parser.add_argument("--png", help="PNG file to validate")
    parser.add_argument("--ref", help="Reference PDF/SVG for comparison")
    parser.add_argument("--api-key", help="Gemini API key (or set GEMINI_API_KEY)")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(message)s")

    input_path = args.svg or args.png
    if not input_path:
        parser.error("--svg or --png required")

    api_key = args.api_key or os.environ.get("GEMINI_API_KEY")

    if args.ref:
        report = await reference_compare(input_path, args.ref, api_key=api_key)
        print("\n=== Reference Compare (14-Section) ===")
    else:
        report = await self_review(input_path, api_key=api_key)
        print("\n=== Self-Review ===")

    print(f"Severity: {report.severity}")
    print(f"Score: {report.score:.2f}")
    print(f"Summary: {report.summary}")

    if report.matched_sections:
        print(f"\n✅ Matched sections ({len(report.matched_sections)}/14):")
        for s in report.matched_sections:
            print(f"   ✅ {s}")

    print(f"\n❌ Issues: {report.issue_count}")
    for i, issue in enumerate(report.issues, 1):
        print(f"  {i}. [{issue.severity}] {issue.category}: {issue.description}")
        if issue.location:
            print(f"     📍 {issue.location}")
        if issue.suggestion:
            print(f"     → {issue.suggestion}")
    if report.adjustments:
        print(f"\nSuggested adjustments: {json.dumps(report.adjustments, indent=2)}")

    # Save report as JSON
    report_path = Path(input_path).with_name(
        Path(input_path).stem + "_compare_report.json"
    )
    with open(report_path, "w") as f:
        json.dump(report.to_dict(), f, indent=2, ensure_ascii=False)
    print(f"\n📄 Report saved: {report_path}")


def main():
    import asyncio
    asyncio.run(_main())


if __name__ == "__main__":
    main()
