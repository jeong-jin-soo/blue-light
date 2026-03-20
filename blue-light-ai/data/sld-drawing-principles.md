# SLD Drawing Principles — 자동 생성 품질 원칙

SLD(Single Line Diagram) 자동 생성 시 반드시 준수해야 하는 원칙.
코드 변경, 새 섹션 추가, 버그 수정 시 이 원칙에 위배되지 않는지 확인해야 한다.

각 원칙은 **자동 테스트로 검증 가능**하도록 정의되었다.

**참조 표준**: IEC 61082-1:2014, IEC 60617, SS 638:2018, SP Group ELISE 가이드라인

---

## 1. 연결성 (Connectivity)

### C1. 모든 전기 컴포넌트는 선에 연결되어야 한다
> 의도적으로 독립 배치된 장식 요소(LABEL, FLOW_ARROW, CIRCUIT_ID_BOX, DB_INFO_BOX)를
> 제외한 모든 전기 심볼(CB_*, ISOLATOR, CT, KWH_METER, EARTH, BI_CONNECTOR,
> SELECTOR_SWITCH, AMMETER, VOLTMETER, ELR, POTENTIAL_FUSE, INDICATOR_LIGHTS, FUSE,
> BUSBAR)은 최소 하나의 connection endpoint가 해당 심볼의 핀 영역(body edge ± stub)
> 이내에 존재해야 한다.
>
> Ref: IEC 61082-1 §5 — 모든 기능적 심볼은 반드시 연결선으로 결합되어야 한다

**검증 방법**: 각 전기 컴포넌트의 pin 좌표를 계산하고, 모든 connections/fixed_connections에서
해당 좌표 ±tolerance 이내에 endpoint가 있는지 확인.

### C2. 전원에서 부하까지 연속 경로가 존재해야 한다
> Incoming supply에서 각 sub-circuit breaker까지 connections를 따라가면
> 끊어지지 않는 연속 경로가 존재해야 한다.
> 경로는 vertical spine → busbar → sub-circuit tap으로 이어진다.
>
> Ref: IEC 61082-1 — 전력 흐름 경로는 추적 가능해야 한다

**검증 방법**: connection 그래프를 구축하고, spine bottom에서 각 sub-circuit까지
BFS/DFS로 도달 가능한지 확인.

### C3. Connection endpoint는 빈 공간에서 끝나지 않아야 한다 (Dangling Wire 금지)
> 모든 connection의 양쪽 endpoint는 다음 중 하나와 일치해야 한다:
> - 전기 컴포넌트의 pin 위치
> - 다른 connection의 endpoint (T-junction)
> - junction_dot 위치
> - busbar 선 위의 좌표
>
> 예외: cable tick mark (thick_connections), dashed box 선 (dashed_connections)
>
> Ref: CAD DRC 표준 — 단일 노드 네트(dangling wire) 감지는 필수 검증 항목

**검증 방법**: 모든 connection endpoint를 수집하고, 각각이 위 4가지 중 하나와
매칭되는지 확인 (tolerance: 1.0mm).

### C4. 대각선 연결은 의도적이어야 한다
> 수직선(|dx| < 0.5) 또는 수평선(|dy| < 0.5)이 아닌 대각선 connection은
> 의도적인 것이어야 한다 (예: VSS diagonal).
> 의도하지 않은 대각선은 좌표 계산 오류의 증거이다.
>
> 의도적 대각선 목록: VSS diagonal (CT metering), phase fan-out lines
>
> 참고: 심볼 내부 그래픽(예: isolator의 대각선 스트로크)은 connection이 아니므로
> C4 검사 범위에 포함되지 않는다.
>
> Ref: IEC 61082-1 — 배선은 직교(orthogonal) 경로만 사용해야 한다

**검증 방법**: 모든 connections에서 |dx| > 0.5 AND |dy| > 0.5인 것을 수집하고,
알려진 의도적 대각선 패턴에 해당하지 않으면 경고.

