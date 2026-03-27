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

# DXF layer names matching AutoCAD LEW reference (I2R-ETR-NLB-SLD-1.dxf)
# ACI color 2 = yellow (symbol blocks), ACI 7 = white/black, ACI 8 = gray
_LAYER_CONFIG: dict[str, dict] = {
    "SLD": {"color": 2, "lineweight": 25},                # symbol/block inserts (yellow)
    "SLD-LINE": {"color": 7, "lineweight": 25},           # connection lines, spine
    "SLD-TXT": {"color": 7, "lineweight": 25},            # text labels
    "TXT": {"color": 7, "lineweight": 25},                # title block text
    "E-SLD-LEGENDF": {"color": 2, "lineweight": 25},      # symbol legend (yellow)
    "E-SLD-BOX": {"color": 8, "lineweight": 25, "linetype": "CENTER"},  # dashed boxes
    "E-SLD-FRAME": {"color": 8, "lineweight": 25},        # drawing border/frame
}

# Mapping from logical layer names to DXF layer names.
_LOGICAL_TO_DXF_LAYER: dict[str, str] = {
    "SLD_SYMBOLS": "SLD",
    "SLD_CONNECTIONS": "SLD-LINE",
    "SLD_POWER_MAIN": "SLD-LINE",          # busbar uses SLD-LINE with heavier lineweight
    "SLD_ANNOTATIONS": "SLD-TXT",
    "SLD_TITLE_BLOCK": "TXT",
    "SLD_DB_FRAME": "E-SLD-BOX",
    "SLD_FRAME": "E-SLD-FRAME",
    # Direct names accepted
    "SLD": "SLD",
    "SLD-LINE": "SLD-LINE",
    "SLD-TXT": "SLD-TXT",
    "TXT": "TXT",
    "E-SLD-LEGENDF": "E-SLD-LEGENDF",
    "E-SLD-BOX": "E-SLD-BOX",
    "E-SLD-FRAME": "E-SLD-FRAME",
    # Legacy names for backward compat
    "E-SLD-SYM": "SLD",
    "E-SLD-LINE": "SLD-LINE",
    "E-SLD-BUSBAR": "SLD-LINE",
    "E-SLD-TXT": "SLD-TXT",
    "E-SLD-TITLE": "TXT",
}

# Text style name — matches LEW AutoCAD reference (romans.shx, width 0.75)
_TEXT_STYLE = "SLD-TXT"


