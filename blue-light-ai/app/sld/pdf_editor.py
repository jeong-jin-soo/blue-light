"""
PDF template editor for Track A — title block text replacement.

Uses PyMuPDF (fitz) to:
1. Identify text elements in the title block region (bottom ~15% of page)
2. Redact existing text using the redaction API
3. Insert new text at the same positions

Only the title block is edited — all SLD body content (which is vector paths)
remains completely untouched.
"""

from __future__ import annotations

import io
import logging
from dataclasses import dataclass

import fitz  # PyMuPDF

logger = logging.getLogger(__name__)

# Title block is in the bottom ~15% of A3 landscape (297mm height)
# This corresponds to approximately y > 252mm from bottom, or in PDF coords y > ~715pt
_TITLE_BLOCK_Y_THRESHOLD = 0.85  # fraction of page height


@dataclass
class TextRegion:
    """A text element in the title block with its position and content."""

    text: str
    x: float
    y: float
    width: float
    height: float
    font_size: float
    font_name: str


class PdfTemplateEditor:
    """
    Editor for replacing title block text in real LEW SLD PDFs.

    Usage:
        editor = PdfTemplateEditor("template.pdf")
        regions = editor.get_title_block_regions()
        editor.replace_title_block({
            "client_name": "ABC Pte Ltd",
            "address": "123 Street, Singapore",
            "lew_name": "John Doe",
            "lew_licence": "LEW/123456",
            "date": "01/03/2026",
        })
        editor.save("output.pdf")
    """

    def __init__(self, template_pdf_path: str):
        self._doc = fitz.open(template_pdf_path)
        self._page = self._doc[0]  # SLDs are single-page
        self._page_height = self._page.rect.height

    def get_title_block_regions(self) -> list[TextRegion]:
        """
        Extract all text elements in the title block region.

        Returns list of TextRegion objects with position, font, and content.
        """
        y_threshold = self._page_height * _TITLE_BLOCK_Y_THRESHOLD
        regions = []

        # Extract text with position info using "dict" mode
        text_data = self._page.get_text("dict")
        for block in text_data.get("blocks", []):
            if block.get("type") != 0:  # 0 = text block
                continue
            for line in block.get("lines", []):
                for span in line.get("spans", []):
                    # Check if in title block region (PDF Y increases downward)
                    bbox = span.get("bbox", (0, 0, 0, 0))
                    if bbox[1] >= y_threshold:
                        regions.append(TextRegion(
                            text=span.get("text", ""),
                            x=bbox[0],
                            y=bbox[1],
                            width=bbox[2] - bbox[0],
                            height=bbox[3] - bbox[1],
                            font_size=span.get("size", 10),
                            font_name=span.get("font", ""),
                        ))

        logger.info(f"Found {len(regions)} text regions in title block")
        return regions

    def replace_title_block(self, replacements: dict) -> None:
        """
        Replace title block text fields with new values.

        Uses pattern matching to identify which field each text region
        corresponds to, then redacts and re-inserts with new text.

        Args:
            replacements: dict with keys like:
                - client_name, address, postal_code
                - lew_name, lew_licence, lew_mobile
                - drawing_number, date, project_name
        """
        regions = self.get_title_block_regions()
        if not regions:
            logger.warning("No text regions found in title block")
            return

        # Map regions to replacement fields based on position and content patterns
        # Title blocks in Singapore SLDs follow a standard layout
        for region in regions:
            replacement_text = self._match_replacement(region, replacements)
            if replacement_text is not None:
                self._replace_text_region(region, replacement_text)

    def _match_replacement(self, region: TextRegion, replacements: dict) -> str | None:
        """
        Match a text region to a replacement field.

        Uses heuristics based on position and existing text content.
        """
        text = region.text.strip()
        if not text:
            return None

        # Check known field labels and match to replacements
        text_lower = text.lower()

        # Name/Company fields
        if any(kw in text_lower for kw in ("client", "owner", "name of")):
            return replacements.get("client_name")

        # Address fields
        if any(kw in text_lower for kw in ("address", "location", "premises")):
            return replacements.get("address")

        # LEW fields
        if "lew" in text_lower or "licence" in text_lower or "license" in text_lower:
            if "no" in text_lower or "licence" in text_lower:
                return replacements.get("lew_licence")
            return replacements.get("lew_name")

        # Drawing number
        if any(kw in text_lower for kw in ("drawing no", "drg no", "dwg")):
            return replacements.get("drawing_number")

        # Date
        if "date" in text_lower:
            return replacements.get("date")

        # Project name
        if "project" in text_lower:
            return replacements.get("project_name")

        return None

    def _replace_text_region(self, region: TextRegion, new_text: str) -> None:
        """Replace a specific text region using PyMuPDF redaction API."""
        # Define the area to redact
        rect = fitz.Rect(
            region.x - 1,
            region.y - 1,
            region.x + region.width + 1,
            region.y + region.height + 1,
        )

        # Add redaction annotation (marks area for deletion)
        self._page.add_redact_annot(
            rect,
            text=new_text,
            fontname="helv",  # Helvetica (closest built-in to ArialMT)
            fontsize=region.font_size,
            align=fitz.TEXT_ALIGN_LEFT,
            fill=(1, 1, 1),  # White fill to cover old text
        )

        # Apply all redactions
        self._page.apply_redactions()

    def save(self, output_path: str) -> None:
        """Save the edited PDF to a new file."""
        self._doc.save(output_path)
        logger.info(f"Edited PDF saved: {output_path}")

    def get_bytes(self) -> bytes:
        """Get the edited PDF as bytes."""
        return self._doc.tobytes()

    def close(self) -> None:
        """Close the PDF document."""
        self._doc.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
