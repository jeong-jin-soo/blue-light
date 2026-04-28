#!/usr/bin/env python3
"""레퍼런스 DWG에서 main breaker → first CT 거리 분포 측정.

SP §6.9.6 "immediately after" 임계값 (`CT_IMMEDIATELY_AFTER_MAX_GAP_MM`)의
도메인 근거를 갱신할 때 사용한다. circuit_data_extracted.json 의
breaker_specs / cable_specs y 좌표를 기반으로 근사 측정.

Limitation: cable_specs y 가 CT y와 정확히 일치한다고 보장할 수 없으므로
근사치다. 정확한 측정은 converted_dxf 의 INSERT 엔티티 좌표를 직접 파싱하는
별도 스크립트가 필요.

Usage:
    cd blue-light-ai
    .venv/bin/python -m scripts.measure_breaker_ct_gap
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

EXTRACTED = Path(__file__).resolve().parent.parent / "data" / "sld-info" / "sld-dwg-old" / "circuit_data_extracted.json"


def main() -> int:
    if not EXTRACTED.exists():
        print(f"Not found: {EXTRACTED}", file=sys.stderr)
        return 1

    data = json.loads(EXTRACTED.read_text())
    files = list(data["files"].values()) if isinstance(data["files"], dict) else data["files"]

    measurements: list[tuple[str, float]] = []
    for f in files:
        bs = f.get("breaker_specs") or []
        if not bs:
            continue
        mb = max(bs, key=lambda s: s.get("rating_A") or s.get("rating_a") or 0)
        if (mb_y := mb.get("y")) is None:
            continue
        cables = [c for c in (f.get("cable_specs") or []) if c.get("y")]
        above = [c for c in cables if c["y"] > mb_y]
        if not above:
            continue
        nearest = min(above, key=lambda c: c["y"] - mb_y)
        measurements.append((f["filename"], nearest["y"] - mb_y))

    if not measurements:
        print("No measurable samples — extraction missing y coordinates.")
        return 0

    vals = [d for _, d in measurements]
    print(f"Samples: {len(vals)}")
    print(f"  min:    {min(vals):.2f}")
    print(f"  p50:    {statistics.median(vals):.2f}")
    print(f"  mean:   {statistics.mean(vals):.2f}")
    print(f"  stdev:  {statistics.pstdev(vals):.2f}")
    print(f"  p95:    {sorted(vals)[int(len(vals) * 0.95)]:.2f}")
    print(f"  max:    {max(vals):.2f}")
    print()
    print("Closest 10 (likely valid 'immediately after'):")
    for fn, d in sorted(measurements, key=lambda x: x[1])[:10]:
        print(f"  {d:>12.2f} | {fn}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
