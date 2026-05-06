# JIT 재요구(Re-ask) 감사 보고서

작성일: 2026-04-22  
감사 기준: "이미 입력한 값을 후속 단계에서 다시 요구하지 않는다" (JIT 원칙)  
보조 기준: "EMA ELISE 최종 제출 시 필요한 필드가 이미 수집돼 있어야 한다"

---

## 1. Executive Summary

전수 코드 감사 결과 **9개의 JIT 위반(V-1 ~ V-9)** 과 **EMA ELISE 필드 gap 7개**를 식별했다.

- **P0 (즉시 수정)**: 2건 — Correspondence Address 이중 레이어 불일치(V-1), INDIVIDUAL 신청자 companyName 요구(V-2)
- **P1 (우선 수정)**: 4건 — persistToProfile=false 시 companyInfo 소실(V-3), AdminApplicationInfo "update their profile" 문구(V-4), correspondenceAddress 5-part가 LOA에 미반영(V-5), installationAddress 이중 저장소 불일치(V-8)
- **P2 (개선)**: 3건 — P1.4 신규 필드 6개 dead data(V-6), 스냅샷 품질 저하(V-7), EMA 미수집 필드(V-9)

최상위 3개 심각도: V-1(주소 입력했는데 LOA 차단) > V-2(INDIVIDUAL에게 회사정보 요구) > V-3(persistToProfile=false 시 LOA 차단). 수정 난이도는 단순(1일 이하) 2건, 중간(1~3일) 4건, 구조적 개선 필요 3건이다.

---

## 2. 신청 단계 수집 데이터 매트릭스

| 필드 | 수집 지점 | 조건 | User 저장? | Application 저장? | EMA 필드 매핑 |
|---|---|---|---|---|---|
| email | 회원가입 | 필수 | ✅ | ❌ | Particulars of Applicant - Email |
| firstName / lastName | 회원가입 | 필수 | ✅ | ❌ | Name of Applicant |
| phone | 회원가입/ProfilePage | 선택 | ✅ | ❌ | Contact No. for SMS / Telephone No |
| companyName | CompanyInfoModal (JIT) | CORPORATE + User.companyName 없을 때 | ✅ (persistToProfile=true만) | ❌ | Company/Licensee Name |
| uen | CompanyInfoModal (JIT) | 동상 | ✅ (동상) | ❌ | UEN |
| designation | CompanyInfoModal (JIT) | 동상 | ✅ (동상) | ❌ | Designation of Applicant |
| correspondenceAddress (단일) | ProfilePage | 선택 | ✅ | ❌ | Correspondence Address (legacy, 단일 문자열) |
| correspondenceAddress 5-part | Step 3 (Review) | "Installation과 다름" 체크 시 | ❌ | ✅ | Correspondence Address 5-part |
| installationName | Step 1 (Address) | "Custom" 토글 ON 시 | ❌ | ✅ | Installation Name |
| premisesType | CreateApplicationRequest | 선택 | ❌ | ✅ | Premises Type |
| isRentalPremises | Step 0 (Type) | NEW + 임대 | ❌ | ✅ | Landlord EI Licence (연결) |
| landlordEiLicenceNo | Step 3 (Review) | isRentalPremises=true | ❌ | ✅ (암호화) | Landlord EI Licence |
| renewalCompanyNameChanged | Step 0 (Type) | RENEWAL | ❌ | ✅ | 회사명 변경 체크박스 |
| renewalAddressChanged | Step 0 (Type) | RENEWAL | ❌ | ✅ | Installation Address 변경 체크박스 |
| address (installation, 단일) | Step 1 (Address) | 필수 | ❌ | ✅ | Installation Address (legacy) |
| installationAddress 5-part | CreateApplicationRequest | 선택 | ❌ | ✅ | Installation Address 5-part |
| selectedKva | Step 2 (kVA) | 필수 | ❌ | ✅ | Approved Load (kVA) 에 해당 |
| sldOption | Step 0 (Type) | 필수 | ❌ | ✅ | Single Line Drawing 선택 |
| renewalPeriodMonths | Step 0 (Type) | 필수 | ❌ | ✅ | Renew Period |
| spAccountNo | Step 0 (Type) | 선택 | ❌ | ✅ | MSSL Account No (부분 매핑, 형식 다름) |

