# EMA Licence Application Flow — UX Integration Spec

**문서 종류**: UX / Product Specification
**대상 기능**: `NewApplicationPage` — EMA ELISE 누락 필드 통합
**작성일**: 2026-04-22
**상태**: UX 초안 · PM/BE 사인오프 대기

---

## 1. 설계 원칙

- **단순성 우선**: 신청자는 "타입 선택 → 어디·얼마 → 확인" 세 가지 결정만 내리면 된다. EMA 필드 추가로 스텝이 늘어나지 않는다.
- **JIT (Just-in-Time)**: 결제·제출 등 실제로 값이 필요해지는 순간까지 묻지 않는다. 모르는 값은 LEW/관리자/시스템이 나중에 채운다.
- **역할 분리**: Supply Voltage·Approved Load·LEW 동의일자·검사 주기·Licence No 등 현장/전문 정보는 신청자 폼에서 완전히 제외한다.

## 2. 신청자 흐름 Step 구성

현재 4스텝(Type → Address → kVA → Review)을 **그대로 유지**하고, 필드는 기존 스텝 안에 흡수한다. 스텝 수 증가 금지.

```
┌──────────────────────────────────────────────────────────────┐
│  Step 1 Type & Service   (기존 Step 0, 6~9 필드)             │
│  Step 2 Installation     (기존 Step 1, 3~5 필드)             │
│  Step 3 Capacity & Price (기존 Step 2, 1 필드 + 계산)        │
│  Step 4 Review & Submit  (Declaration 4-ck + 제출)           │
└──────────────────────────────────────────────────────────────┘
```

**Step 1 — Type & Service** (필드 count: 최대 9, 조건부 평균 6)
- Application Type (NEW / RENEWAL) — 카드
- Applicant Type (INDIVIDUAL / CORPORATE) — 카드 (기존)
- Licence Period (12mo / 3mo) — 카드 (기존)
- SLD Option (SELF_UPLOAD / REQUEST_LEW) — 카드 (기존)
- SP Account No / MSSL Account No — optional, "모르겠음" 토글 (통합: 싱가포르에서는 대부분 SP = MSSL 1:1)
- Consumer Type — default `Non-contestable`, Advanced 토글 안에 숨김
- Retailer — default `SP Services Limited`, Advanced 토글 안에 숨김
- *(RENEWAL일 때만)* 기존 Licence 선택 / Renewal Reference / 변경 여부 체크 2개
- *(NEW·CORPORATE일 때만)* "임대 시설입니까?" 체크 → YES면 Step 4 제출 직전 JIT로 Landlord EI Licence 수집

**Step 2 — Installation** (필드 count: 3, RENEWAL prefill 시 0~1)
- Installation Address — 단일 한 줄 입력(현행 유지). EMA 5-part(Block/Unit/Street/Building/Postal)는 백엔드에서 파싱 or Admin 보정.
- Postal Code — "모르겠음" 토글 허용 (6자리 SG postal)
- Premises / Building Type — 드롭다운 (현행 유지; EMA Premises Type과 매핑)
- *(조건부, NEW일 때만)* Installation Name — 기본값은 Applicant Name + "Premises"로 자동 생성하되, "다르게 지정" 토글 시 노출

**Step 3 — Capacity & Price** (필드 count: 1)
- kVA 선택 또는 "I don't know — LEW가 확정" (현행 유지)
- Generator 여부는 **Step 3에 두지 않는다**. Review 화면에 작은 체크박스 "디젤/가스 발전기가 있습니까?"로 배치 → 체크 시 "LEW가 현장에서 세부사항 확인" 배지만 표시.

**Step 4 — Review & Submit** (필드 count: Declaration 4 + 조건부 1~2)
- 입력값 요약 카드
- *(CORPORATE 이면서 User.companyName 없음)* JIT 모달: UEN + Company Name + Name of Applicant + Designation (기존 `CompanyInfoModal` 확장).
- *(NEW 이면서 "임대" 체크했던 경우)* JIT 모달: Landlord EI Licence No
- Correspondence 주소: 기본 "Installation 주소와 동일" 체크 선택됨 → 해제 시에만 5-part 노출
- Contact: Email(필수, prefill), Mobile SMS(필수, prefill), Telephone/Ext/Fax(선택, 접어두기)
- Declaration 4-checkbox (3-그룹 축약, 하단 §5)
- Submit 버튼 (confirm dialog)

