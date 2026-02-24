"""
SLD Generator (v3) -- orchestrates PDF creation using the symbol library and layout engine.

Creates a complete Single Line Diagram with:
- Incoming supply (3-phase / single-phase with L1/L2/L3/N labels)
- Isolator (for >= 45kVA installations)
- Metering
- Main breaker (with circuit ID)
- Main busbar (double-line professional representation)
- ELCB group protection
- Sub-circuit breakers (with circuit IDs, cable annotations, vertical name labels)
- Earth protection (with dashed conductor connections)
- Professional 7-cell title block
- Cable schedule table with grid lines
- Symbol legend

Outputs PDF (for EMA submission) and SVG (for web preview).
"""

import logging
import math

from app.sld.backend import DrawingBackend
from app.sld.layout import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    compute_layout,
    format_cable_spec,
)
from app.sld.pdf_backend import PdfBackend
from app.sld.svg_backend import SvgBackend
from app.sld.symbols.breakers import ACB, MCB, MCCB, RCCB, ELCB, CircuitBreaker
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.meters import Ammeter, KwhMeter
from app.sld.symbols.motors import Generator as GeneratorSymbol, Motor
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import ATS, Isolator
from app.sld.symbols.transformers import CurrentTransformer, PowerTransformer
from app.sld.title_block import draw_border, draw_title_block

logger = logging.getLogger(__name__)