### C5. T-junction과 교차점에는 junction dot가 있어야 한다
> 세 개 이상의 connection이 한 점에서 만나는 T-junction에는
> 반드시 junction_dot이 존재해야 한다.
> 교차하지만 연결되지 않는 선은 교차점에 dot이 없어야 한다.
>
> Ref: IEC 61082-1 §6.3.2 — T 접속은 접속점 표시(dot) 유무와 관계없이 연결된 것으로 간주되나,
> 명확성을 위해 dot 표시를 권장한다

**검증 방법**: 3개 이상의 connection endpoint가 같은 좌표(±0.5mm)에 모이는 곳에
junction_dot이 있는지 확인.

---

## 2. 비겹침 (Non-Overlap)

### O1. 전기 심볼의 body는 서로 겹치지 않아야 한다
> 두 전기 컴포넌트의 body bounding box (x, y, x+width, y+height)는
> 겹치지 않아야 한다. Stub 영역은 인접 심볼과 겹칠 수 있다 (설계상 의도).
>
> 예외: CT 심볼은 spine 위에 오버레이됨 (junction_arrow로 표시)
>
> Ref: EPLAN collision check, DXF overlap detection (entity AABB intersection)

**검증 방법**: 모든 전기 컴포넌트 쌍에 대해 AABB 겹침 검사.
최소 clearance: 0.5mm.

### O2. 텍스트 라벨은 전기 심볼과 겹치지 않아야 한다
> LABEL 컴포넌트의 텍스트 영역은 전기 심볼의 body bounding box와
> 겹치지 않아야 한다. 텍스트 너비는 `len(text) × char_width`로 추정.
>
> 예외: 심볼 자체의 라벨 (comp.label)은 심볼 옆에 배치되므로 적용 안 함
>
> Ref: IEC 61082-1 — 참조 지정자(reference designation)는 심볼과 겹치지 않아야 한다

**검증 방법**: LABEL 컴포넌트의 텍스트 BB와 전기 심볼 BB 교차 검사.

### O3. 텍스트 라벨끼리 겹치지 않아야 한다
> 같은 영역의 LABEL 컴포넌트들은 서로의 텍스트 영역과 겹치지 않아야 한다.
>
> 예외: 의도적으로 같은 위치에 배치된 multi-line 라벨 (\P 구분자)
>
> Ref: CAD DRC — 텍스트 가독성 검증 (text-to-text overlap detection)

**검증 방법**: 인접 LABEL 쌍의 텍스트 BB 교차 검사.

### O4. Connection 선은 심볼 body를 관통하지 않아야 한다
> Connection 선의 경로가 전기 심볼의 body bounding box를 통과하면 안 된다.
> 연결 대상 심볼의 pin을 통한 진입은 허용.
>
> 예외: spine backbone 선은 CT 심볼 body를 의도적으로 통과함

**검증 방법**: 각 connection 선분과 비관련 심볼의 body BB 교차 검사.

### O5. DB box 내의 모든 요소는 box 경계 안에 있어야 한다
> Main breaker, ELCB, busbar, sub-circuits 등 DB 내부에 속하는 모든
> 컴포넌트의 body는 dashed DB box 경계 안에 있어야 한다.
>
> 예외: cable tick marks, leader lines, earth bar (box 외부)
>
> Ref: IEC 61082-1 — dash-dotted 박스는 물리적 인클로저 경계를 나타낸다

**검증 방법**: DB box 경계와 내부 컴포넌트의 BB 포함 관계 검사.

---

## 3. 배치 순서 (Flow Order)

### F1. 스파인 컴포넌트는 전원→부하 순서를 따라야 한다
> 수직 스파인 위의 전기 컴포넌트 Y좌표는 전원(하단)→부하(상단) 방향으로
> 증가해야 한다. 즉 supply 측 컴포넌트가 load 측 컴포넌트보다 작은 Y 값을 가진다.
>
> Direct Metering: MeterBoard → Isolator → MainBreaker → ELCB → Busbar
> CT Metering: Isolator → MCCB → ProtCT → MeteringCT → BI → ELCB → Busbar
>
> Ref: CT_METERING_SPINE_ORDER, sg-sld-domain-knowledge.md §3
> Ref: IEC 61082-1 — 신호/전력 흐름 방향은 일관되어야 한다 (싱가포르: bottom-to-top)

