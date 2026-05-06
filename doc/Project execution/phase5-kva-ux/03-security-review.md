# Phase 5 — kVA UX 보안/권한/데이터 무결성 리뷰

**작성일**: 2026-04-17
**대상 변경**: `Application` 에 `kva_status / kva_source / kva_confirmed_by / kva_confirmed_at` 4컬럼 추가, 신규 `PATCH /api/admin/applications/{id}/kva` (+ `?force=true`), 결제 전환 경로에 `kvaStatus=CONFIRMED` 가드, `CreateApplicationRequest` 에 `kvaStatus` 수용, 관리자 목록 `kvaStatus` 필터, 감사 이벤트 `KVA_CONFIRMED_BY_LEW` / 인앱 알림 `KVA_CONFIRMED`
**리뷰어**: Security Architect
**결론**: **블로커 4건 해결 후 머지 가능**. kVA → quoteAmount 는 직접 매출 변수(SGD $350~$3,500)이고, Phase 1~3 와 달리 **결제 상태 전이의 가드**를 신규 도입하므로 가격 조작·상태 전이·동시성 공격 벡터가 한 번에 열린다. (1) 스펙의 결제 API 경로가 실제 코드와 불일치, (2) `Application` 에 `@Version` 부재, (3) `PATCH /kva` 가 `PAID` 이후에도 허용될 경우 소급 가격 조작, (4) `force=true` 오버라이드 감사 로그 강제 누락이 머지 전 차단 항목.

---

## 1. 가격 조작 공격 벡터 (최우선)

kVA tier 매핑은 `ApplicationService.create()` L117 / L266 에서 `tierPrice + sldFee + emaFee` 로 산출되어 `application.quoteAmount` 에 영구 저장된다. Phase 5 는 이 금액을 **LEW 가 사후에 재계산**하도록 경로를 여는 것이므로 다음 4개 시나리오를 모두 서버에서 차단해야 한다.

### 1.1 Applicant 의 API 직접 호출로 kVA 변경
- `CreateApplicationRequest` 는 `@NotNull @Positive` 의 `selectedKva` 를 유지한다(AC-S1). 생성 이후 `selectedKva` 를 변경할 수 있는 엔드포인트는 **반드시 `PATCH /api/admin/applications/{id}/kva` 만** 이어야 하며, 기존 `ApplicationController` 의 applicant-self 업데이트 경로(`updateDetails`) 가 `selectedKva` 를 수용한다면 Phase 5 범위에서 **필드 화이트리스트에서 제외**해야 한다. `Application.updateDetails(..., selectedKva, ...)` 는 L302 에 존재하며 REVISION_REQUESTED 재제출 흐름에서 호출된다 — 이 경로에서도 `kvaStatus=CONFIRMED` 인 건은 `selectedKva` 를 **무시하고 원본 값 유지**하거나, `kvaStatus` 를 다시 `UNKNOWN` 으로 강제 전환한 뒤 재확정을 요구하도록 분기해야 한다. 그대로 두면 applicant 가 재제출 시 자유롭게 tier 를 바꿀 수 있어 가격 우회 가능.

### 1.2 UNKNOWN 상태에서 결제 전환 시도 (Race)
- 스펙 AC-S3 은 `POST /api/applications/{id}/payment` 를 가드한다고 하지만 **실제 코드에 이 엔드포인트는 존재하지 않는다**. `PENDING_REVIEW → PENDING_PAYMENT` 전환은 `POST /api/admin/applications/{id}/approve` (`AdminApplicationController` L158-163, `AdminApplicationService.approveForPayment` L243-265)가 유일하며, 실제 결제 확인은 `AdminPaymentService` L45 의 `PENDING_PAYMENT → PAID` 이다. → **가드는 `AdminApplicationService.approveForPayment` 진입부** 에 두어야 한다. 스펙 §5 "PaymentService.initiate() 진입부 이중 체크"는 존재하지 않는 서비스를 가정하므로 수정 필요(**B-1**).
- 동시성: LEW_A 가 `approveForPayment` 호출하는 순간 LEW_B 가 `PATCH /kva` 로 UNKNOWN→CONFIRMED 전환 시, 두 요청 모두 기존 상태 읽기 시점엔 `UNKNOWN` 또는 `PENDING_REVIEW/CONFIRMED` 를 보고 통과할 수 있다. Phase 3 B-1 과 동일한 패턴의 `@Version` 이 `Application` 엔티티에 **없음**(L1-112 확인, no `@Version`). → **B-2**.

