# SLD Drawing Principles — 자동 생성 품질 원칙

SLD(Single Line Diagram) 자동 생성 시 반드시 준수해야 하는 원칙.
코드 변경, 새 섹션 추가, 버그 수정 시 이 원칙에 위배되지 않는지 확인해야 한다.

각 원칙은 **자동 테스트로 검증 가능**하도록 정의되었다.

---

## 1. 연결성 (Connectivity)

### C1. 모든 전기 컴포넌트는 선에 연결되어야 한다
> 의도적으로 독립 배치된 장식 요소(LABEL, FLOW_ARROW, CIRCUIT_ID_BOX, DB_INFO_BOX)를
> 제외한 모든 전기 심볼(CB_*, ISOLATOR, CT, KWH_METER, EARTH, BI_CONNECTOR,
> SELECTOR_SWITCH, AMMETER, VOLTMETER, ELR, POTENTIAL_FUSE, INDICATOR_LIGHTS, FUSE,
> BUSBAR)은 최소 하나의 connection endpoint가 해당 심볼의 핀 영역(body edge ± stub)
> 이내에 존재해야 한다.

**검증 방법**: 각 전기 컴포넌트의 pin 좌표를 계산하고, 모든 connections/fixed_connections에서
해당 좌표 ±tolerance 이내에 endpoint가 있는지 확인.

### C2. 전원에서 부하까지 연속 경로가 존재해야 한다
> Incoming supply에서 각 sub-circuit breaker까지 connections를 따라가면
> 끊어지지 않는 연속 경로가 존재해야 한다.
> 경로는 vertical spine → busbar → sub-circuit tap으로 이어진다.

**검증 방법**: connection 그래프를 구축하고, spine bottom에서 각 sub-circuit까지
BFS/DFS로 도달 가능한지 확인.

### C3. Connection endpoint는 빈 공간에서 끝나지 않아야 한다
> 모든 connection의 양쪽 endpoint는 다음 중 하나와 일치해야 한다:
> - 전기 컴포넌트의 pin 위치
> - 다른 connection의 endpoint (T-junction)
> - junction_dot 위치
> - busbar 선 위의 좌표
>
> 예외: cable tick mark (thick_connections), dashed box 선 (dashed_connections)

**검증 방법**: 모든 connection endpoint를 수집하고, 각각이 위 4가지 중 하나와
매칭되는지 확인 (tolerance: 1.0mm).

### C4. 대각선 연결은 의도적이어야 한다
> 수직선(|dx| < 0.5) 또는 수평선(|dy| < 0.5)이 아닌 대각선 connection은
> 의도적인 것이어야 한다 (예: VSS diagonal).
> 의도하지 않은 대각선은 좌표 계산 오류의 증거이다.
>
> 의도적 대각선 목록: VSS diagonal (CT metering), phase fan-out lines

**검증 방법**: 모든 connections에서 |dx| > 0.5 AND |dy| > 0.5인 것을 수집하고,
알려진 의도적 대각선 패턴에 해당하지 않으면 경고.

---

## 2. 비겹침 (Non-Overlap)

### O1. 전기 심볼의 body는 서로 겹치지 않아야 한다
> 두 전기 컴포넌트의 body bounding box (x, y, x+width, y+height)는
> 겹치지 않아야 한다. Stub 영역은 인접 심볼과 겹칠 수 있다 (설계상 의도).
>
> 예외: CT 심볼은 spine 위에 오버레이됨 (junction_arrow로 표시)

**검증 방법**: 모든 전기 컴포넌트 쌍에 대해 AABB 겹침 검사.
최소 clearance: 0.5mm.

### O2. 텍스트 라벨은 전기 심볼과 겹치지 않아야 한다
> LABEL 컴포넌트의 텍스트 영역은 전기 심볼의 body bounding box와
> 겹치지 않아야 한다. 텍스트 너비는 `len(text) × char_width`로 추정.
>
> 예외: 심볼 자체의 라벨 (comp.label)은 심볼 옆에 배치되므로 적용 안 함

**검증 방법**: LABEL 컴포넌트의 텍스트 BB와 전기 심볼 BB 교차 검사.

### O3. 텍스트 라벨끼리 겹치지 않아야 한다
> 같은 영역의 LABEL 컴포넌트들은 서로의 텍스트 영역과 겹치지 않아야 한다.
>
> 예외: 의도적으로 같은 위치에 배치된 multi-line 라벨 (\P 구분자)

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

**검증 방법**: spine 컴포넌트를 Y순 정렬하고 기대 순서와 비교.
기존 테스트: `test_spine_flow_order.py`

### F2. 수평 브랜치는 스파인에서 분기해야 한다
> ELR, ASS, KWH, VSS, Potential Fuse 등 수평 브랜치의 arm connection은
> spine X 좌표에서 시작해야 한다. arm이 spine에 연결되지 않으면 안 된다.
>
> 예외: VSS diagonal은 instrument fuse 브랜치의 중간점에서 시작

**검증 방법**: junction_arrows/junction_dots의 X좌표가 spine_x ±1mm 이내인지 확인.

### F3. Sub-circuits는 busbar에서 상방으로 분기해야 한다
> 모든 sub-circuit breaker의 busbar tap은 busbar Y 좌표에 위치하고,
> breaker는 tap 위쪽(Y 증가 방향)에 배치되어야 한다.

**검증 방법**: sub-circuit breaker(label_style="breaker_block")의 y > busbar_y 확인.

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