class SldGenerator:
    """
    Generates complete SLD drawings in PDF format with SVG preview.
    """

    # Symbol registry -- maps type names to symbol classes
    SYMBOL_MAP: dict[str, type] = {
        "ACB": ACB,
        "MCCB": MCCB,
        "MCB": MCB,
        "RCCB": RCCB,
        "ELCB": ELCB,
        "TRANSFORMER": PowerTransformer,
        "CT": CurrentTransformer,
        "KWH_METER": KwhMeter,
        "AMMETER": Ammeter,
        "MOTOR": Motor,
        "GENERATOR": GeneratorSymbol,
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

        # Compute layout (pure coordinate computation -- backend-independent)
        layout_result = compute_layout(requirements)

        # Draw to both backends simultaneously
        component_count = 0
        for backend in (pdf, svg):
            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)
            self._draw_cable_schedule(backend, requirements, layout_result)
            self._draw_legend(backend, layout_result)
            draw_border(backend)
            draw_title_block(
                backend,
                project_name=application_info.get("address", "Electrical Installation"),
                address=application_info.get("address", ""),
                postal_code=application_info.get("postalCode", ""),
                kva=requirements.get("kva", 0),
                lew_name=application_info.get("assignedLewName", ""),
                lew_licence=application_info.get("assignedLewLicenceNo", ""),
                lew_mobile=application_info.get("assignedLewMobile", ""),
                sld_only_mode=application_info.get("sld_only_mode", False),
                client_name=application_info.get("clientName", ""),
                main_contractor=application_info.get("mainContractor", ""),
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
                    char_height=2.8,
                    rotation=comp.rotation,
                )
                count += 1

            elif comp.symbol_name == "BUSBAR":
                # Busbar -- draw as double thick lines
                backend.set_layer("SLD_SYMBOLS")
                gap = 2.0
                bus_start_x = layout_result.busbar_start_x
                bus_end_x = layout_result.busbar_end_x

                if comp.label:  # Main busbar
                    # Top line
                    backend.add_line(
                        (bus_start_x, layout_result.busbar_y + gap / 2),
                        (bus_end_x, layout_result.busbar_y + gap / 2),
                        lineweight=80,
                    )
                    # Bottom line
                    backend.add_line(
                        (bus_start_x, layout_result.busbar_y - gap / 2),
                        (bus_end_x, layout_result.busbar_y - gap / 2),
                        lineweight=80,
                    )
                else:
                    # Sub-busbar
                    backend.add_line(
                        (comp.x, comp.y + gap / 2),
                        (comp.x + (bus_end_x - bus_start_x), comp.y + gap / 2),
                        lineweight=60,
                    )
                    backend.add_line(
                        (comp.x, comp.y - gap / 2),
                        (comp.x + (bus_end_x - bus_start_x), comp.y - gap / 2),
                        lineweight=60,
                    )

                # Busbar label (left side, above busbar -- 10mm gap avoids overlap)
                if comp.label:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.label,
                        insert=(bus_start_x, layout_result.busbar_y + 10),
                        char_height=3.0,
                    )
                # Busbar rating (right side, above busbar)
                if comp.rating:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.rating,
                        insert=(bus_end_x - 30, layout_result.busbar_y + 10),
                        char_height=2.5,
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, isolator, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    symbol.draw(backend, comp.x, comp.y)

                    # Circuit ID + rating label (to the right, above symbol center)
                    backend.set_layer("SLD_ANNOTATIONS")
                    label_text = ""
                    if comp.circuit_id:
                        label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
                    elif comp.rating:
                        label_text = f"{comp.label}\\P{comp.rating}"

                    if label_text:
                        backend.add_mtext(
                            label_text,
                            insert=(comp.x + 18, comp.y + 14),
                            char_height=2.3,
                        )

                    # Cable annotation (below symbol, to the right)
                    if comp.cable_annotation:
                        backend.set_layer("SLD_ANNOTATIONS")
                        backend.add_mtext(
                            comp.cable_annotation,
                            insert=(comp.x + 18, comp.y - 3),
                            char_height=2.0,
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
        """Draw a cable schedule table with grid lines in the lower-left area."""
        sub_circuits = requirements.get("sub_circuits", [])
        if not sub_circuits:
            return

        # Cable schedule position (bottom-left, above title block)
        table_x = 15
        table_y = 55  # Just above title block
        row_height = 5.0
        col_widths = [22, 55, 25, 25, 40]  # ID, Name, Breaker, Rating, Cable
        total_width = sum(col_widths)
        total_rows = min(len(sub_circuits) + 1, 9)  # +1 for header, max 8 data rows

        backend.set_layer("SLD_TITLE_BLOCK")

        # Table border
        table_height = total_rows * row_height
        backend.add_lwpolyline(
            [
                (table_x, table_y),
                (table_x + total_width, table_y),
                (table_x + total_width, table_y + table_height),
                (table_x, table_y + table_height),
            ],
            close=True,
        )

        # Column dividers
        x_pos = table_x
        for w in col_widths[:-1]:
            x_pos += w
            backend.add_line(
                (x_pos, table_y),
                (x_pos, table_y + table_height),
            )

        # Row dividers
        for row in range(total_rows):
            row_y_line = table_y + table_height - row * row_height
            backend.add_line(
                (table_x, row_y_line),
                (table_x + total_width, row_y_line),
            )

        # Header text
        header_y = table_y + table_height - 1
        headers = ["Circuit", "Description", "Breaker", "Rating", "Cable"]
        x_pos = table_x
        backend.set_layer("SLD_TITLE_BLOCK")
        for idx, header in enumerate(headers):
            backend.add_mtext(
                header,
                insert=(x_pos + 2, header_y),
                char_height=2.5,
            )
            x_pos += col_widths[idx]

        # Data rows
        backend.set_layer("SLD_ANNOTATIONS")
        from app.sld.layout import _classify_circuit

        for idx, circuit in enumerate(sub_circuits):
            if idx >= total_rows - 1:
                break

            row_y = header_y - (idx + 1) * row_height

            sc_name = str(circuit.get("name", f"DB-{idx + 1}"))
            circuit_id = _classify_circuit(sc_name, idx)
            breaker_type = str(circuit.get("breaker_type", "MCB"))
            breaker_rating = circuit.get("breaker_rating", 32)
            cable = format_cable_spec(circuit.get("cable", "-"))

            row_data = [circuit_id, sc_name, breaker_type, f"{breaker_rating}A", cable or "-"]
            x_pos = table_x
            for i, text in enumerate(row_data):
                backend.add_mtext(
                    str(text),
                    insert=(x_pos + 2, row_y),
                    char_height=2.2,
                )
                x_pos += col_widths[i]

    def _draw_legend(
        self,
        backend: DrawingBackend,
        layout_result: LayoutResult,
    ) -> None:
        """Draw a symbol legend box in the right area (below incoming supply)."""
        legend_x = 330
        legend_y = 200  # Safely within page bounds (top will be at y=240)
        row_h = 8
        col_w = 70

        backend.set_layer("SLD_TITLE_BLOCK")

        # Legend border
        legend_items = [
            ("MCCB", "Moulded Case Circuit Breaker"),
            ("MCB", "Miniature Circuit Breaker"),
            ("ELCB", "Earth Leakage Circuit Breaker"),
            ("kWh", "kWh Meter"),
            ("Earth", "Earth Bar / Ground Connection"),
        ]

        legend_height = (len(legend_items) + 1) * row_h
        backend.add_lwpolyline(
            [
                (legend_x, legend_y),
                (legend_x + col_w, legend_y),
                (legend_x + col_w, legend_y + legend_height),
                (legend_x, legend_y + legend_height),
            ],
            close=True,
        )

        # Legend title
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext(
            "LEGEND",
            insert=(legend_x + 3, legend_y + legend_height - 2),
            char_height=3.0,
        )

        # Divider under title
        backend.set_layer("SLD_TITLE_BLOCK")
        backend.add_line(
            (legend_x, legend_y + legend_height - row_h),
            (legend_x + col_w, legend_y + legend_height - row_h),
        )

        # Legend entries
        backend.set_layer("SLD_ANNOTATIONS")
        for i, (symbol_abbr, description) in enumerate(legend_items):
            entry_y = legend_y + legend_height - (i + 2) * row_h + 2

            # Draw small symbol representation
            if symbol_abbr in ("MCCB", "MCB", "ELCB"):
                # Small rectangle with X
                sx = legend_x + 4
                sy = entry_y + 1
                sw, sh = 5, 5
                backend.set_layer("SLD_SYMBOLS")
                backend.add_lwpolyline(
                    [(sx, sy), (sx + sw, sy), (sx + sw, sy + sh), (sx, sy + sh)],
                    close=True,
                )
                backend.add_line((sx, sy), (sx + sw, sy + sh))
                backend.add_line((sx + sw, sy), (sx, sy + sh))
            elif symbol_abbr == "kWh":
                # Small circle
                backend.set_layer("SLD_SYMBOLS")
                backend.add_circle((legend_x + 7, entry_y + 3.5), radius=3)
            elif symbol_abbr == "Earth":
                # Earth symbol (three horizontal lines)
                backend.set_layer("SLD_SYMBOLS")
                sx = legend_x + 7
                sy = entry_y + 1
                backend.add_line((sx - 3, sy), (sx + 3, sy))
                backend.add_line((sx - 2, sy + 1.5), (sx + 2, sy + 1.5))
                backend.add_line((sx - 1, sy + 3), (sx + 1, sy + 3))

            # Description text
            backend.set_layer("SLD_ANNOTATIONS")
            backend.add_mtext(
                f"{symbol_abbr} = {description}",
                insert=(legend_x + 14, entry_y + 5),
                char_height=2.0,
            )


# -- Helper: Dashed line drawing --


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
