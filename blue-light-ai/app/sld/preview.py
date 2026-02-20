"""
DXF to SVG conversion using ezdxf's drawing add-on.
"""

import logging

import ezdxf
from ezdxf.addons.drawing import Frontend, RenderContext
from ezdxf.addons.drawing import svg as svg_backend
from ezdxf.addons.drawing import layout as drawing_layout

logger = logging.getLogger(__name__)


def dxf_to_svg(dxf_path: str) -> str:
    """
    Convert a DXF file to SVG string using ezdxf SVGBackend.

    Args:
        dxf_path: Path to the DXF file.

    Returns:
        SVG string representation of the drawing.
    """
    try:
        doc = ezdxf.readfile(dxf_path)
        return doc_to_svg(doc)
    except Exception as e:
        logger.error(f"Failed to convert DXF to SVG: {e}", exc_info=True)
        raise


def doc_to_svg(doc: ezdxf.document.Drawing) -> str:
    """
    Convert an ezdxf Document to SVG string.

    Args:
        doc: ezdxf Drawing document (in-memory).

    Returns:
        SVG string representation.
    """
    msp = doc.modelspace()
    context = RenderContext(doc)
    backend = svg_backend.SVGBackend()
    frontend = Frontend(context, backend)

    frontend.draw_layout(msp)

    # Auto-size page (0, 0 = auto-detect from content)
    page = drawing_layout.Page(0, 0)
    svg_string = backend.get_string(page)

    return svg_string
