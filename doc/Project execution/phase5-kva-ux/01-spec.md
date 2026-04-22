# Phase 5 — kVA 입력 UX 개선 (옵션 A: 최소 변경)

**작성일**: 2026-04-19
**범위**: NewApplicationPage Step 2의 kVA 선택 UX에 "I don't know — let LEW confirm" 경로를 정식화하고, LEW가 이후 kVA를 확정하는 플로우를 API·상태 전이·감사 로그로 구조화한다.
**원칙**: Phase 1(신청 단순화) → Phase 2(서류 인프라) → Phase 3(LEW 구조화 워크플로) 연장선. **Just-in-Time Disclosure** + **Role-based Deferral**을 kVA 영역에 적용.
**선행 배포**: Phase 1~4 전부 머지 완료 가정.

---

## 1. 사용자 스토리 & 측정 지표

### User Stories
- **US-1 (HDB 거주자)**: kVA 개념을 처음 들어본다. "잘 모르겠음"을 선택해 신청을 중단 없이 완료하고, LEW가 확정한 값으로 결제하고 싶다.
- **US-2 (Shophouse 소상공인)**: SP 고지서는 임대인 명의라 kVA 확인이 어렵다. LEW가 Phase 3의 DocumentRequest로 SP Account PDF 또는 Main Breaker Photo를 요청하면 업로드하고, 확정 결과를 통보받고 싶다.
- **US-3 (총무/비기술직 법인 담당자)**: 임의 tier를 찍었다가 재협의·환불이 벌어지는 것을 피하고 싶다. "Not sure"를 명시적으로 고를 수 있어야 안전하다.
- **US-4 (LEW)**: "kVA 미확정" 신청을 대시보드에서 필터링해, 업로드된 근거 서류를 검토하고 한 번의 액션으로 확정하고 싶다. 확정 시 가격 재산정·상태 전이가 자동으로 이어지길 기대한다.
- **US-5 (LEW/감사)**: 누가 언제 어떤 값으로 kVA를 확정했는지 감사 로그로 추적 가능해야 한다.

### 측정 지표 (배포 후 4주)
| 지표 | 베이스라인 | 목표 |
|---|---|---|
| "I don't know" 선택률 (Step 2 진입 대비) | — | 계측 확보 (참고지표) |
| Step 2 이탈률 | 측정 필요 | -10%p |
| 신청 제출 완료율 (Step 2 진입→제출) | 측정 필요 | +8%p |
| LEW kVA 확정 SLA (제출→CONFIRMED median) | — | ≤ 1 business day |
| 결제 후 kVA 정정에 따른 환불·재산정 건수 | 현재 수기 집계 | -70% |

---

## 2. 수용 기준 (Acceptance Criteria) — 17개

### A. 신청자 UI (6개)
1. **AC-U1** GIVEN Step 2의 kVA 드롭다운, WHEN 렌더링, THEN 최상단에 `"I don't know — let LEW confirm"` 옵션이 존재하고 기존 tier 옵션(45/100/200/300/500)은 그대로 유지된다.
2. **AC-U2** GIVEN 사용자가 "I don't know"를 선택, THEN 폼 state는 `kvaStatus='UNKNOWN'`, `selectedKva=45`(placeholder)로 설정되고, 가격 영역은 `"From S$350 (final price after LEW confirmation)"` 포맷으로 표시된다.
3. **AC-U3** GIVEN 사용자가 기존 tier(45/100/…)를 선택, THEN `kvaStatus='CONFIRMED'`, `kvaSource='USER_INPUT'`으로 즉시 설정되고 가격은 tier 매핑값 그대로(기존 포맷) 표시된다.
4. **AC-U4** GIVEN Step 1에서 `buildingType`이 선택됨, WHEN Step 2 진입, THEN kVA 드롭다운 하단에 **Tip 문구** `"Tip: HDB flats are typically 45 kVA"`(또는 building type별 매핑)가 **텍스트로만** 표시되며 **어떤 tier도 pre-select되지 않는다**.
5. **AC-U5** GIVEN `buildingType`이 미선택, WHEN Step 2 진입, THEN Tip 영역에는 일반 안내(`"Check your SP bill or main breaker nameplate for kVA"`)만 표시된다.
6. **AC-U6** GIVEN Tip 박스, WHEN 클릭/펼치기, THEN "SP 고지서에서 확인하는 법", "메인 차단기 nameplate 확인법" 2개 하위 설명이 접이식으로 노출된다. 모바일에서 기본 접힘.

