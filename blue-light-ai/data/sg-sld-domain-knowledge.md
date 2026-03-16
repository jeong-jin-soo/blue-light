# Singapore SLD Domain Knowledge Reference

SLD(Single Line Diagram) 생성 시 필요한 싱가포르 전기 설비 도메인 지식.
이 파일은 SLD 레이아웃 코드 작성 및 AI 시스템 프롬프트에서 참조.

**출처**: SP Group "How to Apply for Electricity Connection" (Jan 2026),
SS 638:2018, CP 5:2018, EMA Metering Code, 실제 LEW SLD 레퍼런스

---

## 1. 공급 전압 분류 (SP Group §1.2)

| 분류 | 전압 | 용도 |
|------|------|------|
| Low Tension (LT) | 230V 1-phase, 400V 3-phase | 일반 주거/상업 |
| High Tension (HT) | 22kV, 6.6kV | 대형 상업/산업 |
| Extra-High Tension (EHT) | 66kV | 대규모 산업 |
| Ultra-High Tension (UHT) | 230kV | 초대형 시설 |

### LT 공급 용량 한계 (§1.2.3)
- **1-phase 230V**: 최대 23kVA, 100A
- **3-phase 400V 4-wire**: 최대 5,000kVA (변전소 당)
- **직접 서비스 연결**: 최대 280kVA (400A) — 초과 시 변전소 필요 (§1.4)
- **가정용 간이 절차**: 45kVA 이하 (§1.5)
- **Power factor**: 0.85 기준

---

## 2. 계량(Metering) 유형 결정 규칙

### 직접 계량 (Direct Metering, `sp_meter`)
- **적용**: 1-phase 전체 + 3-phase ≤100A per phase
- **구성**: SP kWh meter on meter board
- **SLD 표기**: Meter Board에 ISOLATOR → kWh METER → MCB 수평 배치

### CT 계량 (CT Metering, `ct_meter`)
- **적용**: 3-phase >100A per phase (§6.7)
- **구성**: Pre-wired metering panel on customer's main switchboard
- **필수 부품** (SP Group §6.7.1):
  - Approved type test terminal block
  - 6A 4-pole Type C MCB (10kA) — incoming
  - Metering cables to busbars
  - 3× metering current transformers (CTs)
  - Incoming MCB rated per applied load (approved by SP)

### Landlord Supply (계량 없음)
- **적용**: 건물주/MCST에서 전원 공급받는 세입자
- **구성**: 계량 장치 없음 (landlord가 관리)
- **SLD 표기**: "FROM LANDLORD SUPPLY" 라벨, `metering: null`

---

## 3. ★ SLD 컴포넌트 흐름 순서 (전원→부하)

### 전기적 흐름의 기본 원칙
SLD는 전원(Supply)에서 부하(Load)로의 전력 흐름을 표현한다.
**컴포넌트 배치 순서는 반드시 실제 전기적 흐름을 따라야 한다.**

### 3.1 Direct Metering (≤100A, SP Meter)

```
전원(Supply) — 하단
  │
  ├─ Incoming Cable
  │
  ├─ Meter Board [ISOLATOR → kWh METER → MCB] (수평)
  │
  ├─ Isolator (landlord supply일 때만)
  │
  ├─ Main Breaker (MCCB/MCB/ACB)
  │
  ├─ ELCB/RCCB (Earth Leakage Protection)
  │
  ├─ Main Busbar (COMB BAR / BUSBAR)
  │
  └─ Sub-Circuits (MCB + Load) — 상단
부하(Load)
```

### 3.2 CT Metering (>100A 3-phase) ★★★

**SP Group §6.9.6: CT 인클로저는 incoming circuit breaker(s) "immediately after"에 위치**

