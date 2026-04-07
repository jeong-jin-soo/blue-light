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

import asyncio
import logging
import math
import re
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from app.sld.backend import DrawingBackend
from app.sld.block_replayer import BlockReplayer
from app.sld.dxf_backend import DxfBackend
from app.sld.layout import (
    LayoutConfig,
    LayoutResult,
    PlacedComponent,
    compute_layout,
)
from app.sld.layout.engine_v3 import compute_layout_v3
from app.sld.pdf_backend import PdfBackend
from app.sld.real_symbols import get_symbol_dimensions
from app.sld.locale import SG_LOCALE
from app.sld.page_config import A2_LANDSCAPE, A3_LANDSCAPE, PageConfig, auto_page_size
from app.sld.svg_backend import SvgBackend
from app.sld.symbol import Symbol, create_symbol
from app.sld.title_block import TitleBlockConfig, draw_border, draw_title_block_frame, fill_title_block_data

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# SldResult — 단일 출력 타입
# ---------------------------------------------------------------------------

@dataclass
class SldResult:
    """SLD 파이프라인 출력. 모든 호출자가 동일한 타입을 받는다."""

    pdf_bytes: bytes = b""
    svg_string: str = ""
    dxf_bytes: bytes | None = None
    vision_report: Any = None  # VisionReport (순환 import 방지)
    overflow_metrics: Any = None  # OverflowMetrics
    layout_warnings: list[str] = field(default_factory=list)
    component_count: int = 0

    def save(
        self,
        pdf_path: str | Path | None = None,
        svg_path: str | Path | None = None,
        dxf_path: str | Path | None = None,
    ) -> None:
        """파일 저장 유틸리티. 경로가 주어진 것만 저장."""
        if pdf_path and self.pdf_bytes:
            Path(pdf_path).write_bytes(self.pdf_bytes)
            logger.info("PDF saved: %s", pdf_path)
        if svg_path and self.svg_string:
            Path(svg_path).write_text(self.svg_string, encoding="utf-8")
            logger.info("SVG saved: %s", svg_path)
        if dxf_path and self.dxf_bytes:
            Path(dxf_path).write_bytes(self.dxf_bytes)
            logger.info("DXF saved: %s", dxf_path)


# Reference DXF files for importing native CAD symbol blocks.
_DATA_DIR = Path(__file__).resolve().parent.parent.parent / "data"
_BLOCK_LIBRARY_DXF = _DATA_DIR / "templates" / "symbols" / "sld_block_library.dxf"
_REFERENCE_DXF_PATH = _DATA_DIR / "sld-info" / "slds-dxf" / "150A TPN SLD 1 DWG.dxf"
_REFERENCE_DXF_FALLBACK = _DATA_DIR / "sld-info" / "slds-dxf" / "100A TPN SLD 1 DWG.dxf"

# Block Replayer — loads block library once at module level
try:
    _BLOCK_REPLAYER = BlockReplayer.load()
    logger.info("BlockReplayer loaded: %d blocks", len(_BLOCK_REPLAYER._blocks))
except Exception as _exc:
    logger.warning("BlockReplayer load failed: %s — falling back to procedural rendering", _exc)
    _BLOCK_REPLAYER = None


