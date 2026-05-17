"""crop_detector 단위 테스트.

합성 이미지를 사용하여 API 호출 없이 크롭 탐지 로직 검증.
"""

from __future__ import annotations

import io
import tempfile
from pathlib import Path

import pytest
from PIL import Image, ImageDraw

from app.sld.crop_detector import (
    CropDetectorConfig,
    CropRegion,
    _apply_exclusion_zones,
    _cells_to_bbox_mm,
    _compute_density,
    _compute_diff_mask,
    _find_connected_regions,
    _grid_cell_scores,
    _load_and_normalize,
    _region_area_mm2,
    _bbox_overlap_ratio,
    detect_crop_regions,
    detect_dense_regions,
)


# ---------------------------------------------------------------------------
# Test helpers
# ---------------------------------------------------------------------------

def _make_image(width: int = 840, height: int = 594, color: int = 255) -> Image.Image:
    """합성 테스트 이미지 (grayscale)."""
    return Image.new("L", (width, height), color)


def _draw_rect(img: Image.Image, x: int, y: int, w: int, h: int, color: int = 0):
    """이미지에 사각형 그리기."""
    draw = ImageDraw.Draw(img)
    draw.rectangle([x, y, x + w, y + h], fill=color)


def _save_temp(img: Image.Image, tmp_path: Path, name: str = "test.png") -> str:
    """이미지를 임시 파일로 저장."""
    path = tmp_path / name
    img.save(str(path), format="PNG")
    return str(path)


# ---------------------------------------------------------------------------
# CropDetectorConfig
# ---------------------------------------------------------------------------

class TestCropDetectorConfig:
    def test_default_values(self):
        cfg = CropDetectorConfig()
        assert cfg.cell_size_mm == 20.0
        assert cfg.blur_radius == 3
        assert cfg.diff_threshold == 30
        assert cfg.max_regions == 5
        assert cfg.page_w_mm == 420.0
        assert cfg.page_h_mm == 297.0

    def test_custom_config(self):
        cfg = CropDetectorConfig(cell_size_mm=30.0, max_regions=3)
        assert cfg.cell_size_mm == 30.0
        assert cfg.max_regions == 3


# ---------------------------------------------------------------------------
# _load_and_normalize
# ---------------------------------------------------------------------------

class TestLoadAndNormalize:
    def test_same_size(self, tmp_path):
        gen = _make_image(840, 594)
        ref = _make_image(840, 594)
        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        g, r, w, h = _load_and_normalize(gen_path, ref_path)
        assert g.shape == r.shape
        assert w == 840
        assert h == 594

    def test_different_size_resize(self, tmp_path):
        gen = _make_image(840, 594)
        ref = _make_image(600, 400)  # 다른 크기
        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        g, r, w, h = _load_and_normalize(gen_path, ref_path)
        assert g.shape == r.shape  # ref가 gen 크기로 resize 됨
        assert w == 840
        assert h == 594


# ---------------------------------------------------------------------------
# _compute_diff_mask
# ---------------------------------------------------------------------------

class TestComputeDiffMask:
    def test_identical_images_no_diff(self):
        import numpy as np
        cfg = CropDetectorConfig()
        img = np.full((594, 840), 255, dtype=np.int16)  # 흰색
        mask = _compute_diff_mask(img, img.copy(), cfg)
        assert mask.sum() == 0  # 차이 없음

    def test_known_diff_region(self):
        import numpy as np
        cfg = CropDetectorConfig(blur_radius=0, diff_threshold=10)

        gen = np.full((594, 840), 255, dtype=np.int16)
        ref = gen.copy()
        # ref 중앙에 검은 사각형 (100x100 px)
        ref[247:347, 370:470] = 0

        mask = _compute_diff_mask(gen, ref, cfg)
        # 차이 영역에 값이 있어야 함
        center_diff = mask[247:347, 370:470].sum()
        assert center_diff > 0
        # 나머지 영역은 0
        border_diff = mask[:100, :100].sum()
        assert border_diff == 0

    def test_small_diff_below_threshold(self):
        import numpy as np
        cfg = CropDetectorConfig(blur_radius=0, diff_threshold=30)

        gen = np.full((100, 100), 200, dtype=np.int16)
        ref = np.full((100, 100), 220, dtype=np.int16)  # 차이 = 20 < 30
        mask = _compute_diff_mask(gen, ref, cfg)
        assert mask.sum() == 0  # threshold 미만이므로 탐지 안 됨


