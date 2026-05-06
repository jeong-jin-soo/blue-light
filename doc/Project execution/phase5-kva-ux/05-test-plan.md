# Phase 5 테스트 계획서 — kVA 입력 UX 개선

**작성일**: 2026-04-17
**QA 담당**: tester agent
**대상 스펙**: `01-spec.md` AC-U1~U6, AC-S1~S4, AC-A1~A4, AC-P1~P3 (17개)
**대상 PR**: PR#1 (백엔드 API + 상태 가드), PR#2 (신청자 UI), PR#3 (LEW/ADMIN UI)

---

## 1. 테스트 전략

### 테스트 환경

| 레이어 | 도구 | 비고 |
|---|---|---|
| 백엔드 | JUnit 5 + Spring Boot Test (MockMvc) | PR#1 자동화 |
| 프론트엔드 | `npm run build` + 수동 검증 | PR#2·PR#3 |
| E2E | 수동 시나리오 (Playwright 미설치) | 로컬 8090+5174 |
| API 통합 | curl / Postman | 로컬 백엔드 기준 |

### 자동화 / 수동 배분

| 분류 | 자동화 | 수동 |
|---|---|---|
| kVA 상태 가드 | MockMvc (`@WithMockUser`) | — |
| PATCH /kva 권한 | MockMvc (역할별 4케이스) | — |
| 감사 로그 metadata | MockMvc + DB 쿼리 | — |
| UI 드롭다운·Tip | — | 수동 (브라우저) |
| 가격 포맷 전환 | — | 수동 |
| 인앱 알림 | MockMvc (DB 확인) | 벨 아이콘 UX |

---

## 2. AC 매트릭스 — 17개

| AC ID | 설명 요약 | TC ID | 자동화 |
|---|---|---|---|
| AC-U1 | 드롭다운 최상단 "I don't know" 옵션 + 기존 tier 유지 | TC-UI-01 | 수동 |
| AC-U2 | UNKNOWN 선택 → kvaStatus=UNKNOWN, selectedKva=45, "From S$350" 표시 | TC-UI-02 | 수동 |
| AC-U3 | tier 선택 → kvaStatus=CONFIRMED, kvaSource=USER_INPUT, tier 가격 표시 | TC-UI-04 | 수동 |
| AC-U4 | buildingType 있으면 Tip 한 줄 표시, pre-select 없음 | TC-UI-03 | 수동 |
| AC-U5 | buildingType 미선택 → 일반 안내만 | TC-UI-03 | 수동 |
| AC-U6 | Tip 접이식 상세 2개, 모바일 기본 접힘 | TC-UI-03 | 수동 |
| AC-S1 | POST UNKNOWN → selected_kva=45, kva_status=UNKNOWN, kva_source=NULL, quote=S$350 | TC-STATE-01 | MockMvc |
| AC-S2 | POST tier 직접 선택 → kva_status=CONFIRMED, kva_source=USER_INPUT, confirmedBy=NULL | TC-STATE-01 | MockMvc |
| AC-S3 | kva_status=UNKNOWN 상태에서 결제 API → 400 KVA_NOT_CONFIRMED, 상태 전이 차단 | TC-STATE-02 | MockMvc |
| AC-S4 | LEW 확정 성공 → quote_amount 재계산, Application status 유지 | TC-STATE-04 | MockMvc |
| AC-A1 | 할당 LEW/ADMIN PATCH /kva → 200, CONFIRMED 응답 | TC-API-01 | MockMvc |
| AC-A2 | 미할당 LEW PATCH /kva → 403 FORBIDDEN | TC-API-02 | MockMvc |
| AC-A3 | 허용 tier 외 값(예: 250) → 400 INVALID_KVA_TIER, 롤백 | TC-API-03 | MockMvc |
| AC-A4 | 확정 성공 → 감사 로그 KVA_CONFIRMED_BY_LEW + 인앱 알림 KVA_CONFIRMED | TC-API-01 | MockMvc |
| AC-P1 | 재확정 → 409 KVA_ALREADY_CONFIRMED; ADMIN ?force=true → 성공, previous값 감사 로그 | TC-API-05 | MockMvc |
| AC-P2 | Phase 3 DocumentRequest 재사용, Phase 5 API 변경 없음 | TC-REGRESSION-01 | 수동 |
| AC-P3 | GET /admin/applications?kvaStatus=UNKNOWN 필터 동작 | TC-API-01 | MockMvc |