**검증 방법**: spine 컴포넌트를 Y순 정렬하고 기대 순서와 비교.
기존 테스트: `test_spine_flow_order.py`

### F2. 수평 브랜치는 스파인에서 분기해야 한다
> ELR, ASS, KWH, VSS, Potential Fuse 등 수평 브랜치의 arm connection은
> spine X 좌표에서 시작해야 한다. arm이 spine에 연결되지 않으면 안 된다.
>
> 예외: VSS diagonal은 instrument fuse 브랜치의 중간점에서 시작
>
> Ref: IEC 61082-1 — 분기 연결은 주선(main line)에서 직교로 분기해야 한다

**검증 방법**: junction_arrows/junction_dots의 X좌표가 spine_x ±1mm 이내인지 확인.

### F3. Sub-circuits는 busbar에서 상방으로 분기해야 한다
> 모든 sub-circuit breaker의 busbar tap은 busbar Y 좌표에 위치하고,
> breaker는 tap 위쪽(Y 증가 방향)에 배치되어야 한다.

**검증 방법**: sub-circuit breaker(label_style="breaker_block")의 y > busbar_y 확인.

### F4. CT metering 브랜치의 좌/우 배치 규약
> CT metering 섹션에서 수평 브랜치의 좌/우 배치는 다음 규약을 따른다:
> - **좌측**: ELR, ASS(Ammeter Selector Switch), Ammeter
> - **우측**: VSS(Voltmeter Selector Switch), Voltmeter, kWh Meter, BI Connector
> - **좌측 (하단)**: Potential Fuse + Indicator Lights
>
> 참고: 이것은 LEW 도면의 de facto 표준(일반적 관례)이며, SP Group의 강제 규정은 아니다.
> 개별 LEW가 좌우를 반대로 배치할 수 있으나, 본 시스템은 레퍼런스 SLD 관례를 따른다.

**검증 방법**: CT 브랜치 컴포넌트의 X좌표가 spine_x 기준으로 올바른 방향에 있는지 확인.

---

## 4. 완전성 (Completeness)

### P1. 필수 섹션은 항상 렌더링되어야 한다
> 입력 조건에 관계없이 다음 섹션은 항상 존재해야 한다:
> - main_breaker
> - main_busbar
> - sub_circuits (최소 1개)
> - db_box
> - earth_bar
>
> Ref: `test_section_completeness.py`
> Ref: EMA ELISE — 필수 도면 구성요소 (incoming, protection, distribution, earthing)

**검증 방법**: `result.sections_rendered`에서 필수 키 확인.

### P2. 조건부 섹션은 조건에 맞게 렌더링되어야 한다
> | 조건 | 렌더링 섹션 |
> |------|-----------|
> | metering=sp_meter | meter_board |
> | metering=ct_meter | unit_isolator, ct_pre_mccb_fuse, ct_metering_section |
> | supply_source=landlord | incoming_supply, unit_isolator |
> | elcb_rating > 0 | elcb |

**검증 방법**: 입력 조합별 sections_rendered 검사.
기존 테스트: `test_section_completeness.py`

### P3. Meter board 섹션과 unit isolator 섹션은 동시에 렌더링되지 않는다
> sp_meter 시 meter board 내부에 이미 isolator가 포함(수평 배열)되어 있으므로
> 별도 unit_isolator 섹션과 중복 배치되지 않는다.
>
> - `sp_meter` + `sp_powergrid` → meter_board 섹션 (내부에 isolator 포함)
> - `ct_meter` → unit_isolator 섹션 (별도 배치, meter board 없음)
> - `landlord` + no metering → unit_isolator 섹션만
>
> 예외: CT metering 시 engine이 metering flag를 일시 해제하여
> unit_isolator를 별도 배치 (meter board 없이)

### P4. 모든 sub-circuit에는 circuit ID가 할당되어야 한다
> SPARE를 포함한 모든 sub-circuit에 고유한 circuit_id가 부여되어야 한다.
> 1-phase: S1, S2, P1, P2, ...
> 3-phase interleaved: L1S1, L2S1, L3S1, ...

