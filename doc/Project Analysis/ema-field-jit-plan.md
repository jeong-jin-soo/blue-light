# EMA ELISE 필드 정합 — JIT 기반 수집 계획

**문서 종류**: Product Specification (Refinement / Strategy)
**대상 기능**: Installation Licence 신청(New) 및 갱신(Renewal) 폼
**작성일**: 2026-04-22
**상태**: 제품 사인오프 대기
**참조**:
- 현재 폼 `blue-light-frontend/src/pages/applicant/NewApplicationPage.tsx`
- Step: `steps/BeforeYouBeginGuide.tsx`, `steps/StepReview.tsx`
- 엔티티: `blue-light-backend/src/main/java/com/bluelight/backend/domain/application/Application.java`
- 유사 JIT 스펙: `doc/Project Analysis/service-orders-refinement-spec.md`

---

## 1. 원칙 재확인

1. **단순성**: 신청자는 "현장 주소 + 용량 + 결제" 3가지 실질 정보만으로 신청을 시작할 수 있어야 한다.
2. **JIT(Just-in-Time)**: EMA가 요구하는 필드라도, 실제로 EMA 제출 직전까지 확정이 불필요한 필드는 수집을 미룬다. "언젠가 쓸지 모르니 미리 받는다"는 금지.
3. **역할 분리**: 신청자가 모르는 정보(LEW Consent Date, Inspection Interval, 최종 Supply Voltage 등)는 **LEW 검토 단계**에서 LEW가 기입한다. 관리자·시스템이 자동 기록할 수 있는 정보는 신청자에게 노출하지 않는다.

---

## 2. JIT 매핑 테이블

"모르겠다" 허용 = 필드 옆에 "모르겠어요(LEW에게 확인 요청)" 토글을 제공한다는 의미.

