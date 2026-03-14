"""DXF 블록 엔티티를 DrawingBackend 프리미티브로 재생.

블록 라이브러리(dxf_block_library.json)에서 로드한 블록 정의를 사용하여
모든 백엔드(DXF, PDF, SVG)에서 심볼을 렌더링한다.

- DxfBackend: insert_block() 직접 사용 (원본 100% 동일)
- PdfBackend/SvgBackend: 엔티티를 프리미티브로 변환하여 재생

Usage:
    from app.sld.block_replayer import BlockReplayer

    replayer = BlockReplayer.load()
    replayer.draw(backend, "MCCB", x=100, y=50, target_height_mm=15.0)
"""

from __future__ import annotations

import json
import math
from pathlib import Path
from typing import Any

from app.sld.backend import DrawingBackend

# Library JSON path
_LIBRARY_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "data" / "templates" / "dxf_block_library.json"
)


class BlockReplayer:
    """DXF 블록 엔티티를 DrawingBackend 프리미티브로 재생."""

    def __init__(self, library: dict[str, Any]):
        """Initialize with loaded library dict.

        Args:
            library: dxf_block_library.json 로드 결과.
        """
        # Merge blocks and custom_blocks into a single lookup
        self._blocks: dict[str, dict] = {}
        self._blocks.update(library.get("blocks", {}))
        self._blocks.update(library.get("custom_blocks", {}))

    @classmethod
    def load(cls, path: Path | None = None) -> BlockReplayer:
        """Load block library from JSON file."""
        p = path or _LIBRARY_PATH
        with open(p, encoding="utf-8") as f:
            library = json.load(f)
        return cls(library)

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def has_block(self, block_name: str) -> bool:
        """Check if a block definition exists in the library."""
        return block_name in self._blocks

    def get_block_def(self, block_name: str) -> dict | None:
        """Get raw block definition dict."""
        return self._blocks.get(block_name)

    def block_height_du(self, block_name: str) -> float:
        """Get block height in Drawing Units."""
        blk = self._blocks.get(block_name)
        return blk["height_du"] if blk else 0.0

    def block_width_du(self, block_name: str) -> float:
        """Get block width in Drawing Units."""
        blk = self._blocks.get(block_name)
        return blk["width_du"] if blk else 0.0

    def compute_scale(self, block_name: str, target_height_mm: float) -> float:
        """Compute DU→mm scale factor for a given target height.

        scale = target_height_mm / block_height_du
        """
        h = self.block_height_du(block_name)
        if h <= 0:
            return 1.0
        return target_height_mm / h

    def draw(
        self,
        backend: DrawingBackend,
        block_name: str,
        x: float,
        y: float,
        *,
        target_height_mm: float | None = None,
        scale: float | None = None,
        rotation: float = 0.0,
        layer: str | None = None,
    ) -> None:
        """블록을 지정 위치에 렌더링.

        Args:
            backend: DrawingBackend 인스턴스.
            block_name: 블록 이름 (예: "MCCB").
            x, y: 삽입점 (mm 좌표, 블록의 bottom-left 기준).
            target_height_mm: 목표 높이(mm). scale과 동시 지정 불가.
            scale: 직접 스케일 팩터 지정. target_height_mm과 동시 지정 불가.
            rotation: 회전 각도 (도, CCW).
            layer: 레이어 설정 (None이면 변경하지 않음).
        """
        if block_name not in self._blocks:
            raise ValueError(f"Unknown block: {block_name}")

        # Determine scale
        if target_height_mm is not None and scale is not None:
            raise ValueError("target_height_mm and scale are mutually exclusive")
        if target_height_mm is not None:
            s = self.compute_scale(block_name, target_height_mm)
        elif scale is not None:
            s = scale
        else:
            s = 1.0

        if layer:
            backend.set_layer(layer)

        # DxfBackend path: use native block INSERT for 100% fidelity
        from app.sld.dxf_backend import DxfBackend
        if isinstance(backend, DxfBackend) and backend.has_block(block_name):
            backend.insert_block(block_name, x, y, scale=s, rotation=rotation)
            return

        # PDF/SVG path: replay entities as drawing primitives
        self._replay_entities(backend, block_name, x, y, s, rotation)

    def get_pins(
        self,
        block_name: str,
        x: float,
        y: float,
        *,
        target_height_mm: float | None = None,
        scale: float | None = None,
    ) -> dict[str, tuple[float, float]]:
        """블록의 연결 핀 위치를 페이지 좌표(mm)로 반환.

        Args:
            block_name: 블록 이름.
            x, y: 삽입점 (mm).
            target_height_mm: 목표 높이(mm).
            scale: 직접 스케일 팩터.

        Returns:
            {"top": (px, py), "bottom": (bx, by), ...}
        """
        blk = self._blocks.get(block_name)
        if not blk:
            return {}

        if target_height_mm is not None:
            s = self.compute_scale(block_name, target_height_mm)
        elif scale is not None:
            s = scale
        else:
            s = 1.0

        pins: dict[str, tuple[float, float]] = {}
        for pin_name, local_pos in blk.get("pins", {}).items():
            pins[pin_name] = (x + local_pos[0] * s, y + local_pos[1] * s)
        return pins

    def get_scaled_size(
        self,
        block_name: str,
        *,
        target_height_mm: float | None = None,
        scale: float | None = None,
    ) -> tuple[float, float]:
        """Get scaled (width_mm, height_mm) for a block.

        Returns:
            (width_mm, height_mm)
        """
        blk = self._blocks.get(block_name)
        if not blk:
            return (0.0, 0.0)

        if target_height_mm is not None:
            s = self.compute_scale(block_name, target_height_mm)
        elif scale is not None:
            s = scale
        else:
            s = 1.0

        return (blk["width_du"] * s, blk["height_du"] * s)

    def get_pin_half_width(self, block_name: str, target_height_mm: float) -> float:
        """Get horizontal pin offset (half-width) at a given target height.

        Computes the X distance from the block's insertion point to the
        top/bottom pin's X coordinate, scaled to the target height.
        This is the data-driven equivalent of the layout engine's
        ``_breaker_half_width()`` function.

        Returns 0.0 if the block or pins are not available.
        """
        blk = self._blocks.get(block_name)
        if not blk or "pins" not in blk:
            return 0.0
        top_pin = blk["pins"].get("top")
        if not top_pin:
            return 0.0
        s = self.compute_scale(block_name, target_height_mm)
        return abs(top_pin[0]) * s

    # ------------------------------------------------------------------
    # Entity replay (PDF/SVG backends)
    # ------------------------------------------------------------------

    def _replay_entities(
        self,
        backend: DrawingBackend,
        block_name: str,
        x: float,
        y: float,
        scale: float,
        rotation: float,
    ) -> None:
        """Replay block entities as DrawingBackend primitives."""
        block_def = self._blocks[block_name]
        rot_rad = math.radians(rotation)
        cos_r = math.cos(rot_rad)
        sin_r = math.sin(rot_rad)

        for ent in block_def.get("entities", []):
            t = ent["type"]

            if t == "LINE":
                s = _transform(ent["start"], x, y, scale, cos_r, sin_r)
                e = _transform(ent["end"], x, y, scale, cos_r, sin_r)
                backend.add_line(s, e)

            elif t == "CIRCLE":
                c = _transform(ent["center"], x, y, scale, cos_r, sin_r)
                backend.add_circle(c, ent["radius"] * scale)

            elif t == "ARC":
                c = _transform(ent["center"], x, y, scale, cos_r, sin_r)
                sa = ent["start_angle"] + rotation
                ea = ent["end_angle"] + rotation
                backend.add_arc(c, ent["radius"] * scale, sa, ea)

            elif t == "LWPOLYLINE":
                self._replay_lwpolyline(
                    backend, ent, x, y, scale, cos_r, sin_r
                )

            elif t == "TEXT":
                ins = _transform(ent["insert"], x, y, scale, cos_r, sin_r)
                h = ent.get("height", 0) * scale
                if h < 0.5:
                    h = 1.5  # minimum readable height
                backend.add_mtext(
                    ent["text"],
                    insert=ins,
                    char_height=h,
                    rotation=rotation,
                )

    def _replay_lwpolyline(
        self,
        backend: DrawingBackend,
        ent: dict,
        x: float,
        y: float,
        scale: float,
        cos_r: float,
        sin_r: float,
    ) -> None:
        """Replay LWPOLYLINE, converting bulge segments to arcs."""
        raw_pts = ent["points"]
        bulges = ent.get("bulges", [0.0] * len(raw_pts))
        closed = ent.get("closed", False)

        # Transform all points
        pts = [_transform(p, x, y, scale, cos_r, sin_r) for p in raw_pts]

        # Check if any segment has non-zero bulge
        has_bulge = any(abs(b) > 1e-6 for b in bulges)

        if not has_bulge:
            # Simple polyline — use add_lwpolyline directly
            backend.add_lwpolyline(pts, close=closed)
            return

        # Mixed polyline: emit line segments and arcs separately
        n = len(pts)
        segments = n if closed else n - 1

        for i in range(segments):
            p1 = pts[i]
            p2 = pts[(i + 1) % n]
            bulge = bulges[i] if i < len(bulges) else 0.0

            if abs(bulge) < 1e-6:
                # Straight segment
                backend.add_line(p1, p2)
            else:
                # Arc segment from bulge
                _draw_bulge_arc(backend, p1, p2, bulge)


