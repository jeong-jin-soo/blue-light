"""DXF 레퍼런스 구조 추출기.

28개 LEW 레퍼런스 DXF에서 구조적 핑거프린트를 추출한다.
추출 결과는 data/regression/reference_specs.json에 저장되며,
rule_deriver.py에서 범용 규칙 도출에 사용된다.

Usage:
    python -m app.sld.regression.extractor          # 전체 추출
    python -m app.sld.regression.extractor --file "63A TPN SLD 1 DWG.dxf"  # 단일 파일
"""

from __future__ import annotations

import logging
import math
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import ezdxf

from .rules import (
    BlockUsage,
    ReferenceDatabase,
    ReferenceSpec,
    SpineComponent,
    SubcircuitRow,
)

logger = logging.getLogger(__name__)

_BASE_DIR = Path(__file__).resolve().parent.parent.parent.parent  # blue-light-ai/
DXF_DIR = _BASE_DIR / "data" / "sld-info" / "slds-dxf"


# ---------------------------------------------------------------------------
# SLD type classification (ported from dxf_ingest/pipeline.py)
# ---------------------------------------------------------------------------

def classify_sld_type(filename: str, block_names: set[str]) -> str:
    """Classify SLD type from filename and block content."""
    name = filename.upper()
    if "SLD-CT" in block_names:
        return "ct_metering_3phase"
    if "CABLE EXTENSION" in name or "CABLE_EXTENSION" in name:
        return "cable_extension"
    if "SINGLE" in name or "SINGLE PHASE" in name:
        return "direct_metering_1phase"
    if "TPN" in name or "3P" in name:
        return "direct_metering_3phase"
    # Fallback: check for DP ISOL without 3P indicators
    if "DP ISOL" in block_names and "3P ISOL" not in block_names and "TPN" not in name:
        return "direct_metering_1phase"
    return "unknown"


# ---------------------------------------------------------------------------
# Core extractor
# ---------------------------------------------------------------------------

# Tolerances (in DXF Drawing Units)
_X_TOL = 100   # X alignment tolerance for spine detection (~2mm)
_Y_TOL = 300   # Y grouping tolerance for subcircuit row detection (~6mm)
_VLINE_MIN = 400  # Minimum vertical line length to consider
_LABEL_RADIUS = 3000  # Search radius for associated labels (~60mm)
_PHASE_RADIUS = 2000  # Search radius for phase labels (~40mm)

# Patterns
_PHASE_PAT = re.compile(r"^[LN]\d+S?\d*$", re.IGNORECASE)
_RATING_PAT = re.compile(
    r"^\d+[AV]?$|^[BCD]\d+A?$|^[ST]PN$|^MCB$|^MCCB$|^RCCB$|^ELCB$|^ACB$|^\d+kA$",
    re.IGNORECASE,
)
_CABLE_PAT = re.compile(r"\d+C\s+[\d.]+", re.IGNORECASE)  # e.g., "1C 2.5sqmm"