| EMA 필드 | 수집 시점 | 수집 주체 | 필수 | 조건부 표시 | 모르겠다 허용 | 이유 |
|---|---|---|---|---|---|---|
| **Application Type** (New / Renewal) | Step 1 | 신청자 | 필수 | - | - | 분기 로직 근원. |
| **Applicant Type** (Individual / Corporate) | Step 1 | 신청자 | 필수 | - | - | UEN·Designation 표시 여부 결정. |
| **Name of Applicant** | 프로필(가입 시 이미 수집) | 신청자 | 필수 | - | - | 계정 정보 재사용. 폼에서 재입력 금지. |
| **NRIC/FIN** | 프로필 | 신청자 | 필수 | - | - | 가입 시 1회 수집. |
| **Designation** | 법인 JIT 모달(Step 4 Submit 시) | 신청자 | Corporate일 때만 필수 | Corporate 선택 시 | - | 개인 신청자에게 노출 금지. |
| **Company Name / UEN** | 법인 JIT 모달(Submit 시) | 신청자 | Corporate일 때만 필수 | Corporate 선택 시 | - | 개인 신청자에게 노출 금지. 기존 CompanyInfoModal 재사용. |
| **Installation Name** | Step 2 | 신청자 | 필수 | - | - | EMA 필수. "현장의 명칭(예: XX 빌딩 1층 매장)". |
| **Installation Address (Block/Unit/Street/Building/Postal)** | Step 2 | 신청자 | Block·Street·Postal 필수 / Unit·Building 선택 | - | Unit·Building만 "해당 없음" | OneMap API 역지오코딩으로 분해 시도 후 사용자 확인. |
| **Premises Type** (Commercial, Factories, Farm, Residential 등) | Step 2 | 신청자 | 필수 | - | - | EMA 분류를 드롭다운으로 제시. 기본값 없음 (잘못된 추론 방지). |
| **Consumer Type** (Non-contestable / Contestable) | **LEW 검토 단계** | LEW | 필수 | - | 신청자 단계에서는 수집하지 않음 | 신청자가 모르는 경우가 대다수. MSSL/Retailer와 연동. |
| **Retailer** | **LEW 검토 단계** | LEW | Contestable일 때 필수 | - | 동일 | Contestable 확정 후에만 의미. |
| **MSSL Account No (894-62-8407-5)** | **LEW 검토 단계** | LEW | 필수 | - | 신청자 단계에서는 수집하지 않음 | 실측 후 LEW가 기입. 제안 기본값 — JIT. Trade-off: 신청자 일부가 지로 청구서로 미리 알 수 있음 → Step 2 말미에 "알고 계시면 입력(선택)" 필드를 **숨김 옵션**으로 제공. |
| **Landlord EI Licence No** | Step 2 | 신청자 | 임대 시만 필수 | "임대 시설" 체크 시 | 허용 | "모름" 체크 시 LEW가 확정. |
| **Correspondence Address** | Step 2 | 신청자 | 선택 | "설치 주소와 다름" 체크 시 | - | 미체크 시 Installation Address 그대로 사용. |
| **Contact No For SMS / Telephone / Ext / Fax** | 프로필 | 신청자 | SMS 번호만 필수 | - | - | 가입 시 수집 완료. Fax·Ext는 저장만 하고 노출 금지. |
| **Supply Voltage** | **LEW 검토 단계** | LEW | 필수 | - | 신청자 단계에서는 수집하지 않음 | 신청자 단위에서 오입력 빈발. LEW 확정. |
| **Approved Load (kVA)** | Step 3 (추정) → **LEW 검토 단계 최종 확정** | 신청자 추정 + LEW 확정 | 필수 | - | 허용 (기존 "I don't know") | KVA_UNKNOWN_SENTINEL 패턴 재사용. |
| **Generator (유무/용량)** | **LEW 검토 단계** | LEW | 선택 | - | - | 일반 시설에 드물다. LEW가 체크. |
| **LEW Appointment Date** | **LEW 검토 단계** | LEW | 필수 | - | - | LEW 자신이 본인 수락 시 자동 `now()` 기본값. |
| **LEW's Consent Date** | **LEW 검토 단계** | LEW | 필수 | - | - | LEW 서명 시점. |
| **Inspection Interval** | **LEW 검토 단계** | LEW | 필수 | - | - | LEW 판단. |
| **Owner Authorization Letter 체크** | Step 4 Review | 신청자 | 법인·임대 시만 필수 | Corporate OR 임대 시 | - | EMA 요구. LOA 스냅샷 기존 로직 재사용. |
| **Renewal: Company/UEN 변경 여부** | Step 1 (Renewal 분기) | 신청자 | Renewal일 때만 | Application Type=Renewal | - | 미체크 시 기존 값 자동 승계. |
| **Renewal: Installation Address 변경 여부** | Step 1 (Renewal 분기) | 신청자 | Renewal일 때만 | Application Type=Renewal | - | 미체크 시 Step 2 스킵 가능. |
| **Single Line Drawing 옵션** (첨부 / 3개월 내 제출 / LEW 작성 의뢰) | Step 3 | 신청자 | 필수 | - | - | 현재 2-옵션에서 3-옵션으로 확장. |
| **Declaration 4개 체크박스** | Step 4 Review | 신청자 | 전체 필수 | - | - | EMA 제출 시 반드시 서명 등가. |
| **Licence No / Application No / Expiry Date** | **관리자/시스템 자동** | System | - | - | - | ELISE 제출 후 회신 값. |
| **LOA 스냅샷(applicantName/company/uen/designation)** | Submit 시 자동 스냅샷 | System | - | - | - | Application.java 기존 정책. |
| **Fee 계산 결과** | Step 3 자동 | System | - | - | - | PriceApi 기존 로직. |

> 숨김 옵션(MSSL "알면 입력"과 같은)은 기본 접힘 상태. "JIT 위반이 아닌가?"에 대한 답변: 신청자가 **자발적으로** 가진 정보를 재타이핑 없이 받을 수 있는 기회 1회만 제공하고, 공란이면 LEW로 넘긴다. 이는 "강제 수집"이 아니라 "선택적 단축 경로"이므로 원칙에 부합한다.

---

## 3. 신청자가 직접 입력하는 최소 필드 목록 (Step별 10개 이내)

**Step 0 — Before You Begin**: 안내만, 입력 없음.