### B. 상태·가격 (4개)
7. **AC-S1** GIVEN `POST /api/applications` body에 `kvaStatus='UNKNOWN'`, THEN 서버는 `selected_kva=45`, `kva_status='UNKNOWN'`, `kva_source=NULL`로 저장하고 `quote_amount`는 45 kVA 기준 placeholder 금액으로 계산한다.
8. **AC-S2** GIVEN `POST /api/applications` body에 tier 직접 선택, THEN `kva_status='CONFIRMED'`, `kva_source='USER_INPUT'`, `kva_confirmed_by=NULL`, `kva_confirmed_at=NULL`로 저장된다. (LEW가 아닌 본인 선택이므로 confirmedBy는 기록하지 않음)
9. **AC-S3** GIVEN `status='PENDING_REVIEW'` + `kva_status='UNKNOWN'`, WHEN 결제 API(`POST /api/applications/{id}/payment`) 호출, THEN 400 `KVA_NOT_CONFIRMED`로 거부되고 상태 전이(`PENDING_PAYMENT`)는 발생하지 않는다.
10. **AC-S4** GIVEN LEW가 kVA 확정 성공, THEN `quote_amount`가 새로운 tier 기준으로 재계산되어 갱신되고, Application 상태는 기존 상태(`PENDING_REVIEW` 유지)를 그대로 둔다. 결제 활성화는 `kva_status='CONFIRMED'` 조건만으로 충분(상태 자동 전이 없음).

### C. LEW 확정 API (4개)
11. **AC-A1** GIVEN `PATCH /api/admin/applications/{id}/kva` body `{selectedKva, note}`, WHEN 호출자가 해당 Application의 assigned LEW 또는 ADMIN, THEN 200 응답으로 `{kvaStatus:'CONFIRMED', selectedKva, kvaSource:'LEW_VERIFIED', kvaConfirmedBy, kvaConfirmedAt, quoteAmount}`를 반환한다.
12. **AC-A2** GIVEN 동일 API 호출자가 **할당되지 않은 LEW**, THEN 403 `FORBIDDEN`. ADMIN은 모든 Application에 허용.
13. **AC-A3** GIVEN `selectedKva`가 허용 tier(45/100/200/300/500) 이외 값, THEN 400 `INVALID_KVA_TIER`. 트랜잭션 롤백.
14. **AC-A4** GIVEN 확정 성공, THEN 감사 로그 `KVA_CONFIRMED_BY_LEW`가 `{actor_user_seq, application_seq, previous_kva, previous_status, new_kva, note}` metadata와 함께 기록되고, 신청자에게 인앱 알림 `KVA_CONFIRMED`가 발송된다 (이메일은 범위 외).

### D. 권한·중복 확정 (3개)
15. **AC-P1** GIVEN `kva_status='CONFIRMED'`인 Application에 대한 재확정 요청(PATCH /kva), THEN 409 `KVA_ALREADY_CONFIRMED`. ADMIN은 `?force=true` 쿼리파라미터로 덮어쓸 수 있고, 이 경우 감사 로그의 `previous_kva/previous_status`에 기존값이 기록된다.
16. **AC-P2** GIVEN Phase 3 `DocumentRequest` 인프라, WHEN LEW가 SP_ACCOUNT_PDF 또는 MAIN_BREAKER_PHOTO를 요청, THEN 기존 Phase 3 흐름만으로 동작하며 Phase 5에서는 별도 API/DTO 변경 없음(재사용).
17. **AC-P3** GIVEN `/admin/applications` 목록 필터, THEN `kvaStatus=UNKNOWN` 필터가 기존 쿼리 파라미터에 추가되어 LEW가 "kVA 확정 대기" 신청을 필터링할 수 있다.