---

## 3. 시나리오 기반 테스트 케이스

### 신청자 UI (TC-UI-01~05)

**TC-UI-01** (수동 / AC-U1)
```
1. 신청자 로그인 → NewApplicationPage Step 2 진입
2. kVA 드롭다운 클릭
기대: 최상단에 "— I don't know — let LEW confirm me later" (이탤릭, 회색)
      divider 이후 45/100/200/300/500 kVA 옵션 순서대로 노출
      기존 tier 옵션 제거·순서 변경 없음
```

**TC-UI-02** (수동 / AC-U2)
```
1. "I don't know" 선택
기대:
  - 드롭다운 트리거에도 이탤릭 "I don't know…" 표시
  - 가격 카드: "From S$350" (primary 톤) + "Final price will be set after…" 보조 문구
  - 가격 분석표: opacity-50, pointer-events-none (회색화, 삭제 아님)
  - Next 버튼: enabled (제출 가능)
2. 제출 payload 확인: kvaStatus="UNKNOWN", selectedKva=45
```

**TC-UI-03** (수동 / AC-U4, AC-U5, AC-U6)
```
시나리오 A (buildingType=HDB_FLAT):
  - Step 1에서 HDB Flat 선택 → Step 2 진입
  기대: Tip 박스 내 "HDB flats are typically 45 kVA." 한 줄 표시
        드롭다운 pre-select 없음 (기본값 placeholder 유지)

시나리오 B (buildingType 미선택):
  기대: Tip 박스에 "Check your SP bill or main breaker nameplate for kVA" 일반 안내만
        buildingType 한 줄 숨김

시나리오 C (Tip 접이식 / AC-U6):
  - "How to read your SP bill" 클릭 → 접이식 펼침 확인
  - "Where is the main breaker nameplate?" 클릭 → 펼침
  - 모바일(640px 미만) 시뮬레이션: 두 항목 기본 접힘 확인
```

**TC-UI-04** (수동 / AC-U3)
```
1. "100 kVA" tier 선택
기대:
  - 가격 카드: "S$650" (tier 매핑값, primary 포맷)
  - 가격 분석표: 정상 표시, 100 kVA 강조
  - Next 버튼: enabled
2. 제출 payload: kvaStatus="CONFIRMED", selectedKva=100
```

**TC-UI-05** (수동 / AC-S3 UX 연계)
```
1. kvaStatus=UNKNOWN 신청 제출 완료 → 신청 상세 접근
기대:
  - kVA 섹션: "kVA pending LEW review" amber pill 표시
  - "What happens next" info box 3단계 표시
  - "Pay now" 버튼: disabled + 하단 helper text 항상 표시 (tooltip 아님)
  - helper: "Your kVA needs to be confirmed by your LEW before payment."
2. 결제 URL 직접 접근
기대: redirect → 신청 상세 + banner "Payment will open once your LEW confirms the kVA."
```

---

### 상태 & 가격 (TC-STATE-01~04)