### P3. Meter board와 unit isolator는 동시에 렌더링되지 않는다
> Meter board 내부에 이미 isolator가 포함되어 있으므로 중복 배치 금지.
>
> 예외: CT metering 시 engine이 metering flag를 일시 해제하여
> unit_isolator를 별도 배치 (meter board 없이)

### P4. 모든 sub-circuit에는 circuit ID가 할당되어야 한다
> SPARE를 포함한 모든 sub-circuit에 고유한 circuit_id가 부여되어야 한다.
> 1-phase: S1, S2, P1, P2, ...
> 3-phase interleaved: L1S1, L2S1, L3S1, ...

---

## 5. 간격 (Spacing)

### S1. Sub-circuit 간 최소 간격은 8mm이다
> 인접한 sub-circuit breaker 중심 간 거리가 8mm 미만이면
> 라벨이 겹쳐 인쇄 시 판독 불가능하다.
> 8mm 미만일 경우 multi-row 분할을 적용해야 한다.

**검증 방법**: _detect_overflow()의 actual_min_spacing 확인.

### S2. 스파인 섹션 간 최소 간격이 존재해야 한다
> 인접한 스파인 컴포넌트의 body 사이에 최소 1mm의 간격이 있어야 한다.
> 간격이 없으면 두 심볼이 시각적으로 하나로 합쳐진다.
>
> stub 영역의 겹침은 허용 (설계상 인접 심볼의 stub이 만나는 것이 정상).

**검증 방법**: spine 컴포넌트를 Y순 정렬 후 인접 쌍의 body 간 거리 계산.

### S3. CT metering 브랜치 간 최소 clearance는 2mm이다
> 같은 방향(left/right)의 수평 브랜치 bounding box 사이에
> 최소 2mm 간격이 필요하다.

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

### B3. Earth bar와 라벨은 오른쪽 경계를 넘지 않아야 한다
> Earth bar 심볼 + conductor 라벨의 오른쪽 끝이 max_x를 넘으면
> 왼쪽으로 이동시켜야 한다.

---

## 7. 싱가포르 규정 준수 (Compliance)

### SG1. kVA/breaker rating 매핑은 SP Group 기준을 따른다
> kVA → breaker rating 변환은 KVA_TO_BREAKER_MAP 테이블 기준.
> Ref: `sld_spec.py`, SP Group §1.2

### SG2. CT metering은 3-phase ≥125A에서 필수이다
> 3-phase 설비에서 breaker rating ≥ 125A이면 ct_meter가 자동 적용.
> Ref: SP Group §6.7

### SG3. ELCB/RCCB sensitivity는 용도별 기준을 따른다
> 소켓 회로: 30mA. 분전 회로 (>100A): 100~300mA.
> Ref: SS 638, `sld_spec.py`

### SG4. Sub-circuit breaker rating은 main breaker를 초과하지 않는다
> Sub-circuit의 breaker rating > main breaker rating이면 하드 에러.

### SG5. 극수(Poles)는 위상에 맞아야 한다
> 1-phase: SPN 또는 DP. 3-phase: TPN 또는 4P.
> 불일치 시 자동 교정.

---

## 8. 좌표 정확성 (Coordinate Accuracy)

### A1. 심볼 치수는 real_symbols에서 조회해야 한다
> 컴포넌트의 width, height, stub 값은 반드시 `get_real_symbol(name)`에서
> 조회해야 한다. LayoutConfig의 값이나 하드코딩된 상수를 직접 사용하지 않는다.
>
> **이 원칙이 없으면**: real_symbol_paths.json이 업데이트될 때 좌표 불일치 발생.

### A2. Connection endpoint는 심볼의 pin 좌표와 일치해야 한다
> 심볼에 연결되는 connection의 endpoint는 해당 심볼의
> `vertical_pins()` 또는 `horizontal_pins()`에서 반환되는 좌표와 일치해야 한다.
>
> **이 원칙이 없으면**: 심볼과 선 사이에 미세한 갭이 생기거나,
> 후처리가 이를 교정하려다 다른 연결을 깨뜨림 (validate_connectivity 문제의 근인).

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

---

## 원칙 적용 가이드

### 새 섹션을 추가할 때
1. **C1, C3** 확인: 모든 컴포넌트가 connection에 연결되었는가?
2. **O1, O2** 확인: 기존 컴포넌트/라벨과 겹치지 않는가?
3. **F1** 확인: spine 순서가 전기적 흐름을 따르는가?
4. **A1, A2** 확인: 심볼 치수를 real_symbols에서 조회했는가?
5. **P2** 확인: section_registry에 새 섹션이 등록되었는가?
6. **test_section_completeness.py** 업데이트

### 좌표 관련 버그를 수정할 때
1. **A3** 확인: 후처리가 원인인가? 그렇다면 후처리를 수정하지 말고 원본 섹션을 수정
2. **A2** 확인: pin 좌표를 real_symbols에서 조회하고 있는가?
3. **C4** 확인: 의도하지 않은 대각선이 생기지 않았는가?

### 새 SLD 타입을 추가할 때
1. **section_registry.py**에 새 시퀀스 함수 추가
2. **F1** 확인: 전기적 흐름 순서가 맞는가?
3. **P1** 확인: 필수 섹션이 포함되었는가?
4. **sg-sld-domain-knowledge.md**에 흐름도 추가
5. **test_section_completeness.py**에 새 조합 추가