### 1.3 PAID 이후 kVA 변경 — 소급 가격 조작
스펙은 "`kvaStatus=CONFIRMED` 이후 `force=true` 로만 재확정" 만 규정하고 **Application 상태별 금지 규칙**이 빠져 있다. `PAID / IN_PROGRESS / COMPLETED / EXPIRED` 상태에서 `PATCH /kva` 가 `quoteAmount` 를 재계산한다면 이미 결제된 금액과 엔티티 표시금액이 어긋나고, 환불/추가결제 프로세스 없이 회계 불일치가 발생한다. → **B-3**: 서비스에서 `application.status ∈ {PAID, IN_PROGRESS, COMPLETED, EXPIRED}` 이면 **ADMIN + force 여부와 무관하게 409 `KVA_LOCKED_AFTER_PAYMENT`** 로 차단. 예외적으로 회계 조정이 필요한 케이스는 별도 `AdminPriceAdjustmentController` (Phase 6 이상) 로 분리하고 환불 레코드와 쌍으로 기록.

### 1.4 내부자(악의적 LEW) 가 낮은 tier 확정
- 방어는 **검증보다 탐지**에 의존할 수밖에 없다. 감사 로그 `KVA_CONFIRMED_BY_LEW` metadata 에 `{previousKva, previousQuote, newKva, newQuote, priceDelta, note, documentRequestIds[]}` 를 저장하여 `priceDelta < 0` 이고 `documentRequestIds` 가 비어 있는 케이스를 후행 이상 탐지(권장 R-1). LEW `note` 최소 10자(PR#3) 는 필수 유지.

---

## 2. 권한 매트릭스

### 2.1 `PATCH /api/admin/applications/{id}/kva`
- **컨트롤러**: `@PreAuthorize("hasAnyRole('ADMIN','SYSTEM_ADMIN','LEW')")`. Phase 3 DocumentRequest 와 동일.
- **서비스 이중 체크**: `OwnershipValidator.validateOwnerOrAdminOrAssignedLew(applicantSeq=null, requestorSeq, role, application.assignedLew?.userSeq)` 재사용. `applicantSeq=null` 을 넘기면 L67 `validateOwner` 에서 NPE → **`ownerSeq` 도 `application.user.userSeq` 로 실제 값**을 전달해야 한다. applicant 역할이 이 API 를 치면 403 이 나오도록 `hasAnyRole` 에서 이미 필터되나, 서비스 계층에서도 방어적으로 확인.
- **LEW 미할당 건**: assignedLew 가 null 인 application 에 대해 LEW 가 `PATCH /kva` 치면 L62 `assignedLewSeq != null` 가드에서 탈락 → 403. ADMIN 은 통과. 정보 누설 방지를 위해 **Phase 3 AC-P2 와 동일하게 404** 로 통일할지, 403 으로 두는지 결정 필요 — 스펙 AC-A2 는 403 을 명시하므로 일관성을 위해 이 신규 API 는 403 유지하되, Phase 3 DocumentRequest 와 응답 코드가 상이한 점을 프론트 에러 핸들러에 반영.
- **APPLICANT**: Phase 5 에서 이 API 는 명시 금지. `@PreAuthorize` 가 제일 앞 가드이므로 안전.

### 2.2 force=true 는 ADMIN 전용
- AC-P1 에 `ADMIN 은 ?force=true` 로 오버라이드라고 되어 있으나 컨트롤러에서 **쿼리 파라미터 파싱 + 역할 재검사**를 별도 수행해야 한다. `force=true && !hasRole('ADMIN')` 이면 403 `FORCE_REQUIRES_ADMIN`. LEW 에게 force 를 허용하면 재확정 이력이 감사 로그만 남고 UI 상 이유 없이 tier 가 바뀌는 공격이 가능 — **B-4** 와 연결.

---

## 3. 상태 전이 무결성

| 조합 | 허용 | 거부 |
|---|---|---|
| `kva_status=UNKNOWN` + `status=PENDING_REVIEW` + `approveForPayment` | — | 400 `KVA_NOT_CONFIRMED` |
| `kva_status=UNKNOWN` + `PATCH /kva` (LEW/ADMIN) | **CONFIRMED 로 1회 전환** | — |
| `kva_status=CONFIRMED` + `PATCH /kva` 재호출 (force 미지정) | — | 409 `KVA_ALREADY_CONFIRMED` |
| `kva_status=CONFIRMED` + `PATCH /kva?force=true` (ADMIN) | 허용 + 감사 로그 previous 값 | — |
| `status ∈ {PAID, IN_PROGRESS, COMPLETED, EXPIRED}` + `PATCH /kva` | — | 409 `KVA_LOCKED_AFTER_PAYMENT` (**신규, §1.3**) |
| `status=REVISION_REQUESTED` 재제출 + applicant 가 `selectedKva` 변경 | — | 화이트리스트에서 제외(§1.1) |

- `kva_status` 자체 전이는 단방향(`UNKNOWN → CONFIRMED`). 역전이 차단은 `Application.confirmKva(...)` 도메인 메서드에서 `if (kvaStatus == CONFIRMED && !force) throw` 로 가드하고 force 는 파라미터로 전달.
- Application 에 `@Version private Long version;` 추가 필수(§1.2, **B-2**). Phase 3 에서 `DocumentRequest` 에만 추가되고 `Application` 은 누락된 상태가 유지되고 있어 이번 기회에 함께 해결해야 한다.

---

## 4. 감사 로깅 확장

`AuditAction` 에 아래 이벤트 **3종** 추가 (스펙 1종에서 확장):

```java
// Phase 5 — kVA confirmation
KVA_CONFIRMED_BY_LEW,
KVA_OVERRIDDEN_BY_ADMIN,      // force=true 경로 — 별도 식별자
KVA_CONFIRMATION_DENIED       // 상태 위반/권한 위반 시도 탐지
```

- **이유**: `force=true` 는 normal confirm 과 **동일 이벤트로 묶이면 이상 탐지 쿼리가 복잡**해진다. 별도 이벤트로 분리.
- metadata(JSON):
  ```json
  { "previousKva": 45, "previousStatus": "UNKNOWN", "previousQuote": 350.00,
    "newKva": 200,    "newQuote": 1200.00, "priceDelta": 850.00,
    "note": "SP bill confirmed", "force": false,
    "applicationStatus": "PENDING_REVIEW",
    "documentRequestIds": [42, 51] }
  ```
- `before_value` / `after_value` JSON 컬럼(이미 존재, `AuditLog` L51-55) 활용. `description` 에는 PDPA class-2 data 저장 금지(LEW note 는 UI helper 로 "Do not include NRIC/UEN" 안내, Phase 3 R-1 재사용).
- **force 경로는 `@Auditable` 애너테이션으로는 분기 기록이 불가**하므로 `AuditLogService.record(...)` 명시 호출로 수동 작성(**B-4**).

---

## 5. 데이터 모델 무결성

### 5.1 NOT NULL / FK
- `kva_status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED'` — 하위호환 OK.
- `kva_source VARCHAR(20) NULL` — UNKNOWN 일 때 NULL 허용. 단, `CHECK (kva_status='UNKNOWN' OR kva_source IS NOT NULL)` 체크 제약이 없으면 "CONFIRMED + source NULL" 레코드가 발생 가능. MySQL 8.0 은 CHECK 를 지원하므로 **체크 제약 추가 권장**(혹은 마이그레이션 사후 검증 쿼리 필수, R6 스펙).
- `kva_confirmed_by BIGINT NULL` + FK → `users(user_seq)` — 삭제 시 `ON DELETE SET NULL` 추가. 스펙 V_01 에 `ON DELETE` 절이 없어 기본 RESTRICT 로 들어가 LEW 계정 soft-delete 경로에서 문제 발생 가능.

### 5.2 기존 레코드 백필
- 기본값 `'CONFIRMED'` + `UPDATE ... SET kva_source='USER_INPUT'` 로 9건 백필 안전. `kva_confirmed_by / kva_confirmed_at` 은 NULL 유지(실제 LEW 가 확정한 적 없음). 이 규칙을 코드가 전제로 삼아야 — `kvaSource='USER_INPUT'` 이고 `kvaConfirmedBy IS NULL` 인 레코드를 "applicant self-selected" 로 해석하도록 DTO 매퍼에 코드 주석.
- 사후 검증: `SELECT COUNT(*) FROM applications WHERE kva_status IS NULL OR (kva_status='CONFIRMED' AND kva_source IS NULL)` → 0 확인(스펙 R6 반영).

### 5.3 schema.sql 위치
- `blue-light-backend/src/main/resources/schema.sql` 은 Phase 1~4 모든 커밋이 반영된 최신본에 이어 append — 머지 시점에 Phase 4 커밋(i18n)이 schema 를 건드렸는지 PR 베이스 확인 필수. 미확인 시 개발서버 부팅 시 `idx_applications_kva_status` 충돌 가능성.

---

## 6. 하위호환성

- `CreateApplicationRequest.selectedKva` `@NotNull @Positive` 유지. `kvaStatus` 누락 → 서버가 `CONFIRMED` + `USER_INPUT` 으로 해석(스펙 §4). 기존 모바일/구버전 프런트도 동작. 단 `kvaStatus='UNKNOWN'` 인데 `selectedKva` 가 45 가 아닌 값을 보내는 악의적 요청 → 서버가 **무조건 45로 강제 덮어쓰기**(스펙 AC-S1). 이 덮어쓰기 규칙은 `ApplicationService.create()` 최상단에 `if (kvaStatus==UNKNOWN) request.setSelectedKva(45)` 로 우선 실행해야 이후 가격 계산 L117 이 안전.
- Idempotent 마이그레이션: `ADD COLUMN ... DEFAULT 'CONFIRMED'` 는 MySQL 8.0 INSTANT 적용. 롤백 SQL 도 V_01 하단 주석에 병기(Phase 3 패턴과 일관).

---

## 7. UI 방어 vs 서버 방어

- FE: UNKNOWN 시 결제 버튼 비활성(AC-U2). 이는 **UX 보조**일 뿐이며 신청자가 curl 로 `POST /api/admin/applications/{id}/approve` 를 쳐도(해당 엔드포인트는 ADMIN/LEW 전용이므로 401/403) 막혀야 한다. 현재 컨트롤러 L42 `@PreAuthorize("hasAnyRole('ADMIN','LEW')")` 가 있으므로 applicant 가 직접 PENDING_PAYMENT 전이 시도하는 벡터는 이미 차단됨.
- **신규 가드 위치**: `AdminApplicationService.approveForPayment` L244 이후 `if (application.getKvaStatus() != KvaStatus.CONFIRMED) throw 400 KVA_NOT_CONFIRMED`. 스펙의 `PaymentService.initiate()` 는 존재하지 않으므로 경로 이름을 스펙에서 정정(§1.2, **B-1**).
- DocumentRequest fulfill 경로: Phase 3 의 fulfill 은 kVA 상태와 독립이므로 가드 불필요. 단 LEW 가 "SP_ACCOUNT_PDF" 승인 시 `PATCH /kva` 로 이어지는 것은 UX 동선일 뿐, 서버는 두 API 를 독립 트랜잭션으로 처리.

---

## 8. Phase 3 R-1~R-5 와의 관계

| ID | Phase 3 | Phase 5 반영 |
|---|---|---|
| R-1 ClamAV | 범위 외 | Phase 5 범위 외 — SP bill PDF 업로드는 Phase 3 경로 재사용, 본 PR 은 kVA 상태만 다룸 |
| R-2 LOA snapshot | Phase 2 완료 | 해당 없음 |
| R-3 review history 테이블 | AuditLog 대체 | kVA 재확정 이력도 동일 — 별도 `kva_history` 테이블 불필요. `KVA_CONFIRMED_BY_LEW` + `KVA_OVERRIDDEN_BY_ADMIN` metadata 로 충분 |
| R-4 Rate limit | Phase 3 적용 | kVA 확정은 application 당 사실상 1회(+ADMIN force 예외) — rate limit 불필요. 다만 `KVA_CONFIRMATION_DENIED` 이벤트가 동일 LEW 에서 분당 N회 초과 시 이상 탐지(권장 R-2) |
| R-5 CSP | S3 전환 시점 | 해당 없음 |

---

## 9. 리스크 요약 표

| ID | 리스크 | 심각도 | 가능성 | 완화 | 머지 전 차단 |
|---|---|---|---|---|---|
| P5-R1 | 스펙의 결제 API 경로 불일치(`/payment` → 실제 `/approve`) → 가드 구현 누락 | **H** | H | `AdminApplicationService.approveForPayment` 에 `kvaStatus=CONFIRMED` 가드 | **B-1** |
| P5-R2 | `Application` `@Version` 부재 → kVA 확정과 승인 동시 실행 시 금액/상태 어긋남 | **H** | M | `@Version private Long version;` + 409 `CONCURRENT_UPDATE` 어드바이스 | **B-2** |
| P5-R3 | PAID/IN_PROGRESS 이후 `PATCH /kva` 가 금액 재계산 → 회계 불일치 | **H** | M | `status ∈ {PAID,IN_PROGRESS,COMPLETED,EXPIRED}` 이면 409 `KVA_LOCKED_AFTER_PAYMENT` (force 무관) | **B-3** |
| P5-R4 | `force=true` 오버라이드가 LEW 에도 허용되거나 감사 이벤트가 일반 confirm 과 섞임 | **H** | M | 컨트롤러에서 `force && !ROLE_ADMIN` 403 + `KVA_OVERRIDDEN_BY_ADMIN` 별도 이벤트 + previous 값 필수 | **B-4** |
| P5-R5 | 재제출(REVISION_REQUESTED) 경로에서 applicant 가 `selectedKva` 자유 변경 → 가격 우회 | **H** | M | `updateDetails` 화이트리스트에서 `selectedKva` 제외 또는 `kvaStatus=UNKNOWN` 강제 전환 | 아니오(권장 R-3, 다만 즉시 수정 권장) |
| P5-R6 | 내부자(LEW) 가 낮은 tier 로 확정하여 신청자와 공모 | M | L | `KVA_CONFIRMED_BY_LEW` metadata 에 priceDelta/docRequestIds 저장 + 후행 탐지 쿼리 | 아니오 (R-1) |
| P5-R7 | `kva_source` NULL 일관성 위반 (CONFIRMED+source NULL) | M | L | CHECK 제약 또는 사후 검증 쿼리 필수 | 아니오 |
| P5-R8 | `kva_confirmed_by` FK 의 ON DELETE 미지정 → LEW 계정 정리 시 RESTRICT | L | M | `ON DELETE SET NULL` 명시 | 아니오 |
| P5-R9 | UNKNOWN 신청의 placeholder 금액 350 이 최종가로 오해되어 분쟁 | M | M | AC-U2 보조 문구 + 확정 시 알림 — UX 범위 | 아니오 |
| P5-R10 | `KVA_ALREADY_CONFIRMED` 409 메시지에 previous tier 노출 → 경쟁 LEW 정보 수집 | L | L | 에러 바디는 코드만, 상세는 별도 GET | 아니오 |
| P5-R11 | Rate limit 부재로 LEW 가 force 반복 호출(금액 요동) | L | L | `KVA_OVERRIDDEN_BY_ADMIN` 이벤트가 분당 2회 초과 시 경고 | 아니오 (R-2) |

---

## 10. 머지 전 필수 수정 (Blockers)

- **B-1 · 결제 전환 가드를 실제 코드 경로에 구현**: 스펙 §5 `PaymentService.initiate()` 는 존재하지 않음. `AdminApplicationService.approveForPayment(Long applicationSeq)` (L243) 진입부에 `if (application.getKvaStatus() != KvaStatus.CONFIRMED) throw new BusinessException("kVA not confirmed", BAD_REQUEST, "KVA_NOT_CONFIRMED");` 를 Application lookup 직후 추가. 스펙 문서(01-spec §4, §5)의 API 경로 표기를 실제 경로로 정정. MockMvc 회귀: PENDING_REVIEW + UNKNOWN 상태에서 `POST /api/admin/applications/{id}/approve` → 400 `KVA_NOT_CONFIRMED`.
- **B-2 · `Application` `@Version` 추가**: `domain/application/Application.java` 에 `@Version private Long version;` + schema/migration 컬럼 `version BIGINT NOT NULL DEFAULT 0`. `OptimisticLockException` 을 Phase 3 `ControllerAdvice` 와 같은 핸들러에서 409 `CONCURRENT_UPDATE` 로 변환. 두 스레드가 동시에 (a) `approveForPayment` (b) `PATCH /kva` 를 호출해도 한 건만 성공하는 테스트 추가.
- **B-3 · `PATCH /kva` 의 상태 기반 차단**: `ApplicationKvaService.confirm(..)` 에 `if (application.getStatus() == PAID || IN_PROGRESS || COMPLETED || EXPIRED) throw 409 KVA_LOCKED_AFTER_PAYMENT` 를 `force` 여부와 무관하게 적용. 스펙 AC-P1 은 "force 로 덮어쓰기 가능"만 규정하므로 이 가드를 스펙 §5 상태 전이 규칙에 **명문 추가** 필요. 테스트: PAID 상태 + ADMIN + force=true → 409.
- **B-4 · `force=true` 역할 재검증 + 감사 이벤트 분리**: 컨트롤러에서 `@RequestParam(defaultValue="false") boolean force` 수용 후 `if (force && !authorities.contains("ROLE_ADMIN") && !authorities.contains("ROLE_SYSTEM_ADMIN")) throw 403 FORCE_REQUIRES_ADMIN`. 서비스에서 force=true 경로는 `AuditAction.KVA_OVERRIDDEN_BY_ADMIN` 로, 일반 경로는 `KVA_CONFIRMED_BY_LEW` 로 **명시 호출**(@Auditable 애너테이션만으론 분기 기록 불가). metadata 에 `previousKva / previousQuote / priceDelta / note / force` 필수 포함.

---

## 11. 권장 보완 (Phase 6+)

- **R-1 · 내부자 탐지 쿼리**: `KVA_CONFIRMED_BY_LEW` 이벤트 중 `priceDelta < 0` 이고 `documentRequestIds` 가 비어 있는 건을 주간 집계하여 ADMIN 리뷰. Phase 6 observability 범위.
- **R-2 · `KVA_CONFIRMATION_DENIED` 이상 탐지**: 동일 LEW 가 분당 2회 초과 403/409 를 유발하면 경고. Phase 3 R-1 과 동일한 구조.
- **R-3 · 재제출 화이트리스트 재정비**: `ApplicationService.updateDetails` 가 수용하는 필드를 DTO 단위에서 명시적으로 분리하고, `selectedKva / quoteAmount / sldFee` 는 applicant 가 변경 불가능한 필드로 격리. Phase 5 의 곁가지지만 선제 반영 권장.
- **R-4 · kVA 확정 SLA 자동 리마인더**: 스펙 §9 범위 외로 분리된 항목. 24h 초과 UNKNOWN 건에 대해 일 1회 LEW 에게 인앱 알림. Phase 6.
- **R-5 · 가격 이력 테이블**: 현재는 `quote_amount` 최종값만 저장. 분쟁/환불 증가 시 `application_price_history(application_seq, kva, quote, changed_at, changed_by, reason)` 테이블 분리. 초기에는 AuditLog metadata 로 충분.

---

## 참고 코드 위치

- `blue-light-backend/src/main/java/com/bluelight/backend/api/application/ApplicationService.java` L117, L266 (quoteAmount 산출), L302 `updateDetails` 의 `selectedKva` 수용 — §1.1
- `blue-light-backend/src/main/java/com/bluelight/backend/api/admin/AdminApplicationService.java` L243-265 `approveForPayment`, L278-296 `validateStatusTransition` — §1.2, B-1
- `blue-light-backend/src/main/java/com/bluelight/backend/api/admin/AdminApplicationController.java` L158-163 `POST /applications/{id}/approve` (스펙 `/payment` 경로와 불일치) — B-1
- `blue-light-backend/src/main/java/com/bluelight/backend/api/admin/AdminPaymentService.java` L45-47 `PENDING_PAYMENT → PAID` 가드 — §1.3 참조
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/application/Application.java` L112 `assignedLew`, L294 `approveForPayment`, `@Version` 부재 — B-2
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/application/ApplicationStatus.java` L6-41 (신규 가드에서 사용할 7개 상태)
- `blue-light-backend/src/main/java/com/bluelight/backend/common/util/OwnershipValidator.java` L56-68 `validateOwnerOrAdminOrAssignedLew` 재사용 — §2.1
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditAction.java` L32-37 (Phase 5 `KVA_*` 3종 추가 위치) — §4
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/audit/AuditLog.java` L51-55 `beforeValue/afterValue` JSON — §4
- `blue-light-backend/src/main/resources/schema.sql` `applications` 테이블 — Phase 4 커밋 이후 위치 확인 후 ALTER 추가
