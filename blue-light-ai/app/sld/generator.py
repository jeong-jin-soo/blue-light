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
from pathlib import Path

from app.sld.backend import DrawingBackend
from app.sld.dxf_backend import DxfBackend
from app.sld.layout import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    compute_layout,
    format_cable_spec,
)
from app.sld.pdf_backend import PdfBackend
from app.sld.real_symbols import (
    REAL_SYMBOL_MAP,
    RealACB,
    RealCircuitBreaker,
    RealCT,
    RealELCB,
    RealEarth,
    RealFuse,
    RealIsolator,
    RealKwhMeter,
    RealMCB,
    RealMCCB,
    RealRCCB,
    get_real_symbol,
)
from app.sld.svg_backend import SvgBackend
from app.sld.symbols.breakers import ACB, MCB, MCCB, RCCB, ELCB, CircuitBreaker
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.loads import IndustrialSocket, Timer, TimerWithBypass
from app.sld.symbols.meters import Ammeter, KwhMeter, Voltmeter
from app.sld.symbols.motors import Generator as GeneratorSymbol, Motor
from app.sld.symbols.msb_components import IndicatorLight, ProtectionRelay, ShuntTrip
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import ATS, BIConnector, DoublePoleSwitch, Isolator, IsolatorForMachine
from app.sld.symbols.transformers import CurrentTransformer, PotentialTransformer, PowerTransformer
from app.sld.title_block import draw_border, draw_title_block_frame, fill_title_block_data

logger = logging.getLogger(__name__)

# Reference DXF file for importing native CAD symbol blocks (MCCB, RCCB, DP ISOL).
# All 26 DXF template files contain identical block definitions.
_REFERENCE_DXF_PATH = Path(__file__).resolve().parent.parent.parent / "data" / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf"

# DXF block bounding heights in drawing units (for scale computation).
# Measured from the DXF MCCB/RCCB blocks: total height = top_circle_top - bottom_circle_bottom.
_DXF_BLOCK_HEIGHTS = {
    "MCCB": 597.82,   # (548.35 + 49.47) - (49.47 - 49.47)
    "RCCB": 597.82,   # Same geometry as MCCB for the breaker portion
    "DP ISOL": 430.63,  # From DXF block analysis
}

