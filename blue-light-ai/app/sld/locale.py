"""
SLD drawing string constants — centralized for maintainability and future localization.

All display text used in SLD generation is defined here as frozen dataclass defaults.
The default values reproduce the current (Singapore English) behavior exactly.

Usage:
    from app.sld.locale import SG_LOCALE
    locale = SG_LOCALE
    locale.title_block.client_address   # "CLIENT / ADDRESS :"
    locale.circuit.spare                # "SPARE"
"""

from dataclasses import dataclass, field


@dataclass(frozen=True)
class TitleBlockLabels:
    """Title block field labels and static text."""
    client_address: str = "CLIENT / ADDRESS :"
    main_contractor: str = "MAIN CONTRACTOR :"
    electrical_contractor: str = "ELECTRICAL CONTRACTOR :"
    drawing_title: str = "DRAWING TITLE :"
    lew: str = "LEW :"
    checked: str = "CHECKED :"
    date: str = "DATE :"
    drawing_no: str = "DRAWING NO. :"
    rev: str = "REV :"
    scale_nts: str = "SCALE : NTS"
    sheet_1of1: str = "SHEET : 1 OF 1"
    sld_title: str = "SINGLE LINE DIAGRAM\\P(SLD)"
    to_be_filled: str = "(To be filled by LEW)"
    ema_licence: str = "EMA Licence No. : "
    mobile_number: str = "Mobile Number. : "


@dataclass(frozen=True)
class MeterBoardLabels:
    """Meter board area labels."""
    meter_board: str = "METER BOARD"
    located_meter_compartment: str = "LOCATED AT METER COMPARTMENT"
    located_inside_unit: str = "LOCATED INSIDE UNIT"
    kwh_meter_by_sp: str = "KWH METER BY SP"
    kwh_meter_pg: str = "PG KWH METER"
    ct_by_sp: str = "CT BY SP"
    isolator: str = "ISOLATOR"


@dataclass(frozen=True)
class IncomingLabels:
    """Incoming supply area labels."""
    incoming_hdb: str = "INCOMING FROM HDB ELECTRICAL RISER"
    from_landlord: str = "SUPPLY FROM BUILDING RISER"
    from_landlord_supply: str = "FROM LANDLORD SUPPLY"
    from_power_supply: str = "FROM POWER SUPPLY ON SITE"
    approved_load: str = "APPROVED LOAD"


@dataclass(frozen=True)
class CircuitLabels:
    """Circuit and DB box labels."""
    comb_busbar: str = "COMB BAR"
    busbar: str = "BUSBAR"
    db: str = "DB"
    spare: str = "SPARE"
    earth_conductor: str = "CU/GRN-YEL"


@dataclass(frozen=True)
class LegendDescriptions:
    """Legend entry descriptions for the SLD legend box."""
    acb: str = "Air Circuit Breaker"
    mccb: str = "Moulded Case Circuit Breaker"
    mcb: str = "Miniature Circuit Breaker"
    elcb: str = "Earth Leakage Circuit Breaker"
    rccb: str = "Residual Current Circuit Breaker"
    kwh_meter: str = "kWh Meter (Energy Meter)"
    ammeter: str = "Ammeter (Current Meter)"
    voltmeter: str = "Voltmeter (Voltage Meter)"
    earth: str = "Earth Bar / Ground Connection"
    isolator: str = "Isolator / Disconnect Switch"
    isolator_machine: str = "Isolator for Machine"
    double_pole_switch: str = "Double Pole Switch"
    ct: str = "Current Transformer"
    spd: str = "Surge Protection Device"
    ats: str = "Automatic Transfer Switch"
    bi_connector: str = "BI Connector (Bus Isolator)"
    transformer: str = "Power Transformer"
    fuse: str = "Fuse"
    motor: str = "Motor"
    generator: str = "Generator"
    busbar: str = "Busbar (Main Distribution)"
    industrial_socket: str = "Industrial Socket (CEE-Form)"
    timer: str = "Timer / Time Switch"
    timer_bypass: str = "Timer with Bypass Switch"
    shunt_trip: str = "Shunt Trip"
    indicator_light: str = "Indicator Light"
    protection_relay: str = "Protection Relay (O/C E/F)"
    pt: str = "Potential Transformer (Voltage Transformer)"


@dataclass(frozen=True)
class SldLocale:
    """Complete SLD locale — defaults reproduce current Singapore English output exactly."""
    title_block: TitleBlockLabels = field(default_factory=TitleBlockLabels)
    meter_board: MeterBoardLabels = field(default_factory=MeterBoardLabels)
    incoming: IncomingLabels = field(default_factory=IncomingLabels)
    circuit: CircuitLabels = field(default_factory=CircuitLabels)
    legend: LegendDescriptions = field(default_factory=LegendDescriptions)


# Default instance — all values identical to current hardcoded strings.
SG_LOCALE = SldLocale()
