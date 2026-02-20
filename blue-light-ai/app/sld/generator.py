"""
SLD Generator — orchestrates PDF creation using the symbol library and layout engine.

Creates a complete Single Line Diagram with:
- Incoming supply
- Metering
- Main breaker
- Main busbar
- Sub-circuit breakers
- Earth protection
- Title block with project info

Outputs PDF (for EMA submission) and SVG (for web preview).
"""

import logging

from app.sld.backend import DrawingBackend
from app.sld.layout import LayoutConfig, LayoutResult, PlacedComponent, compute_layout
from app.sld.pdf_backend import PdfBackend
from app.sld.svg_backend import SvgBackend
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
    Generates complete SLD drawings in PDF format with SVG preview.
    """

    # Symbol registry — maps type names to symbol classes
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

    def generate(
        self,
        requirements: dict,
        application_info: dict,
        pdf_output_path: str,
        svg_output_path: str | None = None,
    ) -> dict:
        """
        Generate the SLD drawing.

        Args:
            requirements: SLD requirements from the AI agent.
            application_info: Application details (address, kVA, etc.)
            pdf_output_path: Path to save the PDF file.
            svg_output_path: Optional path to save the SVG preview.

        Returns:
            dict with keys: svg_string, component_count, pdf_path
        """
        logger.info(
            f"Generating SLD: kVA={requirements.get('kva')}, "
            f"sub_circuits={len(requirements.get('sub_circuits', []))}"
        )

        # Create backends
        pdf = PdfBackend(pdf_output_path)
        svg = SvgBackend()

        # Compute layout (pure coordinate computation — backend-independent)
        layout_result = compute_layout(requirements)

        # Draw to both backends simultaneously
        for backend in (pdf, svg):
            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            draw_border(backend)
            draw_title_block(
                backend,
                project_name=application_info.get("address", "Electrical Installation"),
                address=application_info.get("address", ""),
                postal_code=application_info.get("postalCode", ""),
                kva=requirements.get("kva", 0),
                lew_name=application_info.get("assignedLewName", ""),
                lew_licence=application_info.get("assignedLewLicenceNo", ""),
                sld_only_mode=application_info.get("sld_only_mode", False),
            )

        # Save PDF
        pdf.save()
        logger.info(f"PDF saved: {pdf_output_path}")

        # Get SVG preview string
        svg_string = svg.get_svg_string()

        if svg_output_path:
            with open(svg_output_path, "w", encoding="utf-8") as f:
                f.write(svg_string)
            logger.info(f"SVG saved: {svg_output_path}")

        return {
            "svg_string": svg_string,
            "component_count": component_count,
            "pdf_path": pdf_output_path,
        }

    def _get_symbol(self, symbol_name: str):
        """Get a symbol instance by its block/type name."""
        # Map CB_XXX names back to the breaker type
        if symbol_name.startswith("CB_"):
            breaker_type = symbol_name[3:]  # e.g., "CB_MCCB" -> "MCCB"
            cls = self.SYMBOL_MAP.get(breaker_type)
            if cls:
                return cls()

        cls = self.SYMBOL_MAP.get(symbol_name)
        if cls:
            return cls()
        return None

    def _draw_components(self, backend: DrawingBackend, layout_result: LayoutResult) -> int:
        """Draw all components from the layout result. Returns count."""
        count = 0

        for comp in layout_result.components:
            if comp.symbol_name == "LABEL":
                # Text-only component
                backend.set_layer("SLD_ANNOTATIONS")
                backend.add_mtext(
                    comp.label,
                    insert=(comp.x, comp.y),
                    char_height=3,
                )
                count += 1

            elif comp.symbol_name == "BUSBAR":
                # Busbar — draw as thick line directly
                backend.set_layer("SLD_SYMBOLS")
                backend.add_line(
                    (layout_result.busbar_start_x, layout_result.busbar_y),
                    (layout_result.busbar_end_x, layout_result.busbar_y),
                    lineweight=50,
                )
                # Busbar label
                backend.set_layer("SLD_ANNOTATIONS")
                backend.add_mtext(
                    comp.label,
                    insert=(layout_result.busbar_start_x, layout_result.busbar_y + 8),
                    char_height=3,
                )
                if comp.rating:
                    backend.add_mtext(
                        comp.rating,
                        insert=(layout_result.busbar_end_x - 30, layout_result.busbar_y + 4),
                        char_height=2.5,
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    symbol.draw(backend, comp.x, comp.y)

                    # Rating label (to the right)
                    if comp.rating:
                        backend.set_layer("SLD_ANNOTATIONS")
                        backend.add_mtext(
                            f"{comp.label}\\P{comp.rating}",
                            insert=(comp.x + 14, comp.y + 10),
                            char_height=2.5,
                        )

                    # Cable annotation (below and to the right)
                    if comp.cable_annotation:
                        backend.set_layer("SLD_ANNOTATIONS")
                        backend.add_mtext(
                            comp.cable_annotation,
                            insert=(comp.x + 14, comp.y + 3),
                            char_height=2,
                        )

                    count += 1
                else:
                    logger.warning(f"Unknown symbol: {comp.symbol_name}")

        return count

    def _draw_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw all connection lines."""
        backend.set_layer("SLD_CONNECTIONS")
        for start, end in layout_result.connections:
            backend.add_line(start, end)
