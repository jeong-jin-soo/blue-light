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
- Isolator (for installations requiring CT metering, typically ≥125A 3-phase)
- CT metering (for ≥125A 3-phase / ≥150A single-phase)
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
from app.sld.block_replayer import BlockReplayer
from app.sld.dxf_backend import DxfBackend
from app.sld.layout import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    compute_layout,
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
from app.sld.page_config import PageConfig
from app.sld.svg_backend import SvgBackend
from app.sld.title_block import TitleBlockConfig, draw_border, draw_title_block_frame, fill_title_block_data

logger = logging.getLogger(__name__)

# Reference DXF files for importing native CAD symbol blocks.
# 150A TPN has the most blocks (MCCB, RCCB, DP ISOL, SLD-CT, VOLTMETER, 2A FUSE, SS, EF, LED IND LTG).
_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "data"
_REFERENCE_DXF_PATH = _DATA_DIR / "sld-info" / "slds-dxf" / "150A TPN SLD 1 DWG.dxf"
_REFERENCE_DXF_FALLBACK = _DATA_DIR / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf"

# Block Replayer — loads block library once at module level
try:
    _BLOCK_REPLAYER = BlockReplayer.load()
    logger.info("BlockReplayer loaded: %d blocks", len(_BLOCK_REPLAYER._blocks))
except Exception as _exc:
    logger.warning("BlockReplayer load failed: %s — falling back to procedural rendering", _exc)
    _BLOCK_REPLAYER = None

# Legacy fallback: DXF block heights (used only when BlockReplayer is unavailable)
_DXF_BLOCK_HEIGHTS = {
    "MCCB": 597.82,
    "RCCB": 597.82,
    "DP ISOL": 430.63,
}


# Map symbol type names to DXF/library block names.
# BlockReplayer uses this mapping to find the right block definition.
_SYMBOL_TO_DXF_BLOCK = {
    # Circuit breakers
    "MCCB": "MCCB",
    "CB_MCCB": "MCCB",
    "MCB": "MCCB",         # MCB = MCCB block at different scale (per reference DXF)
    "CB_MCB": "MCCB",
    "RCCB": "RCCB",
    "CB_RCCB": "RCCB",
    "ELCB": "RCCB",        # ELCB uses RCCB block (same IEC symbol)
    "CB_ELCB": "RCCB",
    # Isolators
    "ISOLATOR": "IEC ISOLATOR",   # meter board / unit isolator — IEC switch symbol
    "DP_ISOL_DEVICE": "DP ISOL",  # circuit-level isolator device at conductor top
    "3P_ISOLATOR": "3P ISOL",
    # CT metering
    "CT": "SLD-CT",
    # Meters
    "KWH_METER": "KWH_METER",   # custom block
    "VOLTMETER": "VOLTMETER",
    # Protection / auxiliaries
    "EARTH": "EARTH",           # custom block
    "FUSE": "2A FUSE",
    "POTENTIAL_FUSE": "2A FUSE",
    "SELECTOR_SWITCH": "SS",
    "ELR": "EF",
    "INDICATOR_LIGHTS": "LED IND LTG",
}