---

## 3. 데이터 모델 변경

### 3-1. `applications` 테이블 — 4개 컬럼 추가

```sql
ALTER TABLE applications
  ADD COLUMN kva_status        VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'
    COMMENT 'UNKNOWN | CONFIRMED',
  ADD COLUMN kva_source        VARCHAR(20) NULL
    COMMENT 'USER_INPUT | LEW_VERIFIED',
  ADD COLUMN kva_confirmed_by  BIGINT      NULL,
  ADD COLUMN kva_confirmed_at  DATETIME(6) NULL,
  ADD KEY idx_applications_kva_status (kva_status),
  ADD CONSTRAINT fk_applications_kva_confirmed_by
    FOREIGN KEY (kva_confirmed_by) REFERENCES users(user_seq);
```

- **제안서와 달라진 점**: `ESTIMATED` enum 값 제거 → `UNKNOWN | CONFIRMED` 2개만. Smart Default pre-select를 채택하지 않았으므로 `SYSTEM_SUGGESTION` source도 불필요 → `USER_INPUT | LEW_VERIFIED` 2개만.
- **기존 `selected_kva` 컬럼**: NOT NULL 유지. `UNKNOWN` 상태에서도 45로 채워 하방 안전(placeholder) 보장.
- **기본값 `CONFIRMED`**: 기존 레코드 전수에 대해 사용자가 직접 tier를 선택한 상태로 간주 (백필과 일관).

### 3-2. schema.sql 수정 위치
- `blue-light-backend/src/main/resources/schema.sql` — `applications` 테이블 정의에 4개 컬럼 + 인덱스 + FK 추가 (line 43 근처 `selected_kva` 하단).
- 마이그레이션 파일: `doc/Project execution/phase5-kva-ux/migration/V_01_add_kva_status_columns.sql`.

### 3-3. Java Enum
```java
// com.bluelight.backend.domain.application.KvaStatus
public enum KvaStatus { UNKNOWN, CONFIRMED }

// com.bluelight.backend.domain.application.KvaSource
public enum KvaSource { USER_INPUT, LEW_VERIFIED }
```
- Application 엔티티에 `@Enumerated(EnumType.STRING)` 필드 4개 추가.
- `CreateApplicationRequest` DTO에 `kvaStatus`(nullable, 기본 CONFIRMED) 추가. `selectedKva`는 기존 필드 재사용.

---

## 4. API 스펙

### `POST /api/applications` (기존 확장)
Request 변경:
```json
{ "address":"...", "postalCode":"...", "buildingType":"HDB",
  "selectedKva": 45, "kvaStatus": "UNKNOWN", ... }
```
- `kvaStatus` 누락 → 서버에서 `CONFIRMED`로 간주(하위호환).
- `kvaStatus='UNKNOWN'`이면 `selectedKva`는 무조건 45로 서버가 강제 설정(클라이언트 값 무시).
- `kvaSource` 자동 설정: UNKNOWN → NULL, CONFIRMED → USER_INPUT.

### `PATCH /api/admin/applications/{id}/kva` (신규, ADMIN/LEW)
Request:
```json
{ "selectedKva": 100, "note": "SP 고지서 확인 결과 100 kVA 계약" }
```
Response 200:
```json
{ "applicationId": 1234,
  "kvaStatus": "CONFIRMED",
  "kvaSource": "LEW_VERIFIED",
  "selectedKva": 100,
  "quoteAmount": 650.00,
  "kvaConfirmedBy": 17,
  "kvaConfirmedAt": "2026-04-20T03:20:11Z" }
```
Errors: 400 `INVALID_KVA_TIER`, 403 `FORBIDDEN`, 404 `APPLICATION_NOT_FOUND`, 409 `KVA_ALREADY_CONFIRMED`(force 미지정 시).

### `POST /api/applications/{id}/payment` (기존, 가드 추가)
- 처리 전 `application.kvaStatus` 검증. `UNKNOWN`이면 400 `KVA_NOT_CONFIRMED` 반환 및 상태 전이 차단.
- 기존 결제 흐름·응답 포맷은 그대로.