## 3. 조건부 표시 규칙

| 필드 | 표시 조건 | 기본 상태 | 비고 |
|---|---|---|---|
| UEN, Company Name, Designation, Name of Applicant | `applicantType=CORPORATE` **AND** User에 companyName 없음 | Step 4 JIT 모달 | 이미 프로필에 있으면 모달 스킵 |
| Landlord EI Licence | Step 1에서 "임대 시설" 체크 + `NEW` | Step 4 JIT 모달 | RENEWAL이면 기존 값 prefill |
| Renew Period (3/12mo) | 항상 | Step 1 노출 | EMA와 동일 |
| Renewal Reference No, Existing Licence No/Expiry, 변경 체크박스 2개 | `applicationType=RENEWAL` | Step 1 하단 섹션 | 기존 앱 선택 시 prefill |
| "회사명 변경" / "주소 변경" 체크박스 | `RENEWAL` | Step 1 내 Renewal 섹션 | 체크 시 Step 2(주소) / Step 4(회사) 재확인 강제 |
| Correspondence Address 5-part | "Installation과 동일" **미체크** | Step 4 하단 | 체크가 기본값 |
| Telephone/Ext/Fax | 항상 | Step 4, `<details>`로 접힘 | Mobile SMS만 필수 |
| Generator 세부 | Step 3 체크 YES | Review에 배지만 | 세부 값은 LEW가 입력 |
| Consumer Type, Retailer | 항상 존재 | Step 1 Advanced 토글 안 (닫힘) | 99%는 SP/Non-contestable |
| Installation Name | NEW에서 "다르게 지정" 토글 ON | Step 2 | 기본은 자동 생성 |
| SLD 상태 3-radio (첨부/3개월 내/LEW 의뢰) | 항상 | Step 1 SLD Option 확장 | 현행 2-radio를 3-radio로 확장 |

## 4. "모르겠다" 토글 허용 필드

| 필드 | Toggle 문구 (권장) | Fallback 행동 |
|---|---|---|
| SP/MSSL Account No | "아직 모릅니다 — LEW가 확인 후 기입" | 서버 `spAccountNo=null`, 상태 메모 자동 기록 |
| Postal Code | "주소만 알고 번호는 몰라요" | LEW/Admin에게 `postal_code_pending` 플래그 전달 |
| Building / Premises Type | "선택하지 않음 — LEW 판단" | `buildingType=null` 허용 |
| kVA | "I don't know — let LEW confirm me later" (현행 유지) | 서버 placeholder 45, `kvaUnknown=true` |
| Installation Name | "Applicant 이름 사용" (= 자동 생성) | 자동 생성된 값 저장 |
| Landlord EI Licence No | "곧 받을 예정 — 제출 후 업로드" | 상태 `AWAITING_LANDLORD_DOC` |

**토글 UX 원칙**: 입력 필드 **아래**에 체크박스 또는 링크 스타일 버튼으로 배치. 체크 시 입력 disabled + 회색 배경 + 짧은 rationale 표시("LEW가 SP 빌에서 확인합니다").

## 5. Declaration 4-checkbox 배치

EMA 원본의 4개 서약 조항을 "내용 카테고리"로 묶어 **3개 체크**로 축약한다. 4개를 4줄로 나열하면 "눈감고 체크" 현상이 심해지므로 의미 단위로 그룹핑하고 중요한 조항만 독립시킨다.

**Review Step 하단 블록** (ASCII wireframe):

```
┌─ Declaration ────────────────────────────────────────────┐
│ [✓] (pre-checked) 제출 정보는 사실이며, EMA 규정에 따라  │
│     허위 기재 시 법적 책임이 있음을 이해합니다.          │
│     (EMA 조항 1+4 축약)                                  │
│                                                          │
│ [ ] 제 전기 설비는 싱가포르 전기 규정 및 SP Group 기술   │
│     요건에 부합함을 확인합니다. (EMA 조항 2)             │
│                                                          │
│ [ ] 지정된 LEW가 정기 점검을 수행하고, 결과를 EMA에      │
│     보고하는 데 동의합니다. (EMA 조항 3)                 │
└──────────────────────────────────────────────────────────┘
[Submit Application] ← 3개 모두 체크 시에만 활성화
```

