"""
DXF drawing backend using ezdxf.

Implements DrawingBackend protocol to generate DXF files for SLD drawings.
DXF files can be opened and edited in AutoCAD/LibreCAD by LEWs.

Style calibration matches real LEW SLD samples:
- Line weight: 0.25mm uniform (busbar 0.50mm)
- Color: ACI 7 (black on screen, prints black)
- Font: Arial TrueType (matches ArialMT in real samples)
- Coordinate system: bottom-left origin, mm units, A3 landscape (420x297mm)
"""

from __future__ import annotations

import io
import logging
import math
from pathlib import Path

import ezdxf
from ezdxf import units
from ezdxf.enums import TextEntityAlignment

from app.sld.page_config import A3_LANDSCAPE, PageConfig

logger = logging.getLogger(__name__)

# A3 landscape dimensions in mm
_PAGE_WIDTH = 420.0
_PAGE_HEIGHT = 297.0

# Layer configuration matching real LEW SLD style
# ACI color 7 = white (on black background) / black (on white paper, i.e., print)
_LAYER_CONFIG: dict[str, dict] = {
    "SLD_SYMBOLS": {"color": 7, "lineweight": 25},       # 0.25mm
    "SLD_CONNECTIONS": {"color": 7, "lineweight": 25},    # 0.25mm
    "SLD_POWER_MAIN": {"color": 7, "lineweight": 50},    # 0.50mm (busbar)
    "SLD_ANNOTATIONS": {"color": 7, "lineweight": 25},   # 0.25mm
    "SLD_TITLE_BLOCK": {"color": 7, "lineweight": 25},   # 0.25mm
    "SLD_DB_FRAME": {"color": 8, "lineweight": 25, "linetype": "CENTER"},  # ref: E-SLD-FRAME
}

# Text style name
_TEXT_STYLE = "REAL_SLD"