**TC-STATE-01** (MockMvc / AC-S1, AC-S2)
```java
// 케이스 A: UNKNOWN 제출
POST /api/applications
Body: { "selectedKva": 45, "kvaStatus": "UNKNOWN", ... }
기대: 201
DB: kva_status='UNKNOWN', selected_kva=45, kva_source=NULL,
    quote_amount=350.00 (45kVA 기준 placeholder)
    kva_confirmed_by=NULL, kva_confirmed_at=NULL

// 케이스 B: tier 직접 선택
POST /api/applications
Body: { "selectedKva": 100, "kvaStatus": "CONFIRMED", ... }
기대: 201
DB: kva_status='CONFIRMED', kva_source='USER_INPUT',
    kva_confirmed_by=NULL, kva_confirmed_at=NULL

// 케이스 C: kvaStatus 누락 (구버전 클라이언트 하위호환)
POST /api/applications
Body: { "selectedKva": 200 }  // kvaStatus 필드 없음
기대: 201, kva_status='CONFIRMED', kva_source='USER_INPUT'

// 케이스 D: UNKNOWN 시 클라이언트가 selectedKva=200 전송해도 서버 강제 45
POST /api/applications
Body: { "selectedKva": 200, "kvaStatus": "UNKNOWN" }
기대: 201, DB: selected_kva=45 (서버 강제 적용)
```

**TC-STATE-02** (MockMvc / AC-S3)
```java
// 전제: kva_status='UNKNOWN', status='PENDING_REVIEW' 신청
POST /api/applications/{id}/payment
기대: 400, errorCode: "KVA_NOT_CONFIRMED"
      메시지: "LEW가 kVA를 확정하면 결제가 활성화됩니다"
DB: application.status = 'PENDING_REVIEW' 유지 (전이 없음)
```

**TC-STATE-03** (MockMvc / AC-S2 회귀)
```java
// 기존 kva_status='CONFIRMED' 신청의 결제 흐름 영향 없음 (회귀)
// 전제: kva_status='CONFIRMED', status='PENDING_REVIEW' 신청
POST /api/applications/{id}/payment
기대: 기존 동작 그대로 (200 또는 상태 전이 성공)
      KVA_NOT_CONFIRMED 에러 미발생
```

**TC-STATE-04** (MockMvc / AC-S4)
```java
// LEW 확정 후 quote_amount 재계산, status 유지
// 전제: kva_status='UNKNOWN', selected_kva=45, quote_amount=350.00, status='PENDING_REVIEW'
PATCH /api/admin/applications/{id}/kva (할당 LEW 토큰)
Body: { "selectedKva": 100, "note": "SP 고지서 확인 결과 100 kVA 계약" }
기대: 200
      kva_status='CONFIRMED', selected_kva=100, quote_amount=650.00
      status='PENDING_REVIEW' 유지 (자동 전이 없음)
      kva_confirmed_by=lewUserSeq, kva_confirmed_at NOT NULL
```

---

### LEW 확정 API (TC-API-01~05)

**TC-API-01** (MockMvc / AC-A1, AC-A4, AC-P3)
```java
// 할당 LEW 확정 성공
PATCH /api/admin/applications/{id}/kva (할당 LEW 토큰)
Body: { "selectedKva": 100, "note": "SP 고지서 100kVA 확인" }
기대: 200
Response: { kvaStatus:"CONFIRMED", kvaSource:"LEW_VERIFIED", selectedKva:100,
            quoteAmount:650.00, kvaConfirmedBy:lewSeq, kvaConfirmedAt:NotNull }
DB: audit_log.event_type='KVA_CONFIRMED_BY_LEW'
    metadata: { actor_user_seq, application_seq, previous_kva:45,
                previous_status:'UNKNOWN', new_kva:100, note }
DB: notification.type='KVA_CONFIRMED', recipient=신청자 userSeq

// 목록 필터 (AC-P3)
GET /api/admin/applications?kvaStatus=UNKNOWN (ADMIN 토큰)
기대: 200, 응답에 kva_status=UNKNOWN인 신청만 포함
     kvaStatus 파라미터 미전송 시 전체 목록 반환 (기존 동작 유지)
```

**TC-API-02** (MockMvc / AC-A2)
```java
// 미할당 LEW → 403
PATCH /api/admin/applications/{id}/kva (미할당 LEW 토큰)
Body: { "selectedKva": 100, "note": "..." }
기대: 403, errorCode: "FORBIDDEN"
DB: kva_status 변경 없음

// ADMIN은 모든 신청에 허용
PATCH /api/admin/applications/{id}/kva (ADMIN 토큰)
기대: 200 (할당 여부 무관)
```