**Step 1 — Application Type**
1. Application Type (New / Renewal)
2. Applicant Type (Individual / Corporate)
3. (Renewal 전용) 기존 라이센스 선택, 변경 사항 2개 체크박스

**Step 2 — Site**
4. Installation Name
5. Installation Address (통합 입력창 → OneMap 자동 분해 → 5-파트 편집 가능)
6. Premises Type (드롭다운)
7. (조건부) 임대 시설 체크 → Landlord EI Licence

**Step 3 — Capacity & Drawing**
8. Approved Load kVA (기존 KvaPriceCard, "모르겠음" 유지)
9. Single Line Drawing 옵션 3-way (첨부 / 3개월 내 제출 / LEW 의뢰)

**Step 4 — Review**
10. Declaration 4개 체크박스 + (조건부) Owner Authorization Letter 체크

**압축 근거**
- Corporate UEN·Designation은 **Submit 모달**에서 한 번에 받음 → Step 수 증가 없음.
- Correspondence Address는 기본값(= Installation Address)이 정답인 경우가 95% → "다름" 체크 시에만 노출.
- EMA의 Consumer Type/Retailer/MSSL/Supply Voltage/Generator/LEW Appointment 등 6개 이상은 신청자에게 "모르는 정보"이므로 **전량 후단계 이관**.

---

## 4. LEW 검토 단계에서 추가되는 필드

신청자 Submit 이후 LEW가 수락 시점에 열리는 **LEW Review Form**(신규 화면)에서 받음. 신청자에게는 조회 권한만 주거나, 열람 불가(기본값 — 신청자 혼란 방지)로 둔다.

- Supply Voltage (단상/삼상, 230V/400V 등)
- Consumer Type (Non-contestable / Contestable)
- Retailer (Contestable 시)
- MSSL Account No (4-파트 포맷 검증)
- Approved Load (kVA) — 신청자 추정값 덮어쓰기 허용
- Generator 유무 및 용량
- LEW Appointment Date (수락 시 자동 `now()` 기본값, 편집 가능)
- LEW's Consent Date
- Inspection Interval (개월)
- LEW 서명 라인(=LEW 식별자 자동 기록)

**미노출 이유**: 신청자가 오입력하면 EMA 반려. 따라서 처음부터 LEW 책임 필드로 분리한다.

---

## 5. 관리자/시스템 자동 기록 필드

- **Application No** (내부 시퀀스, 기존 `applicationSeq`)
- **Licence No** (ELISE 회신 시 기입 — 관리자 화면)
- **Expiry Date** (Licence No와 동시에 기입, 또는 Renewal 시 자동 계산)
- **LOA 스냅샷 4종** (Submit 시 자동, `@Column(updatable=false)` 정책 유지)
- **Fee 계산 결과** (Step 3 즉시, PriceApi)
- **결제 상태** (PENDING_PAYMENT → PAID)
- **LEW 수락 이력**, **관리자 Reject/Revision 이력**, **감사 로그** (BaseEntity createdBy/updatedBy)
- **EMA 제출 응답 원문** (향후 ELISE 연동 시 관리자 전용 필드)

---

## 6. 재정의된 Phase 계획 (P1~P3)

### P1 — "반려되지 않는 최소집합" (2~3 스프린트)
**범위**
- Installation Name 신규 필드
- Installation Address 5-파트 분해 UI (OneMap 연동 + 수동 편집)
- Premises Type 드롭다운 (EMA enum 정확 반영)
- Landlord EI Licence 조건부 필드
- SLD 옵션 2→3 way
- Declaration 4개 체크박스 + Owner Authorization 조건부
- Renewal 변경 사항 체크박스 2종
- LEW Review Form (신규 화면, Supply Voltage·Consumer Type·MSSL·Consent Date·Inspection Interval·LEW Appointment)
- Applicant 프로필에 Telephone Ext/Fax 저장 컬럼만 추가(노출은 안 함)

**완료 정의**: 수기 EMA 제출 담당자가 "필수 필드 누락"으로 반려당하지 않는다. 신청자가 모르는 필드는 전부 LEW 영역에 존재한다.
**예상 복잡도**: 중(신규 Step UI + LEW Review 신규 화면 + OneMap 통합).

