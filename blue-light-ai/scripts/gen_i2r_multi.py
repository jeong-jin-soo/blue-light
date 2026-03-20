"""Generate I2R multi-DB SLD (MSB + DB2)."""
from app.sld.generator import SldPipeline
from pathlib import Path

msb = [
    {'circuit_id': 'RL1', 'name': '3 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L1', 'section': 'Lighting'},
    {'circuit_id': 'RL2', 'name': '3 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L1', 'section': 'Lighting'},
    {'circuit_id': 'RL3', 'name': '3 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L1', 'section': 'Lighting'},
    {'circuit_id': 'RS1', 'name': '2 Nos 13A Double S/S/O', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 2.5mm2 PVC cable + 2.5mm2 CPC in metal trunking / conduit', 'phase': 'L1', 'section': 'Power'},
    {'circuit_id': 'RS2', 'name': '3 Nos 13A Double S/S/O', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 2.5mm2 PVC cable + 2.5mm2 CPC in metal trunking / conduit', 'phase': 'L1', 'section': 'Power'},
    {'circuit_id': 'YL1', 'name': '3 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L2', 'section': 'Lighting'},
    {'circuit_id': 'YS1', 'name': '2 Nos 13A Double S/S/O', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 2.5mm2 PVC cable + 2.5mm2 CPC in metal trunking / conduit', 'phase': 'L2', 'section': 'Power'},
    {'circuit_id': 'YISO1', 'name': '1 No. 32A DP Isolator', 'breaker_type': 'MCB', 'breaker_rating': 32, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 6mm2 PVC cable + 6mm2 CPC in metal trunking / conduit', 'phase': 'L2', 'section': 'Power'},
    {'circuit_id': 'YH3', 'name': '1 No. Heater Point', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 4mm2 PVC cable + 4mm2 CPC in metal trunking / conduit', 'phase': 'L2', 'section': 'Power'},
    {'circuit_id': 'BL1', 'name': '3 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L3', 'section': 'Lighting'},
    {'circuit_id': 'BL2', 'name': '2 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': 'L3', 'section': 'Lighting'},
    {'circuit_id': 'BISO3', 'name': '1 No. 20A DP Isolator', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 4mm2 PVC cable + 4mm2 CPC in metal trunking / conduit', 'phase': 'L3', 'section': 'Power'},
    {'circuit_id': 'BISO4', 'name': '1 No. 32A DP Isolator', 'breaker_type': 'MCB', 'breaker_rating': 32, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 6mm2 PVC cable + 6mm2 CPC in metal trunking / conduit', 'phase': 'L3', 'section': 'Power'},
    {'circuit_id': 'RYBISO5', 'name': '1 No. 20A TPN Isolator', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'TPN', 'fault_kA': 6, 'cable': '4 x 4mm2 PVC cable + 4mm2 CPC in metal trunking / conduit', 'phase': 'RYB', 'section': 'Power'},
    {'circuit_id': 'SPARE', 'name': 'Spare', 'breaker_type': 'SPARE', 'breaker_rating': 0, 'poles': 'SPN', 'phase': 'L1', 'section': 'Power'},
]

db2 = []
for ph in ['L1','L2','L3']:
    db2.append({'circuit_id': f'{ph}S1', 'name': '2 Nos Lighting Points', 'breaker_type': 'MCB', 'breaker_rating': 10, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 1.5mm2 PVC cable + 1.5mm2 CPC in metal trunking / conduit', 'phase': ph, 'section': 'Lighting'})
    for j in range(5):
        db2.append({'circuit_id': f'{ph}P{j+1}', 'name': f'{1+j%2} No. 13A Double S/S/O', 'breaker_type': 'MCB', 'breaker_rating': 20, 'breaker_characteristic': 'B', 'poles': 'SPN', 'fault_kA': 6, 'cable': '2 x 2.5mm2 PVC cable + 2.5mm2 CPC in metal trunking / conduit', 'phase': ph, 'section': 'Power'})

req = {
    'supply_type': 'three_phase', 'kva': 69.282, 'voltage': 400,
    'supply_source': 'building_riser', 'metering': 'ct_meter', 'ct_ratio': '100/5A',
    'metering_config': {
        'ct_ratio': '100/5A',
        'protection_ct_ratio': '100/5A',
        'metering_ct_class': 'CL1 5VA',
        'protection_ct_class': '5P10 20VA',
        'elr_spec': '0 - 3A 0.2 SEC',
        'ammeter_range': '0 - 100A',
        'voltmeter_range': '0 - 500V',
    },
    'incoming_cable': '4 x 50mm2 PVC/PVC cable + 50mm2 CPC in metal trunking',
    'unit_isolator': {'type': 'ISOLATOR', 'rating': 100, 'poles': '4P'},
    'main_breaker': {'type': 'MCCB', 'rating': 100, 'poles': 'TPN', 'fault_kA': 35},
    'elcb': {'type': 'RCCB', 'rating': 63, 'sensitivity_ma': 30, 'poles': 4},
    'post_elcb_mcb': {'type': 'MCB', 'rating': 63, 'poles': 'TPN', 'breaker_characteristic': 'B'},
    'internal_cable': '4 x 35mm2 PVC cable + 50mm2 CPC in cable tray',
    'db_topology': 'hierarchical',
    'distribution_boards': [
        {'name': 'MSB', 'busbar_rating': 100, 'db_location': 'Located inside unit #05-26', 'approved_load': '69.282 kVA at 400V', 'sub_circuits': msb,
         'feeder_circuits': [{'target_db': 'DB2', 'breaker_type': 'MCB', 'breaker_rating': 63, 'breaker_characteristic': 'C', 'fault_kA': 10, 'cable': '4 x 16mm2 PVC cable + 50mm2 CPC'}]},
        {'name': 'DB2', 'fed_from': 'MSB', 'main_breaker': {'type': 'MCB', 'rating': 40, 'poles': 'TPN', 'fault_kA': 6, 'breaker_characteristic': 'B'}, 'busbar_rating': 80, 'sub_circuits': db2,
         'protection_groups': [
             {'phase': ph, 'rccb': {'type': 'RCCB', 'rating': 40, 'sensitivity_ma': 30, 'poles': 2}, 'circuits': [c['circuit_id'] for c in db2 if c['phase']==ph]}
             for ph in ['L1','L2','L3']
         ]},
    ],
}
app_info = {'client_name': 'Easytentage.com Pte Ltd', 'client_address': 'North Link Building #05-26, 10 Admiralty St, Singapore 757695', 'lew_name': 'Abdul Jabbar', 'lew_licence': '8/35550', 'drawing_number': 'I2R-ETR-NLB-SLD'}

pipeline = SldPipeline()
result = pipeline.run(req, application_info=app_info)
Path('output/I2R_multi_v3.svg').write_text(result.svg_string)
Path('output/I2R_multi_v3.pdf').write_bytes(result.pdf_bytes)
if result.dxf_bytes: Path('output/I2R_multi_v3.dxf').write_bytes(result.dxf_bytes)
print(f'SVG: {len(result.svg_string)} chars, PDF: {len(result.pdf_bytes)} bytes')