```
전원(Supply) — 하단
  │
  ├─ Incoming Cable
  │
  ├─ Main Breaker (MCCB/ACB) ←── 전원 직후 첫 보호장치
  │
  ├─ Post-MCCB Potential Fuse (2A SPN MCB) ←── CT 회로 보호
  │                                              (§6.8.6: 6A 10kA MCB)
  ├─ Protection CT (for ELR) ←── 보호 CT (5P10 20VA)
  │   └─ [LEFT branch] ELR (Earth Leakage Relay)
  │
  ├─ Metering CT ←── 계량 CT (CL1 5VA)
  │   ├─ [LEFT branch] ASS → Ammeter
  │   ├─ [RIGHT branch] VSS → Voltmeter
  │   └─ [RIGHT branch] SPPG kWh Meter
  │
  ├─ Pre-Busbar Potential Fuse (2A SPN MCB) ←── 계량 회로 보호
  │
  ├─ BI Connector ←── 계량 구간 → 분전반 모선 연결
  │
  ├─ ELCB/RCCB (있는 경우)
  │
  ├─ Main Busbar
  │
  └─ Sub-Circuits — 상단
부하(Load)
```

**⚠️ 흔한 실수 방지:**
- BI Connector ≠ 전원 입력(Input). **Busbar Interconnect** = 계측 구간 출력 → 모선 연결
- MCCB는 CT보다 **전원 가까이** (아래쪽). CT 인클로저는 MCCB "immediately after" (§6.9.6)
- Protection CT가 Metering CT보다 전원 가까이 (아래쪽)

### 3.3 Cable Extension SLD

```
전원(Supply) — 하단
  │
  ├─ Incoming Cable ("FROM LANDLORD SUPPLY")
  │
  ├─ Main Breaker (MCB/MCCB) — 계량 없음
  │
  ├─ Main Busbar
  │
  └─ Sub-Circuits — 상단
부하(Load)
```

---

## 4. 주요 컴포넌트 역할 사전

| 약어 | 정식 명칭 | 역할 | SLD 위치 |
|------|---------|------|---------|
| **MCCB** | Moulded Case Circuit Breaker | 주 차단기 (125A~630A) | 전원 직후 |
| **MCB** | Miniature Circuit Breaker | 소형 차단기 (≤100A) | 주 차단기 또는 분기 |
| **ACB** | Air Circuit Breaker | 대형 차단기 (>630A) | 전원 직후 |
| **ELCB** | Earth Leakage Circuit Breaker | 누전 차단기 | MCCB → ELCB → Busbar |
| **RCCB** | Residual Current Circuit Breaker | 누전 차단기 (ELCB와 동등) | MCCB → RCCB → Busbar |
| **CT** | Current Transformer | 전류 변성기 (계량/보호) | MCCB 직후 (§6.9.6) |
| **ELR** | Earth Leakage Relay | 지락 계전기 | Protection CT에서 분기 |
| **ASS** | Ammeter Selector Switch | 전류계 선택 스위치 | Metering CT에서 LEFT 분기 |
| **VSS** | Voltmeter Selector Switch | 전압계 선택 스위치 | Metering CT에서 RIGHT 분기 |
| **BI Connector** | Busbar Interconnect | CT 계측 출력 → 모선 연결 | CT 구간 최상단 (부하측) |
| **Potential Fuse** | Potential Fuse Link | CT 전압 회로 보호 (2A) | MCCB↔CT 사이, CT↔BI 사이 |
| **ISOLATOR** | Isolator / Disconnect Switch | 단로기 | 계량판 또는 unit isolator |
| **kWh Meter** | Kilowatt-Hour Meter | 전력량계 | Meter board 또는 CT 분기 |

### 극수 (Poles) 약어
| 약어 | 의미 | 용도 |
|------|------|------|
| SP / SPN | Single Pole (+ Neutral) | 1-phase 분기 회로 |
| DP | Double Pole | 1-phase 주 차단기 |
| TP / TPN | Triple Pole (+ Neutral) | 3-phase 주 차단기 |
| 4P | Four Pole | 3-phase ELCB/RCCB |

---

## 5. CT 사양 규칙

### CT Ratio 결정 (SP Group §6.7, §5.3)
- 계량 CT: **CL1 5VA** (정확도 등급) — IEC 61869
- 보호 CT: **5P10 20VA** (또는 15VA) — IEC 61869
- >300A: IDMTL/DTL 계전기 + Class 5P10 필수 (§5.3.1)

### CT Ratio 표준값
| 부하 전류 | CT Ratio |
|----------|----------|
| 100A~150A | 100/5A |
| 150A~200A | 150/5A or 200/5A |
| 200A~300A | 200/5A or 300/5A |
| 300A~400A | 400/5A |
| 400A~600A | 500/5A or 600/5A |