class SldGenerator:
    """
    Generates complete SLD drawings in PDF format with SVG preview.
    """

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
        page_config: PageConfig | None = None,
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

        # Normalize application_info keys (drawing_no→drawing_number, contractor split)
        try:
            from app.sld.circuit_normalizer import normalize_application_info
            application_info = normalize_application_info(application_info)
        except Exception as exc:
            logger.warning("application_info normalization failed: %s", exc)

        # Compute layout (pure coordinate computation -- backend-independent)
        pc = page_config
        tb_config = TitleBlockConfig.from_page_config(pc) if pc else None
        layout_result = compute_layout(requirements, application_info=application_info,
                                       page_config=pc)

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
            dxf = DxfBackend(page_config=pc)
            # Import native CAD symbol blocks from reference DXF templates
            for ref_path in (_REFERENCE_DXF_PATH, _REFERENCE_DXF_FALLBACK):
                if ref_path.exists():
                    dxf.import_symbol_blocks(str(ref_path))
            pdf = PdfBackend(pdf_output_path, page_config=pc)
            svg = SvgBackend(page_config=pc)
            backends = [dxf, pdf, svg]

            dxf_path = pdf_output_path.replace(".pdf", ".dxf")
        else:
            # Legacy: ReportLab PDF + SVG only
            pdf = PdfBackend(pdf_output_path, page_config=pc)
            svg = SvgBackend(page_config=pc)
            backends = [pdf, svg]

        # Draw to all backends simultaneously
        component_count = 0
        for backend in backends:
            draw_border(backend, page_config=pc)
            draw_title_block_frame(backend, tb_config=tb_config)

            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_fanout_groups(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)
            self._draw_junction_dots(backend, layout_result)
            self._draw_junction_arrows(backend, layout_result)
            self._draw_arrow_points(backend, layout_result)
            self._draw_solid_boxes(backend, layout_result)

            fill_title_block_data(backend, **title_block_kwargs, tb_config=tb_config)

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

        # Include overflow metrics if detected
        if layout_result.overflow_metrics:
            result["overflow_metrics"] = layout_result.overflow_metrics.to_dict()
            result["layout_warnings"] = layout_result.overflow_metrics.warnings

        return result

    @staticmethod
    def generate_pdf_bytes(
        requirements: dict,
        application_info: dict | None = None,
        backend_type: str = "dxf",
        page_config: PageConfig | None = None,
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

        # Normalize application_info keys (drawing_no→drawing_number, contractor split)
        try:
            from app.sld.circuit_normalizer import normalize_application_info
            app_info = normalize_application_info(app_info)
        except Exception as exc:
            logger.warning("application_info normalization failed: %s", exc)

        generator = SldGenerator()
        pc = page_config
        tb_config = TitleBlockConfig.from_page_config(pc) if pc else None

        pdf = PdfBackend(output_path=None, page_config=pc)  # in-memory buffer
        svg = SvgBackend(page_config=pc)

        layout_result = compute_layout(requirements, application_info=app_info,
                                       page_config=pc)

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
            dxf = DxfBackend(page_config=pc)
            # Import native CAD symbol blocks from reference DXF templates
            for ref_path in (_REFERENCE_DXF_PATH, _REFERENCE_DXF_FALLBACK):
                if ref_path.exists():
                    dxf.import_symbol_blocks(str(ref_path))
            backends = [dxf, pdf, svg]
        else:
            backends = [pdf, svg]

        for backend in backends:
            draw_border(backend, page_config=pc)
            draw_title_block_frame(backend, tb_config=tb_config)

            generator._draw_components(backend, layout_result)
            generator._draw_connections(backend, layout_result)
            generator._draw_fanout_groups(backend, layout_result)
            generator._draw_dashed_connections(backend, layout_result)
            generator._draw_junction_dots(backend, layout_result)
            generator._draw_junction_arrows(backend, layout_result)
            generator._draw_arrow_points(backend, layout_result)
            generator._draw_solid_boxes(backend, layout_result)

            fill_title_block_data(backend, **title_block_kwargs, tb_config=tb_config)

        if backend_type == "dxf":
            dxf_bytes = dxf.get_bytes()

        return pdf.get_bytes(), svg.get_svg_string(), dxf_bytes

    @staticmethod
    def _get_dxf_block_name(symbol_name: str) -> str | None:
        """Map a symbol type name to its DXF block name, if one exists."""
        return _SYMBOL_TO_DXF_BLOCK.get(symbol_name)

    @staticmethod
    def _dxf_label_offset_x(symbol_name: str, symbol_width: float, symbol_height: float) -> float:
        """DXF 백엔드용 라벨 X 오프셋 계산.

        블록의 실제 스케일링 폭을 사용하여 라벨이 심볼 바로 옆(3mm)에
        배치되도록 한다. 블록이 없으면 절차적 폭을 사용.

        Args:
            symbol_name: 심볼 이름 (e.g. "CB_MCCB").
            symbol_width: 절차적 심볼 폭 (mm).
            symbol_height: 절차적 심볼 높이 (mm, 스케일 계산용).

        Returns:
            라벨 X 오프셋 (comp.x 기준, mm).
        """
        if _BLOCK_REPLAYER is None:
            return symbol_width + 3

        dxf_block_name = _SYMBOL_TO_DXF_BLOCK.get(symbol_name)
        if not dxf_block_name or not _BLOCK_REPLAYER.has_block(dxf_block_name):
            return symbol_width + 3

        scaled_w, _ = _BLOCK_REPLAYER.get_scaled_size(
            dxf_block_name, target_height_mm=symbol_height,
        )
        # 블록은 compute_aligned_insertion()으로 핀 정렬 → 블록 중심이
        # 절차적 핀(comp.x + width/2)에 위치. 라벨은 블록 우측 가장자리 + 3mm.
        # 블록 우측 가장자리 = comp.x + width/2 + scaled_w/2
        # 라벨 X 오프셋 = width/2 + scaled_w/2 + 3
        return symbol_width / 2 + scaled_w / 2 + 3

    def _get_symbol(self, symbol_name: str):
        """Get a calibrated symbol instance by its block/type name."""
        try:
            return get_real_symbol(symbol_name)
        except ValueError:
            logger.warning("Unknown symbol type: %s", symbol_name)
            return None

    @staticmethod
    def _build_ditto_map(
        layout_result: LayoutResult,
    ) -> tuple[set[int], dict[int, int]]:
        """Pre-scan breaker specs to identify ditto (duplicate) breakers.

        Returns:
            ditto_indices: set of component indices that are ditto copies
            ditto_prev_map: maps each ditto index → previous index for chain arrows
        """
        breaker_spec_groups: dict[str, list[int]] = {}
        entries = [
            (idx, comp)
            for idx, comp in enumerate(layout_result.components)
            if comp.label_style == "breaker_block"
            and not comp.no_ditto  # Protection group RCCBs always show full labels
        ]
        entries.sort(key=lambda t: t[1].x)

        last_prefix = "X"
        for idx, comp in entries:
            cid = comp.circuit_id or ""
            m = re.match(r"([A-Z]+)", cid)
            prefix = m.group(1) if m else "X"
            if prefix == "SP":
                prefix = last_prefix
            else:
                last_prefix = prefix
            sig = f"{prefix}|{comp.breaker_characteristic}|{comp.rating}|{comp.poles}|{comp.breaker_type_str}|{comp.fault_kA}"
            breaker_spec_groups.setdefault(sig, []).append(idx)

        ditto_indices: set[int] = set()
        ditto_prev_map: dict[int, int] = {}
        for indices in breaker_spec_groups.values():
            if len(indices) >= 2:
                sorted_idx = sorted(indices, key=lambda i: layout_result.components[i].x)
                for k in range(1, len(sorted_idx)):
                    ditto_indices.add(sorted_idx[k])
                    ditto_prev_map[sorted_idx[k]] = sorted_idx[k - 1]

        return ditto_indices, ditto_prev_map

    def _draw_label_component(self, backend: DrawingBackend, comp: PlacedComponent) -> None:
        """Draw a text-only LABEL component."""
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext(
            comp.label, insert=(comp.x, comp.y),
            char_height=2.8, rotation=comp.rotation, center_across=True,
        )

    def _draw_busbar_component(
        self, backend: DrawingBackend, comp: PlacedComponent, layout_result: LayoutResult,
    ) -> None:
        """Draw a BUSBAR component (main or sub-busbar)."""
        backend.set_layer("SLD_POWER_MAIN")
        bus_start_x = layout_result.busbar_start_x
        bus_end_x = layout_result.busbar_end_x

        if comp.label:  # Main busbar
            backend.add_line(
                (bus_start_x, layout_result.busbar_y),
                (bus_end_x, layout_result.busbar_y),
                lineweight=50,
            )
        else:
            row_bus_width = bus_end_x - bus_start_x
            backend.add_line(
                (comp.x, comp.y),
                (comp.x + row_bus_width, comp.y),
                lineweight=50,
            )
        if comp.rating:
            backend.set_layer("SLD_ANNOTATIONS")
            backend.add_mtext(
                comp.rating,
                insert=(bus_end_x - 30, layout_result.busbar_y + 5),
                char_height=2.5,
            )

    def _draw_circuit_id_component(self, backend: DrawingBackend, comp: PlacedComponent) -> None:
        """Draw a CIRCUIT_ID_BOX component."""
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext(
            comp.circuit_id, insert=(comp.x + 1.5, comp.y),
            char_height=2.2, rotation=90.0,
        )

    def _draw_db_info_component(self, backend: DrawingBackend, comp: PlacedComponent) -> None:
        """Draw a DB_INFO_BOX component (DB rating + approved load).

        All positions and sizes come from PlacedComponent fields set by the layout
        engine — the renderer adds NO offsets of its own.
        """
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext(comp.label, insert=(comp.x, comp.y), char_height=comp.title_char_height)
        if comp.rating:
            backend.add_mtext(comp.rating,
                              insert=(comp.x, comp.y + comp.rating_offset_y),
                              char_height=comp.rating_char_height)

    def _draw_symbol_component(
        self,
        backend: DrawingBackend,
        comp: PlacedComponent,
        comp_idx: int,
        layout_result: LayoutResult,
        ditto_indices: set[int],
        ditto_prev_map: dict[int, int],
    ) -> None:
        """Draw a symbol component (breaker, meter, earth, CT, etc.)."""
        symbol = self._get_symbol(comp.symbol_name)
        if not symbol:
            if comp.symbol_name == "CB_SPARE" and comp.label:
                # SPARE circuit: no breaker symbol, just render "SPARE" label text
                backend.add_mtext(
                    comp.label,
                    insert=(comp.x + 6, comp.y + 2),
                    char_height=1.8,
                )
            else:
                logger.warning(f"Unknown symbol: {comp.symbol_name}")
            return

        use_horizontal = (
            comp.rotation == 90.0
            and hasattr(symbol, 'draw_horizontal')
            and getattr(comp, 'label_style', '') != 'breaker_block'
        )

        # Determine if we need special procedural rendering kwargs
        needs_special_kwargs = False
        trip_kwargs = {}
        if not use_horizontal:
            is_sub_circuit = comp.label_style == "breaker_block"
            is_ditto = comp_idx in ditto_indices
            should_skip_trip = not is_sub_circuit or is_ditto
            if should_skip_trip and isinstance(symbol, RealCircuitBreaker):
                trip_kwargs["skip_trip_arrow"] = True
            if comp.enclosed and isinstance(symbol, RealIsolator):
                trip_kwargs["enclosed"] = True
                needs_special_kwargs = True
        else:
            needs_special_kwargs = True  # horizontal uses special kwargs for procedural fallback
            # Horizontal CBs (meter board) — no trip arrow per LEW reference convention
            if isinstance(symbol, RealCircuitBreaker):
                trip_kwargs["skip_trip_arrow"] = True

        # BlockReplayer path: renders extracted DXF block geometry.
        # DxfBackend → native INSERT (100% fidelity).
        # PDF/SVG → _replay_entities() (primitive conversion).
        # Supports vertical (0°) and horizontal (90°) orientations.
        block_used = False
        if _BLOCK_REPLAYER is not None:
            dxf_block_name = self._get_dxf_block_name(comp.symbol_name)
            if dxf_block_name and _BLOCK_REPLAYER.has_block(dxf_block_name):
                # Determine rotation and pin based on intent + block native orientation
                native_horiz = _BLOCK_REPLAYER.is_native_horizontal(dxf_block_name)
                if use_horizontal:
                    target_pin = (comp.x, comp.y)
                    if native_horiz:
                        # Block is already horizontal → no rotation, use left pin
                        rotation = 0.0
                        pin_name = "left"
                    else:
                        # Block is vertical → rotate 90° to make horizontal
                        rotation = 90.0
                        pin_name = "bottom"  # rotated: bottom→left
                else:
                    # Vertical placement
                    proc_pins = symbol.vertical_pins(comp.x, comp.y)
                    target_pin = proc_pins.get("bottom", (comp.x, comp.y))
                    if native_horiz:
                        # Block is horizontal → rotate 90° to make vertical
                        rotation = 90.0
                        pin_name = "left"  # rotated: left→bottom
                    else:
                        rotation = 0.0
                        pin_name = "bottom"
                try:
                    # Native-horizontal blocks: scale by width (= horizontal extent)
                    # so the symbol fits the layout slot correctly.
                    if native_horiz and use_horizontal:
                        target_w = symbol.height  # layout uses height as h_extent
                        block_w = _BLOCK_REPLAYER.block_width_du(dxf_block_name)
                        scale_override = target_w / block_w if block_w > 0 else None
                    else:
                        scale_override = None

                    ix, iy, scale = _BLOCK_REPLAYER.compute_aligned_insertion(
                        dxf_block_name, target_pin, pin_name,
                        target_height_mm=symbol.height,
                    )
                    if scale_override is not None:
                        scale = scale_override
                        # Recompute insertion from scale
                        ix, iy, _ = _BLOCK_REPLAYER.compute_aligned_insertion(
                            dxf_block_name, target_pin, pin_name,
                            scale=scale,
                        )
                except ValueError:
                    ix, iy = comp.x, comp.y
                    block_height_du = _BLOCK_REPLAYER.block_height_du(dxf_block_name)
                    scale = symbol.height / (block_height_du or _DXF_BLOCK_HEIGHTS.get(dxf_block_name, 597.82))
                _BLOCK_REPLAYER.draw(
                    backend, dxf_block_name, ix, iy,
                    scale=scale, rotation=rotation, layer="SLD_SYMBOLS",
                )
                block_used = True

        # Fallback: procedural symbol rendering
        if not block_used:
            if use_horizontal:
                if getattr(comp, 'no_right_stub', False):
                    trip_kwargs['no_right_stub'] = True
                symbol.draw_horizontal(backend, comp.x, comp.y, **trip_kwargs)
            else:
                symbol.draw(backend, comp.x, comp.y, **trip_kwargs)

        # Enclosed isolator: draw enclosure box around block-rendered isolator
        if block_used and getattr(comp, 'enclosed', False):
            pad = 1.0
            sw = getattr(symbol, 'width', 6)
            sh = getattr(symbol, 'height', 12)
            bx, by = comp.x, comp.y
            backend.set_layer("SLD_SYMBOLS")
            backend.add_lwpolyline([
                (bx - pad, by - pad), (bx + sw + pad, by - pad),
                (bx + sw + pad, by + sh + pad), (bx - pad, by + sh + pad),
            ], close=True)

        # Chain arrow for ditto MCBs
        if comp_idx in ditto_prev_map and isinstance(symbol, RealCircuitBreaker):
            prev_comp = layout_result.components[ditto_prev_map[comp_idx]]
            self._draw_chain_arrow(backend, prev_comp, comp, symbol, use_horizontal)

        # Labels
        backend.set_layer("SLD_ANNOTATIONS")
        if comp.label_style == "breaker_block":
            is_isolator = (comp.breaker_type_str or "").upper() == "ISOLATOR"
            if not is_isolator:
                self._draw_breaker_block_label(
                    backend, comp, is_ditto=comp_idx in ditto_indices,
                )
        else:
            self._draw_default_symbol_label(backend, comp, symbol, use_horizontal)

    def _draw_default_symbol_label(
        self, backend: DrawingBackend, comp: PlacedComponent, symbol, use_horizontal: bool,
    ) -> None:
        """Draw default label + cable annotation for non-breaker-block symbols."""
        label_text = ""
        if comp.circuit_id:
            label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
        elif comp.rating:
            label_text = f"{comp.label}\\P{comp.rating}"
        elif comp.label:
            label_text = comp.label

        if label_text:
            if use_horizontal:
                if comp.symbol_name == "ELR":
                    # ELR: label left of box (spec text, 2 lines)
                    ch = 1.6
                    lines = label_text.split("\\P")
                    max_len = max(len(ln) for ln in lines) if lines else 4
                    text_w_est = max_len * ch * 0.6
                    label_x = comp.x - text_w_est - 1.5
                    v_half = symbol.height / 2 if symbol else 2.0
                    label_y = comp.y + v_half
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                elif comp.symbol_name == "KWH_METER":
                    # KWH: label right of box (per reference DWG)
                    ch = 1.6
                    rw = getattr(symbol, '_rect_w', 7.8)
                    stub = getattr(symbol, '_stub', 2.0)
                    label_x = comp.x + rw + stub + 1.5
                    v_half = getattr(symbol, '_rect_h', 3.9) / 2
                    label_y = comp.y + v_half
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                else:
                    v_half = symbol.width / 2 if symbol else 4
                    h_extent = symbol.height if symbol else 14
                    ch = 1.6
                    # Meter board horizontal symbols: label below, centered
                    lines = label_text.split("\\P")
                    longest = max(len(ln) for ln in lines) if lines else 1
                    text_w_est = longest * ch * 0.6
                    label_x = comp.x + h_extent / 2 - text_w_est / 2
                    label_y = comp.y - v_half - 0.8
                    backend.add_mtext(
                        label_text,
                        insert=(label_x, label_y),
                        char_height=ch,
                    )
            else:
                # Phase 8A: DXF 백엔드에서 라벨을 블록 실제 폭 기준으로 배치
                if symbol and isinstance(backend, DxfBackend):
                    lx = self._dxf_label_offset_x(
                        comp.symbol_name, symbol.width, symbol.height)
                else:
                    lx = symbol.width + 3 if symbol else 8
                if comp.label_y_override is not None:
                    label_abs_y = comp.label_y_override
                else:
                    label_abs_y = comp.y + (symbol.height / 2 + 2 if symbol else 14)
                backend.add_mtext(
                    label_text,
                    insert=(comp.x + lx, label_abs_y),
                    char_height=1.6,
                )

        if comp.cable_annotation:
            backend.set_layer("SLD_ANNOTATIONS")
            # Phase 8A: DXF 백엔드에서 케이블 주석도 블록 폭 기준
            if symbol and isinstance(backend, DxfBackend):
                cable_offset_x = self._dxf_label_offset_x(
                    comp.symbol_name, symbol.width, symbol.height)
            else:
                cable_offset_x = symbol.width + 3 if symbol else 8
            backend.add_mtext(
                comp.cable_annotation,
                insert=(comp.x + cable_offset_x, comp.y - 2),
                char_height=2.0,
            )

    def _draw_components(self, backend: DrawingBackend, layout_result: LayoutResult) -> int:
        """Draw all components from the layout result. Returns count."""
        ditto_indices, ditto_prev_map = self._build_ditto_map(layout_result)
        count = 0

        for comp_idx, comp in enumerate(layout_result.components):
            name = comp.symbol_name
            if name == "LABEL":
                self._draw_label_component(backend, comp)
            elif name == "FLOW_ARROW":
                _draw_flow_arrow(backend, comp.x, comp.y, direction="down")
            elif name == "FLOW_ARROW_UP":
                _draw_ac_supply_symbol(backend, comp.x, comp.y)
            elif name == "BUSBAR":
                self._draw_busbar_component(backend, comp, layout_result)
            elif name == "CIRCUIT_ID_BOX":
                self._draw_circuit_id_component(backend, comp)
            elif name == "DB_INFO_BOX":
                self._draw_db_info_component(backend, comp)
            else:
                self._draw_symbol_component(
                    backend, comp, comp_idx, layout_result,
                    ditto_indices, ditto_prev_map,
                )
            count += 1

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
            # ISOLATOR circuits render MCB symbol at busbar — label shows "MCB"
            _display_type = "MCB" if comp.breaker_type_str.upper() == "ISOLATOR" else comp.breaker_type_str
            info_items.append(_display_type)
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
                # Phase 8A: DXF 백엔드에서 블록 실제 폭 기준으로 라벨 배치
                sym_w, sym_h = self._get_breaker_dims(comp.breaker_type_str)
                if isinstance(backend, DxfBackend):
                    sym_name = f"CB_{comp.breaker_type_str}" if comp.breaker_type_str else comp.symbol_name
                    lx = self._dxf_label_offset_x(sym_name, sym_w, sym_h)
                    base_x = comp.x + lx
                elif comp.breaker_type_str in ("MCCB", "ACB"):
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

    def _draw_fanout_groups(
        self, backend: DrawingBackend, layout_result: LayoutResult,
    ) -> None:
        """Draw 3-phase fan-out groups.

        DXF backend: creates editable FANOUT_3P blocks.
        PDF/SVG backend: draws procedural lines matching reference DXF geometry.

        Reference: 63A TPN SLD 14 — dy/dx ratio = 193/727 ≈ 0.266
        """
        if not layout_result.fanout_groups:
            return

        _FAN_RATIO = 0.266
        backend.set_layer("SLD_CONNECTIONS")

        for center_x, busbar_y, side_xs in layout_result.fanout_groups:
            if isinstance(backend, DxfBackend):
                # Find MCB bottom Y
                max_spacing = max(abs(sx - center_x) for sx in side_xs)
                mcb_bottom_y = busbar_y + max_spacing  # approximate
                # Better: find the actual MCB Y from connections
                for start, end in layout_result.connections:
                    (sx, sy), (ex, ey) = start, end
                    if abs(sx - center_x) < 0.5 and abs(ex - center_x) < 0.5:
                        if sy > busbar_y and ey > sy:
                            mcb_bottom_y = ey
                            break
                        if ey > busbar_y and sy > ey:
                            mcb_bottom_y = sy
                            break
                backend.create_fanout_block(
                    center_x, busbar_y, side_xs, mcb_bottom_y,
                )
            else:
                # PDF/SVG: draw procedural lines
                for sx in side_xs:
                    dx = sx - center_x
                    fan_h = abs(dx) * _FAN_RATIO
                    # Diagonal: center busbar → side intermediate
                    backend.add_line(
                        (center_x, busbar_y),
                        (sx, busbar_y + fan_h),
                    )

    def _draw_dashed_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw dashed connection lines (DB box boundary per reference DWG).

        Reference uses IEC CENTER linetype: long dash + short dash alternating.
        DXF backend: single LINE per side with CENTER linetype on SLD_DB_FRAME layer.
        PDF/SVG backend: procedural dash pattern via _draw_center_line().
        """
        if isinstance(backend, DxfBackend):
            backend.set_layer("SLD_DB_FRAME")
            for start, end in layout_result.dashed_connections:
                backend.add_line(start, end)
        else:
            backend.set_layer("SLD_CONNECTIONS")
            for start, end in layout_result.dashed_connections:
                _draw_center_line(backend, start, end, long_dash=8.0, short_dash=1.5, gap=2.0)

    def _draw_junction_dots(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw filled junction dots at busbar tap points."""
        backend.set_layer("SLD_SYMBOLS")
        for cx, cy in layout_result.junction_dots:
            backend.add_filled_circle((cx, cy), radius=0.5)

    def _draw_junction_arrows(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw curved hook connectors at CT branch junction points.

        Each hook is a half-ellipse (horizontally 2x wider than tall) protruding
        on the OPPOSITE side of the branch direction.
        Left branch → hooks protrude right.  Right branch → hooks protrude left.
        """
        backend.set_layer("SLD_SYMBOLS")
        r0 = 0.6        # original circle radius (mm) — preserve area (π·r0²)
        rx = r0 * 2     # horizontal semi-axis — 2x wider
        ry = r0 / 2     # vertical semi-axis — halved (area = π·rx·ry = π·r0²)
        offset = ry     # two half-ellipses touch at the center
        n_pts = 16       # polyline segments per half-ellipse

        for cx, cy, direction in layout_result.junction_arrows:
            sign = 1.0 if direction == "left" else -1.0  # protrude opposite
            for oy in (offset, -offset):
                ey = cy + oy
                pts = []
                for i in range(n_pts + 1):
                    t = math.pi * i / n_pts  # 0 → π  (half ellipse)
                    px = cx + sign * rx * (math.sin(t) - 0.5)  # straddle spine
                    py = ey - ry * math.cos(t)  # top → bottom
                    pts.append((px, py))
                backend.add_lwpolyline(pts)

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


def _draw_center_line(
    backend: DrawingBackend,
    start: tuple[float, float],
    end: tuple[float, float],
    long_dash: float = 8.0,
    short_dash: float = 1.5,
    gap: float = 2.0,
) -> None:
    """Draw an IEC CENTER linetype: long dash, gap, short dash, gap, repeat.

    Reference DWG uses this pattern for DB box boundaries.
    """
    dx = end[0] - start[0]
    dy = end[1] - start[1]
    length = math.sqrt(dx * dx + dy * dy)

    if length < 0.1:
        return

    ux, uy = dx / length, dy / length
    # Pattern: [long_dash, gap, short_dash, gap]
    pattern = [long_dash, gap, short_dash, gap]
    cycle_len = sum(pattern)

    pos = 0.0
    step_idx = 0
    while pos < length:
        seg_len = pattern[step_idx % 4]
        is_dash = (step_idx % 2 == 0)  # even indices = dash, odd = gap
        if is_dash:
            seg_start = (start[0] + ux * pos, start[1] + uy * pos)
            seg_end_pos = min(pos + seg_len, length)
            seg_end = (start[0] + ux * seg_end_pos, start[1] + uy * seg_end_pos)
            backend.add_line(seg_start, seg_end)
        pos += seg_len
        step_idx += 1