class ReferenceExtractor:
    """28개 레퍼런스 DXF에서 구조적 핑거프린트를 추출한다."""

    def extract(self, dxf_path: Path) -> ReferenceSpec:
        """단일 DXF 파일에서 핑거프린트 추출."""
        doc = ezdxf.readfile(str(dxf_path))
        msp = doc.modelspace()

        # Phase 1: 원시 엔티티 수집
        inserts = self._collect_inserts(msp)
        texts = self._collect_texts(msp)
        vlines = self._collect_vertical_lines(msp)
        layers = Counter(e.dxf.layer for e in msp)

        # SLD 유형 분류
        block_names = set(b.name for b in inserts)
        sld_type = classify_sld_type(dxf_path.name, block_names)

        # Phase 2: 구조 분석
        spines = self._detect_spines(inserts)
        subcircuit_rows = self._detect_subcircuit_rows(inserts, texts)
        extent_x, extent_y = self._compute_extents(msp)

        # Phase 3: 텍스트 분석
        has_phase_labels = any(_PHASE_PAT.match(t["text"]) for t in texts)
        has_rating_labels = any(_RATING_PAT.match(t["text"]) for t in texts)
        has_cable_annotations = any(_CABLE_PAT.search(t["text"]) for t in texts)

        # Block counts
        block_counts = dict(Counter(b.name for b in inserts))

        # RCCB 개수로 DB 수 추정
        rccb_count = block_counts.get("RCCB", 0)
        num_dbs = max(rccb_count, 1)

        return ReferenceSpec(
            filename=dxf_path.name,
            sld_type=sld_type,
            block_counts=block_counts,
            total_subcircuits=sum(r.count for r in subcircuit_rows),
            num_dbs=num_dbs,
            has_rccb=rccb_count > 0,
            has_ct="SLD-CT" in block_names,
            has_fuse="2A FUSE" in block_names,
            has_isolator=bool(block_names & {"DP ISOL", "3P ISOL"}),
            spine_orders=[[c.block_name for c in spine] for spine in spines],
            spine_components=[c for spine in spines for c in spine],
            subcircuit_rows=subcircuit_rows,
            has_phase_labels=has_phase_labels,
            has_rating_labels=has_rating_labels,
            has_cable_annotations=has_cable_annotations,
            extent_x=extent_x,
            extent_y=extent_y,
            layer_usage=dict(layers),
        )

    # -- 원시 엔티티 수집 --

    def _collect_inserts(self, msp) -> list[BlockUsage]:
        result = []
        for e in msp:
            if e.dxftype() != "INSERT":
                continue
            name = e.dxf.name
            if name.startswith("*"):
                continue
            result.append(BlockUsage(
                name=name,
                x=round(e.dxf.insert.x, 2),
                y=round(e.dxf.insert.y, 2),
                scale=round(getattr(e.dxf, "xscale", 1.0), 4),
                rotation=round(getattr(e.dxf, "rotation", 0.0), 1),
            ))
        return result

    def _collect_texts(self, msp) -> list[dict]:
        result = []
        for e in msp:
            if e.dxftype() == "TEXT":
                result.append({
                    "text": e.dxf.text.strip(),
                    "x": round(e.dxf.insert.x, 2),
                    "y": round(e.dxf.insert.y, 2),
                    "height": round(e.dxf.height, 2),
                    "layer": e.dxf.layer,
                })
            elif e.dxftype() == "MTEXT":
                raw = e.text.replace("\\P", "\n").strip()
                result.append({
                    "text": raw,
                    "x": round(e.dxf.insert.x, 2),
                    "y": round(e.dxf.insert.y, 2),
                    "height": round(e.dxf.char_height, 2),
                    "layer": e.dxf.layer,
                })
        return result

    def _collect_vertical_lines(self, msp) -> list[dict]:
        result = []
        for e in msp:
            if e.dxftype() != "LINE":
                continue
            dx = abs(e.dxf.start.x - e.dxf.end.x)
            dy = abs(e.dxf.start.y - e.dxf.end.y)
            if dy > _VLINE_MIN and dx < _X_TOL:
                x = round((e.dxf.start.x + e.dxf.end.x) / 2, 2)
                y_min = round(min(e.dxf.start.y, e.dxf.end.y), 2)
                y_max = round(max(e.dxf.start.y, e.dxf.end.y), 2)
                result.append({
                    "x": x, "y_min": y_min, "y_max": y_max,
                    "length": round(y_max - y_min, 2),
                    "layer": e.dxf.layer,
                })
        return result

    def _compute_extents(self, msp) -> tuple[tuple[float, float], tuple[float, float]]:
        xs, ys = [], []
        for e in msp:
            try:
                if e.dxftype() == "LINE":
                    xs.extend([e.dxf.start.x, e.dxf.end.x])
                    ys.extend([e.dxf.start.y, e.dxf.end.y])
                elif e.dxftype() in ("INSERT", "TEXT", "MTEXT"):
                    xs.append(e.dxf.insert.x)
                    ys.append(e.dxf.insert.y)
            except Exception:
                pass
        if not xs:
            return (0, 0), (0, 0)
        return (round(min(xs), 2), round(max(xs), 2)), (round(min(ys), 2), round(max(ys), 2))

    # -- 구조 분석 --

    def _detect_spines(self, inserts: list[BlockUsage]) -> list[list[SpineComponent]]:
        """주 수직 스파인 감지.

        전략: 메인 컴포넌트(MCCB, RCCB, SLD-CT, ISOL 등)가 X좌표 기준으로
        정렬된 그룹을 찾는다. 각 그룹이 하나의 스파인.
        """
        main_types = {"MCCB", "RCCB", "SLD-CT", "DP ISOL", "3P ISOL", "2A FUSE", "EF"}
        candidates = [b for b in inserts if b.name in main_types and abs(b.rotation) < 1.0]

        if not candidates:
            return []

        # X좌표 기준 그룹핑
        x_groups: dict[float, list[BlockUsage]] = {}
        for b in candidates:
            placed = False
            for gx in list(x_groups.keys()):
                if abs(b.x - gx) < _X_TOL:
                    x_groups[gx].append(b)
                    placed = True
                    break
            if not placed:
                x_groups[b.x] = [b]

        # 스파인 = 2개 이상 다른 유형 또는 MCCB+RCCB 조합
        spines = []
        for gx, blocks in x_groups.items():
            types = set(b.name for b in blocks)
            if len(types) >= 2 or (len(blocks) >= 2 and "MCCB" in types):
                sorted_blocks = sorted(blocks, key=lambda b: b.y)
                spine = [
                    SpineComponent(
                        block_name=b.name,
                        y=round(b.y, 2),
                        scale=b.scale,
                    )
                    for b in sorted_blocks
                ]
                spines.append(spine)

        return spines

    def _detect_subcircuit_rows(
        self, inserts: list[BlockUsage], texts: list[dict]
    ) -> list[SubcircuitRow]:
        """서브회로 행 감지.

        서브회로 = 같은 Y레벨에 3개 이상의 upright MCCB.
        """
        mccbs = [b for b in inserts if b.name == "MCCB" and abs(b.rotation) < 1.0]
        if len(mccbs) < 3:
            return []

        # Y좌표 기준 그룹핑
        y_groups: dict[float, list[BlockUsage]] = {}
        for b in mccbs:
            placed = False
            for gy in list(y_groups.keys()):
                if abs(b.y - gy) < _Y_TOL:
                    y_groups[gy].append(b)
                    placed = True
                    break
            if not placed:
                y_groups[b.y] = [b]

        rows = []
        for gy, blocks in y_groups.items():
            if len(blocks) < 3:
                continue
            sorted_blocks = sorted(blocks, key=lambda b: b.x)
            xs = [b.x for b in sorted_blocks]
            spacings = [round(xs[i + 1] - xs[i], 2) for i in range(len(xs) - 1)]
            phase_labels = self._find_phase_labels(sorted_blocks, texts)
            rows.append(SubcircuitRow(
                y=round(gy, 2),
                count=len(blocks),
                x_spacings=spacings,
                phase_labels=phase_labels,
            ))

        return rows

    def _find_phase_labels(self, breakers: list[BlockUsage], texts: list[dict]) -> list[str]:
        """서브회로 차단기 근처에서 위상 라벨(L1S1, L2S2 등) 찾기."""
        labels = []
        for b in breakers:
            best = ""
            best_dist = float("inf")
            for t in texts:
                if _PHASE_PAT.match(t["text"]):
                    dist = math.sqrt((t["x"] - b.x) ** 2 + (t["y"] - b.y) ** 2)
                    if dist < best_dist and dist < _PHASE_RADIUS:
                        best = t["text"]
                        best_dist = dist
            labels.append(best)
        return labels


