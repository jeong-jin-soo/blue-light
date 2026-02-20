"""
SLD Generator — orchestrates DXF creation using the symbol library and layout engine.

Creates a complete Single Line Diagram with:
- Incoming supply
- Metering
- Main breaker
- Main busbar
- Sub-circuit breakers
- Earth protection
- Title block with project info

Outputs both DXF (for CAD) and SVG (for web preview).
"""

import logging

import ezdxf
from ezdxf.document import Drawing

from app.sld.layout import LayoutConfig, LayoutResult, PlacedComponent, compute_layout
from app.sld.preview import doc_to_svg
from app.sld.symbols.breakers import ACB, MCB, MCCB, RCCB, CircuitBreaker
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.meters import Ammeter, KwhMeter
from app.sld.symbols.motors import Generator, Motor
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import ATS, Isolator
from app.sld.symbols.transformers import CurrentTransformer, PowerTransformer
from app.sld.title_block import draw_border, draw_title_block

logger = logging.getLogger(__name__)


class SldGenerator:
    """
    Generates complete SLD drawings in DXF format with SVG preview.
    """

    # Symbol registry — maps type names to symbol instances
    SYMBOL_MAP: dict[str, type] = {
        "ACB": ACB,
        "MCCB": MCCB,
        "MCB": MCB,
        "RCCB": RCCB,
        "TRANSFORMER": PowerTransformer,
        "CT": CurrentTransformer,
        "KWH_METER": KwhMeter,
        "AMMETER": Ammeter,
        "MOTOR": Motor,
        "GENERATOR": Generator,
        "ISOLATOR": Isolator,
        "ATS": ATS,
        "FUSE": Fuse,
        "EARTH": EarthSymbol,
        "SPD": SurgeProtector,
    }

    def __init__(self):
        self.doc: Drawing | None = None

    def generate(
        self,
        requirements: dict,
        application_info: dict,
        dxf_output_path: str,
        svg_output_path: str | None = None,
    ) -> dict:
        """
        Generate the SLD drawing.

        Args:
            requirements: SLD requirements from the AI agent.
            application_info: Application details (address, kVA, etc.)
            dxf_output_path: Path to save the DXF file.
            svg_output_path: Optional path to save the SVG preview.

        Returns:
            dict with keys: svg_string, component_count, dxf_path
        """
        logger.info(f"Generating SLD: kVA={requirements.get('kva')}, "
                     f"sub_circuits={len(requirements.get('sub_circuits', []))}")

        # Create DXF document
        self.doc = ezdxf.new(dxfversion="R2013", setup=True)
        self._setup_layers()

        # Register all needed symbols
        self._register_symbols(requirements)

        # Compute layout
        layout_result = compute_layout(requirements)

        # Draw components
        msp = self.doc.modelspace()
        component_count = self._draw_components(msp, layout_result)

        # Draw connections
        self._draw_connections(msp, layout_result)

        # Draw border and title block
        draw_border(msp)
        draw_title_block(
            msp,
            project_name=application_info.get("address", "Electrical Installation"),
            address=application_info.get("address", ""),
            postal_code=application_info.get("postalCode", ""),
            kva=requirements.get("kva", 0),
            lew_name=application_info.get("assignedLewName", ""),
            lew_licence=application_info.get("assignedLewLicenceNo", ""),
        )

        # Save DXF
        self.doc.saveas(dxf_output_path)
        logger.info(f"DXF saved: {dxf_output_path}")

        # Generate SVG preview
        svg_string = doc_to_svg(self.doc)

        if svg_output_path:
            with open(svg_output_path, "w", encoding="utf-8") as f:
                f.write(svg_string)
            logger.info(f"SVG saved: {svg_output_path}")

        return {
            "svg_string": svg_string,
            "component_count": component_count,
            "dxf_path": dxf_output_path,
        }

    def _setup_layers(self) -> None:
        """Create DXF layers for different element types."""
        layers = {
            "SLD_SYMBOLS": 4,       # Cyan
            "SLD_CONNECTIONS": 7,    # White
            "SLD_ANNOTATIONS": 2,   # Yellow
            "SLD_TITLE_BLOCK": 3,   # Green
        }
        for name, color in layers.items():
            self.doc.layers.add(name, color=color)

    def _register_symbols(self, requirements: dict) -> None:
        """Register all symbol blocks needed for this SLD."""
        # Always register common symbols
        common_types = ["MCCB", "MCB", "KWH_METER", "EARTH"]
        for sym_type in common_types:
            cls = self.SYMBOL_MAP.get(sym_type)
            if cls:
                cls().register(self.doc)

        # Register main breaker type
        main_breaker_type = requirements.get("main_breaker", {}).get("type", "MCCB")
        cls = self.SYMBOL_MAP.get(main_breaker_type)
        if cls:
            cls().register(self.doc)

        # Register sub-circuit breaker types
        for circuit in requirements.get("sub_circuits", []):
            sc_type = circuit.get("breaker_type", "MCB")
            cls = self.SYMBOL_MAP.get(sc_type)
            if cls:
                cls().register(self.doc)

    def _draw_components(self, msp, layout_result: LayoutResult) -> int:
        """Draw all components from the layout result. Returns count."""
        count = 0

        for comp in layout_result.components:
            if comp.symbol_name == "LABEL":
                # Text-only component
                msp.add_mtext(
                    comp.label,
                    dxfattribs={
                        "layer": "SLD_ANNOTATIONS",
                        "char_height": 3,
                        "insert": (comp.x, comp.y),
                    },
                )
                count += 1

            elif comp.symbol_name == "BUSBAR":
                # Busbar — draw as thick line directly
                bus_width = layout_result.busbar_end_x - layout_result.busbar_start_x
                msp.add_line(
                    (layout_result.busbar_start_x, layout_result.busbar_y),
                    (layout_result.busbar_end_x, layout_result.busbar_y),
                    dxfattribs={"layer": "SLD_SYMBOLS", "lineweight": 50},
                )
                # Busbar label
                msp.add_mtext(
                    comp.label,
                    dxfattribs={
                        "layer": "SLD_ANNOTATIONS",
                        "char_height": 3,
                        "insert": (layout_result.busbar_start_x, layout_result.busbar_y + 8),
                    },
                )
                if comp.rating:
                    msp.add_mtext(
                        comp.rating,
                        dxfattribs={
                            "layer": "SLD_ANNOTATIONS",
                            "char_height": 2.5,
                            "insert": (layout_result.busbar_end_x - 30, layout_result.busbar_y + 4),
                        },
                    )
                count += 1

            elif comp.symbol_name in self.doc.blocks:
                # Block reference (symbol)
                msp.add_blockref(
                    comp.symbol_name,
                    insert=(comp.x, comp.y),
                    dxfattribs={"layer": "SLD_SYMBOLS"},
                )

                # Rating label (to the right)
                if comp.rating:
                    msp.add_mtext(
                        f"{comp.label}\\P{comp.rating}",
                        dxfattribs={
                            "layer": "SLD_ANNOTATIONS",
                            "char_height": 2.5,
                            "insert": (comp.x + 14, comp.y + 10),
                        },
                    )

                # Cable annotation (below and to the right)
                if comp.cable_annotation:
                    msp.add_mtext(
                        comp.cable_annotation,
                        dxfattribs={
                            "layer": "SLD_ANNOTATIONS",
                            "char_height": 2,
                            "insert": (comp.x + 14, comp.y + 3),
                        },
                    )

                count += 1
            else:
                logger.warning(f"Unknown symbol: {comp.symbol_name}")

        return count

    def _draw_connections(self, msp, layout_result: LayoutResult) -> None:
        """Draw all connection lines."""
        for start, end in layout_result.connections:
            msp.add_line(
                start, end,
                dxfattribs={"layer": "SLD_CONNECTIONS"},
            )