**근거**: Heuristic #5(오류 방지) + #8(미학적 디자인). 기본 체크는 "이미 상식에 속하는 사실 서약"에만 적용하고, 행위/약속에 해당하는 2개는 사용자가 능동 체크하게 한다. 대안: (a) 4개 전부 unchecked — 안전하지만 완료율 감소 관측 예상, (b) 모두 pre-checked + "동의합니다" 단일 버튼 — 법적으로 취약. 권장안이 둘 사이의 균형.

## 6. Renewal 플로우 차이

**Prefill되는 필드** (기존 Completed Application 선택 시 자동):
- Installation Address, Postal Code, Premises Type, Applicant Type, UEN/Company, selectedKva, SP Account No.

**재확인 강제 필드**:
- Renewal Reference No (매 갱신마다 새 값)
- Licence Period (12/3mo 재선택)
- Declaration 체크박스 (매번 신규 체크)
- Existing Licence No + Expiry Date (자동 prefill 후 사용자 확인용 표시)

**변경 체크박스 배치** (Step 1의 Renewal 섹션 하단):

```
┌─ 이전 신청 이후 변경사항 ─────────────────────────┐
│ [ ] 회사명이 변경되었습니다 → Step 4에서 재입력   │
│ [ ] 설치 주소가 변경되었습니다 → Step 2에서 재입력│
└──────────────────────────────────────────────────┘
```

체크 시 해당 스텝의 prefill을 clear하고 `required`로 전환. 미체크 시 read-only 카드로 요약만 보여줌.

## 7. 시나리오 워크스루

### A. 개인 신청자 · NEW · 주소 OK / MSSL 모름
1. **Step 1**: NEW 카드 클릭 → Individual 기본값 → 12 Months → SLD Self-upload. SP/MSSL 란 아래 "아직 모릅니다" 토글 체크 → 필드 disabled. Advanced(Consumer/Retailer) 닫힘 상태 유지.
2. **Step 2**: 주소·postal·building type 입력. Installation Name 토글은 무시(자동 생성).
3. **Step 3**: 45 kVA 선택 → 가격 표시.
4. **Step 4**: Installation 주소와 동일 체크 유지 → Correspondence 입력 없음. Email·Mobile prefill 확인. Declaration 3개 체크 → Submit. **본 화면 수: 4, 입력 필드 수: 5 (address, postal, building, kva, declaration)**.

### B. 법인 신청자 · RENEWAL · 대부분 prefill · 변경 없음
1. **Step 1**: RENEWAL → Corporate → 기존 licence 카드 클릭 → 자동 채움. Renewal Reference No 1개만 입력. "변경 체크박스" 둘 다 미체크.
2. **Step 2**: 모든 필드 prefill → 사용자는 확인만 하고 Continue.
3. **Step 3**: prefill된 kVA 확인 → Continue.
4. **Step 4**: 회사정보는 User.companyName에 이미 있으므로 JIT 모달 스킵. Correspondence 동일 체크 유지. Declaration 3개 체크 → Submit. **입력 필드 수: 2 (renewal ref, declaration 2개)**.

### C. 법인 신청자 · NEW · SLD는 LEW 의뢰
1. **Step 1**: NEW → Corporate → 12 Months → **SLD: Request LEW to Prepare** → SP Account 입력. "임대 시설" 체크.
2. **Step 2**: 주소 입력.
3. **Step 3**: kVA UNKNOWN 선택 → "From S$350" 표시.
4. **Step 4**: JIT 모달 (1): Company Info 입력(UEN, Company, Name, Designation). JIT 모달 (2): Landlord EI Licence No 입력. Correspondence 동일 체크 유지. Declaration → Submit. **보조 모달 2회 통과, 본 화면 수: 4**.

## 8. Acceptance Criteria