### Ammeter Range 도출
CT ratio의 1차측 값 = Ammeter range 상한
- 100/5A → 0-100A
- 200/5A → 0-200A
- 500/5A → 0-500A

### CT 인클로저 위치 규칙 (§6.9.6)
**"The enclosure shall be located immediately after the incoming circuit breaker(s)."**
→ CT는 MCCB/ACB **바로 뒤** (부하측)에 위치

---

## 6. 보호 장치 규칙

### 주 차단기 선정 (SS 638, CP 5)
| 부하 전류 | 차단기 유형 |
|----------|-----------|
| ≤100A | MCB |
| 125A~630A | MCCB |
| >630A | ACB |

### ELCB/RCCB 감도
| 용도 | 감도 | 극수 |
|------|------|------|
| 1-phase 주거 | 30mA | 2P (DP) |
| 3-phase ≤100A TPN | 30mA | 4P |
| 3-phase >100A | 100mA~300mA | 4P |

### 분기 회로 차단기 (Singapore Standard)
| 부하 유형 | MCB 정격 | Trip Curve | Fault kA | 케이블 |
|----------|---------|------------|----------|--------|
| Lighting | 10A SPN | Type B | 6kA | 1.5sqmm |
| Power/Socket | 20A SPN | Type B | 6kA | 2.5sqmm |
| Aircon | 20A~32A SPN | Type B | 6kA | 2.5~6sqmm |
| Water Heater | 20A~32A SPN | Type B | 6kA | 2.5~6sqmm |

### Short-time Withstand Current (§5.4.1)
- 230kV: 63kA for 1 sec
- 66kV: 40kA/50kA for 3 secs

---

## 7. Meter Board 구성 (§6.4, Appendix 25-27)

### 직접 계량 Meter Board (≤100A)
수평 배치: **[Incoming ISOLATOR] → [kWh METER] → [Outgoing MCB]**
- Incoming/Outgoing MCB 명확히 라벨링 (§6.1.6)
- 1-phase: Appendix 26 참조
- 3-phase: Appendix 27 참조

### CT 계량 Meter Panel (>100A, §6.7)
- Pre-wired metering panel
- 필수: test terminal block, 6A 4P MCB (Type C, 10kA), 3× metering CT
- 전압 케이블: 4mm², 전류 케이블: 6mm² (§6.8.4)
- 원거리 설치 시: 30A HRC fuse + 6A 4P MCB (§6.8.6)

---

## 8. 케이블 규격 (Singapore Standard)

### 분기 케이블 형식
```
2 x 1C {size}sqmm PVC + {earth}sqmm PVC CPC IN METAL TRUNKING
```

### 인커밍 케이블 형식 (대용량)
```
4 x 1C {size}sqmm XLPE/SWA + {earth}sqmm PVC CPC IN CABLE TRAY
```

### 차단기 정격별 케이블 사이즈
| 정격 | 분기 케이블 | 인커밍 (1-phase) | 인커밍 (3-phase) |
|------|-----------|----------------|-----------------|
| 10A | 1.5sqmm | — | — |
| 16A | 2.5sqmm | — | — |
| 20A | 2.5sqmm | — | — |
| 32A | 6sqmm | 6sqmm | 10sqmm |
| 40A | 10sqmm | 10sqmm | 16sqmm |
| 63A | 16sqmm | 16sqmm | 16sqmm |
| 80A | — | 25sqmm | 35sqmm |
| 100A | — | 35sqmm | 50sqmm |
| 125A | — | — | 50sqmm |

---

## 9. kVA 승인 부하 테이블

### 1-phase (230V)
| 차단기 | kVA |
|--------|------|
| 32A | 7.36 |
| 40A | 9.2 |
| 63A | 14.49 |
| 80A | 18.4 |
| 100A | 23 |

### 3-phase (400V)
| 차단기 | kVA |
|--------|------|
| 32A | 22.17 |
| 40A | 27.7 |
| 63A | 43.65 |
| 80A | 55.4 |
| 100A | 69.28 |
| 125A | 86.6 |
| 150A | 103.9 |
| 200A | 138.56 |
| 300A | 207.8 |
| 400A | 277.1 |
| 500A | 346.4 |

---

## 10. 접지 시스템 (§5.6)

