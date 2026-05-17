"""복잡도 기반 자동 크롭 영역 탐지기.

두 가지 방식으로 SLD 이미지에서 정밀 비교가 필요한 영역을 자동 추출:
  1. Image Diff: 생성 SLD와 레퍼런스의 픽셀 차이 → "틀린 곳"
  2. Pixel Density: 검정 픽셀 밀도 → "복잡한 곳"

우선순위:
  P1 = diff ∩ complex (틀리고 + 복잡) → 반드시 크롭
  P2 = diff only (틀렸지만 단순) → 크롭
  P3 = complex only (복잡하지만 동일) → 스킵

Usage:
    from app.sld.crop_detector import detect_crop_regions, CropDetectorConfig

    regions = detect_crop_regions("gen.png", "ref.png")
    for r in regions:
        print(r.name, r.priority, r.bbox_mm)
"""

from __future__ import annotations

import io
import logging
from collections import deque
from dataclasses import dataclass, field
from pathlib import Path
from typing import Sequence

logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Data models
# ---------------------------------------------------------------------------

@dataclass
class CropRegion:
    """탐지된 크롭 영역."""

    name: str  # e.g., "diff_region_1"
    bbox_mm: tuple[float, float, float, float]  # (x_min, y_min, x_max, y_max) SLD 좌표 (mm)
    priority: int  # 1=diff+complex, 2=diff-only
    diff_score: float  # 0.0~1.0 차이 비율
    density_score: float  # 0.0~1.0 검정 밀도
    area_mm2: float  # 영역 면적 (mm²)


@dataclass
class CropDetectorConfig:
    """크롭 탐지 설정. 모든 매직넘버를 한 곳에 집중."""

    # Grid
    cell_size_mm: float = 20.0

    # Image Diff
    blur_radius: int = 3
    diff_threshold: int = 30  # 0~255 픽셀 차이 임계값
    diff_cell_threshold: float = 0.05  # 셀 내 차이 픽셀 5% 이상 → 고차이 셀

    # Pixel Density
    density_threshold: float = 0.08  # 검정 비율 8% 이상 → 고밀도 셀
    black_threshold: int = 128  # 이 값 미만 = 검정 픽셀

    # Region filtering
    max_regions: int = 5
    max_region_fraction: float = 0.50  # 전체 면적의 50% 이상 영역 스킵
    min_cluster_cells: int = 2  # 최소 2셀 연결돼야 영역

    # Exclusion zones (mm)
    exclude_bottom_mm: float = 60.0  # 타이틀 블록 제외
    exclude_border_mm: float = 5.0  # 프레임 테두리 제외

    # Page (defaults: A3 landscape)
    page_w_mm: float = 420.0
    page_h_mm: float = 297.0

    # Margin around detected regions (mm)
    region_margin_mm: float = 10.0


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _load_and_normalize(
    gen_png: str | Path,
    ref_png: str | Path,
) -> tuple:
    """양쪽 PNG를 같은 크기의 grayscale numpy 배열로 반환.

    Returns: (gen_gray, ref_gray, img_width, img_height)
    """
    import numpy as np
    from PIL import Image

    gen_img = Image.open(str(gen_png)).convert("L")
    ref_img = Image.open(str(ref_png)).convert("L")

    # 크기가 다르면 ref를 gen 크기로 resize
    if gen_img.size != ref_img.size:
        logger.info(
            "Image size mismatch: gen=%s ref=%s → resizing ref",
            gen_img.size, ref_img.size,
        )
        ref_img = ref_img.resize(gen_img.size, Image.LANCZOS)

    return (
        np.array(gen_img, dtype=np.int16),
        np.array(ref_img, dtype=np.int16),
        gen_img.size[0],  # width in pixels
        gen_img.size[1],  # height in pixels
    )


def _apply_blur(gray: "np.ndarray", radius: int) -> "np.ndarray":
    """Gaussian blur를 numpy 배열에 적용."""
    from PIL import Image, ImageFilter
    import numpy as np

    img = Image.fromarray(gray.astype(np.uint8), mode="L")
    blurred = img.filter(ImageFilter.GaussianBlur(radius=radius))
    return np.array(blurred, dtype=np.int16)