### P5. 모든 컴포넌트에는 참조 지정자와 정격 정보가 있어야 한다
> 전기 심볼에는 다음이 표시되어야 한다:
> - **참조 지정자** (reference designation): 고유 식별 명칭 (예: MCCB, ELCB, MCB-1)
> - **정격 정보** (rating): 전류, 극수, 차단용량 등 (예: 63A TPN 10kA)
>
> Ref: IEC 81346 — 참조 지정자 체계 (Q=차단기, F=퓨즈, W=케이블)
> Ref: EMA ELISE — 모든 보호 장치에 type, poles, trip rating, fault rating 표시 필수

**검증 방법**: 전기 컴포넌트의 label 또는 인접 LABEL에 정격 문자열이 있는지 확인.

### P6. 케이블 사양은 완전하게 표시되어야 한다
> 케이블 라벨에는 다음 정보가 포함되어야 한다:
> - 심수 × 종류 × 단면적 (예: 4 x 1C 16sqmm)
> - 절연/외피 (예: XLPE/SWA, PVC/PVC, XLPE/PVC)
> - 보호도체 (예: + 10sqmm PVC CPC)
> - 포설 방법 (예: IN CABLE TRAY, IN METAL TRUNKING, IN CONDUIT)
>
> 일반 형식: `{count} x {cores}C {size}sqmm {type} + {cpc}sqmm {cpc_type} CPC IN {method}`
>
> 싱가포르에서 사용되는 케이블 유형:
> - **PVC/PVC**: 소규모 주거용 (주로 METAL TRUNKING)
> - **XLPE/PVC**: 중규모 상업용 (CABLE TRAY 또는 METAL TRUNKING)
> - **XLPE/SWA**: 대규모 산업용 또는 직매/노출 배선 (CABLE TRAY)
> - **PVC IN CONDUIT**: 구형 설비
>
> Ref: SS 638 — 케이블 사양 표기 규정
> Ref: `models.py:format_cable_spec()` — 입력 dict에서 자동 생성

**검증 방법**: 케이블 라벨 문자열이 정규식 패턴에 매칭되는지 확인.

---

## 5. 간격 (Spacing)

### S1. Sub-circuit 간 최소 간격은 8mm이다
> 인접한 sub-circuit breaker 중심 간 거리가 8mm 미만이면
> 라벨이 겹쳐 인쇄 시 판독 불가능하다.
> 8mm 미만일 경우 multi-row 분할을 적용해야 한다.
>
> 참고: A3 landscape (420×297mm) NTS 기준. 실제 LEW SLD 샘플에서의 회로 간격은 8~12mm.

**검증 방법**: _detect_overflow()의 actual_min_spacing 확인.

### S2. 스파인 섹션 간 최소 간격이 존재해야 한다
> 인접한 스파인 컴포넌트의 body 사이에 최소 1mm의 간격이 있어야 한다.
> 간격이 없으면 두 심볼이 시각적으로 하나로 합쳐진다.
>
> stub 영역의 겹침은 허용 (설계상 인접 심볼의 stub이 만나는 것이 정상).
>
> Ref: IEC 61082-1 — 심볼 간 최소 clearance는 1~2 그리드 유닛

**검증 방법**: spine 컴포넌트를 Y순 정렬 후 인접 쌍의 body 간 거리 계산.

### S3. CT metering 브랜치 간 최소 clearance는 2mm이다
> 같은 방향(left/right)의 수평 브랜치 bounding box 사이에
> 최소 2mm 간격이 필요하다.

### S4. 텍스트는 인쇄 시 판독 가능한 크기여야 한다
> 모든 텍스트 라벨의 높이는 인쇄 스케일에서 최소 2.5mm (IEC) 또는 1/8" (ANSI)여야 한다.
> DXF 출력에서 text height 값으로 검증한다.
>
> Ref: IEC 61082-1 — 최소 텍스트 높이 규정

**검증 방법**: 모든 LABEL 컴포넌트 및 comp.label의 text_height ≥ 최소값 확인.

---

## 6. 경계 (Boundary)

### B1. 모든 요소는 도면 영역 내에 있어야 한다
> 모든 컴포넌트, connection, 텍스트는 도면 경계 (min_x, min_y, max_x, max_y)
> 안에 있어야 한다. 경계를 벗어나면 인쇄/PDF에서 잘린다.
>
> 허용 초과: 2mm (인쇄 여유)