### P2 — "신청자 편의 극대화" (1~2 스프린트)
**범위**
- Correspondence Address "다름" 체크박스 + 5-파트
- MSSL "알면 입력" 숨김 옵션 (Step 2 말미 아코디언)
- 프로필 재사용 정비(Name·NRIC/FIN·SMS 자동 주입, 편집 비활성화 시각화)
- Renewal 시 "변경 없음" 체크 → Step 2 스킵 자동화
- "모르겠어요" 토글 공통 컴포넌트화

**완료 정의**: 반복 신청 시 신청자의 키 입력 수가 P1 대비 30% 이상 감소.
**예상 복잡도**: 하~중.

### P3 — "자동화·연동" (1~3 스프린트, ELISE 연동 가능 시점에 착수)
**범위**
- ELISE 제출 API 연동 시 Licence No / Expiry Date 자동 기입
- Consumer Type 자동 판정(kVA 임계값 기반 초기 추정, LEW 확정 여전히 필요)
- Retailer 목록 드롭다운 + EMA 동기화
- 감사 로그 / LOA 재발급 UI
- Generator 유무 기본 "아니오"의 ML 기반 예측(장기 후보 — 스킵 가능)

**완료 정의**: 관리자 수작업이 "ELISE 최종 제출 클릭"으로 축소.
**예상 복잡도**: 상(외부 연동·권한 설계).

---

## 7. Acceptance Criteria

다음을 모두 만족해야 JIT 원칙 준수로 인정한다.

- [ ] Individual 신청자에게는 Company Name·UEN·Designation 입력 필드가 어느 화면에도 렌더되지 않는다.
- [ ] Corporate 신청자는 Step 1~3 동안 UEN/Designation을 보지 않고, Submit 시점의 CompanyInfoModal에서만 1회 입력한다.
- [ ] Step 1 진입 후 Step 2 제출까지 신청자가 타이핑해야 하는 필수 필드가 7개 이하다(Installation Name, Address 검색어, Premises Type 선택, kVA 선택, SLD 옵션 선택, Declaration 체크, SMS 번호는 프로필 자동).
- [ ] Installation Address 5-파트 중 Unit·Building에 "해당 없음" 토글이 존재한다.
- [ ] Consumer Type / Retailer / MSSL / Supply Voltage / Generator / Consent Date / Inspection Interval / LEW Appointment Date 필드는 신청자 화면(Step 0~4)에 **절대 렌더되지 않는다**.
- [ ] LEW Review Form에서 위 8개 필드가 모두 편집 가능하다.
- [ ] Renewal에서 "Company/UEN 변경 없음" + "Installation Address 변경 없음" 모두 체크 시 Step 2가 자동 스킵된다.
- [ ] SLD 옵션이 3-way(첨부·3개월 내 제출·LEW 의뢰)이며, "3개월 내 제출" 선택 시 상태 머신이 `PENDING_SLD` 같은 후속 단계를 지원한다.
- [ ] Owner Authorization Letter 체크박스는 Corporate 또는 임대 체크 시에만 표시된다.
- [ ] Declaration 4개 체크박스 전체 체크 없이 Submit 버튼이 비활성화된다.
- [ ] Landlord EI Licence 필드는 "임대 시설" 체크 시에만 노출되고 "모름" 허용된다.
- [ ] Licence No, Expiry Date, Application No는 어떤 신청자 화면에도 입력 컨트롤로 존재하지 않는다(조회용 표시만 허용).
- [ ] "모르겠어요" 토글이 있는 필드는 LEW Review Form의 대응 필드에 시각적 경고 배지(예: "신청자가 확인 요청")가 표시된다.
- [ ] MSSL "알면 입력" 숨김 옵션은 기본 접힘 상태이며, 공란으로 Submit해도 검증 에러가 발생하지 않는다.
- [ ] EMA 제출 담당자(관리자)가 반려 없이 1건 제출 성공한 케이스가 End-to-End 테스트에 포함된다.