---

## 3. EMA ELISE 필드 vs 우리 플랫폼 동의어 매핑 (이중 저장소 분석 포함)

### 3.1 "이중 저장소" 필드 — writer/reader 불일치 목록

아래 5쌍은 동일한 의미를 가지지만 두 곳에 나뉘어 저장된다. 한쪽만 읽는 consumer가 있으면 "또 묻기" 버그의 근본 원인이 된다.

#### Pair 1: Correspondence Address

| 항목 | Layer A: `User.correspondenceAddress` (단일) | Layer B: `Application.correspondenceAddressBlock/Unit/Street/Building/PostalCode` |
|---|---|---|
| 수집 경로 | ProfilePage 직접 입력 | Step 3 "다른 주소" 체크 해제 시 |
| 저장 여부 | ✅ User 테이블 | ✅ Application 테이블 (암호화) |
| `validateApplicantProfile` 체크 | ✅ Layer A만 | ❌ Layer B 무시 |
| LOA PDF 렌더 | ✅ Layer A만 | ❌ Layer B 무시 |
| `AdminApplicationInfo` 경고 | ✅ Layer A(userCorrespondenceAddress) | ❌ Layer B 무시 |
| EMA ELISE 대응 | 단일 문자열, 5-part 분리 불가 | EMA가 요구하는 5-part |

**불일치**: Layer B에 저장돼도 LOA 생성 시 Layer A가 null이면 `INCOMPLETE_PROFILE`. V-1의 근본 원인.

#### Pair 2: Installation Address

| 항목 | Layer A: `Application.address` (단일) | Layer B: `Application.installationAddressBlock/Unit/Street/Building/PostalCode` |
|---|---|---|
| 수집 경로 | Step 1 (필수 입력) | CreateApplicationRequest (선택, 미전송 시 null) |
| 저장 여부 | ✅ Application 테이블 | ✅ Application 테이블 (평문) |
| LOA PDF 렌더 | ✅ Layer A (`application.getAddress()`) | ❌ Layer B 무시 |
| EMA ELISE 대응 | 단일 문자열, 분리 불가 | EMA가 요구하는 5-part |

**불일치**: Step 1에서 단일 문자열만 필수 수집. Layer B는 선택 수집이고 현재 프론트엔드에서 전송 안 됨(CreateApplicationRequest에 필드는 있으나 NewApplicationPage에서 Layer B 5-part 입력 UI가 없음). EMA 제출 시 Layer B 5-part가 비어 있게 된다.

#### Pair 3: companyName

| 항목 | Layer A: `User.companyName` | Layer B: `Application.loaCompanyNameSnapshot` |
|---|---|---|
| 수집 경로 | JIT CompanyInfoModal (persistToProfile=true 시) / ProfilePage | LOA 생성 시 Layer A를 캡처 |
| `validateApplicantProfile` | Layer A만 체크 | 미사용 |
| LOA PDF 렌더 | Layer A만 사용 | 미사용 |

**불일치**: Layer B는 법적 감사 기록용. LOA 생성 로직이 Layer B를 사용하지 않아 Layer A가 null이면 차단됨. V-2·V-3의 근본 원인.

#### Pair 4: designation

| 항목 | Layer A: `User.designation` | Layer B: `Application.loaDesignationSnapshot` |
|---|---|---|
| 수집 경로 | JIT CompanyInfoModal / ProfilePage | LOA 생성 시 Layer A 캡처 |
| `validateApplicantProfile` | Layer A만 체크 | 미사용 |

**불일치**: Pair 3과 동일 패턴.

#### Pair 5: uen

| 항목 | Layer A: `User.uen` | Layer B: `Application.loaUenSnapshot` |
|---|---|---|
| 수집 경로 | JIT CompanyInfoModal / ProfilePage | LOA 생성 시 Layer A 캡처 |
| LOA PDF 렌더 | Layer A만 사용 | 미사용 |

**불일치**: Pair 3과 동일 패턴.

### 3.2 EMA ELISE 필드 gap — 우리가 수집하지 않는 필드

