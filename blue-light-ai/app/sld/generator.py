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
import re
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
    get_symbol_dimensions,
)
from app.sld.locale import SG_LOCALE
from app.sld.svg_backend import SvgBackend
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

    # Full legend descriptions for all known symbols — sourced from locale module
    _lg = SG_LOCALE.legend
    LEGEND_DESCRIPTIONS: dict[str, str] = {
        "ACB": _lg.acb, "MCCB": _lg.mccb, "MCB": _lg.mcb,
        "ELCB": _lg.elcb, "RCCB": _lg.rccb,
        "KWH_METER": _lg.kwh_meter, "AMMETER": _lg.ammeter, "VOLTMETER": _lg.voltmeter,
        "EARTH": _lg.earth, "ISOLATOR": _lg.isolator,
        "ISOLATOR_MACHINE": _lg.isolator_machine,
        "DOUBLE_POLE_SWITCH": _lg.double_pole_switch,
        "TRANSFORMER": _lg.transformer, "CT": _lg.ct,
        "FUSE": _lg.fuse, "SPD": _lg.spd, "ATS": _lg.ats,
        "BI_CONNECTOR": _lg.bi_connector,
        "MOTOR": _lg.motor, "GENERATOR": _lg.generator,
        "BUSBAR": _lg.busbar,
        "INDUSTRIAL_SOCKET": _lg.industrial_socket,
        "TIMER": _lg.timer, "TIMER_BYPASS": _lg.timer_bypass,
        "SHUNT_TRIP": _lg.shunt_trip, "INDICATOR_LIGHT": _lg.indicator_light,
        "PROTECTION_RELAY": _lg.protection_relay, "PT": _lg.pt,
    }
    del _lg  # Clean up temporary reference

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

    @staticmethod
    def _get_breaker_dims(breaker_type: str) -> tuple[float, float]:
        """Get (width, height) for a breaker type from real_symbol_paths.json.

        Maps breaker_type_str values to symbol registry keys and returns
        calibrated dimensions.  Falls back to MCB if type is unknown.
        """
        _TYPE_MAP = {
            "MCB": "MCB",
            "MCCB": "MCCB",
            "ACB": "ACB",
            "RCCB": "RCCB",
            "ELCB": "ELCB",
        }
        sym_key = _TYPE_MAP.get(breaker_type, "MCB")
        dims = get_symbol_dimensions(sym_key)
        return dims["width_mm"], dims["height_mm"]

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
            main_contractor=application_info.get("contractor_name", "") or application_info.get("mainContractor", "") or application_info.get("main_contractor", ""),
            elec_contractor=application_info.get("elec_contractor", "") or application_info.get("electrical_contractor", "") or "LicenseKaki",
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
            else:
                logger.warning("Reference DXF not found: %s — DXF symbol blocks will be missing", _REFERENCE_DXF_PATH)
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
        # Support title block data from both application_info (API) and
        # requirements['title_block'] (direct/testing calls)
        app_info = application_info or {}
        if not app_info and "title_block" in requirements:
            app_info = requirements["title_block"]
        generator = SldGenerator()

        pdf = PdfBackend(output_path=None)  # in-memory buffer
        svg = SvgBackend()

        layout_result = compute_layout(requirements, application_info=app_info)

        title_block_kwargs = dict(
            project_name=app_info.get("project_title", "") or app_info.get("client_name", "") or app_info.get("address", "Electrical Installation"),
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
            main_contractor=app_info.get("contractor_name", "") or app_info.get("mainContractor", "") or app_info.get("main_contractor", ""),
            elec_contractor=app_info.get("elec_contractor", "") or app_info.get("electrical_contractor", "") or "LicenseKaki",
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
            else:
                logger.warning("Reference DXF not found: %s — DXF symbol blocks will be missing", _REFERENCE_DXF_PATH)
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
        """Get a calibrated symbol instance by its block/type name."""
        try:
            return get_real_symbol(symbol_name)
        except ValueError:
            logger.warning("Unknown symbol type: %s", symbol_name)
            return None

    def _draw_components(self, backend: DrawingBackend, layout_result: LayoutResult) -> int:
        """Draw all components from the layout result. Returns count."""
        count = 0

        # Pre-scan: identify duplicate breaker specs for label deduplication
        # Build spec signature → list of component indices (sorted left-to-right)
        # KEY: include circuit_id PREFIX (S/P/H/SP) in the signature so that
        # ditto groups reset when circuit category changes.
        # Reference DWG: P1(B20A) labeled, P2-P4 ditto, H5(B20A) labeled again, H6 ditto
        breaker_spec_groups: dict[str, list[int]] = {}
        for idx, comp in enumerate(layout_result.components):
            if comp.label_style == "breaker_block":
                # Extract category prefix from circuit_id (S, P, H, SP, L1S, L1P, etc.)
                cid = comp.circuit_id or ""
                prefix_match = re.match(r"([A-Z]+)", cid)
                category_prefix = prefix_match.group(1) if prefix_match else "X"
                sig = f"{category_prefix}|{comp.breaker_characteristic}|{comp.rating}|{comp.poles}|{comp.breaker_type_str}|{comp.fault_kA}"
                breaker_spec_groups.setdefault(sig, []).append(idx)

        # For groups with 2+ identical specs, only the FIRST (leftmost) gets full label
        # Rest use chain arrow pattern: arrow→arc→arrow→arc (connected)
        # Singapore LEW convention: label shown once per category group
        ditto_breaker_indices: set[int] = set()
        # Map each ditto index → previous index in same group (for chain arrows)
        ditto_prev_map: dict[int, int] = {}
        for sig, indices in breaker_spec_groups.items():
            if len(indices) >= 2:
                # Sort by x position (leftmost first)
                sorted_indices = sorted(indices, key=lambda i: layout_result.components[i].x)
                # All except the first are ditto
                for k in range(1, len(sorted_indices)):
                    ditto_breaker_indices.add(sorted_indices[k])
                    ditto_prev_map[sorted_indices[k]] = sorted_indices[k - 1]

        # Cable annotations are now handled by layout.py's shared leader line system
        # (_add_cable_leader_lines) — no per-breaker cable text rendering needed.

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
                # DB rating and approved load labels inside the DB dashed box
                # (outer dashed box is drawn by _place_db_box via dashed_connections)
                bx = comp.x
                by = comp.y

                backend.set_layer("SLD_ANNOTATIONS")
                # DB rating title in bold/larger text (e.g., "40A DB")
                backend.add_mtext(
                    comp.label,
                    insert=(bx + 3, by),
                    char_height=3.0,  # Larger for emphasis (matches reference DWG)
                )
                # Approved load + premises (multi-line via \\P)
                if comp.rating:
                    backend.add_mtext(
                        comp.rating,
                        insert=(bx + 3, by - 4),
                        char_height=1.8,
                    )
                count += 1

            else:
                # Symbol (breaker, meter, earth, isolator, CT, etc.)
                symbol = self._get_symbol(comp.symbol_name)
                if symbol:
                    # Check for horizontal drawing mode (rotation=90 used for meter board)
                    # NOTE: Sub-circuit breakers also have rotation=90.0 but for TEXT rotation,
                    # NOT symbol orientation. Only meter board components (label_style != "breaker_block")
                    # should use horizontal drawing.
                    use_horizontal = (
                        comp.rotation == 90.0
                        and hasattr(symbol, 'draw_horizontal')
                        and getattr(comp, 'label_style', '') != 'breaker_block'
                    )

                    # For DXF backend: use native block INSERT when available
                    # (MCCB/RCCB blocks imported from reference DXF template)
                    # Skip DXF block insertion for horizontal symbols (no rotated blocks)
                    dxf_block_used = False
                    if not use_horizontal and isinstance(backend, DxfBackend):
                        dxf_block_name = self._get_dxf_block_name(comp.symbol_name)
                        if dxf_block_name and backend.has_block(dxf_block_name):
                            scale = symbol.height / _DXF_BLOCK_HEIGHTS.get(dxf_block_name, 597.82)
                            backend.insert_block(
                                dxf_block_name, comp.x, comp.y, scale=scale
                            )
                            dxf_block_used = True

                    if not dxf_block_used:
                        # Trip arrows only on labeled (non-ditto) sub-circuit MCBs above busbar.
                        # MCBs below busbar (main breaker, meter board) and ditto circuits: no trip arrow.
                        # Ditto MCBs get chain arrows (drawn below) instead of per-symbol trip arrows.
                        is_sub_circuit_breaker = comp.label_style == "breaker_block"
                        is_ditto = comp_idx in ditto_breaker_indices
                        should_skip_trip = (
                            not is_sub_circuit_breaker  # incoming chain MCBs (below busbar)
                            or is_ditto                  # ditto sub-circuit MCBs (chain arrow instead)
                        )
                        trip_kwargs = {}
                        if should_skip_trip and isinstance(symbol, RealCircuitBreaker):
                            trip_kwargs["skip_trip_arrow"] = True
                        if use_horizontal:
                            symbol.draw_horizontal(backend, comp.x, comp.y, **trip_kwargs)
                        else:
                            symbol.draw(backend, comp.x, comp.y, **trip_kwargs)

                    # Chain arrow pattern: arrow→arc→arrow→arc (LEW convention)
                    # For ditto MCBs, draw a connecting arrow from previous arc to current arc.
                    # This replaces the separate "→" ditto arrow with a connected chain.
                    if comp_idx in ditto_prev_map and isinstance(symbol, RealCircuitBreaker):
                        prev_idx = ditto_prev_map[comp_idx]
                        prev_comp = layout_result.components[prev_idx]
                        self._draw_chain_arrow(backend, prev_comp, comp, symbol, use_horizontal)

                    backend.set_layer("SLD_ANNOTATIONS")

                    if comp.label_style == "breaker_block":
                        # LEW-style stacked breaker label block
                        is_ditto = comp_idx in ditto_breaker_indices
                        self._draw_breaker_block_label(
                            backend, comp,
                            is_ditto=is_ditto,
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
                            if use_horizontal:
                                # Horizontal symbol: label BELOW, centered on symbol
                                # symbol.width = vertical extent when drawn horizontally
                                # symbol.height = horizontal extent when drawn horizontally
                                v_half = symbol.width / 2 if symbol else 4
                                h_extent = symbol.height if symbol else 14
                                label_y = comp.y - v_half - 1.5  # gap below symbol bottom
                                # Center label horizontally on component body
                                label_x = comp.x + h_extent / 2 - 5
                                backend.add_mtext(
                                    label_text,
                                    insert=(label_x, label_y),
                                    char_height=1.6,
                                )
                            else:
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

    def _draw_chain_arrow(
        self,
        backend: DrawingBackend,
        prev_comp: PlacedComponent,
        curr_comp: PlacedComponent,
        symbol: RealCircuitBreaker,
        use_horizontal: bool = False,
    ) -> None:
        """Draw a connecting chain arrow from previous MCB's arc to current MCB's arc.

        LEW convention for identical breakers: instead of separate ditto arrows,
        the arcs are connected by arrows in a chain pattern:
          arrow→arc→arrow→arc→arrow→arc
        Each connecting arrow's shaft starts at the previous arc surface and
        its arrowhead touches the current arc surface.
        """
        w, h = symbol.width, symbol.height
        ar = symbol._arc_r
        sweep = (symbol._arc_end - symbol._arc_start) % 360
        mid_angle_deg = symbol._arc_start + sweep / 2
        mid_angle_rad = math.radians(mid_angle_deg)

        backend.set_layer("SLD_SYMBOLS")

        if use_horizontal:
            # Horizontal layout: arcs are rotated 90° — connection goes vertically
            # In horizontal draw, the arc center is at different coordinates
            # For now, skip horizontal chain arrows (meter board rarely has ditto)
            pass
        else:
            # Vertical layout — standard sub-circuit MCBs above busbar
            # Arc center for each MCB
            prev_cx = prev_comp.x + w / 2
            prev_arc_cy = prev_comp.y + h / 2
            curr_cx = curr_comp.x + w / 2
            curr_arc_cy = curr_comp.y + h / 2

            # Arc midpoint on each MCB (where the arrow touches the arc surface)
            # Mid-angle ~0° = rightmost point of arc
            cos_mid = math.cos(mid_angle_rad)
            sin_mid = math.sin(mid_angle_rad)

            prev_arc_mx = prev_cx + ar * cos_mid
            prev_arc_my = prev_arc_cy + ar * sin_mid
            curr_arc_mx = curr_cx + ar * cos_mid
            curr_arc_my = curr_arc_cy + ar * sin_mid

            # Draw shaft: line from previous arc midpoint to current arc midpoint
            backend.add_line(
                (prev_arc_mx, prev_arc_my),
                (curr_arc_mx, curr_arc_my),
            )

            # Draw arrowhead at current arc midpoint (same style as trip arrow)
            # Direction: from arc midpoint toward center (inward)
            dx = curr_cx - curr_arc_mx
            dy = curr_arc_cy - curr_arc_my
            dist = math.sqrt(dx * dx + dy * dy)
            if dist > 0:
                dx /= dist
                dy /= dist
            head_len = 1.2
            px, py = -dy, dx  # perpendicular
            backend.add_line(
                (curr_arc_mx, curr_arc_my),
                (curr_arc_mx + dx * head_len + px * 0.6,
                 curr_arc_my + dy * head_len + py * 0.6),
            )
            backend.add_line(
                (curr_arc_mx, curr_arc_my),
                (curr_arc_mx + dx * head_len - px * 0.6,
                 curr_arc_my + dy * head_len - py * 0.6),
            )

    def _draw_breaker_block_label(
        self,
        backend: DrawingBackend,
        comp: PlacedComponent,
        is_ditto: bool = False,
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
            # HORIZONTAL stacked text to the LEFT of breaker (matching reference DWG)
            # Format: B10A / SPN / MCB / 6kA — each on its own horizontal line
            sym_w, sym_h = self._get_breaker_dims(comp.breaker_type_str)
            char_h = 2.0  # Compact horizontal text
            line_gap = char_h + 0.5  # ~2.5mm line spacing

            if is_ditto:
                # Chain arrow already drawn in symbol section (arrow→arc→arrow→arc)
                pass
            else:
                # Labels stacked from TOP of breaker downward, to the LEFT
                # In layout coords: higher Y = higher on screen
                # So first item at highest Y (top), each subsequent lower
                label_top_y = comp.y + sym_h - 1  # Start 1mm below top of breaker
                # Position LEFT of breaker: text starts here and extends rightward
                # but stays to the left of the breaker symbol edge
                base_x = comp.x - 6  # 6mm left of breaker left edge
                for idx, line_text in enumerate(info_items):
                    backend.add_mtext(
                        line_text,
                        insert=(base_x, label_top_y - idx * line_gap),
                        char_height=char_h,
                    )

            # Cable annotation — now handled by layout.py as shared leader lines
            # (horizontal leader + ticker marks + cable spec text at ends)
            # Individual per-breaker cable text rendering is no longer used.
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
                    # Chain arrow already drawn in symbol section (arrow→arc→arrow→arc)
                    pass
                else:
                    for idx, line_text in enumerate(info_items):
                        backend.add_mtext(
                            line_text,
                            insert=(base_x, comp.y + 2 + idx * line_gap),
                            char_height=char_h,
                        )

                # Cable annotation — now handled by layout.py as shared leader lines
                # (horizontal leader + ticker marks + cable spec text at ends)
                # Individual per-breaker cable text rendering is no longer used.
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
        # Thick connections — heavier line weight (0.5mm) for outgoing cable tick marks
        for start, end in layout_result.thick_connections:
            backend.add_line(start, end, lineweight=50)

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
        Draw a cable schedule table in the right-side empty area.

        Disabled by default — cable specs are already shown inline on the SLD.
        Set layout_result.render_cable_schedule = True to enable.
        """
        if not layout_result.render_cable_schedule:
            return

        sub_circuits = requirements.get("sub_circuits", [])
        if not sub_circuits:
            return

        # -- Determine available right-side space --
        # Earth bar occupies: earth_x_from_db(5) + earth_width(12) + label(~25) ≈ 42mm
        earth_clearance = 45
        db_box_right = layout_result.busbar_end_x + 10  # DB box right edge
        table_start_after = db_box_right + earth_clearance
        right_border = 405  # A3 right margin (420 - 15)
        available_width = right_border - table_start_after

        # Need at least 80mm width for a useful table
        min_table_width = 80
        if available_width < min_table_width:
            # Not enough space — skip cable schedule
            return

        # -- Table layout --
        row_height = 4.5
        total_rows = len(sub_circuits) + 1  # +1 for header (no row limit)
        table_height = total_rows * row_height

        # Position: right of earth bar area
        table_x = table_start_after + 3
        table_width = min(available_width - 6, 150)  # Cap at 150mm

        # Vertical: align top with DB box top area
        db_box_top = layout_result.db_box_end_y if hasattr(layout_result, 'db_box_end_y') else layout_result.busbar_y + 40
        table_top = db_box_top
        table_y = table_top - table_height  # Table bottom

        # Ensure table stays above title block
        if table_y < 62:
            table_y = 62
            table_height = table_top - table_y
            max_data_rows = int(table_height / row_height) - 1
            total_rows = max_data_rows + 1
            table_height = total_rows * row_height

        # Column widths — proportional to table width
        # ID(12%), Name(35%), Breaker(15%), Rating(13%), Cable(25%)
        col_widths = [
            round(table_width * 0.12),
            round(table_width * 0.35),
            round(table_width * 0.15),
            round(table_width * 0.13),
        ]
        col_widths.append(table_width - sum(col_widths))  # Cable gets remainder

        backend.set_layer("SLD_TITLE_BLOCK")

        # Table border
        backend.add_lwpolyline(
            [
                (table_x, table_y),
                (table_x + table_width, table_y),
                (table_x + table_width, table_y + table_height),
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
                (table_x + table_width, row_y_line),
            )

        # Table title — "CABLE SCHEDULE" above the table
        backend.add_mtext(
            "CABLE SCHEDULE",
            insert=(table_x, table_y + table_height + 3),
            char_height=2.5,
        )

        # Header text
        header_y = table_y + table_height - 1
        headers = ["CKT", "DESCRIPTION", "TYPE", "RATING", "CABLE"]
        x_pos = table_x
        for idx, header in enumerate(headers):
            backend.add_mtext(
                header,
                insert=(x_pos + 1.5, header_y),
                char_height=2.0,
            )
            x_pos += col_widths[idx]

        # Data rows
        backend.set_layer("SLD_ANNOTATIONS")
        from app.sld.layout import _assign_circuit_ids

        supply_type = requirements.get("supply_type", "single_phase")
        circuit_ids = _assign_circuit_ids(sub_circuits, supply_type)

        max_data_rows = total_rows - 1
        for idx, circuit in enumerate(sub_circuits):
            if idx >= max_data_rows:
                break

            row_y = header_y - (idx + 1) * row_height

            sc_name = str(circuit.get("name", f"DB-{idx + 1}"))
            circuit_id = circuit_ids[idx] if idx < len(circuit_ids) else f"C{idx + 1}"
            breaker_type = str(circuit.get("breaker_type", "MCB"))
            breaker_rating = circuit.get("breaker_rating", 32)
            cable_full = format_cable_spec(circuit.get("cable", "-"))
            # Abbreviate cable spec for table: remove " IN METAL TRUNKING" etc.
            cable_short = (cable_full or "-")
            for suffix in (" IN METAL TRUNKING", " IN CONDUIT", " IN CABLE TRAY",
                           " IN TRUNKING"):
                cable_short = cable_short.replace(suffix, "")

            row_data = [circuit_id, sc_name, breaker_type, f"{breaker_rating}A", cable_short]
            x_pos = table_x
            for i, text in enumerate(row_data):
                backend.add_mtext(
                    str(text),
                    insert=(x_pos + 1.5, row_y),
                    char_height=1.8,
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
            legend_items.append(("Busbar", SG_LOCALE.legend.busbar, "BUSBAR"))

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


# -- Helper: Ditto arrow (→) for identical breaker specs --


def _draw_ditto_arrow(
    backend: DrawingBackend,
    x: float,
    y: float,
    arrow_len: float = 12.0,
) -> None:
    """
    Draw a ditto arrow → at the given position.

    LEW convention: when multiple sub-circuits have identical breaker specs,
    the first circuit shows full label (B10A / SPN / MCB / 6kA) and subsequent
    circuits show a → arrow meaning "same as the labeled circuit."

    Reference DWG: prominent horizontal arrow (→) spanning the breaker label area.
    """
    backend.set_layer("SLD_ANNOTATIONS")
    half = arrow_len / 2
    head = 2.5  # Arrowhead length

    # Arrow shaft (horizontal line)
    backend.add_line((x - half, y), (x + half, y))
    # Arrowhead (pointing right →)
    backend.add_line((x + half, y), (x + half - head, y + 1.5))
    backend.add_line((x + half, y), (x + half - head, y - 1.5))


# -- Helper: AC supply symbol (~) --


def _draw_ac_supply_symbol(
    backend: DrawingBackend,
    x: float,
    y: float,
) -> None:
    """
    Draw AC supply symbol: "~" wave sign on the incoming supply line.
    Singapore LEW convention — simple wave sign without enclosing circle.
    """
    backend.set_layer("SLD_ANNOTATIONS")
    backend.add_mtext(
        "~",
        insert=(x - 2, y + 2),
        char_height=5.0,
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