### `GET /api/admin/applications?kvaStatus=UNKNOWN` (기존 쿼리 확장)
- 기존 list 컨트롤러에 `kvaStatus` 파라미터 추가(선택). 값이 있으면 WHERE 절 추가, 없으면 무시.

---

## 5. 상태 전이 규칙

```
Application.status: PENDING_REVIEW → PENDING_PAYMENT
  ├─ 기존 조건(LEW review 통과 등) AND
  └─ kva_status = 'CONFIRMED'  ← Phase 5 신규 가드

Application.kva_status: UNKNOWN → CONFIRMED
  └─ PATCH /kva (LEW/ADMIN only) — 단방향, force=true 없이는 재확정 불가
```

- 가드 위치: `PaymentService.initiate()` 진입부(서비스 계층 이중 체크) + 컨트롤러.
- 상태 전이 실패 에러코드: `KVA_NOT_CONFIRMED`(400). 메시지에 "LEW가 kVA를 확정하면 결제가 활성화됩니다" 포함.

---

## 6. 마이그레이션 전략

### 파일 구조
```
doc/Project execution/phase5-kva-ux/migration/
  V_01_add_kva_status_columns.sql
```

### V_01
```sql
ALTER TABLE applications
  ADD COLUMN kva_status        VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
  ADD COLUMN kva_source        VARCHAR(20) NULL,
  ADD COLUMN kva_confirmed_by  BIGINT      NULL,
  ADD COLUMN kva_confirmed_at  DATETIME(6) NULL,
  ADD KEY idx_applications_kva_status (kva_status),
  ADD CONSTRAINT fk_applications_kva_confirmed_by
    FOREIGN KEY (kva_confirmed_by) REFERENCES users(user_seq);

-- 기존 레코드 백필: 모두 사용자가 직접 tier를 입력한 것으로 간주
UPDATE applications
   SET kva_source = 'USER_INPUT'
 WHERE kva_status = 'CONFIRMED' AND kva_source IS NULL;
```

- 백필은 DEFAULT로 `kva_status=CONFIRMED`가 이미 들어가므로 source만 추가 UPDATE.
- 롤백: `ALTER TABLE applications DROP FOREIGN KEY fk_applications_kva_confirmed_by, DROP COLUMN kva_confirmed_at, DROP COLUMN kva_confirmed_by, DROP COLUMN kva_source, DROP COLUMN kva_status, DROP INDEX idx_applications_kva_status;`
- schema.sql은 동시에 갱신(Phase 1~4 동일 패턴).

---

## 7. PR 분리 전략 (3개)

| PR | 제목 | 범위 | 의존 | 독립 배포 |
|---|---|---|---|---|
| **PR#1** | `feat(backend): Application kVA 확정 API + 상태 가드` | schema.sql + migration/V_01, `KvaStatus`/`KvaSource` enum, Application 엔티티 4필드, `CreateApplicationRequest` 확장(UNKNOWN 처리), `PATCH /api/admin/applications/{id}/kva` (`ApplicationKvaController` 또는 기존 admin 컨트롤러 확장), `PaymentService` 가드, `GET /admin/applications?kvaStatus=` 필터, 감사 이벤트 `KVA_CONFIRMED_BY_LEW`, 인앱 알림 `KVA_CONFIRMED`, AC-S1~S4/A1~A4/P1~P3 MockMvc. | — | ✅ |
| **PR#2** | `feat(frontend-applicant): Step 2 "I don't know" 옵션 + Tip 박스` | `NewApplicationPage` Step 2 드롭다운 최상단 옵션 추가, Tip 박스 컴포넌트(`KvaTipBox.tsx` — buildingType 매핑: HDB=45/Shophouse=100/Factory=500/기타 일반), "From S$350" 가격 포맷, `CreateApplicationRequest` payload에 `kvaStatus` 포함, AC-U1~U6. | PR#1 | ✅ |
| **PR#3** | `feat(frontend-lew): 관리자 신청 상세 kVA 확정 UI` | `AdminApplicationDetailPage`에 kVA 배지(UNKNOWN/CONFIRMED) + "kVA 확정" 버튼(UNKNOWN일 때 노출), `KvaConfirmModal.tsx` (tier 선택 + note 최소 10자), API 클라이언트 `confirmApplicationKva`, `/admin/applications` 리스트에 `kvaStatus=UNKNOWN` 필터 추가, Phase 3 DocumentRequest 모달에서 SP_ACCOUNT_PDF/MAIN_BREAKER_PHOTO 사전 체크 suggestion(선택). | PR#1 | ✅ |

