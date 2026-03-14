"""BlockReplayer 시스템 테스트.

블록 라이브러리 로딩, 스케일 변환, 엔티티 재생(LINE/CIRCLE/ARC/LWPOLYLINE/TEXT),
좌표 변환, 핀 계산을 검증한다.
"""

from __future__ import annotations

import math

import pytest

from app.sld.block_replayer import BlockReplayer, _draw_bulge_arc


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

class RecordingBackend:
    """DrawingBackend 프로토콜을 만족하는 기록용 백엔드."""

    def __init__(self):
        self.calls: list[tuple] = []
        self._layer = "default"

    def set_layer(self, n: str) -> None:
        self._layer = n

    def add_line(self, s, e, **kw):
        self.calls.append(("line", s, e))

    def add_circle(self, c, r, **kw):
        self.calls.append(("circle", c, r))

    def add_arc(self, c, r, sa, ea, **kw):
        self.calls.append(("arc", c, r, sa, ea))

    def add_lwpolyline(self, pts, **kw):
        self.calls.append(("lwpoly", list(pts), kw.get("close", False)))

    def add_mtext(self, t, **kw):
        self.calls.append(("mtext", t, kw.get("insert"), kw.get("char_height")))

    def add_filled_rect(self, *a, **kw):
        pass

    def add_filled_circle(self, *a, **kw):
        pass


@pytest.fixture
def replayer():
    """Load real block library."""
    return BlockReplayer.load()


@pytest.fixture
def mini_replayer():
    """Minimal library for unit tests."""
    lib = {
        "blocks": {
            "TEST_LINE": {
                "width_du": 100.0,
                "height_du": 200.0,
                "bounds": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 200},
                "entities": [
                    {"type": "LINE", "start": [0.0, 0.0], "end": [100.0, 200.0]},
                ],
                "pins": {"top": [50.0, 200.0], "bottom": [50.0, 0.0]},
            },
            "TEST_CIRCLE": {
                "width_du": 100.0,
                "height_du": 100.0,
                "bounds": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 100},
                "entities": [
                    {"type": "CIRCLE", "center": [50.0, 50.0], "radius": 50.0},
                ],
                "pins": {"top": [50.0, 100.0], "bottom": [50.0, 0.0]},
            },
            "TEST_BULGE": {
                "width_du": 100.0,
                "height_du": 200.0,
                "bounds": {"min_x": 0, "min_y": 0, "max_x": 100, "max_y": 200},
                "entities": [
                    {
                        "type": "LWPOLYLINE",
                        "points": [[0.0, 0.0], [0.0, 200.0]],
                        "bulges": [0.5, 0.0],
                        "closed": False,
                    },
                ],
                "pins": {},
            },
        },
        "custom_blocks": {},
    }
    return BlockReplayer(lib)


# ---------------------------------------------------------------------------
# Tests: Loading and basic API
# ---------------------------------------------------------------------------


def test_load_real_library(replayer):
    """Real library loads with all expected blocks."""
    assert replayer.has_block("MCCB")
    assert replayer.has_block("RCCB")
    assert replayer.has_block("DP ISOL")
    assert replayer.has_block("KWH_METER")  # custom
    assert replayer.has_block("EARTH")  # custom
    assert not replayer.has_block("NONEXISTENT")


def test_block_dimensions(replayer):
    """Block dimensions are correct."""
    assert replayer.block_height_du("MCCB") == pytest.approx(597.82, rel=0.01)
    assert replayer.block_width_du("MCCB") > 0
    assert replayer.block_height_du("NONEXISTENT") == 0.0


def test_compute_scale(replayer):
    """Scale computation: target_height / height_du."""
    s = replayer.compute_scale("MCCB", 15.0)
    assert s == pytest.approx(15.0 / 597.82, rel=0.001)


def test_get_scaled_size(replayer):
    """Scaled size matches target height exactly."""
    w, h = replayer.get_scaled_size("MCCB", target_height_mm=15.0)
    assert h == pytest.approx(15.0, abs=0.01)
    assert w > 0


# ---------------------------------------------------------------------------
# Tests: Pin system
# ---------------------------------------------------------------------------


def test_pins_at_origin(mini_replayer):
    """Pins at origin with scale=1."""
    pins = mini_replayer.get_pins("TEST_LINE", 0, 0, scale=1.0)
    assert pins["top"] == pytest.approx((50.0, 200.0), abs=0.01)
    assert pins["bottom"] == pytest.approx((50.0, 0.0), abs=0.01)


def test_pins_offset_and_scaled(mini_replayer):
    """Pins with offset and scale."""
    pins = mini_replayer.get_pins("TEST_LINE", 10, 20, target_height_mm=10.0)
    s = 10.0 / 200.0  # scale
    assert pins["top"] == pytest.approx((10 + 50 * s, 20 + 200 * s), abs=0.01)
    assert pins["bottom"] == pytest.approx((10 + 50 * s, 20 + 0 * s), abs=0.01)


def test_pins_real_mccb(replayer):
    """Real MCCB pin positions are reasonable."""
    pins = replayer.get_pins("MCCB", 100, 50, target_height_mm=15.0)
    assert "top" in pins
    assert "bottom" in pins
    # Top should be higher than bottom
    assert pins["top"][1] > pins["bottom"][1]
    # Bottom Y should be at insertion Y
    assert pins["bottom"][1] == pytest.approx(50.0, abs=1.0)


# ---------------------------------------------------------------------------
# Tests: Entity replay
# ---------------------------------------------------------------------------


