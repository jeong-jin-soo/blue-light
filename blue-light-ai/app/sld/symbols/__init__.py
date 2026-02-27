"""
SLD Symbol Library — Complete set of IEC 60617 electrical symbols.

All symbols are designed for Singapore SLD standards (SS 638:2018, CP 5:2018).
Each symbol class implements BaseSymbol with:
  - draw(backend, x, y): Render onto any DrawingBackend
  - pins: Named connection points for layout routing
  - anchors: Named text anchor points for label positioning
  - lineweights: Per-element line thickness specs
  - to_svg(): Standalone SVG export for verification
"""

from app.sld.symbols.base import BaseSymbol
from app.sld.symbols.breakers import ACB, ELCB, MCCB, MCB, RCCB, CircuitBreaker
from app.sld.symbols.busbars import Busbar
from app.sld.symbols.loads import IndustrialSocket, Timer, TimerWithBypass
from app.sld.symbols.meters import Ammeter, KwhMeter, Voltmeter
from app.sld.symbols.motors import Generator, Motor
from app.sld.symbols.msb_components import IndicatorLight, ProtectionRelay, ShuntTrip
from app.sld.symbols.protection import EarthSymbol, Fuse, SurgeProtector
from app.sld.symbols.switches import (
    ATS,
    BIConnector,
    DoublePoleSwitch,
    Isolator,
    IsolatorForMachine,
)
from app.sld.symbols.transformers import CurrentTransformer, PotentialTransformer, PowerTransformer

__all__ = [
    # Base
    "BaseSymbol",
    # Breakers
    "CircuitBreaker",
    "ACB",
    "MCCB",
    "MCB",
    "RCCB",
    "ELCB",
    # Meters
    "KwhMeter",
    "Ammeter",
    "Voltmeter",
    # Switches
    "Isolator",
    "IsolatorForMachine",
    "DoublePoleSwitch",
    "BIConnector",
    "ATS",
    # Protection
    "Fuse",
    "EarthSymbol",
    "SurgeProtector",
    # Transformers
    "PowerTransformer",
    "CurrentTransformer",
    "PotentialTransformer",
    # Motors
    "Motor",
    "Generator",
    # Busbars
    "Busbar",
    # Loads
    "IndustrialSocket",
    "Timer",
    "TimerWithBypass",
    # MSB Components
    "ShuntTrip",
    "IndicatorLight",
    "ProtectionRelay",
]