# ---------------------------------------------------------------------------
# Batch extraction
# ---------------------------------------------------------------------------

def extract_all(dxf_dir: Path | None = None) -> ReferenceDatabase:
    """28개 DXF 전수 분석 → ReferenceDatabase 반환 + JSON 저장."""
    dxf_dir = dxf_dir or DXF_DIR
    extractor = ReferenceExtractor()
    specs: list[ReferenceSpec] = []
    errors: list[str] = []

    dxf_files = sorted(dxf_dir.glob("*.dxf"))
    logger.info("Scanning %d DXF files in %s", len(dxf_files), dxf_dir)

    for dxf_file in dxf_files:
        try:
            spec = extractor.extract(dxf_file)
            specs.append(spec)
            logger.info(
                "  OK %-40s → %-25s blocks=%-3d subckt=%-3d spines=%d",
                spec.filename, spec.sld_type,
                sum(spec.block_counts.values()), spec.total_subcircuits,
                len(spec.spine_orders),
            )
        except Exception as exc:
            errors.append(f"{dxf_file.name}: {exc}")
            logger.error("  FAIL %s: %s", dxf_file.name, exc)

    type_counts = Counter(s.sld_type for s in specs)
    db = ReferenceDatabase(
        specs=specs,
        meta={
            "total_files": len(specs),
            "errors": len(errors),
            "error_details": errors,
            "sld_type_distribution": dict(type_counts),
        },
    )
    db.save()
    return db


# ---------------------------------------------------------------------------
# CLI entry point
# ---------------------------------------------------------------------------

def main():
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    if "--file" in sys.argv:
        idx = sys.argv.index("--file")
        filename = sys.argv[idx + 1]
        path = DXF_DIR / filename
        if not path.exists():
            logger.error("File not found: %s", path)
            sys.exit(1)
        extractor = ReferenceExtractor()
        spec = extractor.extract(path)
        import json
        print(json.dumps(spec.to_dict(), indent=2, ensure_ascii=False))
    else:
        db = extract_all()
        print(f"\nExtracted {len(db.specs)} specs:")
        for sld_type, count in db.meta.get("sld_type_distribution", {}).items():
            print(f"  {sld_type}: {count}")
        if db.meta.get("errors"):
            print(f"\nErrors: {db.meta['errors']}")


if __name__ == "__main__":
    main()