# ------------------------------------------------------------------
# Coordinate transform helpers
# ------------------------------------------------------------------

def _transform(
    local: list[float],
    x: float,
    y: float,
    scale: float,
    cos_r: float,
    sin_r: float,
) -> tuple[float, float]:
    """Transform block-local coordinate to page coordinate.

    Applies: scale → rotate → translate.
    """
    lx = local[0] * scale
    ly = local[1] * scale
    return (
        x + lx * cos_r - ly * sin_r,
        y + lx * sin_r + ly * cos_r,
    )


def _draw_bulge_arc(
    backend: DrawingBackend,
    p1: tuple[float, float],
    p2: tuple[float, float],
    bulge: float,
) -> None:
    """Convert a bulge segment to an arc and draw it.

    Bulge = tan(included_angle / 4).
    Positive bulge = CCW arc (left of direction P1→P2).
    """
    dx = p2[0] - p1[0]
    dy = p2[1] - p1[1]
    chord = math.sqrt(dx * dx + dy * dy)

    if chord < 1e-9:
        return

    # Included angle
    angle = 4.0 * math.atan(abs(bulge))
    # Radius
    radius = chord / (2.0 * math.sin(angle / 2.0))

    # Midpoint of chord
    mx = (p1[0] + p2[0]) / 2.0
    my = (p1[1] + p2[1]) / 2.0

    # Perpendicular unit vector (left of P1→P2)
    nx = -dy / chord
    ny = dx / chord

    # Distance from midpoint to center
    d = radius * math.cos(angle / 2.0)

    # Center: positive bulge → center is left of P1→P2
    sign = 1.0 if bulge > 0 else -1.0
    cx = mx + sign * d * nx
    cy = my + sign * d * ny

    # Compute start/end angles
    sa = math.degrees(math.atan2(p1[1] - cy, p1[0] - cx))
    ea = math.degrees(math.atan2(p2[1] - cy, p2[0] - cx))

    # Ensure correct arc direction
    if bulge > 0:
        # CCW arc: start_angle < end_angle
        if ea < sa:
            ea += 360.0
    else:
        # CW arc: swap so add_arc (which is always CCW) draws correctly
        sa, ea = ea, sa
        if ea < sa:
            ea += 360.0

    backend.add_arc((cx, cy), radius, sa, ea)