| EMA 필드 | 현재 상태 | 수집 주체 | 비고 |
|---|---|---|---|
| MSSL Account No (4-part) | `Application.spAccountNo` 단일 문자열로 수집 | 신청자 | 형식 불일치. EMA는 `894-62-8407-5` 4-part 형식 요구 |
| Consumer Type | 미수집 | LEW/Admin 채움 예정 | EMA 제출 시 필요 |
| Retailer | 미수집 | LEW/Admin 채움 예정 | SP Services Limited 등 |
| Supply Voltage, Approved Load, Generator | 미수집 | LEW가 Certificate of Fitness에서 채움 | 의도된 설계 |
| Inspection Interval | 미수집 | LEW | 의도된 설계 |
| LEW's Consent Date / Appointment Date | 미수집 | LEW 수락 시 자동 기록 가능 | `loaSignedAt` 유사 필드로 유추 가능 |
| Telephone Ext., Fax No. | 미수집 | `User.phone` 단일 필드만 있음 | EMA는 Telephone + Ext 분리 |

---

## 4. 후속 공정 재요구 매트릭스

### 4.1 LOA 생성 (`LoaGenerationService.validateApplicantProfile`)

검증 대상 3개 필드, 참조 소스, 불일치:

| 요구 필드 | 참조 소스 | 수집 경로 | 불일치 케이스 |
|---|---|---|---|
| `User.companyName` | `applicant.getCompanyName()` | JIT CompanyInfoModal (persistToProfile=true 시만) | CORPORATE + persistToProfile=false → FAIL. INDIVIDUAL → 수집 자체 안 함 → FAIL |
| `User.designation` | `applicant.getDesignation()` | 동상 | 동상 |
| `User.correspondenceAddress` | `applicant.getCorrespondenceAddress()` | ProfilePage | P1.4 Application 5-part는 전혀 읽지 않음 → FAIL |

### 4.2 LOA 스냅샷 (`LoaService.generateLoa`)

LOA 생성 시 `Application.recordLoaSnapshot()`은 `User.fullName`, `User.companyName`, `User.uen`, `User.designation`의 당시 값을 스냅샷한다. 스냅샷은 감사·법적 기록용이고, LOA PDF 렌더 자체는 여전히 User 최신값을 직접 사용한다.

### 4.3 결제 (`AdminPaymentService`)

결제 확인 시 사용자 데이터를 재요구하지 않는다. 위반 없음.

### 4.4 라이선스 발급 (`Application.issueLicense`)

`licenseNumber`, `expiryDate`만 파라미터로 받아 저장. 위반 없음.

### 4.5 갱신 (`ApplicationService.createApplication` RENEWAL 경로)

`renewalCompanyNameChanged`/`renewalAddressChanged` 불일치 시 400. 사용자 실수 감지 검증이어서 JIT 위반 아님. 단, 오류 메시지가 "Please check 'Company name has changed'" 수준이어서 UI에서도 명확히 안내해야 한다.

### 4.6 Concierge 대리 생성 (`ApplicationService.createOnBehalfOf`)

`applyCorporateJitCompanyInfo`를 동일하게 호출. V-3의 `persistToProfile=false` 위반이 이 경로에도 적용됨.

### 4.7 Admin 승인·반려, SLD 주문, 3개 신규 서비스

별도 사용자 입력 재요구 없음. 위반 없음.

---

## 5. JIT 위반 상세

### V-1 — Correspondence Address 이중 레이어: LOA가 User Layer만 읽음 [P0, BLOCK]

**시나리오**: 신청자가 Step 3에서 "Correspondence same as installation" 체크를 해제하고 5-part 주소를 입력했다. LOA 생성 시 `validateApplicantProfile`은 `User.correspondenceAddress`(Layer A)를 검사하며 `Application.correspondenceAddressBlock/...`(Layer B)는 전혀 읽지 않는다.

**기술적 원인**:
- `LoaGenerationService.java:380` — `applicant.getCorrespondenceAddress()` 단일 null 체크
- `LoaGenerationService.java:120–140` — LOA PDF에 `applicant.getCorrespondenceAddress()` 렌더
- `Application.correspondenceAddressBlock/...` → LOA 생성 경로에서 consumer 없음

**사용자 영향**: BLOCK — 주소를 정확히 입력했는데도 LOA 생성이 `INCOMPLETE_PROFILE` 400으로 실패하거나, 성공해도 LOA에 구버전 주소가 인쇄된다.