# ---------------------------------------------------------------------------
# _compute_density
# ---------------------------------------------------------------------------

class TestComputeDensity:
    def test_white_image_no_density(self):
        import numpy as np
        cfg = CropDetectorConfig()
        img = np.full((100, 100), 255, dtype=np.int16)
        mask = _compute_density(img, cfg)
        assert mask.sum() == 0

    def test_black_region_detected(self):
        import numpy as np
        cfg = CropDetectorConfig()
        img = np.full((100, 100), 255, dtype=np.int16)
        img[20:40, 20:40] = 0  # 검정 영역
        mask = _compute_density(img, cfg)
        assert mask[20:40, 20:40].sum() == 20 * 20  # 검정 영역 전부 탐지
        assert mask[:20, :20].sum() == 0  # 흰 영역은 0


# ---------------------------------------------------------------------------
# _apply_exclusion_zones
# ---------------------------------------------------------------------------

class TestApplyExclusionZones:
    def test_title_block_excluded(self):
        import numpy as np
        cfg = CropDetectorConfig(
            exclude_bottom_mm=60.0,
            exclude_border_mm=0.0,
            page_w_mm=420.0,
            page_h_mm=297.0,
        )
        img_w, img_h = 840, 594
        mask = np.ones((img_h, img_w), dtype=np.uint8)

        result = _apply_exclusion_zones(mask, img_w, img_h, cfg)

        # 하단 60mm → 594 * (60/297) ≈ 120px
        bottom_px = int(60.0 * (img_h / 297.0))
        assert result[-bottom_px:, :].sum() == 0  # 하단 제외됨
        assert result[img_h // 2, img_w // 2] == 1  # 중앙은 유지

    def test_border_excluded(self):
        import numpy as np
        cfg = CropDetectorConfig(
            exclude_bottom_mm=0.0,
            exclude_border_mm=10.0,
            page_w_mm=420.0,
            page_h_mm=297.0,
        )
        img_w, img_h = 840, 594
        mask = np.ones((img_h, img_w), dtype=np.uint8)

        result = _apply_exclusion_zones(mask, img_w, img_h, cfg)

        border_px = int(10.0 * min(img_w / 420.0, img_h / 297.0))
        assert result[0, 0] == 0  # 좌상단 모서리 제외
        assert result[border_px + 1, border_px + 1] == 1  # 테두리 안쪽은 유지


# ---------------------------------------------------------------------------
# _grid_cell_scores
# ---------------------------------------------------------------------------

class TestGridCellScores:
    def test_uniform_mask(self):
        import numpy as np
        mask = np.ones((100, 100), dtype=np.uint8)
        grid = _grid_cell_scores(mask, cell_px=50)
        # 100/50 = 2x2 그리드, 모든 셀 1.0
        assert grid.shape == (2, 2)
        assert abs(grid[0, 0] - 1.0) < 0.01

    def test_half_filled(self):
        import numpy as np
        mask = np.zeros((100, 100), dtype=np.uint8)
        mask[:50, :] = 1  # 상반부만 활성
        grid = _grid_cell_scores(mask, cell_px=50)
        assert grid.shape == (2, 2)
        assert abs(grid[0, 0] - 1.0) < 0.01  # 상단 셀 = 1.0
        assert abs(grid[1, 0] - 0.0) < 0.01  # 하단 셀 = 0.0


# ---------------------------------------------------------------------------
# _find_connected_regions
# ---------------------------------------------------------------------------

class TestFindConnectedRegions:
    def test_single_region(self):
        import numpy as np
        active = np.array([
            [True, True, False],
            [True, False, False],
        ])
        regions = _find_connected_regions(active)
        assert len(regions) == 1
        assert len(regions[0]) == 3

    def test_two_separate_regions(self):
        import numpy as np
        active = np.array([
            [True, False, True],
            [False, False, True],
        ])
        regions = _find_connected_regions(active)
        assert len(regions) == 2

    def test_diagonal_not_connected(self):
        import numpy as np
        # 대각선은 4-connectivity에서 연결 아님
        active = np.array([
            [True, False],
            [False, True],
        ])
        regions = _find_connected_regions(active)
        assert len(regions) == 2

    def test_empty_grid(self):
        import numpy as np
        active = np.zeros((3, 3), dtype=bool)
        regions = _find_connected_regions(active)
        assert len(regions) == 0


# ---------------------------------------------------------------------------
# _cells_to_bbox_mm
# ---------------------------------------------------------------------------

class TestCellsToBboxMm:
    def test_y_axis_inversion(self):
        """PNG Y=0(상단)은 SLD Y=297(상단)에 대응."""
        cfg = CropDetectorConfig(page_w_mm=420.0, page_h_mm=297.0, region_margin_mm=0.0)
        img_w, img_h = 840, 594
        cell_px = 50

        # PNG 상단 셀 (row=0) → SLD 상단 (y가 큰 값)
        cells_top = [(0, 5)]
        bbox_top = _cells_to_bbox_mm(cells_top, cell_px, img_w, img_h, cfg)

        # PNG 하단 셀 → SLD 하단 (y가 작은 값)
        cells_bottom = [(10, 5)]
        bbox_bottom = _cells_to_bbox_mm(cells_bottom, cell_px, img_w, img_h, cfg)

        # SLD 좌표에서 top의 y_min > bottom의 y_min
        assert bbox_top[1] > bbox_bottom[1]

    def test_margin_applied(self):
        cfg = CropDetectorConfig(page_w_mm=420.0, page_h_mm=297.0, region_margin_mm=10.0)
        img_w, img_h = 840, 594
        cell_px = 50

        cells = [(5, 5)]
        bbox = _cells_to_bbox_mm(cells, cell_px, img_w, img_h, cfg)

        # 마진 없는 경우와 비교
        cfg_no_margin = CropDetectorConfig(page_w_mm=420.0, page_h_mm=297.0, region_margin_mm=0.0)
        bbox_no_margin = _cells_to_bbox_mm(cells, cell_px, img_w, img_h, cfg_no_margin)

        # 마진이 있으면 bbox가 더 넓어야 함
        assert bbox[0] < bbox_no_margin[0]  # x_min 더 작음
        assert bbox[1] < bbox_no_margin[1]  # y_min 더 작음
        assert bbox[2] > bbox_no_margin[2]  # x_max 더 큼
        assert bbox[3] > bbox_no_margin[3]  # y_max 더 큼


# ---------------------------------------------------------------------------
# Utility functions
# ---------------------------------------------------------------------------

class TestUtilities:
    def test_region_area(self):
        assert _region_area_mm2((0, 0, 100, 50)) == 5000.0

    def test_bbox_overlap_ratio_no_overlap(self):
        iou = _bbox_overlap_ratio((0, 0, 10, 10), (20, 20, 30, 30))
        assert iou == 0.0

    def test_bbox_overlap_ratio_full_overlap(self):
        iou = _bbox_overlap_ratio((0, 0, 10, 10), (0, 0, 10, 10))
        assert abs(iou - 1.0) < 0.01

    def test_bbox_overlap_ratio_partial(self):
        # 50% 겹침
        iou = _bbox_overlap_ratio((0, 0, 10, 10), (5, 0, 15, 10))
        assert 0.2 < iou < 0.4  # IoU = 50 / (100+100-50) ≈ 0.33


# ---------------------------------------------------------------------------
# detect_crop_regions (통합 테스트)
# ---------------------------------------------------------------------------

class TestDetectCropRegions:
    def test_identical_images_no_regions(self, tmp_path):
        """동일 이미지 → 크롭 영역 없음."""
        img = _make_image(840, 594, color=255)
        gen_path = _save_temp(img, tmp_path, "gen.png")
        ref_path = _save_temp(img, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        assert len(regions) == 0

    def test_known_difference_detected(self, tmp_path):
        """명확한 차이 영역이 탐지되는지 확인."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        # ref 중앙에 큰 검은 사각형 (차이 생성)
        _draw_rect(ref, 300, 200, 200, 150, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        assert len(regions) >= 1

        # 탐지된 영역이 차이 위치를 포함해야 함
        r = regions[0]
        # PNG 좌표 (300,200)~(500,350) → mm 변환 확인
        # 420/840 = 0.5 mm/px → mm_x ≈ 150~250
        assert r.bbox_mm[0] < 250  # x_min
        assert r.bbox_mm[2] > 150  # x_max
        assert r.diff_score > 0

    def test_max_regions_limit(self, tmp_path):
        """max_regions 초과 시 제한."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        # 여러 분산된 차이 영역 생성
        for i in range(8):
            _draw_rect(ref, 50 + i * 100, 100, 40, 40, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        cfg = CropDetectorConfig(max_regions=3)
        regions = detect_crop_regions(gen_path, ref_path, config=cfg)
        assert len(regions) <= 3

    def test_title_block_excluded(self, tmp_path):
        """타이틀 블록(하단 60mm) 영역의 차이는 무시."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        # ref 하단(타이틀 블록 영역)에만 차이 생성
        # 하단 60mm → 594 * (60/297) ≈ 120px → y=474~594
        _draw_rect(ref, 300, 500, 200, 80, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        # 타이틀 블록 영역이므로 탐지 안 됨 (또는 매우 적음)
        assert len(regions) == 0

    def test_priority_ordering(self, tmp_path):
        """P1(diff+complex) 영역이 P2(diff only) 앞에 오는지 확인."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        # 영역 1: diff + complex (gen에도 복잡한 패턴)
        _draw_rect(gen, 100, 100, 100, 100, color=0)  # gen에 검정 = 복잡
        _draw_rect(ref, 100, 100, 100, 100, color=128)  # ref에 회색 = 다르고 + 복잡

        # 영역 2: diff only (gen은 흰색, ref만 변경)
        _draw_rect(ref, 500, 100, 100, 100, color=0)  # ref에만 검정

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        if len(regions) >= 2:
            assert regions[0].priority <= regions[1].priority

    def test_returns_mm_coordinates(self, tmp_path):
        """bbox_mm이 유효한 mm 좌표인지 확인."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)
        _draw_rect(ref, 400, 250, 100, 100, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        for r in regions:
            x_min, y_min, x_max, y_max = r.bbox_mm
            assert 0 <= x_min < x_max <= 420.0
            assert 0 <= y_min < y_max <= 297.0


# ---------------------------------------------------------------------------
# detect_dense_regions (단일 이미지)
# ---------------------------------------------------------------------------

class TestHotspotExtraction:
    """거대 영역(페이지 50%+)이 hotspot으로 분할되는지 확인."""

    def test_oversized_region_splits_into_hotspots(self, tmp_path):
        """페이지 대부분이 다른 경우 hotspot으로 분할."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        # ref 전체에 걸쳐 여러 검은 영역 (거대한 diff 생성)
        for x in range(50, 750, 80):
            for y in range(50, 400, 80):
                _draw_rect(ref, x, y, 50, 50, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        # 거대 영역이 hotspot으로 분할되어야 함
        assert len(regions) >= 2
        # hotspot 이름이어야 함
        assert any("hotspot" in r.name for r in regions)
        # 각 hotspot의 면적이 전체의 50% 미만이어야 함
        page_area = 420.0 * 297.0
        for r in regions:
            assert r.area_mm2 < page_area * 0.5

    def test_hotspots_dont_overlap(self, tmp_path):
        """추출된 hotspot들이 서로 겹치지 않아야 함."""
        gen = _make_image(840, 594, color=255)
        ref = _make_image(840, 594, color=255)

        for x in range(30, 800, 60):
            for y in range(30, 450, 60):
                _draw_rect(ref, x, y, 40, 40, color=0)

        gen_path = _save_temp(gen, tmp_path, "gen.png")
        ref_path = _save_temp(ref, tmp_path, "ref.png")

        regions = detect_crop_regions(gen_path, ref_path)
        # 겹침 검사
        for i, a in enumerate(regions):
            for b in regions[i + 1:]:
                iou = _bbox_overlap_ratio(a.bbox_mm, b.bbox_mm)
                assert iou < 0.3, f"{a.name} and {b.name} overlap (IoU={iou:.2f})"


class TestDetectDenseRegions:
    def test_blank_image_no_regions(self, tmp_path):
        img = _make_image(840, 594, color=255)
        path = _save_temp(img, tmp_path, "blank.png")
        regions = detect_dense_regions(path)
        assert len(regions) == 0

    def test_dense_area_detected(self, tmp_path):
        img = _make_image(840, 594, color=255)
        # 중앙에 큰 복잡 영역 그리기 (여러 줄)
        draw = ImageDraw.Draw(img)
        for y in range(150, 350, 5):
            draw.line([(300, y), (500, y)], fill=0, width=2)

        path = _save_temp(img, tmp_path, "dense.png")
        regions = detect_dense_regions(path)
        assert len(regions) >= 1
        assert regions[0].density_score > 0