def _compute_diff_mask(
    gen_gray: "np.ndarray",
    ref_gray: "np.ndarray",
    config: CropDetectorConfig,
) -> "np.ndarray":
    """두 이미지의 절대 차이 → 이진 마스크 (uint8, 0 or 1)."""
    import numpy as np

    gen_blur = _apply_blur(gen_gray, config.blur_radius)
    ref_blur = _apply_blur(ref_gray, config.blur_radius)

    diff = np.abs(gen_blur - ref_blur)
    return (diff > config.diff_threshold).astype(np.uint8)


def _compute_density(
    gray: "np.ndarray",
    config: CropDetectorConfig,
) -> "np.ndarray":
    """검정 픽셀 이진 마스크 (uint8, 0 or 1). 값 < black_threshold = 검정."""
    import numpy as np
    return (gray < config.black_threshold).astype(np.uint8)


def _apply_exclusion_zones(
    mask: "np.ndarray",
    img_w: int,
    img_h: int,
    config: CropDetectorConfig,
) -> "np.ndarray":
    """타이틀 블록, 프레임 테두리 영역을 마스크에서 제거.

    SLD 좌표: Y=0(하단)→Y=297(상단). PNG 좌표: Y=0(상단)→Y=height(하단).
    타이틀 블록은 SLD 하단 = PNG 하단(y가 큰 쪽).
    """
    px_per_mm_x = img_w / config.page_w_mm
    px_per_mm_y = img_h / config.page_h_mm

    border_px = int(config.exclude_border_mm * min(px_per_mm_x, px_per_mm_y))
    bottom_px = int(config.exclude_bottom_mm * px_per_mm_y)

    result = mask.copy()

    # 상하좌우 테두리 (px=0이면 스킵 — [-0:]은 전체 선택되므로)
    if border_px > 0:
        result[:border_px, :] = 0  # 상단 (PNG 기준)
        result[-border_px:, :] = 0  # 하단
        result[:, :border_px] = 0  # 좌측
        result[:, -border_px:] = 0  # 우측

    # 타이틀 블록 (PNG 하단 = SLD 하단)
    if bottom_px > 0:
        result[-bottom_px:, :] = 0

    return result


