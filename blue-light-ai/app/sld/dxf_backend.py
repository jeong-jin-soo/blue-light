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
}

# Text style name
_TEXT_STYLE = "REAL_SLD"


class DxfBackend:
    """
    ezdxf-based DXF drawing backend.

    Coordinate system: bottom-left origin, mm units (matching DrawingBackend protocol).
    No coordinate transformation needed — DXF native system matches our convention.
    """

    def __init__(self):
        self._doc = ezdxf.new("R2013")  # AutoCAD 2013 format for broad compatibility
        self._doc.units = units.MM
        self._msp = self._doc.modelspace()
        self._current_layer = "SLD_SYMBOLS"

        self._setup_layers()
        self._setup_text_style()

    def _setup_layers(self) -> None:
        """Create DXF layers matching real LEW SLD style."""
        for name, cfg in _LAYER_CONFIG.items():
            self._doc.layers.add(
                name,
                color=cfg["color"],
                lineweight=cfg["lineweight"],
            )

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
    ) -> None:
        """
        Draw multiline text using MTEXT entity.

        Args:
            text: Text content. '\\P' for line breaks (DXF convention).
            insert: Position (x, y) in mm — top-left anchor.
            char_height: Character height in mm.
            rotation: Text rotation in degrees CCW.
        """
        if not isinstance(text, str):
            text = str(text)

        attribs = self._dxfattribs()

        mtext = self._msp.add_mtext(text, dxfattribs=attribs)
        mtext.dxf.insert = insert
        mtext.dxf.char_height = char_height
        mtext.dxf.style = _TEXT_STYLE
        mtext.dxf.attachment_point = 1  # TOP_LEFT

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
        # A3 landscape: 420mm x 297mm → points (1mm = 2.8346pt)
        page_width_pt = _PAGE_WIDTH * 2.8346
        page_height_pt = _PAGE_HEIGHT * 2.8346

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