class SldPipeline:
    """단일 SLD 생성 파이프라인.

    ❶~❺ compute_layout() → 렌더링 → ❻ Vision AI 검증.
    어디서 호출하든 동일한 파이프라인을 탄다.
    """

    MAX_VISION_RETRIES = 3

    def run(
        self,
        requirements: dict,
        application_info: dict | None = None,
        backend_type: str = "dxf",
        page_config: PageConfig | None = None,
        api_key: str | None = None,
    ) -> SldResult:
        """SLD 생성 파이프라인 실행.

        Args:
            requirements: SLD requirements dict
            application_info: 타이틀 블록 정보 (주소, kVA 등)
            backend_type: "dxf" (기본) 또는 "pdf" (레거시)
            page_config: 페이지 설정 (None이면 A3 가로)
            api_key: Gemini API key (있으면 Vision AI 자동 실행)

        Returns:
            SldResult with pdf_bytes, svg_string, dxf_bytes, vision_report
        """
        app_info = application_info or {}
        if not app_info and "title_block" in requirements:
            app_info = requirements["title_block"]

        try:
            from app.sld.circuit_normalizer import normalize_application_info
            app_info = normalize_application_info(app_info)
        except Exception as exc:
            logger.warning("application_info normalization failed: %s", exc)

        current_requirements = requirements
        vision_report = None

        for attempt in range(1, self.MAX_VISION_RETRIES + 1):
            result = self._generate_once(
                current_requirements, app_info, backend_type, page_config
            )

            # ❻ Vision AI 검증
            if not api_key:
                break

            try:
                from app.sld.vision_validator import self_review, apply_adjustments

                # PDF→PNG가 SVG→PNG보다 안정적 (ezdxf SVG viewBox 문제 우회)
                if result.pdf_bytes:
                    with tempfile.NamedTemporaryFile(
                        suffix=".pdf", prefix="sld_vision_", delete=False,
                    ) as tmp:
                        tmp.write(result.pdf_bytes)
                        tmp_path = tmp.name
                else:
                    with tempfile.NamedTemporaryFile(
                        suffix=".svg", prefix="sld_vision_", delete=False, mode="w",
                    ) as tmp:
                        tmp.write(result.svg_string)
                        tmp_path = tmp.name

                vision_report = asyncio.run(
                    self_review(tmp_path, api_key=api_key)
                )
                result.vision_report = vision_report

                logger.info(
                    "Vision review (attempt %d/%d): severity=%s score=%.2f issues=%d",
                    attempt, self.MAX_VISION_RETRIES,
                    vision_report.severity, vision_report.score, vision_report.issue_count,
                )

                # 임시 파일 정리
                Path(tmp_path).unlink(missing_ok=True)
                png_tmp = Path(tmp_path).with_suffix(".png")
                png_tmp.unlink(missing_ok=True)

                if vision_report.severity != "fail" or attempt >= self.MAX_VISION_RETRIES:
                    break

                if vision_report.adjustments:
                    current_requirements = apply_adjustments(
                        current_requirements, vision_report.adjustments
                    )
                    logger.info("Vision retry %d: adjustments %s", attempt, vision_report.adjustments)
                else:
                    break  # 조정할 파라미터 없으면 재시도 불필요

            except Exception as vision_err:
                logger.warning("Vision review failed (attempt %d): %s", attempt, vision_err)
                break

        return result

    def _generate_once(
        self,
        requirements: dict,
        app_info: dict,
        backend_type: str,
        page_config: PageConfig | None,
    ) -> SldResult:
        """단일 생성 (레이아웃 + 렌더링). Vision AI 없이."""
        # Page size: explicit > requirements["page_size"] > A3 default
        pc = page_config
        if pc is None:
            ps = str(requirements.get("page_size", "")).upper()
            if ps == "A2":
                pc = A2_LANDSCAPE
            elif ps == "AUTO":
                pc = auto_page_size(requirements)
            # else: None → downstream defaults to A3
        tb_config = TitleBlockConfig.from_page_config(pc) if pc else None

        # ❶~❺ Layout — single-DB는 v3 (region 기반), multi-DB는 v2
        _dbs = requirements.get("distribution_boards")
        _is_multi = _dbs and len(_dbs) > 1
        if _is_multi:
            layout_result = compute_layout(requirements, application_info=app_info,
                                           page_config=pc)
        else:
            layout_result = compute_layout_v3(requirements, application_info=app_info,
                                              page_config=pc)

        # ❺½ Post-layout validation + auto-fix (max 2 attempts)
        if layout_result.config:
            from app.sld.layout.post_validator import validate_and_fix
            validation_issues = validate_and_fix(layout_result, layout_result.config)
            if validation_issues:
                unfixed = [i for i in validation_issues if not i.fix_applied]
                if unfixed:
                    logger.warning("Post-layout: %d unfixed issues remain", len(unfixed))

        # Title block data
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

        # ── DXF-first pipeline ──────────────────────────────────
        # DXF is the single source of truth.  PDF and SVG are
        # derived from the DXF document after rendering.
        # Legacy PdfBackend / SvgBackend kept only as fallback.
        dxf = DxfBackend(page_config=pc)
        for ref_path in (_BLOCK_LIBRARY_DXF, _REFERENCE_DXF_PATH, _REFERENCE_DXF_FALLBACK):
            if ref_path.exists():
                dxf.import_symbol_blocks(str(ref_path))

        if backend_type == "dxf":
            # DXF-first: render once into DXF, convert to PDF/SVG
            backends = [dxf]
        else:
            # Legacy fallback: PDF + SVG only (no DXF)
            pdf = PdfBackend(output_path=None, page_config=pc)
            svg = SvgBackend(page_config=pc)
            backends = [pdf, svg]

        # Render into selected backends
        _scale = layout_result.config.component_scale if layout_result.config else 1.0
        _cx = pc.page_width / 2 if pc else 210.0
        _cy = pc.page_height / 2 if pc else 148.5
        component_count = 0
        for backend in backends:
            draw_border(backend, page_config=pc)
            draw_title_block_frame(backend, tb_config=tb_config)

            # Scale SLD content (symbols, connections, labels) uniformly.
            # Border and title block are drawn at full size BEFORE this.
            if hasattr(backend, 'begin_content_scale'):
                backend.begin_content_scale(_scale, _cx, _cy)

            component_count = self._draw_components(backend, layout_result)
            self._draw_connections(backend, layout_result)
            self._draw_fanout_groups(backend, layout_result)
            self._draw_dashed_connections(backend, layout_result)
            self._draw_junction_dots(backend, layout_result)
            self._draw_junction_arrows(backend, layout_result)
            self._draw_arrow_points(backend, layout_result)
            self._draw_solid_boxes(backend, layout_result)

            if hasattr(backend, 'end_content_scale'):
                backend.end_content_scale()

            fill_title_block_data(backend, **title_block_kwargs, tb_config=tb_config)

        # Overflow metrics
        overflow = layout_result.overflow_metrics
        warnings = overflow.warnings if overflow else []

        if backend_type == "dxf":
            # DXF-first: derive PDF/SVG from the DXF document
            logger.info("DXF-first pipeline: converting DXF → PDF + SVG")
            return SldResult(
                pdf_bytes=dxf.to_pdf_bytes(),
                svg_string=dxf.to_svg_string(),
                dxf_bytes=dxf.get_bytes(),
                overflow_metrics=overflow,
                layout_warnings=warnings,
                component_count=component_count,
            )
        else:
            # Legacy fallback
            return SldResult(
                pdf_bytes=pdf.get_bytes(),
                svg_string=svg.get_svg_string(),
                dxf_bytes=None,
                overflow_metrics=overflow,
                layout_warnings=warnings,
                component_count=component_count,
            )

    # -- Rendering methods --

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

    # -- 이하 모든 _draw_* 메서드는 SldPipeline._generate_once()에서 호출 --

    def _get_symbol(self, symbol_name: str) -> Symbol | None:
        """Get a unified Symbol instance (Block or Procedural)."""
        return create_symbol(symbol_name, _BLOCK_REPLAYER)

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

    def _draw_label_component(self, backend: DrawingBackend, comp: PlacedComponent,
                              layout_result: "LayoutResult | None" = None) -> None:
        """Draw a text-only LABEL component.

        Sub-circuit labels use top-left anchor (center_across=False) so that
        single-line and multi-line labels start at the same Y position.
        """
        _cfg = (layout_result.config if layout_result else None) or LayoutConfig()
        backend.set_layer("SLD_ANNOTATIONS")
        backend.add_mtext(
            comp.label, insert=(comp.x, comp.y),
            char_height=_cfg.label_char_height, rotation=comp.rotation,
            center_across=False,
        )

    def _draw_busbar_component(
        self, backend: DrawingBackend, comp: PlacedComponent, layout_result: LayoutResult,
    ) -> None:
        """Draw a BUSBAR component (main or sub-busbar)."""
        backend.set_layer("SLD_POWER_MAIN")

        # Each BUSBAR component stores its own extent: x=start, cable_annotation=end_x
        bus_start = comp.x
        if comp.cable_annotation:
            try:
                bus_end = float(comp.cable_annotation)
            except ValueError:
                bus_end = comp.x + 100  # fallback
        else:
            # Legacy: use global busbar extent
            bus_start = layout_result.busbar_start_x
            bus_end = layout_result.busbar_end_x

        backend.add_line(
            (bus_start, comp.y),
            (bus_end, comp.y),
            lineweight=50,
        )
        # Busbar label: use comp.rating (legacy) or comp.label (PG sub-busbars)
        _busbar_text = comp.rating or comp.label or ""
        if _busbar_text:
            _cfg = layout_result.config or LayoutConfig()
            backend.set_layer("SLD_ANNOTATIONS")
            backend.add_mtext(
                _busbar_text,
                insert=(bus_start + 3, comp.y + _cfg.busbar_rating_y),
                char_height=_cfg.label_ch_busbar_rating,
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
        backend.set_layer("SLD_DB_TEXT")
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
        """Draw a symbol component (breaker, meter, earth, CT, etc.).

        Uses the unified Symbol interface — no block_used branching.
        """
        symbol = self._get_symbol(comp.symbol_name)
        if not symbol:
            if comp.symbol_name == "CB_SPARE" and comp.label:
                _cfg = layout_result.config or LayoutConfig()
                backend.add_mtext(
                    comp.label,
                    insert=(comp.x + _cfg.breaker_label_x_default, comp.y + 2),
                    char_height=_cfg.label_ch_breaker_sub,
                )
            else:
                logger.warning(f"Unknown symbol: {comp.symbol_name}")
            return

        use_horizontal = (
            comp.rotation == 90.0
            and hasattr(symbol.procedural, 'draw_horizontal')
            and getattr(comp, 'label_style', '') != 'breaker_block'
        )

        # Determine skip_trip_arrow
        skip_trip = False
        if not use_horizontal:
            is_sub = comp.label_style == "breaker_block"
            is_ditto = comp_idx in ditto_indices
            skip_trip = not is_sub or is_ditto
        else:
            skip_trip = True  # horizontal CBs: no trip arrow

        # Unified render — Symbol handles block vs procedural internally
        render_kwargs: dict = dict(
            horizontal=use_horizontal,
            skip_trip_arrow=skip_trip if symbol.is_circuit_breaker else False,
            enclosed=comp.enclosed if symbol.is_isolator else False,
            no_right_stub=getattr(comp, 'no_right_stub', False),
            no_left_stub=getattr(comp, 'no_left_stub', False),
        )
        # BI_CONNECTOR crossbar extension (CT metering reference style)
        if comp.symbol_name == "BI_CONNECTOR" and getattr(comp, 'crossbar_extend', 0) > 0:
            render_kwargs["crossbar_extend"] = comp.crossbar_extend
        # DP ISOL device: REF DXF uses scale=0.81 (smaller than standard)
        if comp.symbol_name == "DP_ISOL_DEVICE":
            render_kwargs["render_scale"] = 0.81
        symbol.render(
            backend, comp.x, comp.y,
            **render_kwargs,
        )

        # Chain arrow for ditto MCBs
        if comp_idx in ditto_prev_map and symbol.is_circuit_breaker:
            prev_comp = layout_result.components[ditto_prev_map[comp_idx]]
            self._draw_chain_arrow(backend, prev_comp, comp, symbol, use_horizontal)

        # Labels
        backend.set_layer("SLD_ANNOTATIONS")
        _cfg = layout_result.config
        if comp.label_style == "breaker_block":
            is_isolator = (comp.breaker_type_str or "").upper() == "ISOLATOR"
            if not is_isolator:
                self._draw_breaker_block_label(
                    backend, comp, is_ditto=comp_idx in ditto_indices, config=_cfg,
                )
        else:
            self._draw_default_symbol_label(backend, comp, symbol, use_horizontal, config=_cfg)

    def _draw_default_symbol_label(
        self, backend: DrawingBackend, comp: PlacedComponent, symbol,
        use_horizontal: bool, config: LayoutConfig | None = None,
    ) -> None:
        """Draw default label + cable annotation for non-breaker-block symbols."""
        # Resolve config (prefer explicit, fall back to default)
        if config is None:
            config = LayoutConfig()

        label_text = ""
        if comp.circuit_id:
            label_text = f"{comp.circuit_id}\\P{comp.label} {comp.rating}"
        elif comp.rating:
            label_text = f"{comp.label}\\P{comp.rating}"
        elif comp.label:
            label_text = comp.label

        # Spine/meter board breaker/isolator labels use 1.8mm (larger than CT metering 1.6mm)
        is_spine_or_meterboard = comp.symbol_name in (
            "CB_ELCB", "CB_RCCB", "CB_MCB", "CB_MCCB", "CB_ACB",
            "ISOLATOR", "ISOLATOR_ENCLOSED",
        )
        ch = 1.8 if is_spine_or_meterboard else config.label_ch_horizontal
        wr = config.text_width_ratio
        gap = config.symbol_label_gap

        if label_text:
            if use_horizontal:
                if comp.symbol_name == "ELR":
                    # ELR: label left of box (spec text, 2 lines)
                    lines = label_text.split("\\P")
                    max_len = max(len(ln) for ln in lines) if lines else 4
                    text_w_est = max_len * ch * wr
                    label_x = comp.x - text_w_est - gap
                    v_half = symbol.height / 2 if symbol else 2.0
                    label_y = comp.y + v_half
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                elif comp.symbol_name == "KWH_METER":
                    # KWH: label right of box (per reference DWG)
                    _proc = symbol.procedural
                    rw = getattr(_proc, '_rect_w', 7.8)
                    stub = getattr(_proc, '_stub', 2.0)
                    label_x = comp.x + rw + stub + gap
                    v_half = getattr(_proc, '_rect_h', 3.9) / 2
                    label_y = comp.y + v_half
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                elif comp.symbol_name == "SELECTOR_SWITCH":
                    # Label ABOVE the circle (per reference DWG: "ASS"/"VSS" above switch)
                    # comp.x = body left edge (circle left edge)
                    _proc = symbol.procedural
                    r = getattr(_proc, '_radius', 2.0)
                    text_w_est = len(label_text) * ch * wr
                    label_x = comp.x + r - text_w_est / 2
                    label_y = comp.y + r + ch + gap
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                elif comp.symbol_name in ("AMMETER", "VOLTMETER"):
                    # Range label to the OUTER side (left for AMMETER, right for VOLTMETER)
                    # comp.x = body left edge (circle left edge)
                    _proc = symbol.procedural
                    r = getattr(_proc, '_radius', 2.5)
                    stub = getattr(_proc, '_stub', 2.0)
                    if comp.symbol_name == "AMMETER":
                        # Left of circle (outer end of left branch)
                        text_w_est = len(label_text) * ch * wr
                        label_x = comp.x - text_w_est - gap
                        label_y = comp.y + ch * 0.4
                    else:
                        # Right of circle (outer end of right branch)
                        label_x = comp.x + 2 * r + stub + 1.0
                        label_y = comp.y + ch * 0.4
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                elif comp.symbol_name in ("POTENTIAL_FUSE", "FUSE"):
                    # Fuse: label ABOVE the symbol (per reference DWG)
                    v_half = symbol.width / 2 if symbol else 4
                    h_extent = symbol.height if symbol else 14
                    text_w_est = len(label_text) * ch * wr
                    label_x = comp.x + h_extent / 2 - text_w_est / 2
                    label_y = comp.y + v_half + config.fuse_label_gap_above
                    backend.add_mtext(
                        label_text, insert=(label_x, label_y), char_height=ch,
                    )
                else:
                    v_half = symbol.width / 2 if symbol else 4
                    h_extent = symbol.height if symbol else 14
                    # Meter board horizontal symbols: label below, centered
                    lines = label_text.split("\\P")
                    longest = max(len(ln) for ln in lines) if lines else 1
                    text_w_est = longest * ch * wr
                    label_x = comp.x + h_extent / 2 - text_w_est / 2
                    label_y = comp.y - v_half - config.generic_label_gap_below
                    backend.add_mtext(
                        label_text,
                        insert=(label_x, label_y),
                        char_height=ch,
                    )
            else:
                # Label X offset: Symbol handles DXF/procedural difference
                lx = symbol.label_offset_x(backend) if symbol else 8
                if comp.label_y_override is not None:
                    label_abs_y = comp.label_y_override
                else:
                    label_abs_y = comp.y + (symbol.height / 2 + 2 if symbol else 14)
                backend.add_mtext(
                    label_text,
                    insert=(comp.x + lx, label_abs_y),
                    char_height=ch,
                )

        if comp.cable_annotation:
            backend.set_layer("SLD_ANNOTATIONS")
            cable_offset_x = symbol.label_offset_x(backend) if symbol else 8
            backend.add_mtext(
                comp.cable_annotation,
                insert=(comp.x + cable_offset_x, comp.y - config.cable_annotation_y),
                char_height=config.label_ch_cable,
            )

    def _draw_components(self, backend: DrawingBackend, layout_result: LayoutResult) -> int:
        """Draw all components from the layout result. Returns count."""
        ditto_indices, ditto_prev_map = self._build_ditto_map(layout_result)
        count = 0

        for comp_idx, comp in enumerate(layout_result.components):
            name = comp.symbol_name
            if name == "LABEL":
                self._draw_label_component(backend, comp, layout_result)
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
        symbol: Symbol,
        use_horizontal: bool = False,
    ) -> None:
        """Draw a connecting chain arrow from previous MCB's arc to current MCB's arc.

        Uses Symbol.get_arc_midpoint() — single source of truth for arc geometry.
        """
        if use_horizontal:
            return

        prev_arc = symbol.get_arc_midpoint(prev_comp.x, prev_comp.y)
        curr_arc = symbol.get_arc_midpoint(curr_comp.x, curr_comp.y)
        if not prev_arc or not curr_arc:
            return

        backend.set_layer("SLD_SYMBOLS")
        backend.add_line(prev_arc, curr_arc)

        # Arrowhead at current arc midpoint (pointing right → toward arc)
        head_len = 1.2
        backend.add_line(
            curr_arc, (curr_arc[0] - head_len, curr_arc[1] + 0.6),
        )
        backend.add_line(
            curr_arc, (curr_arc[0] - head_len, curr_arc[1] - 0.6),
        )

    def _draw_breaker_block_label(
        self,
        backend: DrawingBackend,
        comp: PlacedComponent,
        is_ditto: bool = False,
        config: LayoutConfig | None = None,
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
        if config is None:
            config = LayoutConfig()

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
            # Singapore SLD convention: ≥10kA → uppercase "KA", <10kA → lowercase "kA"
            _ka_suffix = "KA" if comp.fault_kA >= 10 else "kA"
            info_items.append(f"{comp.fault_kA}{_ka_suffix}")  # e.g., "6kA" or "35KA"

        if abs(comp.rotation - 90.0) < 0.1:
            # HORIZONTAL stacked text to the LEFT of breaker (matching reference DWG)
            # Format: B10A / SPN / MCB / 6kA — each on its own horizontal line
            sym_w, sym_h = self._get_breaker_dims(comp.breaker_type_str)
            char_h = config.label_ch_breaker_info
            line_gap = char_h + config.breaker_line_gap_h

            if is_ditto:
                # Chain arrow already drawn in symbol section (arrow→arc→arrow→arc)
                pass
            else:
                # Single multi-line MTEXT matching REF DXF format
                label_top_y = comp.y + sym_h - 1
                base_x = comp.x - config.breaker_label_x_default
                block_text = "\\P".join(info_items)
                backend.add_mtext(
                    block_text,
                    insert=(base_x, label_top_y),
                    char_height=char_h,
                )

            # Cable annotation — now handled by layout.py as shared leader lines
        else:
            # Horizontal text (all text upright, no rotation)
            if comp.label_style == "breaker_block":
                # Sub-circuit breaker — info items stacked vertically, right of symbol
                # Label offset computed from block library (consistent across all backends)
                sym_w, sym_h = self._get_breaker_dims(comp.breaker_type_str)
                sym_name = f"CB_{comp.breaker_type_str}" if comp.breaker_type_str else comp.symbol_name
                _sym = self._get_symbol(sym_name)
                base_x = comp.x + (_sym.label_offset_x() if _sym else sym_w + 3)
                char_h = config.label_ch_breaker_sub
                line_gap = char_h + config.breaker_line_gap_v

                if is_ditto:
                    # Chain arrow already drawn in symbol section (arrow→arc→arrow→arc)
                    pass
                else:
                    # Single multi-line MTEXT matching REF DXF format
                    # REF: "B10A\nSPN\nMCB\n6kA" as one MTEXT entity
                    block_text = "\\P".join(info_items)
                    backend.add_mtext(
                        block_text,
                        insert=(base_x, comp.y + 2),
                        char_height=char_h,
                    )

                # Cable annotation — now handled by layout.py as shared leader lines
            else:
                # Incoming chain breakers (original horizontal stacked logic)
                block_text = "\\P".join(info_items)
                backend.add_mtext(
                    block_text,
                    insert=(comp.x + config.incoming_label_x, comp.y + config.incoming_label_y),
                    char_height=config.label_ch_breaker_info,
                )

                if comp.cable_annotation:
                    backend.add_mtext(
                        comp.cable_annotation,
                        insert=(comp.x + config.incoming_label_x, comp.y - config.cable_annotation_y),
                        char_height=config.label_ch_breaker_sub,
                    )

    def _draw_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw all solid connection lines via port_connections."""
        backend.set_layer("SLD_CONNECTIONS")
        _THICK_STYLES = {"thick", "thick_fixed"}

        total = 0
        skipped = 0
        for pc in layout_result.port_connections:
            if pc.style in ("dashed", "short_dashed"):
                continue  # Drawn by _draw_dashed_connections
            total += 1
            start, end = layout_result.resolve_port_connection(pc)
            if start is None or end is None:
                skipped += 1
                continue
            kwargs = {"lineweight": 50} if pc.style in _THICK_STYLES else {}
            backend.add_line(start, end, **kwargs)

        if skipped:
            logger.warning("Connections: drawn=%d, skipped=%d / total=%d", total - skipped, skipped, total)

    def _draw_fanout_groups(
        self, backend: DrawingBackend, layout_result: LayoutResult,
    ) -> None:
        """Draw 3-phase fan-out groups.

        Each backend implements draw_fanout() with format-specific rendering:
        DXF: creates editable FANOUT_3P blocks.
        PDF/SVG: draws all line types procedurally (center vertical + diagonals + side verticals).

        Reference: 63A TPN SLD 14 — dy/dx ratio = 193/727 ≈ 0.266
        """
        if not layout_result.fanout_groups:
            return

        backend.set_layer("SLD_CONNECTIONS")

        for center_x, busbar_y, side_xs in layout_result.fanout_groups:
            # Resolve MCB busbar-side entry pin Y for each circuit in the fanout group.
            # The fanout vertical line must STOP at the MCB entry pin (bottom_pin),
            # NOT pass through the MCB body to the exit pin (top_pin).
            #
            # Coordinate model (Y increases upward from busbar):
            #   busbar_y (lowest) → bottom_pin → body_bottom → body_top → top_pin (highest)
            #   Fanout line: busbar_y → bottom_pin (= body_bottom - stub)
            def _find_mcb_entry_pin_y(target_x: float) -> float:
                """Find MCB body bottom Y, then subtract stub to get entry pin."""
                from app.sld.catalog import get_catalog
                cat = get_catalog()
                mcb_def = cat.get("MCB")
                mcb_half_w = mcb_def.width / 2  # 2.5mm

                for comp in layout_result.components:
                    if comp.symbol_name.startswith("CB_") and comp.symbol_name != "CB_SPARE":
                        comp_cx = comp.x + mcb_half_w
                        if abs(comp_cx - target_x) < 1.0:
                            # comp.y = body bottom; entry pin = body bottom - stub
                            return comp.y - mcb_def.stub  # bottom_pin
                # Fallback: busbar + gap (shouldn't happen)
                return busbar_y + 10.0

            mcb_entry_y = _find_mcb_entry_pin_y(center_x)
            backend.draw_fanout(center_x, busbar_y, side_xs, mcb_entry_y)

    def _draw_dashed_connections(self, backend: DrawingBackend, layout_result: LayoutResult) -> None:
        """Draw dashed connection lines via port_connections."""
        for pc in layout_result.port_connections:
            if pc.style not in ("dashed", "short_dashed"):
                continue
            start, end = layout_result.resolve_port_connection(pc)
            if start is None or end is None:
                continue
            if pc.style == "dashed":
                backend.draw_center_line(start, end, long_dash=8.0, short_dash=1.5, gap=2.0)
            else:
                backend.set_layer("SLD_POWER_MAIN")
                backend.draw_short_dashed_line(start, end)

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
        # CT hook: two interlocking half-ellipses straddling the spine.
        # Reference DWG: small flat half-oval hooks, ring_radius=0.8mm.
        # Must match reference proportions exactly.
        rx = 0.6         # horizontal semi-axis (mm) — reference-scale flat hook
        ry = 0.3         # vertical semi-axis (mm) — very flat half-oval
        offset = ry      # two half-ellipses touch at the center
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