- 배포 순서: PR#1 → PR#2/PR#3 병렬. PR#2만 단독 배포해도 기존 사용자가 tier를 직접 고르는 경로는 100% 호환(하위호환 AC-S1 준수).

---

## 8. 리스크 & 완화

| # | 리스크 | 영향 | 완화 |
|---|---|---|---|
| R1 | UNKNOWN 신청이 LEW 확정 지연으로 결제가 정체 | 중 | `/admin/applications?kvaStatus=UNKNOWN` 전용 필터(AC-P3) + 대시보드에 건수 배지. SLA 24h는 내부 지표로만, 사용자에게는 "typically within 1 business day" 안내 문구. |
| R2 | 사용자가 확정 전 신청을 취소 | 저 | 기존 soft delete 흐름(`DELETE /applications/{id}`) 재사용. `kva_confirmed_*` 데이터는 감사 로그로 보존. 특별 처리 없음. |
| R3 | Corporate 신청자가 "Not sure"를 선택했는데 Phase 1의 JIT 회사정보 요청도 동시에 누락 | 중 | 두 흐름은 독립. LEW는 Phase 3 DocumentRequest로 회사정보·kVA 근거를 **동시 배치** 요청 가능(Phase 3 AC-R1 배치 생성 재사용). Phase 5 코드 변경 없음. |
| R4 | LEW 확정 후 가격 상승 → 사용자 이탈 | 중 | 확정 시 인앱 알림에 "가격이 S$350→S$650으로 조정되었습니다" 메시지 포함. 기존 신청 취소 흐름으로 환불 없이 이탈 가능(결제 전이므로). |
| R5 | 이미 CONFIRMED된 신청에 LEW가 실수로 재확정 PATCH | 중 | AC-P1 `KVA_ALREADY_CONFIRMED` 409 + ADMIN `?force=true` 오버라이드. 감사 로그에 previous 값 기록으로 소급 추적 가능. |
| R6 | 기존 레코드 백필 누락 | 고 | schema DEFAULT `'CONFIRMED'` + 명시적 UPDATE (source='USER_INPUT'). 마이그레이션 후 `SELECT COUNT(*) FROM applications WHERE kva_status IS NULL OR (kva_status='CONFIRMED' AND kva_source IS NULL)` 0건 확인. |
| R7 | placeholder 금액 45 kVA 기준이 너무 낮아 사용자가 "From S$350" 라벨을 실제 최종가로 오해 | 중 | UI에 `"(final price after LEW confirmation)"` 보조 문구 필수(AC-U2) + 확정 시 알림으로 재고지. |

---

## 9. 범위 외 (Out of Scope)

1. **Smart Default pre-select** — 사용자 결정으로 제외. Tip 문구 표시만 수행.
2. **OCR / Gemini Vision 기반 자동 kVA 인식** — Phase 6으로 분리.
3. **Decision Tree 설문(옵션 C)** — 사용자 결정으로 제외.
4. **Photo-first 플로우(옵션 D)** — Step 2 이전 차단기 사진 업로드 없음.
5. **kVA 히스토리 전용 테이블** — 감사 로그 `KVA_CONFIRMED_BY_LEW` metadata로 대체.
6. **kVA 확정 이메일 알림** — 인앱만. 이메일 채널 확장은 Phase 6.
7. **LEW 확정 SLA 강제/자동 에스컬레이션** — 사용자 노출 문구만, 자동 리마인더 job은 범위 외.
8. **가격 재산정 이력(price history) 테이블** — `quote_amount` 최종값만 저장. 변경 이력은 감사 로그 metadata로 추적.
9. **ESTIMATED 중간 상태** — enum에서 제외.