**검증 방법**: _detect_overflow()의 overflow 값 확인.

### B2. Multi-DB에서 각 DB는 할당된 영역 내에 있어야 한다
> Multi-DB SLD에서 각 distribution board의 컴포넌트는
> 해당 board에 할당된 LayoutRegion(min_x, max_x) 안에 있어야 한다.
> 다른 DB의 영역을 침범하면 안 된다.

### B3. Earth bar는 도면 경계를 넘지 않아야 한다
> Earth bar 심볼 + conductor 라벨의 오른쪽 끝이 max_x를 넘으면
> 왼쪽으로 이동시켜야 한다.
>
> 싱가포르 접지 규정:
> - LT (230V/400V) 설비: TN-S 또는 TN-C-S 접지 시스템
> - Earth conductor 라벨 형식: `1 x {size}sqmm CU/GRN-YEL` (구리, 녹황색 줄무늬)
> - Earth conductor 사이징: SS 638 테이블 기준 (phase conductor 대비 비율)
> - Earth bar는 항상 DB box 외부에 배치, busbar에서 도체로 연결
>
> Ref: SS 638 — 접지 도체 사이징 및 표기
> Ref: `get_earth_conductor_size()` — phase conductor 크기에서 자동 산출

---

## 7. 싱가포르 규정 준수 (Compliance)

### SG1. kVA/breaker rating 매핑은 SP Group 기준을 따른다
> kVA → breaker rating 변환은 KVA_TO_BREAKER_MAP 테이블 기준.
> Ref: `sld_spec.py`, SP Group §1.2

### SG2. CT metering은 3-phase >100A에서 필수이다
> 직접 계측(direct metering)의 상한은 100A per phase이다.
> 따라서 3-phase 설비에서 breaker rating > 100A이면 CT metering이 자동 적용된다.
> 실질적으로 다음 표준 등급인 **125A부터** CT metering 필수.
>
> | Breaker Rating | Metering |
> |---------------|----------|
> | ≤ 100A TPN | Direct metering (sp_meter) |
> | ≥ 125A TPN | CT metering (ct_meter) |
>
> Ref: SP Group §6.7 — 직접 계측 용량 상한 100A
> Ref: `sld_spec.py` — `INCOMING_SPEC_3PHASE[100].requires_ct = False`, `[125].requires_ct = True`

### SG3. ELCB/RCCB sensitivity는 위상과 정격에 따라 결정된다
> ELCB/RCCB 감도(sensitivity)와 극수(poles) 규칙:
>
> | 조건 | 감도 | 극수 |
> |------|------|------|
> | 1-phase (모든 정격) | 30mA | DP (2-pole) |
> | 3-phase ≤ 100A | 30mA | 4P |
> | 3-phase > 100A | 100~300mA | 4P |
>
> Ref: SS 638, EMA July 2023 (30mA RCCB 주거용 의무화)
> Ref: `sld_spec.py:_validate_elcb()`

### SG4. Sub-circuit breaker rating은 main breaker를 초과하지 않는다
> Sub-circuit의 breaker rating > main breaker rating이면 하드 에러.

### SG5. 극수(Poles)는 용도와 위상에 맞아야 한다
> 극수 규칙은 컴포넌트 종류에 따라 다르다:
>
> | 컴포넌트 | 1-phase | 3-phase |
> |---------|---------|---------|
> | **Main breaker** (MCCB/MCB) | DP (2-pole) | TPN (3-pole+N) |
> | **Sub-circuit breaker** (MCB) | SPN (1-pole+N) | TPN (3-pole+N) |
> | **ELCB/RCCB** | DP (2-pole) | 4P (4-pole) |
> | **ACB** (≥800A) | — | 4P (4-pole) |
>
> 위상-극수 불일치 시 자동 교정.
>
> Ref: SS 638 — 극수 요구사항
> Ref: `sld_spec.py:_validate_poles()`

### SG6. CT metering 인클로저는 incoming breaker 직후에 위치해야 한다
> CT metering panel은 main circuit breaker(MCCB) 바로 다음에 배치되어야 한다.
> Ref: SS 638 / SP Group §6.9.6 — CT metering enclosure는 incoming circuit breaker(s) 직후