**TC-API-03** (MockMvc / AC-A3)
```java
// 허용 tier 외 값
PATCH /api/admin/applications/{id}/kva (할당 LEW 토큰)
Body: { "selectedKva": 250, "note": "테스트" }
기대: 400, errorCode: "INVALID_KVA_TIER"
DB: 트랜잭션 롤백, kva_status 변경 없음

// APPLICANT가 확정 시도
PATCH /api/admin/applications/{id}/kva (APPLICANT 토큰)
기대: 403
```

**TC-API-04** (MockMvc / AC-A2 — ADMIN override)
```java
// ADMIN ?force=true로 미할당 신청 확정
PATCH /api/admin/applications/{id}/kva?force=true (ADMIN 토큰)
기대: 200 (기존에 할당된 LEW 없어도 성공)
```

**TC-API-05** (MockMvc / AC-P1)
```java
// 이미 CONFIRMED인 신청 재확정
// 전제: kva_status='CONFIRMED', selected_kva=100
PATCH /api/admin/applications/{id}/kva (할당 LEW 토큰)
Body: { "selectedKva": 200, "note": "재확정 시도" }
기대: 409, errorCode: "KVA_ALREADY_CONFIRMED"

// ADMIN ?force=true 오버라이드
PATCH /api/admin/applications/{id}/kva?force=true (ADMIN 토큰)
Body: { "selectedKva": 200, "note": "관리자 재확정 — 실측값 반영" }
기대: 200
DB: audit_log.metadata.previous_kva=100, previous_status='CONFIRMED'
    → 이전값이 감사 로그에 기록됨
```

---

### 권한 매트릭스 (TC-AUTH-01~03)

**TC-AUTH-01** (MockMvc / AC-A1, AC-A2)

| 호출자 | assigned? | 기대 응답 | errorCode |
|---|---|---|---|
| ADMIN | 무관 | 200 | — |
| LEW (할당됨) | Y | 200 | — |
| LEW (미할당) | N | 403 | FORBIDDEN |
| APPLICANT | 무관 | 403 | FORBIDDEN |
| 미인증 | — | 401 | UNAUTHORIZED |

```java
// 파라미터화 테스트: 위 5케이스 @ParameterizedTest
@WithMockUser(roles={"LEW"})  // assigned/unassigned 분기
```

**TC-AUTH-02** (MockMvc)
```java
// PAID 이후 kVA 변경 차단
// 전제: application.status='PAID'
PATCH /api/admin/applications/{id}/kva (ADMIN 토큰)
기대: 400 또는 409 (status 기반 가드)
     kva_status 변경 없음
```

**TC-AUTH-03** (MockMvc)
```java
// COMPLETED 이후 차단
// 전제: application.status='COMPLETED'
PATCH /api/admin/applications/{id}/kva (ADMIN 토큰)
기대: 400 또는 409
```

---

## 4. 경계 사례 & 회귀

**TC-MIGRATION-01** (수동 / AC-S2 — 기존 레코드)
```sql
-- 마이그레이션 실행 후 검증 쿼리
SELECT COUNT(*) FROM applications
WHERE kva_status IS NULL
   OR (kva_status = 'CONFIRMED' AND kva_source IS NULL);
-- 기대: 0건 (모든 기존 레코드에 백필 완료)

SELECT COUNT(*) FROM applications
WHERE kva_status = 'CONFIRMED' AND kva_source = 'USER_INPUT';
-- 기대: 기존 레코드 수와 일치
```

**TC-REGRESSION-01** (수동 / AC-P2 — Phase 3 간섭 없음)
```
1. UNKNOWN 신청에 LEW가 Phase 3 DocumentRequest 생성
   (SP_ACCOUNT_PDF + MAIN_BREAKER_PHOTO 배치 요청)
기대: Phase 3 생성 성공, Phase 5 API 변경 없음
2. 신청자 업로드 완료 → LEW PATCH /kva 확정
기대: 두 흐름 독립 동작, 상호 간섭 없음
3. DocumentRequest 목록 카드 정상 렌더 확인 (Phase 3 회귀)
```