class DxfBackend:
    """
    ezdxf-based DXF drawing backend.

    Coordinate system: bottom-left origin, mm units (matching DrawingBackend protocol).
    No coordinate transformation needed — DXF native system matches our convention.
    """

    def __init__(self, page_config: PageConfig | None = None):
        self._page_config = page_config or A3_LANDSCAPE
        self._doc = ezdxf.new("R2013")  # AutoCAD 2013 format for broad compatibility
        self._doc.units = units.MM
        self._msp = self._doc.modelspace()
        self._current_layer = "SLD_SYMBOLS"

        self._setup_layers()
        self._setup_text_style()

    def _setup_layers(self) -> None:
        """Create DXF layers matching real LEW SLD style."""
        # Register CENTER linetype if any layer needs it (ref: E-SLD-FRAME uses CENTER)
        if "CENTER" not in self._doc.linetypes:
            self._doc.linetypes.add(
                "CENTER",
                pattern=[1.25, 0.75, -0.125, 0.25, -0.125],
                description="Center ____ _ ____ _ ____",
            )
        for name, cfg in _LAYER_CONFIG.items():
            layer = self._doc.layers.add(
                name,
                color=cfg["color"],
                lineweight=cfg["lineweight"],
            )
            if "linetype" in cfg:
                layer.dxf.linetype = cfg["linetype"]

    def _setup_text_style(self) -> None:
        """Set up Arial TrueType text style (matches ArialMT in real samples)."""
        self._doc.styles.add(
            _TEXT_STYLE,
            font="Arial",
            dxfattribs={"flags": 0},
        )

    def _dxfattribs(self, **extra) -> dict:
        """Build common dxfattribs dict for current layer."""
        attribs = {"layer": self._current_layer}
        attribs.update(extra)
        return attribs

    # -- Layer management --

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = layer_name

    # -- Drawing primitives --

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        attribs = self._dxfattribs()
        if lineweight is not None:
            attribs["lineweight"] = lineweight
        self._msp.add_line(start, end, dxfattribs=attribs)

    def add_lwpolyline(
        self,
        points: list[tuple[float, float]],
        *,
        close: bool = False,
        lineweight: int | None = None,
    ) -> None:
        if len(points) < 2:
            return
        attribs = self._dxfattribs()
        if lineweight is not None:
            attribs["lineweight"] = lineweight
        pline = self._msp.add_lwpolyline(points, dxfattribs=attribs)
        if close:
            pline.close()

    def add_circle(
        self,
        center: tuple[float, float],
        radius: float,
    ) -> None:
        self._msp.add_circle(center, radius, dxfattribs=self._dxfattribs())

    def add_arc(
        self,
        center: tuple[float, float],
        radius: float,
        start_angle: float,
        end_angle: float,
    ) -> None:
        """Draw an arc. Angles in degrees, CCW from positive X-axis (DXF native convention)."""
        self._msp.add_arc(
            center,
            radius,
            start_angle,
            end_angle,
            dxfattribs=self._dxfattribs(),
        )

    def add_mtext(
        self,
        text: str,
        *,
        insert: tuple[float, float],
        char_height: float = 3.0,
        rotation: float = 0.0,
        center_across: bool = False,
    ) -> None:
        """
        Draw multiline text using MTEXT entity.

        Args:
            text: Text content. '\\P' for line breaks (DXF convention).
            insert: Position (x, y) in mm — top-left anchor.
            char_height: Character height in mm.
            rotation: Text rotation in degrees CCW.
            center_across: If True, center text block across the rotation axis
                (MIDDLE_LEFT attachment). For rotation=90° with 1 line,
                the line center aligns with insert_x. For 2 lines, the
                midpoint between lines aligns with insert_x.
        """
        if not isinstance(text, str):
            text = str(text)

        attribs = self._dxfattribs()

        mtext = self._msp.add_mtext(text, dxfattribs=attribs)
        mtext.dxf.insert = insert
        mtext.dxf.char_height = char_height
        mtext.dxf.style = _TEXT_STYLE
        # MIDDLE_LEFT (4) centers the text block across the rotation axis
        mtext.dxf.attachment_point = 4 if center_across else 1

        if abs(rotation) > 0.1:
            mtext.dxf.rotation = rotation

    def add_filled_rect(
        self,
        x: float,
        y: float,
        width: float,
        height: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled rectangle using HATCH entity."""
        attribs = self._dxfattribs()

        hatch = self._msp.add_hatch(color=0, dxfattribs=attribs)  # ACI 0 = BYBLOCK
        # Set solid fill
        hatch.set_solid_fill()
        # Add rectangular boundary
        hatch.paths.add_polyline_path(
            [
                (x, y),
                (x + width, y),
                (x + width, y + height),
                (x, y + height),
            ],
            is_closed=True,
        )

    def add_filled_circle(
        self,
        center: tuple[float, float],
        radius: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled circle using HATCH entity."""
        attribs = self._dxfattribs()

        hatch = self._msp.add_hatch(color=0, dxfattribs=attribs)
        hatch.set_solid_fill()
        # Add circular boundary using edge path
        edge_path = hatch.paths.add_edge_path()
        edge_path.add_arc(
            center=center,
            radius=radius,
            start_angle=0,
            end_angle=360,
        )

    # -- DXF block import (native CAD symbol quality) --

    def import_symbol_blocks(self, reference_dxf_path: str) -> None:
        """
        Import all SLD symbol block definitions from a reference DXF file.

        Imports every named block (excluding anonymous *-prefixed and AutoCAD
        internal A$C-prefixed blocks) from the reference DXF into this document.

        Args:
            reference_dxf_path: Path to a template DXF file (e.g., "150A TPN SLD 1 DWG.dxf")
        """
        try:
            ref_doc = ezdxf.readfile(reference_dxf_path)
        except Exception as e:
            logger.warning(f"Failed to read reference DXF for block import: {e}")
            return

        imported = []

        for block in ref_doc.blocks:
            block_name = block.name
            # Skip anonymous, standard, and AutoCAD internal blocks
            if block_name.startswith("*") or block_name.startswith("_") or block_name.startswith("A$C"):
                continue
            # Skip empty blocks and already-imported blocks
            if not list(block) or block_name in self._doc.blocks:
                continue

            try:
                new_block = self._doc.blocks.new(name=block_name)
                for entity in block:
                    new_block.add_entity(entity.copy())
                imported.append(block_name)
            except Exception as e:
                logger.warning(f"Failed to import block '{block_name}': {e}")

        if imported:
            logger.info(f"Imported DXF symbol blocks: {imported}")

    def has_block(self, block_name: str) -> bool:
        """Check if a named block definition exists in this document."""
        return block_name in self._doc.blocks

    def insert_block(
        self,
        block_name: str,
        x: float,
        y: float,
        *,
        scale: float = 1.0,
        rotation: float = 0.0,
    ) -> None:
        """
        Insert a block reference at the given position.

        Args:
            block_name: Name of the block to insert (must exist via import_symbol_blocks).
            x, y: Insertion point in mm.
            scale: Uniform scale factor (xscale = yscale).
            rotation: Rotation in degrees CCW.
        """
        self._msp.add_blockref(
            block_name,
            insert=(x, y),
            dxfattribs={
                "layer": self._current_layer,
                "xscale": scale,
                "yscale": scale,
                "rotation": rotation,
            },
        )

    def create_fanout_block(
        self,
        center_x: float,
        busbar_y: float,
        side_xs: list[float],
        mcb_bottom_y: float,
    ) -> None:
        """Create and insert a 3-phase fan-out block at the given busbar position.

        Generates a unique block definition for this fan-out geometry, matching
        the reference DXF pattern (63A TPN SLD 14):
          - Center: vertical line from busbar to MCB
          - Sides: diagonal from center busbar to intermediate, then vertical to MCB

        Reference ratio: fan_height / spacing = 193 / 727 ≈ 0.266

        Args:
            center_x: X coordinate of center circuit on busbar.
            busbar_y: Y coordinate of busbar line.
            side_xs: X coordinates of side circuits (1 or 2 elements).
            mcb_bottom_y: Y coordinate of MCB bottom contact.
        """
        _FAN_RATIO = 0.266

        # Build a unique block name based on geometry
        side_count = len(side_xs)
        spacings = [abs(sx - center_x) for sx in side_xs]
        avg_sp = sum(spacings) / len(spacings) if spacings else 0
        block_name = f"FANOUT_3P_{side_count}S_{avg_sp:.1f}"

        total_h = mcb_bottom_y - busbar_y  # total height from busbar to MCB

        if block_name not in self._doc.blocks:
            # Block origin = (0, 0) at center busbar junction
            block = self._doc.blocks.new(name=block_name)
            layer = "SLD_CONNECTIONS"
            attribs = {"layer": layer, "lineweight": 25}

            # Center vertical: busbar to MCB
            block.add_line((0, 0), (0, total_h), dxfattribs=attribs)

            for sx in side_xs:
                dx = sx - center_x  # signed offset
                fan_h = abs(dx) * _FAN_RATIO

                # Diagonal: center busbar → side intermediate
                block.add_line((0, 0), (dx, fan_h), dxfattribs=attribs)
                # Side vertical: intermediate → MCB bottom
                block.add_line((dx, fan_h), (dx, total_h), dxfattribs=attribs)

        # Insert the block
        self._msp.add_blockref(
            block_name,
            insert=(center_x, busbar_y),
            dxfattribs={
                "layer": "SLD_CONNECTIONS",
                "xscale": 1.0,
                "yscale": 1.0,
            },
        )

    # -- Output --

    def save(self, path: str) -> None:
        """Save the DXF document to a file."""
        self._doc.saveas(path)
        logger.info(f"DXF saved: {path}")

    def get_bytes(self) -> bytes:
        """Get the DXF document as bytes (in-memory)."""
        stream = io.StringIO()
        self._doc.write(stream)
        return stream.getvalue().encode("utf-8")

    def to_pdf_bytes(self) -> bytes:
        """
        Convert DXF to PDF using ezdxf's PyMuPDF rendering backend.

        Returns PDF bytes suitable for EMA submission.
        """
        try:
            from ezdxf.addons.drawing import Frontend, RenderContext
            from ezdxf.addons.drawing.pymupdf import PyMuPdfBackend
        except ImportError:
            logger.error("ezdxf PyMuPDF backend not available. Install with: pip install ezdxf[draw]")
            raise

        import fitz  # PyMuPDF

        ctx = RenderContext(self._doc)
        # Page dimensions → points (1mm = 2.8346pt)
        page_width_pt = self._page_config.page_width * 2.8346
        page_height_pt = self._page_config.page_height * 2.8346

        pdf_doc = fitz.open()
        page = pdf_doc.new_page(width=page_width_pt, height=page_height_pt)

        out = PyMuPdfBackend(page)
        frontend = Frontend(ctx, out)
        frontend.draw_layout(self._msp)
        out.finalize()

        pdf_bytes = pdf_doc.tobytes()
        pdf_doc.close()
        return pdf_bytes

    def to_svg_string(self) -> str:
        """
        Convert DXF to SVG using ezdxf's SVG rendering backend.

        Returns SVG string for web preview.
        """
        try:
            from ezdxf.addons.drawing import Frontend, RenderContext
            from ezdxf.addons.drawing.svg import SVGBackend
        except ImportError:
            logger.error("ezdxf SVG backend not available.")
            raise

        ctx = RenderContext(self._doc)
        out = SVGBackend()
        frontend = Frontend(ctx, out)
        frontend.draw_layout(self._msp)

        # Get SVG element from backend
        svg_element = out.get_svg_document(
            width=_PAGE_WIDTH,
            height=_PAGE_HEIGHT,
        )
        return svg_element.tostring().decode("utf-8") if hasattr(svg_element, 'tostring') else str(svg_element)

    @property
    def doc(self) -> ezdxf.document.Drawing:
        """Access the underlying ezdxf document for advanced operations."""
        return self._doc
