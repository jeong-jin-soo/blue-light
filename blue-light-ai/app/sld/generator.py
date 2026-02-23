"""
SLD Generator — orchestrates PDF creation using the symbol library and layout engine.

Creates a complete Single Line Diagram with:
- Incoming supply (3-phase / single-phase line representation)
- Metering
- Main breaker (with circuit ID)
- Main busbar
- Sub-circuit breakers (with circuit IDs, cable annotations, load info)
- Earth protection (with dashed conductor connections)
- Title block with project info
- Cable schedule table

Outputs PDF (for EMA submission) and SVG (for web preview).
"""

import logging
import math

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
        component_count = 0
        for backend in (pdf, svg):
            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)
            self._draw_cable_schedule(backend, requirements, layout_result)
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
                    char_height=2.5,
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
                # Busbar label (above)
                if comp.label:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.label,
                        insert=(layout_result.busbar_start_x, layout_result.busbar_y + 8),
                        char_height=2.5,
                    )
                # Busbar rating (right side)
                if comp.rating:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.rating,
                        insert=(layout_result.busbar_end_x - 30, layout_result.busbar_y + 4),
                        char_height=2,
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    symbol.draw(backend, comp.x, comp.y)

                    # Circuit ID + rating label (to the right)
                    backend.set_layer("SLD_ANNOTATIONS")
                    label_text = ""
                    if comp.circuit_id:
                        label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
                    elif comp.rating:
                        label_text = f"{comp.label}\\P{comp.rating}"

                    if label_text:
                        backend.add_mtext(
                            label_text,
                            insert=(comp.x + 14, comp.y + 10),
                            char_height=2.2,
                        )

                    # Cable annotation (below and to the right)
                    if comp.cable_annotation:
                        backend.set_layer("SLD_ANNOTATIONS")
                        backend.add_mtext(
                            comp.cable_annotation,
                            insert=(comp.x + 14, comp.y + 2),
                            char_height=1.8,
                        )

                    count += 1
                else:
                    logger.warning(f"Unknown symbol: {comp.symbol_name}")

        return count

    def _draw_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw all solid connection lines."""
        backend.set_layer("SLD_CONNECTIONS")
        for start, end in layout_result.connections:
            backend.add_line(start, end)

    def _draw_dashed_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw dashed connection lines (earth conductors, etc.)."""
        backend.set_layer("SLD_CONNECTIONS")
        for start, end in layout_result.dashed_connections:
            _draw_dashed_line(backend, start, end, dash_len=3.0, gap_len=2.0)

    def _draw_cable_schedule(
        self,
        backend: DrawingBackend,
        requirements: dict,
        layout_result: LayoutResult,
    ) -> None:
        """Draw a compact cable schedule table in the lower-left area."""
        sub_circuits = requirements.get("sub_circuits", [])
        if not sub_circuits:
            return

        # Cable schedule position (bottom-left, above title block)
        table_x = 15
        table_y = 53  # Just above title block
        row_height = 3.5
        col_widths = [20, 50, 25, 25, 35]  # ID, Name, Breaker, Rating, Cable

        backend.set_layer("SLD_TITLE_BLOCK")

        # Header
        headers = ["Circuit", "Description", "Breaker", "Rating", "Cable"]
        header_y = table_y
        x = table_x
        for i, header in enumerate(headers):
            backend.add_mtext(
                header,
                insert=(x + 1, header_y),
                char_height=2,
            )
            x += col_widths[i]

        # Header underline
        total_width = sum(col_widths)
        backend.add_line(
            (table_x, header_y - 3),
            (table_x + total_width, header_y - 3),
        )

        # Data rows
        backend.set_layer("SLD_ANNOTATIONS")
        for idx, circuit in enumerate(sub_circuits):
            row_y = header_y - 3 - (idx + 1) * row_height
            if row_y < 12:  # Don't go below page margin
                break

            circuit_id = f"CB-{idx + 1:02d}"
            name = circuit.get("name", f"DB-{idx + 1}")
            breaker_type = circuit.get("breaker_type", "MCB")
            breaker_rating = circuit.get("breaker_rating", 32)
            cable = circuit.get("cable", "-")

            row_data = [circuit_id, name, breaker_type, f"{breaker_rating}A", cable or "-"]
            x = table_x
            for i, text in enumerate(row_data):
                backend.add_mtext(
                    str(text),
                    insert=(x + 1, row_y),
                    char_height=1.8,
                )
                x += col_widths[i]


# ── Helper: Dashed line drawing ──────────────────────


def _draw_dashed_line(
    backend: DrawingBackend,
    start: tuple[float, float],
    end: tuple[float, float],
    dash_len: float = 3.0,
    gap_len: float = 2.0,
) -> None:
    """Draw a dashed line between two points."""
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    length = math.sqrt(dx * dx + dy * dy)

    if length < 0.1:
        return

    # Normalize direction
    ux, uy = dx / length, dy / length
    segment_len = dash_len + gap_len

    pos = 0.0
    while pos < length:
        seg_start = (start[0] + ux * pos, start[1] + uy * pos)
        seg_end_pos = min(pos + dash_len, length)
        seg_end = (start[0] + ux * seg_end_pos, start[1] + uy * seg_end_pos)
        backend.add_line(seg_start, seg_end)
        pos += segment_len