**TC-REGRESSION-02** (수동 — 법인 JIT 간섭)
```
Phase 2 법인 JIT 모달 + UNKNOWN kVA 동시 보유 신청
기대:
  - LEW 대시보드에 "kVA pending" + "company docs needed" 두 배지 동시 노출
  - 각 플로우 독립 처리, 상호 block 없음
  - Phase 2 JIT 모달 정상 동작 확인
```

---

## 5. E2E 수동 시나리오

### 시나리오 1 — Happy Path (UNKNOWN → LEW 확정 → 결제)

전제: 로컬 백엔드(8090) + 프론트(5174), PENDING_REVIEW 신청 + LEW 할당 완료

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | 신청자 로그인 → Step 2 kVA 드롭다운 | "I don't know" 옵션 최상단, 이탤릭 |
| 2 | "I don't know" 선택 → Next | 가격 "From S$350" 표시, Next enabled |
| 3 | 제출 완료 | 신청 상세 kVA섹션 amber pill "kVA pending LEW review" |
| 4 | "Pay now" 버튼 확인 | disabled + helper text 상시 노출 |
| 5 | LEW 로그인 → 신청 상세 | kVA섹션 UNKNOWN 배지 + "Confirm kVA" primary 버튼 |
| 6 | "Confirm kVA" 클릭 → KvaConfirmModal | tier 선택 + note 입력 (min 10자) |
| 7 | note 9자 입력 → "Confirm & notify" 클릭 | submit disabled 유지 |
| 8 | 100 kVA + note 15자 → "Confirm & notify" | 토스트 "kVA confirmed — Applicant notified." |
| 9 | kVA섹션 → CONFIRMED 상태 자동 전환 | "100 kVA · Confirmed by LEW …" 표시 |
| 10 | 신청자 로그인 → 벨 아이콘 | KVA_CONFIRMED 알림 수신, body에 kva=100, amount=S$650 |
| 11 | 알림 클릭 → 신청 상세 | banner "Your LEW confirmed 100 kVA. Price updated to S$650." |
| 12 | "Pay now" 버튼 확인 | enabled (kvaStatus=CONFIRMED) |

총 예상 소요: 15분

### 시나리오 2 — 기존 CONFIRMED 신청 결제 회귀

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | 기존 tier 직접 선택 신청(kvaStatus=CONFIRMED) 접근 | kVA섹션 배지 없음 (기본 상태) |
| 2 | "Pay now" 버튼 상태 확인 | enabled (Phase 5 이전과 동일) |
| 3 | 결제 API 호출 | KVA_NOT_CONFIRMED 에러 미발생 |

### 시나리오 3 — LEW 확정 후 가격 상승 안내

| 단계 | 액션 | 체크포인트 |
|---|---|---|
| 1 | UNKNOWN 신청 (placeholder S$350) | 신청자 상세 "From S$350" 확인 |
| 2 | LEW가 300 kVA로 확정 | quoteAmount 재계산 S$1,050 (예시) |
| 3 | 신청자 상세 새로고침 | banner에 "Price updated to S$1,050" + "cancel at no cost before paying" 문구 |
| 4 | 신청 취소 가능 확인 | 기존 soft delete 흐름 정상 동작 |

---

## 6. 동시성 테스트

**TC-CONCURRENCY-01**
```java
// 두 LEW가 동시에 동일 신청에 PATCH /kva 시도
// 전제: 두 LEW 모두 해당 신청에 할당됨 (또는 ADMIN 두 명)
CompletableFuture<Response> f1 = CompletableFuture.supplyAsync(() -> patch(lewToken1, 100kVA));
CompletableFuture<Response> f2 = CompletableFuture.supplyAsync(() -> patch(lewToken2, 200kVA));

기대: 한쪽만 200, 나머지 409 KVA_ALREADY_CONFIRMED
DB: selected_kva 단일값만 기록, audit_log 이중 기록 없음
Phase 3 패턴 동일 (낙관적 락 또는 DB 트랜잭션 격리)
```