**제안 해결책**:
- 단기: `validateApplicantProfile(User, Application)` 시그니처 변경. Application.correspondenceAddressBlock이 null이 아니면 User 필드 없어도 통과.
- LOA 렌더도 Application Layer B 있으면 우선 사용, 없으면 User Layer A 폴백.

---

### V-2 — INDIVIDUAL 신청자에게 companyName 요구 [P0, BLOCK]

**시나리오**: 신청자가 `applicantType=INDIVIDUAL`로 신청. Admin/LEW가 LOA 생성 시 `validateApplicantProfile`이 applicantType 무관하게 `User.companyName`을 검사한다. INDIVIDUAL에게는 회사명을 물어본 경로 자체가 없다.

**기술적 원인**:
- `LoaGenerationService.java:374–376` — companyName null 체크. `application.getApplicantType()` 분기 없음
- `LoaGenerationService.java:107` — LOA PDF에 `applicant.getCompanyName()` 인쇄. INDIVIDUAL이면 blank여도 무방한 칸

**사용자 영향**: BLOCK — INDIVIDUAL 신청자는 LOA 생성 자체가 안 된다.

**제안 해결책**: V-1 수정과 함께 applicantType INDIVIDUAL이면 companyName/designation 검증 스킵.

---

### V-3 — CORPORATE + persistToProfile=false 시 companyInfo 소실 [P1, BLOCK]

**시나리오**: 신청자가 CompanyInfoModal에서 "Don't save to profile" 선택 후 제출. `persist=false` 분기는 `User.updateCompanyInfo()`를 호출하지 않는다. 이후 LOA 생성 시 `User.companyName`이 여전히 null → `INCOMPLETE_PROFILE`.

**기술적 원인**:
- `ApplicationService.java:883` — `if (persist && ...)` → persist=false면 User 업데이트 없음
- Application 엔티티에 companyInfo 임시 저장 필드 없음

**사용자 영향**: BLOCK — "내 프로필에 저장하기 싫었을 뿐인데 LOA 생성이 안 된다".

**제안 해결책**: Application에 `jitCompanyName`, `jitUen`, `jitDesignation` 3개 필드 추가. persist=false일 때 이 필드에 저장하고 LOA 검증 시 User Layer 없으면 Application JIT 필드 참조.

---

### V-4 — AdminApplicationInfo "Please ask the applicant to update their profile" [P1, ANNOYING]

**시나리오**: Admin/LEW가 신청 상세 화면을 볼 때 userCompanyName, userUen, userDesignation, userCorrespondenceAddress 중 하나라도 null이면 "Please ask the applicant to update their profile" 경고 배너가 표시된다.

**기술적 원인**:
- `AdminApplicationInfo.tsx:66` — 하드코딩 문구
- `AdminLoaSection.tsx:97–105` — 동일 경고 반복
- INDIVIDUAL 신청자도 동일 경고 발생. INDIVIDUAL에게는 companyName 수집 경로 자체가 없음

**사용자 영향**: ANNOYING — Admin/LEW가 신청자에게 "프로필 업데이트하세요"라고 연락해야 하는 불필요한 수동 프로세스. INDIVIDUAL의 경우 해결 방법 없음.

**제안 해결책**: applicantType 분기. INDIVIDUAL이면 companyName/designation 경고 제거. CORPORATE이면 명확한 행동 유도 문구.

---

### V-5 — Application 5-part correspondenceAddress가 LOA PDF에 미반영 [P1, ANNOYING]

**시나리오**: 신청자가 P1.4 Step 3에서 별도 우편 주소를 5-part로 입력했다. LOA PDF는 `User.correspondenceAddress`(단일 문자열)를 인쇄. 신규 입력된 5-part 주소가 LOA에 전혀 나타나지 않는다.

**기술적 원인**:
- `LoaGenerationService.java:120–140` — User Layer A만 사용
- Application Layer B는 LOA 렌더 경로에서 consumer 없음

**사용자 영향**: ANNOYING — 신청자 입력 주소와 LOA 인쇄 주소가 다를 수 있음. EMA 검토 시 불일치 지적 가능.

**제안 해결책**: V-1 수정 시 자동 해결.