---

## 10. 개발자 Handoff 체크리스트

### Backend (PR#1)
- [ ] `KvaStatus`, `KvaSource` enum + Application 엔티티 4필드 (`@Enumerated(STRING)`)
- [ ] `schema.sql` 업데이트 + `migration/V_01_add_kva_status_columns.sql`
- [ ] `CreateApplicationRequest` DTO: `kvaStatus` 추가(nullable), UNKNOWN 시 서버에서 `selectedKva=45` 강제
- [ ] `ApplicationService.create()`: source 자동 설정(UNKNOWN→NULL, CONFIRMED→USER_INPUT), quote_amount placeholder 계산
- [ ] `ApplicationKvaController` 또는 기존 `AdminApplicationController`에 `PATCH /{id}/kva` 추가
- [ ] 권한 가드: `@PreAuthorize("hasAnyRole('ADMIN','LEW')")` + assigned LEW 비교
- [ ] `PaymentService.initiate()` 진입부에 `kvaStatus != CONFIRMED` 가드 (400 `KVA_NOT_CONFIRMED`)
- [ ] `AuditLog` 이벤트 상수 `KVA_CONFIRMED_BY_LEW` 추가
- [ ] `NotificationType.KVA_CONFIRMED` 추가 + 인앱 발송 훅 (이메일 범위 외)
- [ ] `AdminApplicationController` 목록 쿼리에 `kvaStatus` 파라미터
- [ ] MockMvc: AC-S1~S4, AC-A1~A4, AC-P1~P3

### Frontend (PR#2, Applicant)
- [ ] `NewApplicationPage` Step 2 드롭다운 옵션 최상단 추가 + `kvaStatus` state
- [ ] `KvaTipBox.tsx`: buildingType → suggestion 문구 매핑 + 접이식 상세(SP 고지서/브레이커)
- [ ] 가격 표시 컴포넌트: UNKNOWN 시 `"From S$350"` 포맷 + 보조 문구
- [ ] API 호출 payload에 `kvaStatus` 포함
- [ ] E2E 시나리오 2개: (a) tier 직접 선택 (b) "I don't know" 선택 후 제출

### Frontend (PR#3, LEW)
- [ ] `AdminApplicationDetailPage`: kVA 섹션 + 배지(UNKNOWN/CONFIRMED) + "kVA 확정" 버튼
- [ ] `KvaConfirmModal.tsx`: tier 셀렉트 + note(min 10자) + 확인/취소
- [ ] API 클라이언트 `confirmApplicationKva({id, selectedKva, note})`
- [ ] 목록 필터에 `kvaStatus=UNKNOWN` 토글
- [ ] DocumentRequest 모달의 SP_ACCOUNT_PDF / MAIN_BREAKER_PHOTO 사전 체크 suggestion (nice-to-have)

### Tests
- [ ] Backend: Application 생성 3 케이스(UNKNOWN / CONFIRMED / 구버전 클라이언트 하위호환)
- [ ] Backend: 결제 가드 회귀 (기존 결제 성공 케이스 영향 없음 확인)
- [ ] Backend: force 재확정 감사 로그 metadata 검증
- [ ] Frontend: Step 2 옵션 렌더링, 가격 포맷 전환, Tip 매핑 스냅샷
- [ ] E2E 골든 경로: "신청자 Not sure 제출 → LEW 확정 → 신청자 결제 활성화 → 결제 완료"

### Ops
- [ ] 배포 순서: PR#1 → PR#2/PR#3 병렬
- [ ] 백필 검증 쿼리 실행: `SELECT COUNT(*) FROM applications WHERE kva_status IS NULL OR (kva_status='CONFIRMED' AND kva_source IS NULL)` → 0
- [ ] LEW 공지: "Phase 5 배포 완료 — 'kVA 미확정' 필터 사용 가능. 확정은 PATCH 버튼 1회로 결제 활성화."
- [ ] 배포 D-day에 지표 베이스라인 snapshot (Step 2 이탈률, 제출 완료율)