### SG7. Direct metering (≤100A)은 Meter Board 형식을 따른다
> Direct metering 시 Meter Board 내부 배치 순서:
> ISOLATOR → kWh METER → MCB (수평 배열, 점선 박스)
>
> MCB는 SP Group §6.1.6에 따라 "Outgoing" MCB로 명확히 라벨링해야 한다.
> 1-phase 레이아웃: SP Group Appendix 26 참조.
> 3-phase 레이아웃: SP Group Appendix 27 참조.
>
> Ref: SP Group 직접 계측 가이드라인

### SG8. Title block에 필수 정보가 포함되어야 한다
> SLD 도면의 title block에는 다음 정보가 포함되어야 한다:
>
> **EMA ELISE 필수 항목:**
> - 설치 주소 (full installation address)
> - 테넌트 유닛 이름
> - LEW 이름 + EMA 면허 번호 + 연락처
> - Main contractor 이름
> - Electrical contractor 이름
> - Drawing title (도면 제목)
> - Drawing number (도면 번호)
> - Revision number (개정 번호)
> - Date (날짜)
> - Scale (축척, 일반적으로 NTS)
> - Sheet number (시트 번호)
> - Checked by (검토자)
>
> Ref: EMA ELISE 가이드라인 — title block 필수 항목
> Ref: `locale.py:TitleBlockLabels` — 구현된 전체 필드 목록

### SG9. Incoming supply 라벨은 공급 유형에 맞아야 한다
> 싱가포르 SLD에서 incoming supply 라벨은 공급 유형(supply_source)에 따라
> 정해진 표준 문구를 사용해야 한다:
>
> | supply_source | 라벨 |
> |--------------|------|
> | sp_powergrid (HDB) | `INCOMING FROM HDB ELECTRICAL RISER` |
> | sp_powergrid (상업) | `SUPPLY FROM BUILDING RISER` |
> | landlord (riser) | `FROM LANDLORD RISER` |
> | landlord (supply) | `FROM LANDLORD SUPPLY` |
> | cable_extension | `FROM POWER SUPPLY ON SITE` |
>
> 비표준 라벨 사용 시 SP Group/EMA 제출 시 반려 사유가 될 수 있다.
>
> Ref: `locale.py` — 공급 유형별 라벨 정의
> Ref: SP Group "How to Apply for Electricity Connection" handbook

---

## 8. 좌표 정확성 (Coordinate Accuracy)

### A1. 심볼 치수는 real_symbols에서 조회해야 한다
> 컴포넌트의 width, height, stub 값은 반드시 `get_real_symbol(name)`에서
> 조회해야 한다. LayoutConfig의 값이나 하드코딩된 상수를 직접 사용하지 않는다.
>
> **이 원칙이 없으면**: real_symbol_paths.json이 업데이트될 때 좌표 불일치 발생.
>
> Ref: IEC 61082-1 — 심볼 크기는 module 값(M)에서 파생되어야 한다

### A2. Connection endpoint는 심볼의 pin 좌표와 일치해야 한다
> 심볼에 연결되는 connection의 endpoint는 해당 심볼의
> `vertical_pins()` 또는 `horizontal_pins()`에서 반환되는 좌표와 일치해야 한다.
>
> **이 원칙이 없으면**: 심볼과 선 사이에 미세한 갭이 생기거나,
> 후처리가 이를 교정하려다 다른 연결을 깨뜨림 (validate_connectivity 문제의 근인).
>
> Ref: IEC 61082-1 — 연결선은 심볼의 접속점(connection point)에서 정확히 시작/종료해야 한다

### A3. 후처리는 기존 좌표를 변경하지 않아야 한다 (추가만 허용)
> resolve_overlaps, _add_phase_fanout 등 후처리 단계는
> sub-circuit 영역의 좌표만 변경할 수 있다.
> spine, branches, CT metering 등 상위 섹션의 connection 좌표는
> 후처리에서 변경하지 않는다.
>
> 허용: sub-circuit 컴포넌트 X좌표 재배치, 새 요소 추가
> 금지: spine connection endpoint 변경, branch connection 이동
>
> **이 원칙이 없으면**: 한 섹션에서 정확히 계산한 좌표를 다른 단계가 깨뜨림.
> 실제 사례: validate_connectivity가 VSS 대각선을 4.25mm 이동시킴.