---

### V-6 — P1.4 신규 필드 6개 dead data [P2, COSMETIC]

검색 결과: `installationName`, `premisesType`, `isRentalPremises`, `landlordEiLicenceNo`, `renewalCompanyNameChanged`, `renewalAddressChanged`, `installation_address_*` 5-part, `correspondence_address_*` 5-part가 모두 `ApplicationResponse`를 통해 프론트엔드에 노출되지만, LOA 생성, EMA 제출, Admin/LEW UI에서 실제로 사용되는 위치가 없다.

`landlordEiLicenceNo`는 "LEW 전용 응답에서만 제공 예정"이라는 주석이 있으나 해당 LEW 전용 응답이 아직 구현되지 않았다.

**사용자 영향**: COSMETIC — 수집·암호화 비용이 낭비됨. EMA ELISE 연동 계획이 실현되지 않으면 죽은 데이터.

**제안 해결책**: ELISE 연동 PR에서 이 필드들을 전송 payload에 포함. 미정이면 비고지.

---

### V-7 — LOA 스냅샷이 null companyName을 캡처 [P2, COSMETIC]

V-3 상황(persistToProfile=false)이면 LOA 생성 시 `application.recordLoaSnapshot(applicant.companyName, ...)` 호출에서 `companyName=null`이 스냅샷에 기록된다.

**제안 해결책**: V-3 수정 시 자동 해결.

---

### V-8 — Installation Address 이중 저장소: LOA가 단일 문자열만 사용 [P1, ANNOYING]

**시나리오**: `Application.address` (단일 문자열, 필수 수집)와 `Application.installationAddressBlock/...` (5-part, 선택)가 이중 저장소. LOA PDF는 `application.getAddress()` 단일만 사용. EMA ELISE는 5-part 분리를 요구하는데 우리는 5-part 수집 UI가 NewApplicationPage에 없다.

**기술적 원인**:
- `LoaGenerationService.java:93–100` — `application.getAddress()`, `application.getPostalCode()` 사용
- `NewApplicationPage.tsx:902–910` — Step 1에서 단일 `address` 필드만 입력 UI 존재. installationAddress 5-part 입력 UI 없음
- `CreateApplicationRequest.java:140–144` — 5-part 필드가 DTO에는 있으나 UI에서 전송 안 됨

**사용자 영향**: ANNOYING — EMA ELISE 최종 제출 시 Installation Address 5-part가 null이 되어 재입력 요청.

**제안 해결책**: OneMap API 파싱으로 단일 주소에서 5-part 자동 추출하거나, Step 1 주소 입력 후 5-part를 보조적으로 수집. 혹은 EMA 제출 시 LEW가 검토·보정 단계에서 채우도록 워크플로우 명확화.

---

### V-9 — EMA ELISE 미수집 필드 (미래 재입력 유발) [P2, ANNOYING]

EMA ELISE 양식에서 요구하지만 우리가 현재 수집하지 않는 필드들이다. EMA 제출 시 이 필드들을 별도로 입력해야 하므로 미래의 JIT 위반이 예정돼 있다.

| EMA 필드 | 우리 플랫폼 현황 | 재입력 유발 여부 |
|---|---|---|
| MSSL Account No (4-part) | `spAccountNo` 단일 문자열 | ✅ 형식 불일치로 재입력 필요 |
| Consumer Type | 미수집 | ✅ LEW/Admin이 입력해야 함 |
| Retailer | 미수집 | ✅ LEW/Admin이 입력해야 함 |
| Supply Voltage | 미수집 | ✅ LEW가 Certificate of Fitness에서 입력 (의도된 설계) |
| Approved Load (kVA) | `selectedKva`로 수집됨 | 부분 매핑 (LEW 확인 후 확정) |
| LEW Appointment Date | LOA 생성 일시로 유추 가능 | 자동 기록 가능 |
| Telephone Ext., Fax No. | `User.phone` 단일 | 분리 수집 없음 |
| Installation Name 변경 체크 (Renewal) | `renewalCompanyNameChanged`/`renewalAddressChanged`는 있으나 UEN 변경 체크박스 없음 | ✅ UEN 변경 시 서류 업로드 경로 없음 |

