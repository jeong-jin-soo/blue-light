"""
SLD Generator (v6 LEW-style) -- orchestrates PDF creation using the symbol library and layout engine.

Creates a complete Single Line Diagram matching real LEW (Licensed Electrical Worker) conventions:
- Bottom-up layout: incoming supply at bottom, sub-circuits branch upward
- Vertical text labels (90-degree rotation) for circuit descriptions
- Multi-line breaker block labels (rating / poles / type / kA)
- Inline cable annotations along conductors (no cable schedule table)
- No legend (standard IEC symbols are self-explanatory to LEWs)
- Dense packing for maximum circuits per row

Components drawn:
- Incoming supply (3-phase / single-phase with L1/L2/L3/N labels)
- Current flow direction arrow (pointing upward)
- Isolator (for >= 45kVA installations)
- CT metering (for >= 45kVA installations)
- SP kWh Metering
- Main breaker (with circuit ID, kA fault rating, pole configuration)
- Main busbar (double-line professional representation)
- ELCB group protection
- Sub-circuit breakers (with breaker block labels, vertical text, cable annotations)
- Earth protection (with dashed conductor connections + conductor size)
- Professional 7-cell title block with NTS/Sheet

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
from app.sld.title_block import draw_border, draw_title_block_frame, fill_title_block_data

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

    # Full legend descriptions for all known symbols
    LEGEND_DESCRIPTIONS: dict[str, str] = {
        "ACB": "Air Circuit Breaker",
        "MCCB": "Moulded Case Circuit Breaker",
        "MCB": "Miniature Circuit Breaker",
        "ELCB": "Earth Leakage Circuit Breaker",
        "RCCB": "Residual Current Circuit Breaker",
        "KWH_METER": "kWh Meter (Energy Meter)",
        "EARTH": "Earth Bar / Ground Connection",
        "ISOLATOR": "Isolator / Disconnect Switch",
        "CT": "Current Transformer",
        "FUSE": "Fuse",
        "SPD": "Surge Protection Device",
        "ATS": "Automatic Transfer Switch",
        "MOTOR": "Motor",
        "GENERATOR": "Generator",
        "BUSBAR": "Busbar (Main Distribution)",
    }

    # Legend abbreviations (shorter form for display)
    LEGEND_ABBREVIATIONS: dict[str, str] = {
        "ACB": "ACB",
        "MCCB": "MCCB",
        "MCB": "MCB",
        "ELCB": "ELCB",
        "RCCB": "RCCB",
        "KWH_METER": "kWh",
        "EARTH": "Earth",
        "ISOLATOR": "Isolator",
        "CT": "CT",
        "FUSE": "Fuse",
        "SPD": "SPD",
        "ATS": "ATS",
        "MOTOR": "Motor",
        "GENERATOR": "Gen",
        "BUSBAR": "Busbar",
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
        layout_result = compute_layout(requirements, application_info=application_info)

        # Draw to both backends simultaneously
        # Phase 1: Template (border + title block frame with labels only)
        # Phase 2: SLD drawing (components, connections)
        # Phase 3: Fill title block data values
        component_count = 0
        for backend in (pdf, svg):
            # Phase 1 -- Template frame
            draw_border(backend)
            draw_title_block_frame(backend)

            # Phase 2 -- SLD content on top of template
            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)

            # Cable schedule and legend (disabled by default in v6 LEW-style)
            self._draw_cable_schedule(backend, requirements, layout_result)
            self._draw_legend(backend, layout_result)

            # Phase 3 -- Fill title block data
            fill_title_block_data(
                backend,
                project_name=application_info.get("address", "Electrical Installation"),
                address=application_info.get("address", ""),
                postal_code=application_info.get("postalCode", ""),
                kva=requirements.get("kva", 0),
                voltage=requirements.get("voltage", 0),
                supply_type=requirements.get("supply_type", ""),
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

            elif comp.symbol_name == "FLOW_ARROW":
                # Current flow direction arrow (downward pointing -- legacy)
                _draw_flow_arrow(backend, comp.x, comp.y, direction="down")
                count += 1

            elif comp.symbol_name == "FLOW_ARROW_UP":
                # Current flow direction arrow (upward pointing -- v6 LEW-style)
                _draw_flow_arrow(backend, comp.x, comp.y, direction="up")
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
                    # Sub-busbar (for multi-row circuits)
                    row_bus_width = bus_end_x - bus_start_x
                    backend.add_line(
                        (comp.x, comp.y + gap / 2),
                        (comp.x + row_bus_width, comp.y + gap / 2),
                        lineweight=60,
                    )
                    backend.add_line(
                        (comp.x, comp.y - gap / 2),
                        (comp.x + row_bus_width, comp.y - gap / 2),
                        lineweight=60,
                    )

                # Busbar label (left side, below busbar for bottom-up layout)
                if comp.label:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.label,
                        insert=(bus_start_x, layout_result.busbar_y - 5),
                        char_height=3.0,
                    )
                # Busbar rating (right side, below busbar)
                if comp.rating:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.rating,
                        insert=(bus_end_x - 30, layout_result.busbar_y - 5),
                        char_height=2.5,
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, isolator, CT, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    symbol.draw(backend, comp.x, comp.y)

                    backend.set_layer("SLD_ANNOTATIONS")

                    if comp.label_style == "breaker_block":
                        # LEW-style stacked breaker label block
                        self._draw_breaker_block_label(backend, comp)
                    else:
                        # Default label rendering (for incoming chain components)
                        label_text = ""
                        if comp.circuit_id:
                            label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
                        elif comp.rating:
                            label_text = f"{comp.label}\\P{comp.rating}"
                        elif comp.label:
                            label_text = comp.label

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

    def _draw_breaker_block_label(
        self,
        backend: DrawingBackend,
        comp: PlacedComponent,
    ) -> None:
        """
        Draw LEW-style breaker block label.

        Format (stacked multi-line):
            {rating}A
            {poles}
            {breaker_type}
            {fault_kA}kA

        For vertical text (rotation=90), text runs upward from the breaker.
        The circuit ID is NOT drawn here -- it's in the standalone label above the tail.
        """
        # Build breaker info -- render as SEPARATE text items for better spacing
        # Each line drawn individually to control vertical position precisely
        # Singapore SLD convention: characteristic prefix on rating (e.g., "B20A"),
        # breaker type without suffix (e.g., "MCB" not "MCB-B")
        info_items = []
        if comp.rating:
            rating_text = comp.rating  # e.g., "20A"
            if comp.breaker_characteristic:
                rating_text = f"{comp.breaker_characteristic}{comp.rating}"  # e.g., "B20A"
            info_items.append(rating_text)
        if comp.poles:
            info_items.append(comp.poles)  # e.g., "SPN"
        if comp.breaker_type_str:
            info_items.append(comp.breaker_type_str)  # e.g., "MCB" (no characteristic suffix)
        if comp.fault_kA:
            info_items.append(f"{comp.fault_kA}kA")  # e.g., "6kA"

        if abs(comp.rotation - 90.0) < 0.1:
            # Vertical text: breaker info to the RIGHT of the breaker, rotated 90 degrees
            # Draw each info line as a separate column, going rightward from breaker
            # Order: rating closest to breaker, fault_kA furthest right
            base_x = comp.x + 12  # Start just right of breaker symbol
            char_h = 1.8
            line_gap = char_h * 1.8  # ~3.2mm gap for clear separation
            for idx, line_text in enumerate(info_items):
                backend.add_mtext(
                    line_text,
                    insert=(base_x + idx * line_gap, comp.y),
                    char_height=char_h,
                    rotation=90.0,
                )

            # Inline cable annotation to the LEFT of conductor (vertical)
            if comp.cable_annotation:
                backend.add_mtext(
                    comp.cable_annotation,
                    insert=(comp.x - 6, comp.y),
                    char_height=1.6,
                    rotation=90.0,
                )
        else:
            # Horizontal text: breaker block to the right of symbol (stacked)
            block_text = "\\P".join(info_items)
            backend.add_mtext(
                block_text,
                insert=(comp.x + 18, comp.y + 14),
                char_height=2.0,
            )

            if comp.cable_annotation:
                backend.add_mtext(
                    comp.cable_annotation,
                    insert=(comp.x + 18, comp.y - 3),
                    char_height=1.8,
                )

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
        """
        Draw a cable schedule table with grid lines in the lower-left area.
        Disabled by default in v6 (LEW-style uses inline annotations).
        """
        if not layout_result.render_cable_schedule:
            return

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
        """
        Draw a dynamic symbol legend box in the right area.
        Only includes symbols that are actually used in the diagram.
        Disabled by default in v6 (LEW-style -- symbols are self-explanatory).
        """
        if not layout_result.render_legend:
            return

        legend_x = 320
        legend_y = 200  # Safely within page bounds (top will be at y=240)
        row_h = 8
        col_w = 80

        backend.set_layer("SLD_TITLE_BLOCK")

        # Build dynamic legend items based on symbols_used
        legend_items = []

        # Ordered list of possible legend entries (display priority)
        legend_order = [
            "ACB", "MCCB", "MCB", "ELCB", "RCCB",
            "ISOLATOR", "CT", "KWH_METER", "FUSE", "SPD",
            "ATS", "EARTH", "BUSBAR",
        ]

        for sym_key in legend_order:
            if sym_key in layout_result.symbols_used:
                abbr = self.LEGEND_ABBREVIATIONS.get(sym_key, sym_key)
                desc = self.LEGEND_DESCRIPTIONS.get(sym_key, sym_key)
                legend_items.append((abbr, desc, sym_key))

        # Always include busbar if there's a busbar in the layout
        if "BUSBAR" not in layout_result.symbols_used:
            legend_items.append(("Busbar", "Busbar (Main Distribution)", "BUSBAR"))

        if not legend_items:
            return

        legend_height = (len(legend_items) + 1) * row_h
        # Adjust legend_y if it would go above page
        if legend_y + legend_height > 275:
            legend_y = 275 - legend_height

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
        for i, (symbol_abbr, description, sym_key) in enumerate(legend_items):
            entry_y = legend_y + legend_height - (i + 2) * row_h + 2

            # Draw small symbol representation
            self._draw_legend_symbol(backend, sym_key, legend_x, entry_y)

            # Description text
            backend.set_layer("SLD_ANNOTATIONS")
            backend.add_mtext(
                f"{symbol_abbr} = {description}",
                insert=(legend_x + 14, entry_y + 5),
                char_height=2.0,
            )

    def _draw_legend_symbol(
        self,
        backend: DrawingBackend,
        sym_key: str,
        legend_x: float,
        entry_y: float,
    ) -> None:
        """Draw a small symbolic representation in the legend."""
        sx = legend_x + 4
        sy = entry_y + 1
        sw, sh = 5, 5

        if sym_key in ("MCCB", "MCB", "ELCB", "RCCB", "ACB"):
            # Circuit breaker: Rectangle with X
            backend.set_layer("SLD_SYMBOLS")
            backend.add_lwpolyline(
                [(sx, sy), (sx + sw, sy), (sx + sw, sy + sh), (sx, sy + sh)],
                close=True,
            )
            backend.add_line((sx, sy), (sx + sw, sy + sh))
            backend.add_line((sx + sw, sy), (sx, sy + sh))

            # ACB: additional double-contact indicator (horizontal bar through center)
            if sym_key == "ACB":
                backend.add_line((sx - 1, sy + sh / 2), (sx + sw + 1, sy + sh / 2))

            # ELCB/RCCB: arc indicator for earth leakage
            if sym_key in ("ELCB", "RCCB"):
                backend.add_arc(
                    center=(sx + sw + 2, sy + sh / 2),
                    radius=2,
                    start_angle=120,
                    end_angle=240,
                )

        elif sym_key == "KWH_METER":
            # Small circle with "kWh"
            backend.set_layer("SLD_SYMBOLS")
            backend.add_circle((legend_x + 7, entry_y + 3.5), radius=3)

        elif sym_key == "CT":
            # Current Transformer: two overlapping circles
            backend.set_layer("SLD_SYMBOLS")
            backend.add_circle((legend_x + 6, entry_y + 3.5), radius=2.5)
            backend.add_circle((legend_x + 8.5, entry_y + 3.5), radius=2.5)

        elif sym_key == "EARTH":
            # Earth symbol (three horizontal lines decreasing width)
            backend.set_layer("SLD_SYMBOLS")
            sx_e = legend_x + 7
            sy_e = entry_y + 1
            backend.add_line((sx_e - 3, sy_e), (sx_e + 3, sy_e))
            backend.add_line((sx_e - 2, sy_e + 1.5), (sx_e + 2, sy_e + 1.5))
            backend.add_line((sx_e - 1, sy_e + 3), (sx_e + 1, sy_e + 3))

        elif sym_key == "ISOLATOR":
            # Disconnect switch: diagonal line with contacts
            backend.set_layer("SLD_SYMBOLS")
            backend.add_circle((legend_x + 7, entry_y + 1), radius=1)
            backend.add_line((legend_x + 7, entry_y + 1), (legend_x + 9.5, entry_y + 5))
            backend.add_circle((legend_x + 7, entry_y + 5), radius=1)

        elif sym_key == "FUSE":
            # Fuse: small rectangle
            backend.set_layer("SLD_SYMBOLS")
            backend.add_lwpolyline(
                [(sx, sy + 1), (sx + sw, sy + 1), (sx + sw, sy + 4), (sx, sy + 4)],
                close=True,
            )

        elif sym_key == "BUSBAR":
            # Busbar: double horizontal lines
            backend.set_layer("SLD_SYMBOLS")
            backend.add_line((sx, sy + 2), (sx + sw + 2, sy + 2), lineweight=60)
            backend.add_line((sx, sy + 3.5), (sx + sw + 2, sy + 3.5), lineweight=60)

        elif sym_key == "SPD":
            # SPD: lightning arrow
            backend.set_layer("SLD_SYMBOLS")
            backend.add_line((legend_x + 5, entry_y + 5), (legend_x + 7, entry_y + 3))
            backend.add_line((legend_x + 7, entry_y + 3), (legend_x + 6, entry_y + 3))
            backend.add_line((legend_x + 6, entry_y + 3), (legend_x + 8, entry_y + 1))


# -- Helper: Flow arrow drawing --


def _draw_flow_arrow(
    backend: DrawingBackend,
    x: float,
    y: float,
    direction: str = "up",
) -> None:
    """
    Draw a current flow direction arrow.
    IEC convention: arrow shows direction of conventional current flow.

    Args:
        direction: "up" for bottom-to-top flow (v6 default), "down" for legacy
    """
    backend.set_layer("SLD_ANNOTATIONS")

    if direction == "up":
        # Arrow shaft (short line going up)
        backend.add_line((x, y - 3), (x, y + 3))
        # Arrowhead (pointing up)
        backend.add_line((x, y + 3), (x - 1.5, y + 1))
        backend.add_line((x, y + 3), (x + 1.5, y + 1))
    else:
        # Arrow shaft (short line going down)
        backend.add_line((x, y + 3), (x, y - 3))
        # Arrowhead (pointing down)
        backend.add_line((x, y - 3), (x - 1.5, y - 1))
        backend.add_line((x, y - 3), (x + 1.5, y - 1))

    # "I" label for current
    backend.add_mtext(
        "I",
        insert=(x + 2, y + 2),
        char_height=2.5,
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