### A4. 센터링(centering)은 모든 요소에 동일한 offset을 적용해야 한다
> `_center_vertically()`는 모든 connection 유형 (connections, thick_connections,
> dashed_connections, fixed_connections)과 모든 컴포넌트에 동일한 Y shift를
> 적용해야 한다. 하나라도 빠지면 요소가 분리된다.

### A5. 그리드 정렬: 스파인 X좌표는 일정해야 한다
> 스파인 위의 모든 컴포넌트는 동일한 X 중심선(cx)에 정렬되어야 한다.
> 스파인의 수직 connection은 모두 같은 X좌표를 가져야 한다.
>
> **이 원칙이 없으면**: 컴포넌트가 좌우로 미세하게 어긋나 연결선에 의도하지 않은 대각선 발생.
>
> Ref: IEC 61082-1 — 그리드 시스템 기반 심볼 배치

**검증 방법**: spine 컴포넌트의 center_x와 spine connection의 x좌표가 모두 cx ±0.5mm 이내인지 확인.

---

## 원칙 요약 매트릭스

| 카테고리 | ID | 핵심 규칙 | 근거 표준 | 자동화 |
|---------|-----|---------|---------|-------|
| 연결성 | C1 | 모든 전기 심볼은 선에 연결 | IEC 61082-1 | ✅ 가능 |
| 연결성 | C2 | 전원→부하 연속 경로 | IEC 61082-1 | ✅ 가능 |
| 연결성 | C3 | Dangling wire 금지 | CAD DRC | ✅ 가능 |
| 연결성 | C4 | 의도적 대각선만 허용 | IEC 61082-1 | ✅ 가능 |
| 연결성 | C5 | T-junction dot 필수 | IEC 61082-1 | ✅ 가능 |
| 비겹침 | O1 | 심볼 body 비겹침 | CAD DRC | ✅ 가능 |
| 비겹침 | O2 | 텍스트-심볼 비겹침 | IEC 61082-1 | ✅ 가능 |
| 비겹침 | O3 | 텍스트-텍스트 비겹침 | CAD DRC | ✅ 가능 |
| 비겹침 | O4 | 선이 심볼 관통 금지 | CAD DRC | ✅ 가능 |
| 비겹침 | O5 | DB box 내부 포함 검사 | IEC 61082-1 | ✅ 가능 |
| 배치순서 | F1 | 전원→부하 Y순서 | SP Group/IEC | ✅ 구현됨 |
| 배치순서 | F2 | 브랜치는 spine에서 분기 | IEC 61082-1 | ✅ 가능 |
| 배치순서 | F3 | Sub-circuit는 busbar 상방 | 도면 관례 | ✅ 가능 |
| 배치순서 | F4 | CT 브랜치 좌/우 규약 | LEW de facto 관례 | ✅ 가능 |
| 완전성 | P1 | 필수 섹션 존재 | EMA ELISE | ✅ 구현됨 |
| 완전성 | P2 | 조건부 섹션 정확 매칭 | EMA ELISE | ✅ 구현됨 |
| 완전성 | P3 | MeterBoard/Isolator 중복 금지 | 설계 규칙 | ✅ 가능 |
| 완전성 | P4 | Circuit ID 할당 | 도면 관례 | ✅ 가능 |
| 완전성 | P5 | 참조 지정자 + 정격 정보 | IEC 81346/EMA | ⬜ 미구현 |
| 완전성 | P6 | 케이블 사양 완전성 | SS 638 | ⬜ 미구현 |
| 간격 | S1 | Sub-circuit 최소 8mm | A3 NTS 가독성 | ✅ 구현됨 |
| 간격 | S2 | 스파인 섹션 간 최소 1mm | IEC 61082-1 | ✅ 가능 |
| 간격 | S3 | CT 브랜치 간 최소 2mm | 가독성 | ✅ 가능 |
| 간격 | S4 | 텍스트 최소 크기 2.5mm | IEC 61082-1 | ⬜ 미구현 |
| 경계 | B1 | 도면 영역 내 배치 | 인쇄 규격 | ✅ 구현됨 |
| 경계 | B2 | Multi-DB 영역 격리 | 설계 규칙 | ✅ 가능 |
| 경계 | B3 | Earth bar 경계 + SG 접지 규정 | SS 638 | ✅ 가능 |
| 규정 | SG1 | kVA→breaker 매핑 | SP Group | ✅ 구현됨 |
| 규정 | SG2 | CT metering >100A (≥125A) | SP Group §6.7 | ✅ 구현됨 |
| 규정 | SG3 | ELCB 위상/정격별 감도 | SS 638/EMA | ✅ 구현됨 |
| 규정 | SG4 | Sub-circuit ≤ main rating | 전기 규정 | ✅ 구현됨 |
| 규정 | SG5 | 용도별 극수 규칙 | SS 638 | ✅ 구현됨 |
| 규정 | SG6 | CT 인클로저 위치 | SP Group §6.9.6 | ✅ 구현됨 |
| 규정 | SG7 | Meter Board 배치 + 라벨 | SP Group §6.1.6 | ✅ 구현됨 |
| 규정 | SG8 | Title block 12개 필드 | EMA ELISE | ⬜ 미구현 |
| 규정 | SG9 | Incoming supply 라벨 선택 | SP Group | ✅ 구현됨 |
| 좌표 | A1 | 치수는 real_symbols에서 | IEC 61082-1 | 코드 리뷰 |
| 좌표 | A2 | Endpoint = pin 좌표 | IEC 61082-1 | ✅ 가능 |
| 좌표 | A3 | 후처리 좌표 변경 금지 | 실제 사례 | 코드 리뷰 |
| 좌표 | A4 | 센터링 동일 offset | 설계 규칙 | ✅ 가능 |
| 좌표 | A5 | 스파인 X 정렬 | IEC 61082-1 | ✅ 가능 |

