"""
PDF backend unit tests.

Tests for PdfBackend: canvas creation, drawing primitives, text rendering,
and output serialization (in-memory and file).
"""

from __future__ import annotations

from pathlib import Path

import pytest

from app.sld.pdf_backend import PdfBackend


# ── TestPdfBackendBasics ─────────────────────────────────────────


class TestPdfBackendBasics:
    """Canvas creation and page size verification."""

    def test_creates_in_memory_canvas(self):
        """PdfBackend() without path creates an in-memory canvas."""
        pdf = PdfBackend()
        assert pdf.canvas is not None

    def test_creates_file_canvas(self, tmp_path):
        """PdfBackend(path) creates a file-backed canvas."""
        output = str(tmp_path / "test.pdf")
        pdf = PdfBackend(output_path=output)
        assert pdf.canvas is not None

    def test_a3_landscape_size(self):
        """Page size is A3 landscape (420mm x 297mm)."""
        pdf = PdfBackend()
        width, height = pdf._page_size
        # A3 landscape: 420mm x 297mm → points (1mm ≈ 2.8346pt)
        assert width == pytest.approx(420 * 2.8346, abs=1.0)
        assert height == pytest.approx(297 * 2.8346, abs=1.0)

    def test_set_layer(self):
        """set_layer changes the current layer without error."""
        pdf = PdfBackend()
        for layer in ["SLD_SYMBOLS", "SLD_CONNECTIONS", "SLD_POWER_MAIN",
                       "SLD_ANNOTATIONS", "SLD_TITLE_BLOCK"]:
            pdf.set_layer(layer)  # Should not raise


# ── TestPdfBackendDrawing ────────────────────────────────────────


class TestPdfBackendDrawing:
    """Drawing primitives execute without error."""

    def test_add_line(self):
        pdf = PdfBackend()
        pdf.add_line((0, 0), (100, 100))
        pdf.add_line((10, 20), (30, 40), lineweight=50)

    def test_add_circle(self):
        pdf = PdfBackend()
        pdf.add_circle((50, 50), radius=10.0)

    def test_add_filled_circle(self):
        pdf = PdfBackend()
        pdf.add_filled_circle((50, 50), radius=5.0)
        pdf.add_filled_circle((60, 60), radius=3.0, fill_color="#FF0000")

    def test_add_arc(self):
        pdf = PdfBackend()
        pdf.add_arc((50, 50), radius=10.0, start_angle=0, end_angle=180)
        pdf.add_arc((50, 50), radius=10.0, start_angle=270, end_angle=90)  # wrap

    def test_add_lwpolyline(self):
        pdf = PdfBackend()
        pdf.add_lwpolyline([(0, 0), (10, 0), (10, 10)], close=True)
        pdf.add_lwpolyline([(0, 0), (10, 0)], close=False, lineweight=25)

    def test_add_lwpolyline_too_few_points(self):
        """add_lwpolyline with < 2 points is a no-op (no crash)."""
        pdf = PdfBackend()
        pdf.add_lwpolyline([])
        pdf.add_lwpolyline([(5, 5)])

    def test_add_mtext_single_line(self):
        pdf = PdfBackend()
        pdf.add_mtext("Hello World", insert=(10, 20), char_height=3.0)

    def test_add_mtext_multiline(self):
        """Multiline text with \\P separator."""
        pdf = PdfBackend()
        pdf.add_mtext("Line1\\PLine2\\PLine3", insert=(10, 200))

    def test_add_mtext_rotated(self):
        """Rotated text (90° CCW)."""
        pdf = PdfBackend()
        pdf.add_mtext("Vertical Text", insert=(50, 100), rotation=90.0)

    def test_add_mtext_rotated_multiline(self):
        """Rotated multiline text."""
        pdf = PdfBackend()
        pdf.add_mtext("Line1\\PLine2", insert=(50, 100), rotation=90.0)

    def test_add_mtext_non_string_input(self):
        """Non-string input is converted to string."""
        pdf = PdfBackend()
        pdf.add_mtext(12345, insert=(10, 20))
        pdf.add_mtext({"key": "value"}, insert=(10, 30))

    def test_add_filled_rect(self):
        pdf = PdfBackend()
        pdf.add_filled_rect(10, 20, 50, 30)
        pdf.add_filled_rect(10, 60, 50, 30, fill_color="#0000FF")
        pdf.add_filled_rect(10, 100, 50, 30, fill_color=(0.5, 0.5, 0.5))


# ── TestPdfBackendOutput ─────────────────────────────────────────


class TestPdfBackendOutput:
    """Output serialization and file creation."""

    def test_get_bytes_pdf_header(self):
        """get_bytes() returns valid PDF starting with %PDF-."""
        pdf = PdfBackend()
        pdf.add_line((0, 0), (100, 100))
        data = pdf.get_bytes()
        assert isinstance(data, bytes)
        assert data[:5] == b"%PDF-"

    def test_get_bytes_reasonable_size(self):
        """PDF output has reasonable size (> 100 bytes for minimal content)."""
        pdf = PdfBackend()
        pdf.add_line((0, 0), (100, 100))
        pdf.add_mtext("Test", insert=(50, 50))
        data = pdf.get_bytes()
        assert len(data) > 100

    def test_save_creates_file(self, tmp_path):
        """save() creates a valid PDF file on disk."""
        output_path = str(tmp_path / "test_output.pdf")
        pdf = PdfBackend(output_path=output_path)
        pdf.add_line((0, 0), (100, 100))
        pdf.add_mtext("File test", insert=(50, 50))
        pdf.add_circle((200, 150), radius=20)
        pdf.save()

        saved = Path(output_path)
        assert saved.exists()
        assert saved.stat().st_size > 100

        # Verify it's a valid PDF
        content = saved.read_bytes()
        assert content[:5] == b"%PDF-"

    def test_get_bytes_from_file_canvas(self, tmp_path):
        """get_bytes() works even when using a file-backed canvas."""
        output_path = str(tmp_path / "file_backed.pdf")
        pdf = PdfBackend(output_path=output_path)
        pdf.add_line((0, 0), (50, 50))
        data = pdf.get_bytes()
        assert data[:5] == b"%PDF-"