Declaration: EMA 원본 4개 체크박스 vs 우리 3-group 축약. 의미 매핑:

| 우리 Group | EMA Declaration | 포함 여부 |
|---|---|---|
| Group1: 정보 사실·허위기재 책임 | EMA 4항 (정보 정확성) | ✅ |
| Group2: SG 전기 규정 부합 | EMA 1항 (설비 운영 적합), EMA 2항 (SLD 비치) | 부분 포함 (SLD 비치 명시 없음) |
| Group3: LEW 주기 점검·EMA 보고 동의 | EMA 3항 (전기/전력 설비 운영 동의) | ✅ 대략 매핑 |

EMA 2항 "SLD 사본을 switchroom에 비치"가 우리 Group2에 명시되지 않음. 법적 위험 가능성.

---

## 6. Application 엔티티 스냅샷 필드 정합성 리포트

| 스냅샷 필드 | 쓰는 곳 | 읽는 곳 | 현재 상태 |
|---|---|---|---|
| `loaApplicantNameSnapshot` | `LoaService.generateLoa()` | 없음 (LOA PDF는 `User.fullName` 직접) | 감사 로그 전용 |
| `loaCompanyNameSnapshot` | 동상 | 없음 (LOA PDF는 `User.companyName` 직접) | 감사 로그 전용 |
| `loaUenSnapshot` | 동상 | 없음 | 감사 로그 전용 |
| `loaDesignationSnapshot` | 동상 | 없음 | 감사 로그 전용 |
| `loaSnapshotBackfilledAt` | V_04 마이그레이션 | 없음 | 이력 구분용 |

스냅샷은 법적 감사 기록 목적으로 설계되어 있으나, LOA PDF 렌더링은 여전히 `User` 최신값을 직접 읽는다. V-3 상황에서 스냅샷도 null이 되어 법적 증거 체인이 손상된다.

---

## 7. P1.4 신규 필드 consumer 분석

| 필드 | 저장 | `ApplicationResponse` | LOA 소비 | Admin/LEW UI | ELISE API | 판정 |
|---|---|---|---|---|---|---|
| `installationName` | ✅ | ✅ | ❌ | ❌ (미확인) | 미구현 | dead (ELISE 없으면) |
| `premisesType` | ✅ | ✅ | ❌ | ❌ | 미구현 | dead |
| `isRentalPremises` | ✅ | ✅ | ❌ | ❌ | 미구현 | dead |
| `landlordEiLicenceNo` | ✅(암호화) | 마스킹 ✅ | ❌ | ❌ (LEW 전용 미구현) | 미구현 | dead |
| `correspondenceAddress 5-part` | ✅(암호화) | ✅ | ❌ | ❌ | 미구현 | dead (V-1·V-5 위반 유발) |
| `installationAddress 5-part` | ✅(평문) | ✅ | ❌ | ❌ | 미구현 | dead (V-8 위반 유발) |
| `renewalCompanyNameChanged` | ✅ | ✅ | ❌ | ❌ | 미구현 | 생성 검증만, 이후 dead |
| `renewalAddressChanged` | ✅ | ✅ | ❌ | ❌ | 미구현 | 동상 |

---

## 8. 수정 권고 순서

### P0 (이번 배포 전 수정)

**V-1 + V-2 (묶음 수정)**: `LoaGenerationService.validateApplicantProfile(User)` → `validateApplicantProfile(User, Application)` 시그니처 변경.

1. `application.getApplicantType() == INDIVIDUAL`이면 companyName, designation 검증 스킵.
2. correspondenceAddress는 Application Layer B(`correspondenceAddressBlock != null`) OR User Layer A 중 하나라도 있으면 통과.
3. LOA PDF 렌더에서 correspondenceAddress를 Application 5-part 조합 우선, 없으면 User 단일 폴백.
4. `LoaService.generateLoa()`에서 `validateApplicantProfile(applicant, application)` 호출로 변경.

파일: `LoaGenerationService.java:371–390`, `:54`, `:120–140`

### P1

**V-3**: `applyCorporateJitCompanyInfo`에서 `persist=false`일 때 `Application` 빌더에 `jitCompanyName`, `jitUen`, `jitDesignation` 임시 저장 필드를 추가하거나, V-1 수정의 Application-first 로직이 포함되면 별도 필드 없이 LOA 검증 단계에서 JIT 스냅샷 참조.