| 전압 | 접지 방식 |
|------|---------|
| 230kV | Solidly earthed |
| 66kV | Resistive earthed (NGR 19.5Ω) |
| 22kV | Resistive earthed (NGR 6.5Ω) |
| LT (230V/400V) | TN-S or TN-C-S |

---

## 11. SLD 비교 체크리스트

> **실행 지시는 CLAUDE.md의 "SLD 비교 분석 시 필수 절차" 참조.**
> 아래는 각 섹션의 상세 확인 항목과 코드 위치 레퍼런스.

### 비교 순서 (하단 → 상단, 전원 → 부하 방향)

| # | 섹션 | 확인 항목 | 코드 위치 |
|---|------|----------|----------|
| 1 | **INCOMING SUPPLY** | supply 라벨, AC심볼/케이블+tick, 위상선 (L1 L2 L3 N) | `_place_incoming_supply()` |
| 2 | **INCOMING CABLE** | 케이블 사양 텍스트, tick mark 위치 (좌/우) | `_place_incoming_supply()` 내부 |
| 3 | **METER BOARD** *(sp_meter)* | 점선 박스, ISO→KWH→MCB 수평 배치, 라벨 | `_place_meter_board()` |
| 4 | **UNIT ISOLATOR** *(non-meter)* | 심볼 형태 (enclosed/open), 라벨 위치 (좌/우), 등급 | `_place_unit_isolator()` |
| 5 | **OUTGOING CABLE** | 아이솔레이터→DB 사이 케이블 사양, tick mark | `_place_unit_isolator()` 내부 |
| 6 | **CT PRE-MCCB FUSE** *(ct_meter)* | 2A 퓨즈 + 표시등, 수평 분기 방향 | `_place_ct_pre_mccb_fuse()` |
| 7 | **MAIN BREAKER** | 심볼 종류 (MCB/MCCB), 등급, 극수, 차단용량 | `_place_main_breaker()` |
| 8 | **CT METERING** *(ct_meter)* | CT hook, ELR, ASS/Ammeter, VSS/Voltmeter, kWh, BI CONNECTOR | `_place_ct_metering_section()` |
| 9 | **ELCB/RCCB** | 심볼, 등급, 감도(mA), post-ELCB MCB 유무 | `_place_elcb()` |
| 10 | **INTERNAL CABLE** | 케이블 사양 텍스트 | `_place_internal_cable()` |
| 11 | **MAIN BUSBAR** | 명칭 (BUSBAR/COMB BAR), 등급, DB 정보 박스 | `_place_main_busbar()` |
| 12 | **CIRCUIT BRANCHES** | 심볼 종류 (MCB/ISOLATOR), 위상 그룹 간격, 라벨 | `_place_sub_circuits_rows()` |
| 13 | **DB BOX** | 점선 박스 크기, DB 이름 텍스트 | `_place_db_box()` |
| 14 | **EARTH BAR** | 심볼, 도체 라벨, 연결 위치 | `_place_earth_bar()` |

### 비교 규칙

1. **존재 여부 먼저** — 각 섹션이 렌더링되었는지부터 확인 (없는 것은 비교 불가)
2. **심볼 형태** — 동일 기능이라도 enclosed/open, 원형/사각형 등 시각적 차이 확인
3. **텍스트 내용** — 등급, 명칭, 공백/줄바꿈, 대소문자
4. **라벨 위치** — 좌/우, 상/하 배치 방향
5. **간격·비율** — 섹션 간 간격, 위상 그룹 갭, 전체 DB 폭 비율

### 자동 검증

`test_section_completeness.py`가 6개 입력 조합에서 섹션 존재 여부를 자동 검증한다:
- `LayoutResult.sections_rendered` 필드가 각 섹션의 렌더링 여부를 추적
- 상호 배타 규칙: meter_board ↔ unit_isolator 동시 불가
- 의존 규칙: ct_metering_section → unit_isolator 필수

---

## 변경 이력

| 날짜 | 내용 |
|------|------|
| 2026-03-16 | SLD 비교 체크리스트 추가 (§11): 14개 섹션 순회 검증 절차 |
| 2026-03-13 | 초기 작성: SP Group 핸드북, SS 638, 실제 LEW SLD 레퍼런스 기반 |