1. 신청자가 NEW 흐름에서 입력해야 하는 **필수 필드 총합이 10개 이하**이다 (Declaration 제외).
2. Review 이전 어떤 스텝에서도 Supply Voltage / Approved Load / Inspection Interval / Licence No / LEW Date 입력란이 **보이지 않는다**.
3. 시나리오 A에서 "모르겠다" 토글이 보이는 필드는 최소 **3개 이상**이다 (SP·Postal·Building).
4. 시나리오 B에서 Step 2/3의 입력 액션은 **0회**이며, 사용자는 Continue 버튼만 누른다.
5. CORPORATE 선택 시 Step 1~3 동안 **회사 관련 입력창이 단 하나도 노출되지 않는다**(JIT는 Step 4에서만).
6. Correspondence Address "Installation과 동일" 체크가 **기본값**이며, 해제 전까지 5-part 필드가 DOM에서 숨겨진다.
7. RENEWAL에서 변경 체크박스 미체크 시, 해당 섹션은 read-only로 표시되고 편집 컨트롤이 tab 순회에서 제외된다 (a11y).
8. Declaration 체크 중 하나라도 unchecked 상태면 Submit 버튼이 `disabled`이고 `aria-disabled=true`이다.
9. Generator 체크만으로 새로운 필드가 생기지 않으며, Review 배지로만 표현된다.
10. kVA UNKNOWN 선택 시 가격표는 "From S$350"로 표시되고 실제 금액 계산은 LEW 확정 이후로 미뤄진다 (현행 유지, 회귀 금지).
11. Step 4 JIT 모달 취소 시 Step 4 상태가 보존되며 API 호출이 일어나지 않는다.
12. 모든 "모르겠다" 토글은 키보드만으로 조작 가능하고 스크린리더에 rationale이 전달된다.

## 9. 위험 · 절충점

- **주소 5-part vs 단일 문자열**: EMA 서식은 Block/Unit/Street/Building/Postal 5-part를 요구한다. 신청자 UX를 위해 단일 문자열을 유지하되, 백엔드에 **파싱 서비스 또는 Admin 보정 큐**가 필요. PM 결정 필요: (a) SG OneMap API 연동으로 자동 파싱, (b) Admin 수동 보정, (c) 단계적 파싱 + LEW 검증 중 택1.
- **MSSL vs SP Account**: 실무상 같은 번호지만 포맷이 다름(894-62-8407-5 vs 10자리). 신청자에게는 "SP 계정/MSSL 번호" 단일 필드로 노출하되 서버 저장 시 두 컬럼 동시 기록. BE 스키마에 `mssl_account_no` 컬럼 추가 여부 PM 합의 필요.
- **JIT 모달 중첩**: 시나리오 C에서 Company + Landlord 2개 모달이 연속 노출. 모달 stacking은 인지 부하를 유발 — 대안으로 Step 4 내 인라인 섹션으로 표시하는 방안 검토. 구현 복잡도 낮음, 그러나 Step 4가 길어짐. **권장**: 첫 배포는 모달 연속, 사용자 테스트 후 인라인 전환.
- **Declaration 축약의 법적 리스크**: Legal/EMA 측 사인오프 필요. 원문 그대로 노출하는 안전안은 완료율 저하 — 법무 확인 후 최종 문구 확정.
- **Advanced 토글(Consumer/Retailer)**: 99% 케이스는 기본값이지만 Contestable 시장 소비자가 Advanced를 놓칠 가능성. Review 화면에 "Retailer: SP Services Limited (default)"를 항상 표시하여 오기를 인지 가능하게 함.
- **Backend 스키마 확장**: EMA 신규 필드(consumerType, retailer, landlordLicence, installationName, mssl, correspondenceAddress 5개 컬럼, generator flag)가 `Application` 엔티티에 누적됨. 선택 컬럼 12~15개 추가 예상 → BE와 마이그레이션 타이밍 합의 필요. Nullable 허용.

---

**PM 동기화 항목**: 주소 파싱 전략, MSSL 컬럼 추가, Declaration 축약 문구의 법무 확인, JIT 모달 vs 인라인 결정, Advanced 토글 기본 노출 여부.