def test_replay_line(mini_replayer):
    """LINE entity replays with correct transform."""
    rec = RecordingBackend()
    mini_replayer.draw(rec, "TEST_LINE", x=10, y=20, scale=0.1)

    assert len(rec.calls) == 1
    call = rec.calls[0]
    assert call[0] == "line"
    # (0,0)*0.1 + (10,20) = (10, 20)
    assert call[1] == pytest.approx((10.0, 20.0), abs=0.01)
    # (100,200)*0.1 + (10,20) = (20, 40)
    assert call[2] == pytest.approx((20.0, 40.0), abs=0.01)


def test_replay_circle(mini_replayer):
    """CIRCLE entity replays with correct transform."""
    rec = RecordingBackend()
    mini_replayer.draw(rec, "TEST_CIRCLE", x=5, y=10, scale=0.5)

    assert len(rec.calls) == 1
    call = rec.calls[0]
    assert call[0] == "circle"
    # center (50,50)*0.5 + (5,10) = (30, 35)
    assert call[1] == pytest.approx((30.0, 35.0), abs=0.01)
    # radius 50*0.5 = 25
    assert call[2] == pytest.approx(25.0, abs=0.01)


def test_replay_bulge_emits_arc(mini_replayer):
    """LWPOLYLINE with bulge emits arc instead of line."""
    rec = RecordingBackend()
    mini_replayer.draw(rec, "TEST_BULGE", x=0, y=0, scale=1.0)

    # Should have 1 arc (bulge segment) + 0 lines (second bulge is 0 but it's last point)
    arc_calls = [c for c in rec.calls if c[0] == "arc"]
    assert len(arc_calls) == 1
    # Arc radius should be positive
    assert arc_calls[0][2] > 0


def test_replay_mccb_real(replayer):
    """Real MCCB replay produces expected entity types."""
    rec = RecordingBackend()
    replayer.draw(rec, "MCCB", x=100, y=50, target_height_mm=15.0)

    types = [c[0] for c in rec.calls]
    # MCCB has: 1 LWPOLYLINE with bulge → arc, 2 CIRCLEs
    assert "arc" in types
    assert types.count("circle") == 2


def test_replay_earth_real(replayer):
    """EARTH custom block replays as 4 lines."""
    rec = RecordingBackend()
    replayer.draw(rec, "EARTH", x=0, y=0, target_height_mm=5.0)

    types = [c[0] for c in rec.calls]
    assert types.count("line") == 4


def test_replay_kwh_meter_real(replayer):
    """KWH_METER replays as polyline + text."""
    rec = RecordingBackend()
    replayer.draw(rec, "KWH_METER", x=0, y=0, target_height_mm=6.0)

    types = [c[0] for c in rec.calls]
    assert "lwpoly" in types
    assert "mtext" in types


# ---------------------------------------------------------------------------
# Tests: Rotation
# ---------------------------------------------------------------------------


def test_replay_with_rotation(mini_replayer):
    """LINE entity rotated 90° CCW."""
    rec = RecordingBackend()
    mini_replayer.draw(rec, "TEST_LINE", x=0, y=0, scale=1.0, rotation=90.0)

    call = rec.calls[0]
    assert call[0] == "line"
    # (0,0) rotated = (0,0)
    assert call[1] == pytest.approx((0.0, 0.0), abs=0.01)
    # (100,200) rotated 90° CCW: (-200, 100)
    assert call[2] == pytest.approx((-200.0, 100.0), abs=0.01)


# ---------------------------------------------------------------------------
# Tests: Error handling
# ---------------------------------------------------------------------------


def test_unknown_block_raises(mini_replayer):
    """Drawing unknown block raises ValueError."""
    rec = RecordingBackend()
    with pytest.raises(ValueError, match="Unknown block"):
        mini_replayer.draw(rec, "NONEXISTENT", 0, 0, scale=1.0)


def test_scale_and_target_height_mutually_exclusive(mini_replayer):
    """Cannot specify both scale and target_height_mm."""
    rec = RecordingBackend()
    with pytest.raises(ValueError, match="mutually exclusive"):
        mini_replayer.draw(rec, "TEST_LINE", 0, 0, scale=1.0, target_height_mm=10.0)


# ---------------------------------------------------------------------------
# Tests: Bulge→Arc conversion
# ---------------------------------------------------------------------------


def test_draw_bulge_arc_positive():
    """Positive bulge produces valid arc call."""
    rec = RecordingBackend()
    _draw_bulge_arc(rec, (0.0, 0.0), (0.0, 10.0), 0.5)
    assert len(rec.calls) == 1
    assert rec.calls[0][0] == "arc"
    # Radius should be positive and reasonable
    assert rec.calls[0][2] > 0


def test_draw_bulge_arc_negative():
    """Negative bulge produces valid arc call."""
    rec = RecordingBackend()
    _draw_bulge_arc(rec, (0.0, 0.0), (10.0, 0.0), -0.3)
    assert len(rec.calls) == 1
    assert rec.calls[0][0] == "arc"
    assert rec.calls[0][2] > 0


def test_draw_bulge_arc_semicircle():
    """Bulge=1.0 produces semicircle."""
    rec = RecordingBackend()
    _draw_bulge_arc(rec, (0.0, 0.0), (10.0, 0.0), 1.0)
    assert len(rec.calls) == 1
    _, center, radius, sa, ea = rec.calls[0]
    # For a semicircle with chord=10, radius = 5
    assert radius == pytest.approx(5.0, rel=0.01)
    # Arc span should be ~180°
    assert abs(ea - sa) == pytest.approx(180.0, rel=0.01)