def _grid_cell_scores(
    mask: "np.ndarray",
    cell_px: int,
) -> "np.ndarray":
    """마스크를 그리드 셀로 나누어 셀별 활성 비율 계산.

    Returns: 2D float array (rows, cols), 값 0.0~1.0
    """
    import numpy as np

    h, w = mask.shape
    rows = max(1, h // cell_px)
    cols = max(1, w // cell_px)

    grid = np.zeros((rows, cols), dtype=np.float64)

    for r in range(rows):
        y0 = r * cell_px
        y1 = min(y0 + cell_px, h)
        for c in range(cols):
            x0 = c * cell_px
            x1 = min(x0 + cell_px, w)
            cell = mask[y0:y1, x0:x1]
            total = cell.size
            if total > 0:
                grid[r, c] = cell.sum() / total

    return grid


def _find_connected_regions(
    active: "np.ndarray",
) -> list[list[tuple[int, int]]]:
    """활성 셀의 connected components를 BFS로 탐지 (4-connectivity).

    Args:
        active: 2D bool 배열 (rows, cols)

    Returns: 각 component는 (row, col) 튜플의 리스트
    """
    rows, cols = active.shape
    visited = set()
    regions: list[list[tuple[int, int]]] = []

    for r in range(rows):
        for c in range(cols):
            if active[r, c] and (r, c) not in visited:
                # BFS
                component: list[tuple[int, int]] = []
                queue = deque([(r, c)])
                visited.add((r, c))
                while queue:
                    cr, cc = queue.popleft()
                    component.append((cr, cc))
                    for dr, dc in [(-1, 0), (1, 0), (0, -1), (0, 1)]:
                        nr, nc = cr + dr, cc + dc
                        if 0 <= nr < rows and 0 <= nc < cols and (nr, nc) not in visited and active[nr, nc]:
                            visited.add((nr, nc))
                            queue.append((nr, nc))
                regions.append(component)

    return regions


def _cells_to_bbox_mm(
    cells: list[tuple[int, int]],
    cell_px: int,
    img_w: int,
    img_h: int,
    config: CropDetectorConfig,
) -> tuple[float, float, float, float]:
    """셀 좌표 리스트 → SLD mm 좌표 bbox (마진 포함).

    PNG 좌표(Y=0 상단)를 SLD 좌표(Y=0 하단)로 변환.
    """
    rows = [r for r, c in cells]
    cols = [c for r, c in cells]

    # PNG 픽셀 좌표
    px_x_min = min(cols) * cell_px
    px_x_max = (max(cols) + 1) * cell_px
    px_y_min = min(rows) * cell_px  # PNG top
    px_y_max = (max(rows) + 1) * cell_px  # PNG bottom

    # 픽셀 → mm 변환
    px_per_mm_x = img_w / config.page_w_mm
    px_per_mm_y = img_h / config.page_h_mm

    mm_x_min = px_x_min / px_per_mm_x
    mm_x_max = px_x_max / px_per_mm_x
    # Y축 반전 (PNG y → SLD y)
    mm_y_min = config.page_h_mm - (px_y_max / px_per_mm_y)  # PNG 하단 → SLD 하단
    mm_y_max = config.page_h_mm - (px_y_min / px_per_mm_y)  # PNG 상단 → SLD 상단

    # 마진 추가 + 클램프
    margin = config.region_margin_mm
    mm_x_min = max(0, mm_x_min - margin)
    mm_y_min = max(0, mm_y_min - margin)
    mm_x_max = min(config.page_w_mm, mm_x_max + margin)
    mm_y_max = min(config.page_h_mm, mm_y_max + margin)

    return (mm_x_min, mm_y_min, mm_x_max, mm_y_max)


def _extract_hotspots(
    cells: list[tuple[int, int]],
    diff_grid: "np.ndarray",
    density_grid: "np.ndarray",
    cell_px: int,
    img_w: int,
    img_h: int,
    config: CropDetectorConfig,
) -> list[CropRegion]:
    """거대 영역에서 diff_score가 높은 핫스팟을 추출.

    전체 diff 영역이 너무 크면 (페이지의 50%+), 그 안에서
    diff 점수가 가장 높은 셀들을 중심으로 작은 윈도우를 잡아 반환.

    고정 윈도우(3x3 셀 = 약 60x60mm) 슬라이딩으로 최고 점수 위치를 탐색.
    """
    import numpy as np

    # 윈도우 크기 (셀 단위): 3x3 = 약 60x60mm
    win_r, win_c = 3, 3
    grid_rows, grid_cols = diff_grid.shape

    # 셀 집합 (빠른 lookup)
    cell_set = set(cells)

    # 각 윈도우의 평균 diff 점수 계산
    scored_windows: list[tuple[float, float, int, int]] = []  # (diff, density, row, col)
    for r in range(grid_rows - win_r + 1):
        for c in range(grid_cols - win_c + 1):
            win_cells = [
                (r + dr, c + dc)
                for dr in range(win_r)
                for dc in range(win_c)
                if (r + dr, c + dc) in cell_set
            ]
            if len(win_cells) < win_r:  # 최소 절반 이상 활성 셀
                continue
            avg_diff = float(np.mean([diff_grid[wr, wc] for wr, wc in win_cells]))
            avg_dens = float(np.mean([density_grid[wr, wc] for wr, wc in win_cells]))
            scored_windows.append((avg_diff, avg_dens, r, c))

    # diff 점수 내림차순 정렬
    scored_windows.sort(key=lambda x: -x[0])

    hotspots: list[CropRegion] = []
    used_cells: set[tuple[int, int]] = set()

    for avg_diff, avg_dens, r, c in scored_windows:
        if len(hotspots) >= config.max_regions:
            break

        # 이미 사용된 셀과 겹치면 스킵
        win_cells = [(r + dr, c + dc) for dr in range(win_r) for dc in range(win_c)]
        if any(wc in used_cells for wc in win_cells):
            continue

        used_cells.update(win_cells)

        bbox_mm = _cells_to_bbox_mm(win_cells, cell_px, img_w, img_h, config)
        area = _region_area_mm2(bbox_mm)

        is_complex = avg_dens >= config.density_threshold
        priority = 1 if is_complex else 2

        hotspots.append(CropRegion(
            name=f"hotspot_{len(hotspots) + 1}",
            bbox_mm=bbox_mm,
            priority=priority,
            diff_score=avg_diff,
            density_score=avg_dens,
            area_mm2=area,
        ))

    logger.info("Extracted %d hotspot(s) from oversized region", len(hotspots))
    return hotspots


def _region_area_mm2(bbox_mm: tuple[float, float, float, float]) -> float:
    return (bbox_mm[2] - bbox_mm[0]) * (bbox_mm[3] - bbox_mm[1])


def _bbox_overlap_ratio(
    a: tuple[float, float, float, float],
    b: tuple[float, float, float, float],
) -> float:
    """두 bbox의 IoU (Intersection over Union)."""
    x_overlap = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    y_overlap = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    intersection = x_overlap * y_overlap
    area_a = (a[2] - a[0]) * (a[3] - a[1])
    area_b = (b[2] - b[0]) * (b[3] - b[1])
    union = area_a + area_b - intersection
    if union <= 0:
        return 0.0
    return intersection / union


# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

def detect_crop_regions(
    gen_png: str | Path,
    ref_png: str | Path,
    config: CropDetectorConfig | None = None,
    *,
    gen_page_mm: tuple[float, float] | None = None,
    ref_page_mm: tuple[float, float] | None = None,
) -> list[CropRegion]:
    """두 PNG 이미지에서 크롭 비교가 필요한 영역을 자동 탐지.

    Image Diff + Pixel Density를 결합하여 우선순위가 높은 영역을 반환.

    Args:
        gen_png: Generated SLD PNG 경로
        ref_png: Reference SLD PNG 경로
        config: 탐지 설정 (None이면 기본값)
        gen_page_mm: Generated 페이지 크기 (w, h) mm. None이면 A3.
        ref_page_mm: Reference 페이지 크기 (w, h) mm. None이면 A3.

    Returns:
        우선순위 순 CropRegion 리스트 (최대 config.max_regions개).
        P3(complex only)는 제외됨.
    """
    import numpy as np

    cfg = config or CropDetectorConfig()

    # 페이지 크기 오버라이드
    if gen_page_mm:
        cfg.page_w_mm, cfg.page_h_mm = gen_page_mm

    # 1. 이미지 로드 & 정규화
    gen_gray, ref_gray, img_w, img_h = _load_and_normalize(gen_png, ref_png)
    cell_px = max(1, int(cfg.cell_size_mm * (img_w / cfg.page_w_mm)))

    logger.info(
        "Crop detection: image=%dx%d cell=%dpx (%.0fmm)",
        img_w, img_h, cell_px, cfg.cell_size_mm,
    )

    # 2. Image Diff → 차이 마스크
    diff_mask = _compute_diff_mask(gen_gray, ref_gray, cfg)
    diff_mask = _apply_exclusion_zones(diff_mask, img_w, img_h, cfg)

    # 3. Pixel Density → 복잡도 마스크 (양쪽 합산)
    gen_density_mask = _compute_density(gen_gray, cfg)
    ref_density_mask = _compute_density(ref_gray, cfg)
    # 양쪽 중 하나라도 복잡하면 복잡한 영역
    combined_density = np.maximum(gen_density_mask, ref_density_mask)
    combined_density = _apply_exclusion_zones(combined_density, img_w, img_h, cfg)

    # 4. 그리드 셀별 점수
    diff_grid = _grid_cell_scores(diff_mask, cell_px)
    density_grid = _grid_cell_scores(combined_density, cell_px)

    # 5. 활성 셀 판정
    diff_active = diff_grid >= cfg.diff_cell_threshold
    density_active = density_grid >= cfg.density_threshold

    # 6. Diff 활성 셀의 connected regions 탐지
    diff_regions = _find_connected_regions(diff_active)
    logger.info("Found %d diff region(s) before filtering", len(diff_regions))

    # 7. 각 영역의 우선순위 + 점수 계산
    page_area = cfg.page_w_mm * cfg.page_h_mm
    candidates: list[CropRegion] = []
    region_idx = 0

    for cells in diff_regions:
        # 최소 셀 수 필터
        if len(cells) < cfg.min_cluster_cells:
            continue

        bbox_mm = _cells_to_bbox_mm(cells, cell_px, img_w, img_h, cfg)
        area = _region_area_mm2(bbox_mm)

        # 전체 면적의 N% 이상이면 → 하위 분할 (hotspot 추출)
        if area > page_area * cfg.max_region_fraction:
            logger.info(
                "Oversized region (%d cells, %.0f%% of page) → extracting hotspots",
                len(cells), area / page_area * 100,
            )
            hotspots = _extract_hotspots(
                cells, diff_grid, density_grid, cell_px,
                img_w, img_h, cfg,
            )
            candidates.extend(hotspots)
            continue

        # 셀별 점수 평균
        avg_diff = float(np.mean([diff_grid[r, c] for r, c in cells]))
        avg_density = float(np.mean([density_grid[r, c] for r, c in cells]))

        # 우선순위
        is_complex = avg_density >= cfg.density_threshold
        priority = 1 if is_complex else 2

        region_idx += 1
        candidates.append(CropRegion(
            name=f"region_{region_idx}",
            bbox_mm=bbox_mm,
            priority=priority,
            diff_score=avg_diff,
            density_score=avg_density,
            area_mm2=area,
        ))

    # 8. 정렬: 우선순위 오름차순, 같은 우선순위 내에서 diff_score 내림차순
    candidates.sort(key=lambda r: (r.priority, -r.diff_score))

    # 9. 겹치는 영역 제거 (IoU > 0.3이면 낮은 우선순위 제거)
    filtered: list[CropRegion] = []
    for cand in candidates:
        overlap = False
        for existing in filtered:
            if _bbox_overlap_ratio(cand.bbox_mm, existing.bbox_mm) > 0.3:
                overlap = True
                break
        if not overlap:
            filtered.append(cand)

    # 10. 최대 N개
    result = filtered[:cfg.max_regions]

    logger.info(
        "Crop detection result: %d region(s) [P1=%d P2=%d]",
        len(result),
        sum(1 for r in result if r.priority == 1),
        sum(1 for r in result if r.priority == 2),
    )
    for r in result:
        logger.info(
            "  %s: P%d bbox=(%.0f,%.0f,%.0f,%.0f) diff=%.3f density=%.3f area=%.0fmm²",
            r.name, r.priority, *r.bbox_mm, r.diff_score, r.density_score, r.area_mm2,
        )

    return result


def detect_dense_regions(
    png_path: str | Path,
    config: CropDetectorConfig | None = None,
    *,
    page_mm: tuple[float, float] | None = None,
) -> list[CropRegion]:
    """단일 이미지에서 복잡한 영역만 탐지 (diff 없이).

    Self-review나 단일 이미지 분석 시 사용.
    """
    import numpy as np
    from PIL import Image

    cfg = config or CropDetectorConfig()
    if page_mm:
        cfg.page_w_mm, cfg.page_h_mm = page_mm

    img = Image.open(str(png_path)).convert("L")
    gray = np.array(img, dtype=np.int16)
    img_w, img_h = img.size

    cell_px = max(1, int(cfg.cell_size_mm * (img_w / cfg.page_w_mm)))

    density_mask = _compute_density(gray, cfg)
    density_mask = _apply_exclusion_zones(density_mask, img_w, img_h, cfg)

    density_grid = _grid_cell_scores(density_mask, cell_px)
    active = density_grid >= cfg.density_threshold

    regions = _find_connected_regions(active)

    page_area = cfg.page_w_mm * cfg.page_h_mm
    result: list[CropRegion] = []
    idx = 0

    for cells in regions:
        if len(cells) < cfg.min_cluster_cells:
            continue

        bbox_mm = _cells_to_bbox_mm(cells, cell_px, img_w, img_h, cfg)
        area = _region_area_mm2(bbox_mm)

        if area > page_area * cfg.max_region_fraction:
            continue

        avg_density = float(np.mean([density_grid[r, c] for r, c in cells]))
        idx += 1
        result.append(CropRegion(
            name=f"dense_{idx}",
            bbox_mm=bbox_mm,
            priority=3,
            diff_score=0.0,
            density_score=avg_density,
            area_mm2=area,
        ))

    result.sort(key=lambda r: -r.density_score)
    return result[:cfg.max_regions]