---

## 7. 보안 테스트

**TC-SEC-01** (MockMvc)
```java
// 신청자가 직접 PATCH /api/admin/applications/{id}/kva로 kvaStatus=CONFIRMED 설정 시도
PATCH /api/admin/applications/{id}/kva (APPLICANT 토큰)
기대: 403 (컨트롤러 @PreAuthorize 가드)
DB: kva_status 변경 없음
```

**TC-SEC-02** (수동)
```
1. 브라우저 개발자 도구로 "Pay now" 버튼 disabled 속성 제거
2. 클릭 → 결제 API 호출
기대: 서버 측 PaymentService.initiate()에서 kvaStatus 이중 가드 발동
      400 KVA_NOT_CONFIRMED 반환 (FE 우회 차단)
```

---

## 8. 수동 검증 체크리스트

### PR#1 머지 전 (백엔드)
- [ ] `./gradlew compileJava` 오류 없음
- [ ] `KvaStatus`, `KvaSource` enum 정의 및 Application 엔티티 4필드 컴파일 확인
- [ ] schema.sql + migration/V_01 SQL 문법 오류 없음
- [ ] TC-STATE-01 케이스 A~D 전체 통과 (UNKNOWN 강제, 하위호환)
- [ ] TC-STATE-02: KVA_NOT_CONFIRMED 400 반환, DB 상태 전이 없음
- [ ] TC-STATE-03: 기존 CONFIRMED 결제 흐름 회귀 없음
- [ ] TC-API-01~05 전체 MockMvc 통과
- [ ] TC-AUTH-01 권한 매트릭스 5케이스 통과
- [ ] TC-AUTH-02~03 PAID/COMPLETED 이후 차단 확인
- [ ] 감사 로그 metadata 구조 DB 직접 확인 (`SELECT * FROM audit_log WHERE event_type='KVA_CONFIRMED_BY_LEW'`)
- [ ] 인앱 알림 DB: notification.type='KVA_CONFIRMED', recipient=신청자 userSeq
- [ ] GET /admin/applications?kvaStatus=UNKNOWN 필터 동작
- [ ] TC-SEC-01 APPLICANT 권한 차단 확인

### PR#2 머지 전 (신청자 UI)
- [ ] `npm run build` TypeScript 오류 없음
- [ ] TC-UI-01: 드롭다운 최상단 "I don't know" 이탤릭, 회색 + divider
- [ ] TC-UI-02: UNKNOWN 선택 → "From S$350" + 보조 문구 + 분석표 회색화
- [ ] TC-UI-03 A~C: Tip buildingType 매핑 + 접이식 2개 + 모바일 기본 접힘
- [ ] TC-UI-04: tier 선택 → tier 가격 표시 + Next enabled
- [ ] TC-UI-05: 신청 상세 amber pill + "Pay now" disabled + helper text 상시 노출
- [ ] TC-SEC-02: FE 우회 시 서버 가드 400 확인
- [ ] 모바일(640px) 시뮬레이션: Tip 박스 드롭다운 아래 수직 배치

### PR#3 머지 전 (LEW/ADMIN UI)
- [ ] `npm run build` 오류 없음
- [ ] LEW 상세: UNKNOWN 시 "Confirm kVA" primary 버튼 노출, CONFIRMED 시 미노출
- [ ] KvaConfirmModal: note 9자 → submit disabled; 10자 → enabled
- [ ] 모달 헤더 확인: ADMIN override 시 "Overriding existing confirmation" 배너 노출
- [ ] 확정 성공 → 토스트 + kVA섹션 CONFIRMED 자동 전환
- [ ] ADMIN만 "Override" 버튼 노출 (LEW 세션에서 미노출)
- [ ] `/admin/applications` 목록: "kVA pending only" 토글 + kvaStatus=UNKNOWN 필터
- [ ] 목록 kVA 컬럼: UNKNOWN → "— pending" 이탤릭, CONFIRMED → "100 kVA + source 태그"
- [ ] TC-REGRESSION-01: Phase 3 DocumentRequest 흐름 간섭 없음