class DxfBackend:
    """
    ezdxf-based DXF drawing backend.

    Coordinate system: bottom-left origin, mm units (matching DrawingBackend protocol).
    No coordinate transformation needed — DXF native system matches our convention.
    """

    def __init__(self, page_config: PageConfig | None = None):
        self._page_config = page_config or A3_LANDSCAPE
        self._doc = ezdxf.new("R2018")  # AutoCAD 2018 (AC1032, matches LEW reference)
        self._doc.units = units.MM
        self._doc.header['$PSLTSCALE'] = 0    # Paper space linetype scale disabled
        self._doc.header['$TEXTSIZE'] = 2.5
        self._msp = self._doc.modelspace()
        self._current_layer = _LOGICAL_TO_DXF_LAYER["SLD_SYMBOLS"]
        # Content scale: virtual→physical coordinate transform
        self._content_scale = 1.0
        self._scale_cx = 210.0
        self._scale_cy = 148.5

        self._setup_layers()
        self._setup_text_style()
        self._setup_dimstyle()

    def _setup_dimstyle(self) -> None:
        """Set up dimension style for LEADER entities (matches reference dimstyle 'A')."""
        ds = self._doc.dimstyles.new("A")
        ds.dxf.dimasz = 1.5
        ds.dxf.dimtxt = 1.8
        ds.dxf.dimgap = 0.9

    def _setup_layers(self) -> None:
        """Create DXF layers matching AutoCAD LEW reference."""
        if "CENTER" not in self._doc.linetypes:
            self._doc.linetypes.add(
                "CENTER",
                pattern=[1.25, 0.75, -0.125, 0.25, -0.125],
                description="Center ____ _ ____ _ ____",
            )
        if "DASHED" not in self._doc.linetypes:
            self._doc.linetypes.add(
                "DASHED",
                pattern=[0.75, 0.5, -0.25],
                description="Dashed __ __ __ __ __",
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
        """Set up text styles matching LEW AutoCAD reference."""
        styles = self._doc.styles
        styles.add("SLD-TXT", font="romans.shx", dxfattribs={"width": 0.75})
        styles.add("ARIAL", font="arial.ttf", dxfattribs={"width": 0.95})
        styles.add("SIMPLEX", font="simplex.shx", dxfattribs={"width": 0.75})
        styles.add("TAHOMA", font="tahoma.ttf", dxfattribs={"width": 0.9})
        std = styles.get("Standard")
        std.dxf.font = "arial.ttf"
        self._doc.header['$TEXTSTYLE'] = _TEXT_STYLE

    def _dxfattribs(self, **extra) -> dict:
        """Build common dxfattribs dict for current layer."""
        attribs = {"layer": self._current_layer}
        attribs.update(extra)
        return attribs

    # -- Content scale (virtual→physical coordinate transform) --

    def begin_content_scale(self, scale: float, page_cx: float, page_cy: float) -> None:
        """Apply uniform scale transform for SLD content.

        The layout engine expands boundaries by 1/scale so content fills A3 after scaling.
        SVG/PDF backends use a group transform; DXF applies per-entity coordinate transform.
        """
        self._content_scale = scale
        self._scale_cx = page_cx
        self._scale_cy = page_cy

    def end_content_scale(self) -> None:
        """Reset content scale to 1:1."""
        self._content_scale = 1.0

    def _tx(self, x: float, y: float) -> tuple[float, float]:
        """Transform virtual coordinates to physical page coordinates."""
        s = self._content_scale
        if abs(s - 1.0) < 0.001:
            return (x, y)
        cx, cy = self._scale_cx, self._scale_cy
        return (cx + (x - cx) * s, cy + (y - cy) * s)

    # -- Layer management --

    def set_layer(self, layer_name: str) -> None:
        self._current_layer = _LOGICAL_TO_DXF_LAYER.get(layer_name, layer_name)

    # -- Drawing primitives --

    def add_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        lineweight: int | None = None,
    ) -> None:
        # Guard against zero-length lines (causes AutoCAD AUDIT warnings)
        if abs(start[0]-end[0]) < 0.001 and abs(start[1]-end[1]) < 0.001:
            return
        attribs = self._dxfattribs()
        if lineweight is not None:
            attribs["lineweight"] = lineweight
        self._msp.add_line(self._tx(*start), self._tx(*end), dxfattribs=attribs)

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
        pline = self._msp.add_lwpolyline([self._tx(*p) for p in points], dxfattribs=attribs)
        if close:
            pline.close()

    def add_filled_polygon(
        self,
        points: list[tuple[float, float]],
    ) -> None:
        """Draw a filled polygon using HATCH with SOLID pattern."""
        hatch = self._msp.add_hatch(dxfattribs={"layer": self._current_layer})
        hatch.set_solid_fill()
        hatch.paths.add_polyline_path([self._tx(p[0], p[1]) for p in points], is_closed=True)

    def add_circle(
        self,
        center: tuple[float, float],
        radius: float,
    ) -> None:
        self._msp.add_circle(self._tx(*center), radius * self._content_scale, dxfattribs=self._dxfattribs())

    def add_arc(
        self,
        center: tuple[float, float],
        radius: float,
        start_angle: float,
        end_angle: float,
    ) -> None:
        """Draw an arc. Angles in degrees, CCW from positive X-axis (DXF native convention)."""
        self._msp.add_arc(
            self._tx(*center),
            radius * self._content_scale,
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
        width_factor: float = 0.0,
    ) -> None:
        """
        Draw multiline text using MTEXT entity.

        Args:
            text: Text content. '\\P' for line breaks (DXF convention).
            insert: Position (x, y) in mm — top-left anchor.
            char_height: Character height in mm.
            rotation: Text rotation in degrees CCW.
            center_across: If True, center text block across the rotation axis.
            width_factor: If > 0, wrap text in \\W code (e.g., 0.65 = 65% width).
        """
        if not isinstance(text, str):
            text = str(text)

        if width_factor > 0:
            text = f"{{\\W{width_factor};{text}}}"

        attribs = self._dxfattribs()

        mtext = self._msp.add_mtext(text, dxfattribs=attribs)
        mtext.dxf.insert = self._tx(*insert)
        mtext.dxf.char_height = char_height * self._content_scale
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
        """Draw a filled rectangle — DXF uses outline only (minimize HATCH for AutoCAD)."""
        pts = [(x, y), (x + width, y), (x + width, y + height), (x, y + height)]
        self.add_lwpolyline(pts, close=True)

    def add_filled_circle(
        self,
        center: tuple[float, float],
        radius: float,
        *,
        fill_color: tuple[float, float, float] | str = (0.0, 0.0, 0.0),
    ) -> None:
        """Draw a filled circle — DXF uses outline only (minimize HATCH for AutoCAD)."""
        self.add_circle(center, radius)

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
        tx, ty = self._tx(x, y)
        s = self._content_scale
        self._msp.add_blockref(
            block_name,
            insert=(tx, ty),
            dxfattribs={
                "layer": self._current_layer,
                "xscale": scale * s,
                "yscale": scale * s,
                "rotation": rotation,
            },
        )

    # -- Composite drawing methods (DrawingBackend protocol) --

    def draw_center_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
        *,
        long_dash: float = 8.0,
        short_dash: float = 1.5,
        gap: float = 2.0,
    ) -> None:
        """Draw IEC CENTER linetype line using native DXF linetype on SLD_DB_FRAME layer."""
        prev_layer = self._current_layer
        self.set_layer("SLD_DB_FRAME")
        self.add_line(start, end)
        self._current_layer = prev_layer

    def draw_short_dashed_line(
        self,
        start: tuple[float, float],
        end: tuple[float, float],
    ) -> None:
        """Draw a regular short-dashed line (e.g., SPARE conductor tails)."""
        attribs = self._dxfattribs()
        attribs["linetype"] = "DASHED"
        self._msp.add_line(self._tx(*start), self._tx(*end), dxfattribs=attribs)

    def draw_fanout(
        self,
        center_x: float,
        busbar_y: float,
        side_xs: list[float],
        mcb_entry_y: float,
    ) -> None:
        """Create and insert a 3-phase fan-out block at the given busbar position.

        mcb_entry_y = MCB busbar-side entry pin. Lines stop here;
        MCB symbol draws its own body and exit stub internally.

        Reference ratio: fan_height / spacing = 193 / 727 ≈ 0.266
        """
        _FAN_RATIO = 0.266

        # Build a unique block name based on geometry
        side_count = len(side_xs)
        spacings = [abs(sx - center_x) for sx in side_xs]
        avg_sp = sum(spacings) / len(spacings) if spacings else 0
        block_name = f"FANOUT_3P_{side_count}S_{avg_sp:.1f}"

        total_h = mcb_entry_y - busbar_y  # height from busbar to MCB entry pin

        if block_name not in self._doc.blocks:
            # Block origin = (0, 0) at center busbar junction
            block = self._doc.blocks.new(name=block_name)
            layer = "SLD-LINE"
            attribs = {"layer": layer, "lineweight": 25}

            # Center vertical: busbar → MCB entry pin
            block.add_line((0, 0), (0, total_h), dxfattribs=attribs)

            for sx in side_xs:
                dx = sx - center_x  # signed offset
                fan_h = abs(dx) * _FAN_RATIO

                # Diagonal: center busbar → side intermediate
                block.add_line((0, 0), (dx, fan_h), dxfattribs=attribs)
                # Side vertical: intermediate → MCB entry pin
                block.add_line((dx, fan_h), (dx, total_h), dxfattribs=attribs)

        # Insert the block (apply content scale transform)
        tx, ty = self._tx(center_x, busbar_y)
        s = self._content_scale
        self._msp.add_blockref(
            block_name,
            insert=(tx, ty),
            dxfattribs={
                "layer": "SLD-LINE",
                "xscale": s,
                "yscale": s,
            },
        )

    # -- Paper Space --

    def setup_paper_space(self) -> None:
        """Create A3 landscape Paper Space layout with viewport into Model Space."""
        try:
            layout = self._doc.layouts.get("Layout1")
        except KeyError:
            layout = self._doc.layouts.new("Layout1")

        min_x, min_y = float('inf'), float('inf')
        max_x, max_y = float('-inf'), float('-inf')
        for e in self._msp:
            if e.dxftype() == 'LINE':
                for pt in [e.dxf.start, e.dxf.end]:
                    min_x, min_y = min(min_x, pt[0]), min(min_y, pt[1])
                    max_x, max_y = max(max_x, pt[0]), max(max_y, pt[1])
            elif hasattr(e.dxf, 'insert'):
                min_x = min(min_x, e.dxf.insert[0])
                min_y = min(min_y, e.dxf.insert[1])
                max_x = max(max_x, e.dxf.insert[0])
                max_y = max(max_y, e.dxf.insert[1])

        if min_x == float('inf'):
            return

        ms_cx = (min_x + max_x) / 2
        ms_cy = (min_y + max_y) / 2
        ms_h = max_y - min_y
        ms_w = max_x - min_x

        pw = self._page_config.page_width
        ph = self._page_config.page_height
        margin = 10.0
        vp_w, vp_h = pw - 2 * margin, ph - 2 * margin

        scale_x = ms_w / vp_w if ms_w > 0 else 1
        scale_y = ms_h / vp_h if ms_h > 0 else 1
        view_height = ms_h * max(scale_x / scale_y, 1.0) if scale_y > scale_x else ms_h

        layout.add_viewport(
            center=(pw / 2, ph / 2), size=(vp_w, vp_h),
            view_center_point=(ms_cx, ms_cy), view_height=view_height, status=1,
        )

    # -- Output --

    def save(self, path: str) -> None:
        """Save the DXF document to a file."""
        self.setup_paper_space()
        self._doc.saveas(path)
        logger.info(f"DXF saved: {path}")

    def get_bytes(self) -> bytes:
        """Get the DXF document as bytes (in-memory)."""
        self.setup_paper_space()
        stream = io.StringIO()
        self._doc.write(stream)
        return stream.getvalue().encode("utf-8")

    def to_pdf_bytes(self) -> bytes:
        """
        Convert DXF to PDF using ezdxf's PyMuPDF rendering backend.

        Returns PDF bytes suitable for EMA submission.
        White background, black foreground, A3 landscape.
        """
        try:
            from ezdxf.addons.drawing import Frontend, RenderContext, layout
            from ezdxf.addons.drawing.pymupdf import PyMuPdfBackend
            from ezdxf.addons.drawing.config import (
                Configuration, ColorPolicy, LinePolicy, BackgroundPolicy,
            )
        except ImportError:
            logger.error(
                "ezdxf drawing backends not available. "
                "Install with: pip install 'ezdxf[draw]' PyMuPDF"
            )
            raise

        config = Configuration(
            color_policy=ColorPolicy.BLACK,
            background_policy=BackgroundPolicy.WHITE,
            custom_bg_color="#FFFFFF",
            custom_fg_color="#000000",
            lineweight_scaling=1.0,
            line_policy=LinePolicy.ACCURATE,
        )

        ctx = RenderContext(self._doc)
        backend = PyMuPdfBackend()
        frontend = Frontend(ctx, backend, config=config)
        frontend.draw_layout(self._msp)

        # Outer margins so border frame doesn't touch paper edge
        page = layout.Page(
            self._page_config.page_width,
            self._page_config.page_height,
            layout.Units.mm,
            margins=layout.Margins(top=5, right=5, bottom=5, left=5),
        )
        settings = layout.Settings(fit_page=True)
        return backend.get_pdf_bytes(page, settings=settings)

    def to_svg_string(self) -> str:
        """
        Convert DXF to SVG using ezdxf's SVG rendering backend.

        Returns SVG string for web preview.
        """
        try:
            from ezdxf.addons.drawing import Frontend, RenderContext, layout
            from ezdxf.addons.drawing.svg import SVGBackend as EzdxfSVGBackend
            from ezdxf.addons.drawing.config import (
                Configuration, ColorPolicy, BackgroundPolicy,
            )
        except ImportError:
            logger.error("ezdxf SVG backend not available.")
            raise

        config = Configuration(
            color_policy=ColorPolicy.BLACK,
            background_policy=BackgroundPolicy.WHITE,
            custom_bg_color="#FFFFFF",
            custom_fg_color="#000000",
        )

        ctx = RenderContext(self._doc)
        backend = EzdxfSVGBackend()
        frontend = Frontend(ctx, backend, config=config)
        frontend.draw_layout(self._msp)

        page = layout.Page(
            self._page_config.page_width,
            self._page_config.page_height,
            layout.Units.mm,
            margins=layout.Margins(top=5, right=5, bottom=5, left=5),
        )
        settings = layout.Settings(fit_page=True)
        return backend.get_string(page, settings=settings)

    @property
    def doc(self) -> ezdxf.document.Drawing:
        """Access the underlying ezdxf document for advanced operations."""
        return self._doc