파일: `ApplicationService.java:883–912`

**V-4**: `AdminApplicationInfo.tsx:53–72`, `AdminLoaSection.tsx:91–108` — applicantType 분기. INDIVIDUAL이면 companyName/designation 경고 제거.

**V-5**: V-1 수정 시 자동 해결.

**V-8**: 단기는 주석 추가로 "LEW가 ELISE 제출 단계에서 5-part 보정". 장기는 Step 1에서 OneMap API 파싱 연동.

### P2

**V-6**: ELISE 연동 PR에서 필드 활용. 미정이면 `@Comment("ELISE 연동 예정")` 등 명시.

**V-7**: V-3 수정 시 자동 해결.

**V-9**: MSSL Account No 형식을 4-part로 분리 수집하는 UI 개선. Consumer Type, Retailer는 LEW 검토 단계에 입력 필드 추가 검토.

---

## 9. 아키텍처 의견: 세 레이어의 역할 재정의

현재 코드베이스에는 신청자 신원 데이터를 위한 세 가지 레이어가 공존한다.

**Layer A — User 프로필** (`User.companyName`, `User.correspondenceAddress` 등): 사용자가 명시적으로 관리하는 "글로벌 프로필". 단일 사용자의 모든 신청에 공유된다. 문제는 이 값이 신청 제출 이후에 변경될 수 있고, JIT 경로(persistToProfile=false)에서는 아예 저장되지 않을 수 있다는 점이다.

**Layer B — Application 신청 시점 필드** (`Application.correspondenceAddressBlock/...`, `installationAddressBlock/...`): P1.4에서 신설된 신청별 5-part 주소. 신청 제출 당시의 정확한 값을 담을 수 있다. 그러나 LOA 생성 로직이 이 레이어를 무시하고 Layer A를 읽는다.

**Layer C — LOA 스냅샷** (`loaApplicantNameSnapshot` 등): LOA 생성 시점의 불변 증거 기록. 법적 무결성 목적이며 PDF 렌더에 사용되지 않는다.

**근본 문제**: LOA 검증과 PDF 렌더는 Layer A만 참조하는데, 신청자가 신청 당시 제공한 실제 데이터는 Layer B에 있다. 두 레이어 간 동기화 로직이 없다.

**재정의 제안**:

1. **Layer B를 "신청 당시 정보의 정본"으로 격상**: LOA 생성 시 Layer B(Application 필드)가 있으면 그것을 우선 사용한다. 없으면 Layer A(User 프로필)를 폴백으로 사용한다. 이 원칙을 `LoaGenerationService`에 명시적으로 구현한다. 단일 책임 원칙: LOA는 "신청 당시 데이터"를 인쇄해야 한다.

2. **Layer A를 "기본값 제공자"로 한정**: User 프로필은 신청 폼의 기본값을 채워주는 역할에 집중한다. LOA 생성의 필수 조건 검증에서 Layer A를 배제하거나 Layer B의 폴백으로만 사용한다.

3. **Layer C를 "Layer B 기반으로 채우기"**: 현재 스냅샷은 Layer A를 캡처하는데, Layer B가 있으면 Layer B를 스냅샷으로 채워야 한다. persistToProfile=false 경우에도 법적 기록이 정확해진다.

4. **Application 제출 시점 데이터 완결성**: Application 빌더에 "LOA에 필요한 신청자 정보" 전용 필드 그룹을 두고, 신청 제출 시 그 시점에 사용 가능한 모든 값을 한 번에 채운다. Layer A의 값은 신청 제출 시 이 그룹으로 복사(snapshot-at-submit)된다. 이후 LOA 생성, 스냅샷, PDF 렌더는 모두 이 그룹만 읽는다. JIT CompanyInfoModal의 companyInfo는 persist 여부와 무관하게 Application 그룹에는 항상 저장된다.

이 구조 변경은 "신청 당시 데이터"와 "현재 프로필 데이터"를 명확히 분리하여 JIT 위반이 구조적으로 재발하지 않도록 한다. EMA ELISE 연동 시에도 Application Layer B를 단일 소스로 사용하므로 재입력 없이 제출이 가능해진다.