---

## 9. PR별 실행 순서

### PR#1 머지 전 — 백엔드 API + 상태 가드
1. `./gradlew compileJava` 컴파일 확인
2. TC-STATE-01~04 (MockMvc)
3. TC-API-01~05 (MockMvc)
4. TC-AUTH-01~03 (MockMvc)
5. TC-SEC-01 (MockMvc)
6. TC-CONCURRENCY-01
7. TC-MIGRATION-01 (DB 쿼리 검증)
8. 수동 체크리스트 PR#1 전체

### PR#2 머지 전 — 신청자 UI
1. `npm run build` TypeScript 빌드 확인
2. TC-UI-01~05 (수동)
3. TC-STATE-03 회귀: 기존 CONFIRMED 신청 결제 흐름
4. TC-SEC-02 FE 우회 시도
5. 수동 체크리스트 PR#2 전체

### PR#3 머지 전 — LEW/ADMIN UI + E2E
1. `npm run build` 오류 없음
2. LEW UI 수동 (KvaConfirmModal, 목록 필터, 배지)
3. TC-REGRESSION-01 Phase 3 간섭 없음
4. TC-REGRESSION-02 법인 JIT 간섭 없음
5. E2E 시나리오 1~3 전체 실행
6. 수동 체크리스트 PR#3 전체

---

## 10. 배포 후 스모크 테스트 (개발서버, 10분 이내)

1. 신청자 계정(`lew@bluelight.sg` 환경의 신청자) → Step 2 드롭다운 "I don't know" 옵션 최상단 확인
2. "I don't know" 선택 → "From S$350" 가격 카드 표시, Next 활성 확인
3. UNKNOWN 신청 제출 → 신청 상세 amber pill "kVA pending LEW review" 확인
4. "Pay now" disabled + helper text 상시 노출 확인
5. LEW 계정(`lew@bluelight.sg`) → 신청 상세 kVA섹션 "Confirm kVA" 버튼 노출
6. KvaConfirmModal 오픈 → 100 kVA + note 입력 → "Confirm & notify" 클릭
7. 토스트 "kVA confirmed — Applicant notified." + kVA섹션 CONFIRMED 전환 확인
8. 신청자 계정 전환 → 벨 아이콘 KVA_CONFIRMED 알림 수신 확인
9. 신청 상세 "Pay now" enabled 전환 확인
10. `GET /api/admin/applications?kvaStatus=UNKNOWN` 필터 응답 정상 확인 (curl)
11. DB: `SELECT COUNT(*) FROM applications WHERE kva_status IS NULL` → 0 확인
12. 감사 로그: `SELECT event_type, metadata FROM audit_log WHERE event_type='KVA_CONFIRMED_BY_LEW' ORDER BY created_at DESC LIMIT 1` — metadata 구조 확인

---

## 11. 버그 발견 시 재현 절차 템플릿

```
[BUG] {버그 제목}

## 환경
- OS / 브라우저
- 백엔드: localhost:8090 (branch: ...)
- 프론트엔드: localhost:5174 (branch: ...)

## 심각도
CRITICAL / HIGH / MEDIUM / LOW

## 관련 AC
AC-{ID}

## 재현 단계
1. {정확한 액션}
2. {정확한 액션}

## 기대 결과
{스펙 기준 동작}

## 실제 결과
{실제 발생, 스크린샷 첨부}

## 요청/응답 로그
curl -X PATCH http://localhost:8090/api/admin/applications/{id}/kva \
  -H 'Authorization: Bearer ...' \
  -H 'Content-Type: application/json' \
  -d '{"selectedKva":100,"note":"..."}'
Response: {상태코드, body}
```