# Map symbol type names to DXF block names
_SYMBOL_TO_DXF_BLOCK = {
    "MCCB": "MCCB",
    "CB_MCCB": "MCCB",
    "RCCB": "RCCB",
    "CB_RCCB": "RCCB",
    "ELCB": "RCCB",  # ELCB uses RCCB block (same IEC symbol)
    "CB_ELCB": "RCCB",
}


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
        "VOLTMETER": Voltmeter,
        "MOTOR": Motor,
        "GENERATOR": GeneratorSymbol,
        "ISOLATOR": Isolator,
        "ISOLATOR_MACHINE": IsolatorForMachine,
        "DOUBLE_POLE_SWITCH": DoublePoleSwitch,
        "ATS": ATS,
        "BI_CONNECTOR": BIConnector,
        "FUSE": Fuse,
        "EARTH": EarthSymbol,
        "SPD": SurgeProtector,
        "INDUSTRIAL_SOCKET": IndustrialSocket,
        "TIMER": Timer,
        "TIMER_BYPASS": TimerWithBypass,
        "SHUNT_TRIP": ShuntTrip,
        "INDICATOR_LIGHT": IndicatorLight,
        "PROTECTION_RELAY": ProtectionRelay,
        "PT": PotentialTransformer,
    }

    # Full legend descriptions for all known symbols
    LEGEND_DESCRIPTIONS: dict[str, str] = {
        "ACB": "Air Circuit Breaker",
        "MCCB": "Moulded Case Circuit Breaker",
        "MCB": "Miniature Circuit Breaker",
        "ELCB": "Earth Leakage Circuit Breaker",
        "RCCB": "Residual Current Circuit Breaker",
        "KWH_METER": "kWh Meter (Energy Meter)",
        "AMMETER": "Ammeter (Current Meter)",
        "VOLTMETER": "Voltmeter (Voltage Meter)",
        "EARTH": "Earth Bar / Ground Connection",
        "ISOLATOR": "Isolator / Disconnect Switch",
        "ISOLATOR_MACHINE": "Isolator for Machine",
        "DOUBLE_POLE_SWITCH": "Double Pole Switch",
        "TRANSFORMER": "Power Transformer",
        "CT": "Current Transformer",
        "FUSE": "Fuse",
        "SPD": "Surge Protection Device",
        "ATS": "Automatic Transfer Switch",
        "BI_CONNECTOR": "BI Connector (Bus Isolator)",
        "MOTOR": "Motor",
        "GENERATOR": "Generator",
        "BUSBAR": "Busbar (Main Distribution)",
        "INDUSTRIAL_SOCKET": "Industrial Socket (CEE-Form)",
        "TIMER": "Timer / Time Switch",
        "TIMER_BYPASS": "Timer with Bypass Switch",
        "SHUNT_TRIP": "Shunt Trip",
        "INDICATOR_LIGHT": "Indicator Light",
        "PROTECTION_RELAY": "Protection Relay (O/C E/F)",
        "PT": "Potential Transformer (Voltage Transformer)",
    }

    # Legend abbreviations (shorter form for display)
    LEGEND_ABBREVIATIONS: dict[str, str] = {
        "ACB": "ACB",
        "MCCB": "MCCB",
        "MCB": "MCB",
        "ELCB": "ELCB",
        "RCCB": "RCCB",
        "KWH_METER": "kWh",
        "AMMETER": "Ammeter",
        "VOLTMETER": "Voltmeter",
        "EARTH": "Earth",
        "ISOLATOR": "Isolator",
        "ISOLATOR_MACHINE": "Iso. Machine",
        "DOUBLE_POLE_SWITCH": "DP Switch",
        "TRANSFORMER": "Transformer",
        "CT": "CT",
        "FUSE": "Fuse",
        "SPD": "SPD",
        "ATS": "ATS",
        "BI_CONNECTOR": "BI Conn.",
        "MOTOR": "Motor",
        "GENERATOR": "Gen",
        "BUSBAR": "Busbar",
        "INDUSTRIAL_SOCKET": "Ind. Socket",
        "TIMER": "Timer",
        "TIMER_BYPASS": "Timer/BP",
        "SHUNT_TRIP": "Shunt Trip",
        "INDICATOR_LIGHT": "Ind. Light",
        "PROTECTION_RELAY": "O/C E/F",
        "PT": "PT",
    }

    def generate(
        self,
        requirements: dict,
        application_info: dict,
        pdf_output_path: str,
        svg_output_path: str | None = None,
        backend_type: str = "dxf",
    ) -> dict:
        """
        Generate the SLD drawing.

        Args:
            requirements: SLD requirements from the AI agent.
            application_info: Application details (address, kVA, etc.)
            pdf_output_path: Path to save the PDF file.
            svg_output_path: Optional path to save the SVG preview.
            backend_type: "dxf" (default, real CAD output) or "pdf" (legacy ReportLab).

        Returns:
            dict with keys: svg_string, component_count, pdf_path, dxf_path (if dxf)
        """
        logger.info(
            f"Generating SLD: kVA={requirements.get('kva')}, "
            f"sub_circuits={len(requirements.get('sub_circuits', []))}, "
            f"backend={backend_type}"
        )

        # Compute layout (pure coordinate computation -- backend-independent)
        layout_result = compute_layout(requirements, application_info=application_info)

        # Title block data (shared across all backends)
        # Accept both camelCase (from backend API) and snake_case (from direct calls)
        title_block_kwargs = dict(
            project_name=application_info.get("project_title", "") or application_info.get("address", "Electrical Installation"),
            address=application_info.get("client_address", "") or application_info.get("address", ""),
            postal_code=application_info.get("postalCode", ""),
            kva=requirements.get("kva", 0),
            voltage=requirements.get("voltage", 0),
            supply_type=requirements.get("supply_type", "") or requirements.get("phase_config", ""),
            lew_name=application_info.get("lew_name", "") or application_info.get("assignedLewName", ""),
            lew_licence=application_info.get("lew_licence", "") or application_info.get("assignedLewLicenceNo", ""),
            lew_mobile=application_info.get("lew_mobile", "") or application_info.get("assignedLewMobile", ""),
            sld_only_mode=application_info.get("sld_only_mode", False),
            client_name=application_info.get("client_name", "") or application_info.get("clientName", ""),
            main_contractor=application_info.get("contractor_name", "") or application_info.get("mainContractor", ""),
            elec_contractor=application_info.get("elec_contractor", "") or "LicenseKaki",
            elec_contractor_addr=application_info.get("elec_contractor_addr", "") or application_info.get("contractor_address", ""),
            elec_contractor_tel=application_info.get("elec_contractor_tel", ""),
            drawing_number=application_info.get("drawing_number", ""),
        )

        result = {}
        dxf_path = None

        # Create backends
        if backend_type == "dxf":
            # DXF backend (primary CAD output) + ReportLab PDF (EMA submission) + SVG (preview)
            dxf = DxfBackend()
            # Import native CAD symbol blocks from reference DXF template
            if _REFERENCE_DXF_PATH.exists():
                dxf.import_symbol_blocks(str(_REFERENCE_DXF_PATH))
            pdf = PdfBackend(pdf_output_path)
            svg = SvgBackend()
            backends = [dxf, pdf, svg]

            dxf_path = pdf_output_path.replace(".pdf", ".dxf")
        else:
            # Legacy: ReportLab PDF + SVG only
            pdf = PdfBackend(pdf_output_path)
            svg = SvgBackend()
            backends = [pdf, svg]

        # Draw to all backends simultaneously
        component_count = 0
        for backend in backends:
            draw_border(backend)
            draw_title_block_frame(backend)

            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)
            self._draw_junction_dots(backend, layout_result)
            self._draw_arrow_points(backend, layout_result)
            self._draw_solid_boxes(backend, layout_result)

            self._draw_cable_schedule(backend, requirements, layout_result)
            self._draw_legend(backend, layout_result)

            fill_title_block_data(backend, **title_block_kwargs)

        # Save outputs
        pdf.save()
        logger.info(f"PDF saved: {pdf_output_path}")

        if backend_type == "dxf" and dxf_path:
            dxf.save(dxf_path)
            result["dxf_path"] = dxf_path

        svg_string = svg.get_svg_string()
        if svg_output_path:
            with open(svg_output_path, "w", encoding="utf-8") as f:
                f.write(svg_string)
            logger.info(f"SVG saved: {svg_output_path}")

        result.update({
            "svg_string": svg_string,
            "component_count": component_count,
            "pdf_path": pdf_output_path,
        })
        return result

    @staticmethod
    def generate_pdf_bytes(
        requirements: dict,
        application_info: dict | None = None,
        backend_type: str = "dxf",
    ) -> tuple[bytes, str, bytes | None]:
        """
        Generate SLD as PDF bytes in memory (no file I/O).

        Args:
            requirements: SLD requirements dict.
            application_info: Application details (address, kVA, etc.)
            backend_type: "dxf" (default) or "pdf" (legacy).

        Returns:
            Tuple of (pdf_bytes, svg_string, dxf_bytes_or_none).
        """
        app_info = application_info or {}
        generator = SldGenerator()

        pdf = PdfBackend(output_path=None)  # in-memory buffer
        svg = SvgBackend()

        layout_result = compute_layout(requirements, application_info=app_info)

        title_block_kwargs = dict(
            project_name=app_info.get("project_title", "") or app_info.get("address", "Electrical Installation"),
            address=app_info.get("client_address", "") or app_info.get("address", ""),
            postal_code=app_info.get("postalCode", ""),
            kva=requirements.get("kva", 0),
            voltage=requirements.get("voltage", 0),
            supply_type=requirements.get("supply_type", "") or requirements.get("phase_config", ""),
            lew_name=app_info.get("lew_name", "") or app_info.get("assignedLewName", ""),
            lew_licence=app_info.get("lew_licence", "") or app_info.get("assignedLewLicenceNo", ""),
            lew_mobile=app_info.get("lew_mobile", "") or app_info.get("assignedLewMobile", ""),
            sld_only_mode=app_info.get("sld_only_mode", False),
            client_name=app_info.get("client_name", "") or app_info.get("clientName", ""),
            main_contractor=app_info.get("contractor_name", "") or app_info.get("mainContractor", ""),
            elec_contractor=app_info.get("elec_contractor", "") or "LicenseKaki",
            elec_contractor_addr=app_info.get("elec_contractor_addr", "") or app_info.get("contractor_address", ""),
            elec_contractor_tel=app_info.get("elec_contractor_tel", ""),
            drawing_number=app_info.get("drawing_number", ""),
        )

        dxf_bytes = None
        if backend_type == "dxf":
            dxf = DxfBackend()
            # Import native CAD symbol blocks from reference DXF template
            if _REFERENCE_DXF_PATH.exists():
                dxf.import_symbol_blocks(str(_REFERENCE_DXF_PATH))
            backends = [dxf, pdf, svg]
        else:
            backends = [pdf, svg]

        for backend in backends:
            draw_border(backend)
            draw_title_block_frame(backend)

            generator._draw_components(backend, layout_result)
            generator._draw_connections(backend, layout_result)
            generator._draw_dashed_connections(backend, layout_result)
            generator._draw_junction_dots(backend, layout_result)
            generator._draw_arrow_points(backend, layout_result)
            generator._draw_solid_boxes(backend, layout_result)
            generator._draw_cable_schedule(backend, requirements, layout_result)
            generator._draw_legend(backend, layout_result)

            fill_title_block_data(backend, **title_block_kwargs)

        if backend_type == "dxf":
            dxf_bytes = dxf.get_bytes()

        return pdf.get_bytes(), svg.get_svg_string(), dxf_bytes

    @staticmethod
    def _get_dxf_block_name(symbol_name: str) -> str | None:
        """Map a symbol type name to its DXF block name, if one exists."""
        return _SYMBOL_TO_DXF_BLOCK.get(symbol_name)

    def _get_symbol(self, symbol_name: str):
        """Get a symbol instance by its block/type name.

        Uses real-proportion symbols (calibrated from LEW samples) first,
        falling back to legacy symbols for types not yet calibrated.
        """
        # Try real symbols first (calibrated from real LEW samples)
        try:
            return get_real_symbol(symbol_name)
        except ValueError:
            pass

        # Fallback to legacy symbols for uncalibrated types
        if symbol_name.startswith("CB_"):
            breaker_type = symbol_name[3:]
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

        # Pre-scan: identify duplicate breaker specs for label deduplication
        # Build spec signature → list of component indices (sorted left-to-right)
        breaker_spec_groups: dict[str, list[int]] = {}
        for idx, comp in enumerate(layout_result.components):
            if comp.label_style == "breaker_block":
                sig = f"{comp.breaker_characteristic}|{comp.rating}|{comp.poles}|{comp.breaker_type_str}|{comp.fault_kA}"
                breaker_spec_groups.setdefault(sig, []).append(idx)

        # For groups with 5+ identical specs, only the FIRST (leftmost) gets full label
        # Rest get a "ditto" arrow pointing left toward the first
        # Real LEW SLDs show full labels on most circuits — only use ditto for very large groups
        ditto_breaker_indices: set[int] = set()
        for sig, indices in breaker_spec_groups.items():
            if len(indices) >= 5:
                # Sort by x position (leftmost first)
                sorted_indices = sorted(indices, key=lambda i: layout_result.components[i].x)
                # All except the first are ditto
                for i in sorted_indices[1:]:
                    ditto_breaker_indices.add(i)

        # Pre-scan: identify duplicate cable annotations for deduplication
        # Cable annotation → list of component indices (sorted left-to-right)
        cable_groups: dict[str, list[int]] = {}
        for idx, comp in enumerate(layout_result.components):
            if comp.label_style == "breaker_block" and comp.cable_annotation:
                cable_groups.setdefault(comp.cable_annotation, []).append(idx)

        # For groups with 5+ identical cables, only the FIRST gets cable text
        # Real LEW SLDs show cable specs on each circuit
        ditto_cable_indices: set[int] = set()
        for cable_text, indices in cable_groups.items():
            if len(indices) >= 5:
                sorted_indices = sorted(indices, key=lambda i: layout_result.components[i].x)
                for i in sorted_indices[1:]:
                    ditto_cable_indices.add(i)

        for comp_idx, comp in enumerate(layout_result.components):
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
                # AC supply symbol "~" (per LEW guide convention)
                _draw_ac_supply_symbol(backend, comp.x, comp.y)
                count += 1

            elif comp.symbol_name == "BUSBAR":
                # Busbar -- single line, slightly bolder than connections
                # Real LEW SLDs use a single thin line (0.72pt) for busbar
                backend.set_layer("SLD_POWER_MAIN")
                bus_start_x = layout_result.busbar_start_x
                bus_end_x = layout_result.busbar_end_x

                if comp.label:  # Main busbar
                    backend.add_line(
                        (bus_start_x, layout_result.busbar_y),
                        (bus_end_x, layout_result.busbar_y),
                        lineweight=50,  # 0.5mm — matches real LEW SLD busbar weight
                    )
                else:
                    # Sub-busbar (for multi-row circuits)
                    row_bus_width = bus_end_x - bus_start_x
                    backend.add_line(
                        (comp.x, comp.y),
                        (comp.x + row_bus_width, comp.y),
                        lineweight=50,  # 0.5mm for sub-busbars
                    )

                # Busbar rating label (right side, above busbar)
                if comp.rating:
                    backend.set_layer("SLD_ANNOTATIONS")
                    backend.add_mtext(
                        comp.rating,
                        insert=(bus_end_x - 30, layout_result.busbar_y + 5),
                        char_height=2.5,
                    )
                count += 1

            elif comp.symbol_name == "CIRCUIT_ID_BOX":
                # Circuit ID text at busbar tap point (no box)
                backend.set_layer("SLD_ANNOTATIONS")
                backend.add_mtext(
                    comp.circuit_id,
                    insert=(comp.x - 3, comp.y + 5),
                    char_height=2.2,
                )
                count += 1

            elif comp.symbol_name == "DB_INFO_BOX":
                # Dashed box with DB rating, approved load, and premises address
                box_w = 80
                box_h = 14  # Compact (was 18)
                bx = comp.x
                by = comp.y - box_h  # Box extends downward from comp.y

                # Dashed box outline (4 sides)
                _draw_dashed_line(backend, (bx, by), (bx + box_w, by), dash_len=2.5, gap_len=1.5)
                _draw_dashed_line(backend, (bx + box_w, by), (bx + box_w, by + box_h), dash_len=2.5, gap_len=1.5)
                _draw_dashed_line(backend, (bx + box_w, by + box_h), (bx, by + box_h), dash_len=2.5, gap_len=1.5)
                _draw_dashed_line(backend, (bx, by + box_h), (bx, by), dash_len=2.5, gap_len=1.5)

                # DB rating title (e.g., "100A DB")
                backend.set_layer("SLD_ANNOTATIONS")
                backend.add_mtext(
                    comp.label,
                    insert=(bx + 3, by + box_h - 2),
                    char_height=2.5,  # Compact (was 3.0)
                )
                # Approved load + premises (multi-line via \\P)
                if comp.rating:
                    backend.add_mtext(
                        comp.rating,
                        insert=(bx + 3, by + box_h - 5),  # Compact (was box_h - 7)
                        char_height=1.8,  # Compact (was 2.0)
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, isolator, CT, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    # For DXF backend: use native block INSERT when available
                    # (MCCB/RCCB blocks imported from reference DXF template)
                    dxf_block_used = False
                    if isinstance(backend, DxfBackend):
                        dxf_block_name = self._get_dxf_block_name(comp.symbol_name)
                        if dxf_block_name and backend.has_block(dxf_block_name):
                            scale = symbol.height / _DXF_BLOCK_HEIGHTS.get(dxf_block_name, 597.82)
                            backend.insert_block(
                                dxf_block_name, comp.x, comp.y, scale=scale
                            )
                            dxf_block_used = True

                    if not dxf_block_used:
                        symbol.draw(backend, comp.x, comp.y)

                    backend.set_layer("SLD_ANNOTATIONS")

                    if comp.label_style == "breaker_block":
                        # LEW-style stacked breaker label block
                        is_ditto = comp_idx in ditto_breaker_indices
                        is_cable_ditto = comp_idx in ditto_cable_indices
                        self._draw_breaker_block_label(
                            backend, comp,
                            is_ditto=is_ditto,
                            is_cable_ditto=is_cable_ditto,
                        )
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
                            # Label to the right of the symbol
                            label_offset_x = symbol.width + 3 if symbol else 8
                            label_offset_y = symbol.height / 2 + 2 if symbol else 14
                            backend.add_mtext(
                                label_text,
                                insert=(comp.x + label_offset_x, comp.y + label_offset_y),
                                char_height=2.3,
                            )

                        # Cable annotation (below symbol, to the right)
                        if comp.cable_annotation:
                            backend.set_layer("SLD_ANNOTATIONS")
                            cable_offset_x = symbol.width + 3 if symbol else 8
                            backend.add_mtext(
                                comp.cable_annotation,
                                insert=(comp.x + cable_offset_x, comp.y - 2),
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
        is_ditto: bool = False,
        is_cable_ditto: bool = False,
    ) -> None:
        """
        Draw LEW-style breaker block label.

        Format (stacked multi-line):
            {rating}A
            {poles}
            {breaker_type}
            {fault_kA}kA

        When is_ditto=True, draws a "←" arrow instead of the full label
        (LEW convention: identical specs written once, rest use ditto arrow).

        For vertical text (rotation=90), text runs upward from the breaker.
        The circuit ID is shown in the CIRCUIT_ID_BOX at the busbar tap point.
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
            # Real LEW SLDs show: "B20A", "SPN", "MCB", "6kA" as separate vertical columns
            if comp.breaker_type_str in ("MCCB", "ACB"):
                base_x = comp.x + 12  # MCCB/ACB wider (8.4mm symbol)
            else:
                base_x = comp.x + 10  # MCB (7.2mm symbol + gap)
            char_h = 2.5  # Larger for readability (matches real samples)
            line_gap = 4.0  # Column spacing between breaker spec items

            if is_ditto:
                # Ditto: draw "←" arrow pointing left (toward the first with full label)
                arrow_x = base_x + 2
                arrow_y = comp.y + 2
                backend.add_mtext(
                    "←",
                    insert=(arrow_x, arrow_y),
                    char_height=3.0,
                    rotation=90.0,
                )
            else:
                # Full label: draw each info line as a separate column
                for idx, line_text in enumerate(info_items):
                    backend.add_mtext(
                        line_text,
                        insert=(base_x + idx * line_gap, comp.y),
                        char_height=char_h,
                        rotation=90.0,
                    )

            # Cable annotation to the LEFT of conductor (vertical)
            # Real LEW SLDs show cable specs on each sub-circuit
            if comp.cable_annotation and not is_cable_ditto:
                backend.add_mtext(
                    comp.cable_annotation,
                    insert=(comp.x - 6, comp.y),
                    char_height=2.0,
                    rotation=90.0,
                )
        else:
            # Horizontal text (all text upright, no rotation)
            if comp.label_style == "breaker_block":
                # Sub-circuit breaker — info items stacked vertically, right of symbol
                if comp.breaker_type_str in ("MCCB", "ACB"):
                    base_x = comp.x + 7
                else:
                    base_x = comp.x + 6
                char_h = 1.8
                line_gap = char_h + 0.8  # ~2.6mm per line

                if is_ditto:
                    backend.add_mtext(
                        "←",
                        insert=(base_x, comp.y + 6),
                        char_height=3.0,
                    )
                else:
                    for idx, line_text in enumerate(info_items):
                        backend.add_mtext(
                            line_text,
                            insert=(base_x, comp.y + 2 + idx * line_gap),
                            char_height=char_h,
                        )

                # Cable annotation — horizontal, to the LEFT of conductor
                if comp.cable_annotation and not is_cable_ditto:
                    backend.add_mtext(
                        comp.cable_annotation,
                        insert=(comp.x - 3, comp.y),
                        char_height=1.3,
                    )
            else:
                # Incoming chain breakers (original horizontal stacked logic)
                block_text = "\\P".join(info_items)
                backend.add_mtext(
                    block_text,
                    insert=(comp.x + 8, comp.y + 6),
                    char_height=2.0,
                )

                if comp.cable_annotation:
                    backend.add_mtext(
                        comp.cable_annotation,
                        insert=(comp.x + 8, comp.y - 2),
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

    def _draw_junction_dots(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw filled junction dots at busbar tap points."""
        backend.set_layer("SLD_SYMBOLS")
        for cx, cy in layout_result.junction_dots:
            backend.add_filled_circle((cx, cy), radius=0.8)

    def _draw_arrow_points(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw V-shaped arrowheads at wire termination points."""
        backend.set_layer("SLD_SYMBOLS")
        for ax, ay in layout_result.arrow_points:
            # Downward-pointing filled arrowhead
            backend.add_line((ax - 1.2, ay), (ax, ay - 2.0))
            backend.add_line((ax + 1.2, ay), (ax, ay - 2.0))
            backend.add_line((ax - 1.2, ay), (ax + 1.2, ay))

    def _draw_solid_boxes(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw solid rectangle outlines (DB box, etc.)."""
        backend.set_layer("SLD_CONNECTIONS")
        for x1, y1, x2, y2 in layout_result.solid_boxes:
            backend.add_lwpolyline(
                [(x1, y1), (x2, y1), (x2, y2), (x1, y2)],
                close=True,
            )

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
            "ATS", "BI_CONNECTOR", "EARTH", "BUSBAR",
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
            # Circuit breaker: RIGHT-facing arc + contact arm (Singapore SLD style)
            backend.set_layer("SLD_SYMBOLS")
            mid_x = legend_x + 7
            ar_leg = 2  # legend arc radius
            arc_cy = sy + 3  # arc center y
            contact_y_leg = arc_cy - ar_leg  # contact at bottom of arc (270°)
            # Contact point (bottom of arc)
            backend.add_circle((mid_x, contact_y_leg), radius=0.5)
            # Contact arm (diagonal chord from 270° to 0°)
            backend.add_line((mid_x, contact_y_leg), (mid_x + ar_leg, arc_cy))
            # Arc (RIGHT-facing semicircle: 270° → 0° → 90°)
            backend.add_arc(
                center=(mid_x, arc_cy),
                radius=ar_leg,
                start_angle=270,
                end_angle=90,
            )

            # ACB: additional horizontal bar through arc center
            if sym_key == "ACB":
                backend.add_line((mid_x - 3, arc_cy), (mid_x + 3, arc_cy))

            # ELCB/RCCB: toroid circle indicator (to the RIGHT)
            if sym_key in ("ELCB", "RCCB"):
                backend.add_circle((mid_x + ar_leg + 2.5, arc_cy), radius=1.5)

        elif sym_key == "KWH_METER":
            # Small rectangle with "KWH" (Singapore SLD standard)
            backend.set_layer("SLD_SYMBOLS")
            backend.add_lwpolyline(
                [(sx, sy + 0.5), (sx + sw + 1, sy + 0.5),
                 (sx + sw + 1, sy + sh - 0.5), (sx, sy + sh - 0.5)],
                close=True,
            )

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

        elif sym_key == "BI_CONNECTOR":
            # BI Connector: two opposing arrowheads
            backend.set_layer("SLD_SYMBOLS")
            mid_y_bi = entry_y + 3
            # Left arrowhead
            backend.add_lwpolyline(
                [(sx, mid_y_bi + 1.5), (sx + 2.5, mid_y_bi), (sx, mid_y_bi - 1.5)],
                close=True,
            )
            # Right arrowhead
            backend.add_lwpolyline(
                [(sx + sw, mid_y_bi + 1.5), (sx + sw - 2.5, mid_y_bi), (sx + sw, mid_y_bi - 1.5)],
                close=True,
            )
            # Connecting bar
            backend.add_line((sx + 2.5, mid_y_bi), (sx + sw - 2.5, mid_y_bi))


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


# -- Helper: AC supply symbol (~) --


def _draw_ac_supply_symbol(
    backend: DrawingBackend,
    x: float,
    y: float,
) -> None:
    """
    Draw AC supply symbol: a circle with "~" inside.
    Per LEW guide convention for incoming AC supply indication.
    """
    backend.set_layer("SLD_SYMBOLS")
    r = 4.0  # Circle radius
    backend.add_circle((x, y), radius=r)

    # "~" text inside the circle
    backend.set_layer("SLD_ANNOTATIONS")
    backend.add_mtext(
        "~",
        insert=(x - 2, y + 2),
        char_height=4.0,
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