---

## 원칙 적용 가이드

### 새 섹션을 추가할 때
1. **C1, C3** 확인: 모든 컴포넌트가 connection에 연결되었는가?
2. **C5** 확인: T-junction에 junction_dot가 있는가?
3. **O1, O2** 확인: 기존 컴포넌트/라벨과 겹치지 않는가?
4. **F1** 확인: spine 순서가 전기적 흐름을 따르는가?
5. **A1, A2** 확인: 심볼 치수를 real_symbols에서 조회했는가?
6. **P2** 확인: section_registry에 새 섹션이 등록되었는가?
7. **P5** 확인: 참조 지정자와 정격 정보가 라벨에 포함되었는가?
8. **SG9** 확인: incoming supply 라벨이 공급 유형에 맞는가?
9. **test_section_completeness.py** 업데이트

### 좌표 관련 버그를 수정할 때
1. **A3** 확인: 후처리가 원인인가? → 후처리가 아닌 원본 섹션 수정
2. **A2** 확인: pin 좌표를 real_symbols에서 조회하고 있는가?
3. **A5** 확인: 스파인 X좌표가 일관된가?
4. **C4** 확인: 의도하지 않은 대각선이 생기지 않았는가?

### 새 SLD 타입을 추가할 때
1. **section_registry.py**에 새 시퀀스 함수 추가
2. **F1** 확인: 전기적 흐름 순서가 맞는가?
3. **P1** 확인: 필수 섹션이 포함되었는가?
4. **sg-sld-domain-knowledge.md**에 흐름도 추가
5. **test_section_completeness.py**에 새 조합 추가
6. **SG2, SG6~SG7** 확인: metering 방식별 싱가포르 규정 반영되었는가?
7. **SG9** 확인: incoming supply 라벨이 올바른가?

### 도면 품질 검수 체크리스트
1. 연결 경로 추적: C1 → C2 → C3 (전체 연결 무결성)
2. 시각 품질: O1 → O2 → O3 → S4 (겹침 없음 + 가독성)
3. 전기적 정확성: F1 → SG1~SG9 (흐름 순서 + 규정 준수)
4. 정보 완전성: P5 → P6 → SG8 → SG9 (정격 + 케이블 + title block + 라벨)
