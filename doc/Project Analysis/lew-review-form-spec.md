# LEW Review Form — Certificate of Fitness 수집 화면 스펙

**문서 종류**: Product Specification (Refinement / Strategy)
**대상 기능**: LEW Review Form (신규) — EMA ELISE 제출 전 LEW가 Certificate of Fitness(이하 CoF) 필드를 채우는 전용 화면
**작성일**: 2026-04-22
**상태**: 제품 사인오프 대기 · 구현 전 세션 분리
**연계 문서**:
- `ema-field-jit-plan.md` §2·§4 "LEW 검토 단계에서 추가되는 필드"
- `ema-license-flow-ux-spec.md` — 신청자 4-스텝 흐름 (본 스펙은 신청자 흐름을 건드리지 않음)
- `ema-pdpa-assessment.md` §5 Access Control, §9(① ④ ⑧)
- `jit-reask-audit.md` §5 V-9 — EMA 미수집 필드 목록

---

## 1. 목적·범위

- **목적**: LEW 담당자가 자신에게 배정된 신청의 현장 확인을 완료한 뒤 EMA ELISE 제출에 필요한 CoF 필드 10종을 1회로 기입하고, 확정(finalize) 후 결제 단계로 이행시키는 전용 화면을 제공한다.
- **범위 내**: `Application`과 1:1로 매핑되는 신규 엔티티 `CertificateOfFitness`, LEW 전용 API(`/api/lew/**`), LEW Review Form UI(3-step), 상태 머신 변경(선택안 2개), PDPA/감사 요구사항.
- **범위 외**: 신청자 측 폼 변경, ELISE 외부 API 연동, Retailer 마스터 데이터 운영 UI, LOA 재발급, CoF PDF 출력.
- **사용자**: 배정된 LEW 1인만 R/W. ADMIN/SYSTEM_ADMIN은 R(마스킹), APPLICANT는 R(제출 후, 마스킹 + 일부 필드 은닉).

## 2. 데이터 모델

### 2.1 신규 엔티티 `CertificateOfFitness`

`Application`과 `@OneToOne(fetch = LAZY, mappedBy = "certificateOfFitness")` 양방향. 신청 당시에는 미생성(null), LEW의 Draft Save 시점에 insert. **CoF는 독립 엔티티**로 분리한다. 이유는 (a) 접근 제어가 컬럼 단위가 아닌 테이블/엔드포인트 단위로 깔끔해지고, (b) `UpdateApplicationRequest` DTO 화이트리스트 오염이 원천 차단되며, (c) `ema-pdpa-assessment.md` §9(④) 권고와 일치한다.

| 필드 | 타입 | Null | 암호화 | 기본값 | 복잡도 | 비고 |
|---|---|---|---|---|---|---|
| `cof_seq` | BIGINT PK | N | - | auto | L | |
| `application_seq` | BIGINT FK UNIQUE | N | - | - | L | `applications.application_seq` 참조, ON DELETE RESTRICT |
| `mssl_account_no_enc` | VARBINARY(255) | Y | AES-256-GCM | - | H | 앞 12자리 암호문 |
| `mssl_account_no_hmac` | CHAR(64) | Y | HMAC-SHA256 | - | H | 검색 키 (FIELD_ENCRYPTION_KEY 별 salt) |
| `mssl_account_no_last4` | VARCHAR(4) | Y | - | - | L | 마스킹 표시용 |
| `consumer_type` | VARCHAR(20) | N | - | `NON_CONTESTABLE` | L | ENUM: `NON_CONTESTABLE`, `CONTESTABLE` |
| `retailer_code` | VARCHAR(32) | Y | - | `SP_SERVICES_LIMITED` | M | Contestable 시 유효 값 필수. 코드는 §3.3 마스터 |
| `supply_voltage_v` | INT | N | - | - | L | 230/400/6600/22000 허용 (CHECK 제약) |
| `approved_load_kva` | INT | N | - | - | L | 신청자 `selected_kva` prefill, LEW 덮어쓰기 |
| `has_generator` | BOOLEAN | N | - | false | L | 체크박스 |
| `generator_capacity_kva` | INT | Y | - | - | L | `has_generator=true` 일 때 필수 (서비스 검증) |
| `inspection_interval_months` | INT | N | - | - | L | 허용: 6/12/24/36/60 |
| `lew_appointment_date` | DATE | N | - | `CURRENT_DATE` | L | 기본값 오늘, 편집 가능 |
| `lew_consent_date` | DATE | Y | - | - | L | finalize 시점에 자동 today, 수기 덮어쓰기 허용 |
| `certified_by_lew_seq` | BIGINT FK | Y | - | - | L | `users.user_seq`, finalize 시 기록 |
| `certified_at` | DATETIME | Y | - | - | L | finalize 시점. null이면 Draft |
| `draft_saved_at` | DATETIME | Y | - | - | L | 마지막 Draft Save |
| `version` | INT | N | - | 0 | L | JPA `@Version` 낙관적 락 |
| `created_at` / `updated_at` / `created_by` / `updated_by` | - | - | - | - | L | BaseEntity 상속 |
| `deleted_at` | DATETIME | Y | - | - | L | Soft delete, `@SQLRestriction` |

**schema.sql 초안**:

```sql
CREATE TABLE IF NOT EXISTS certificate_of_fitness (
    cof_seq                   BIGINT        NOT NULL AUTO_INCREMENT,
    application_seq           BIGINT        NOT NULL,
    mssl_account_no_enc       VARBINARY(255),
    mssl_account_no_hmac      CHAR(64),
    mssl_account_no_last4     VARCHAR(4),
    consumer_type             VARCHAR(20)   NOT NULL DEFAULT 'NON_CONTESTABLE',
    retailer_code             VARCHAR(32)   DEFAULT 'SP_SERVICES_LIMITED',
    supply_voltage_v          INT           NOT NULL,
    approved_load_kva         INT           NOT NULL,
    has_generator             BOOLEAN       NOT NULL DEFAULT FALSE,
    generator_capacity_kva    INT,
    inspection_interval_months INT          NOT NULL,
    lew_appointment_date      DATE          NOT NULL,
    lew_consent_date          DATE,
    certified_by_lew_seq      BIGINT,
    certified_at              DATETIME,
    draft_saved_at            DATETIME,
    version                   INT           NOT NULL DEFAULT 0,
    created_at                DATETIME      NOT NULL,
    updated_at                DATETIME      NOT NULL,
    created_by                VARCHAR(100),
    updated_by                VARCHAR(100),
    deleted_at                DATETIME,
    PRIMARY KEY (cof_seq),
    UNIQUE KEY uk_cof_application (application_seq),
    KEY idx_cof_hmac (mssl_account_no_hmac),
    KEY idx_cof_lew (certified_by_lew_seq),
    CONSTRAINT fk_cof_application FOREIGN KEY (application_seq) REFERENCES applications (application_seq),
    CONSTRAINT fk_cof_lew FOREIGN KEY (certified_by_lew_seq) REFERENCES users (user_seq),
    CONSTRAINT chk_cof_voltage CHECK (supply_voltage_v IN (230, 400, 6600, 22000)),
    CONSTRAINT chk_cof_interval CHECK (inspection_interval_months IN (6, 12, 24, 36, 60))
);
```

**Application 쪽 추가 코드**: `Application.java`에 `@OneToOne(mappedBy = "application", cascade = {PERSIST, MERGE}, fetch = LAZY) private CertificateOfFitness certificateOfFitness;` 만 추가. 기존 컬럼 변경 없음.

## 3. API 엔드포인트 스펙

모든 엔드포인트는 `/api/lew/**` 네임스페이스에 둔다(SecurityConfig H-4 바로잡기 권고 반영). ADMIN은 `/api/admin/applications/{id}`에서 마스킹된 CoF를 조회하며, CoF를 수정할 수 없다.

### 3.1 `GET /api/lew/applications/{id}` — 배정 신청 상세
- **인증**: LEW
- **권한**: `@PreAuthorize("hasRole('LEW') and @appSec.isAssignedLew(#id, authentication)")`
- **응답**: `LewApplicationResponse` — 신청자 입력 필드 전체 + Correspondence Address 5-part 평문 + MSSL 전체(없으면 null) + CoF Draft 현재 값.
- **감사 로그**: `APPLICATION_VIEWED_BY_LEW` (조회 시마다).

### 3.2 `PUT /api/lew/applications/{id}/cof` — CoF 필드 기입/Draft Save
- **인증**: LEW
- **권한**: 위와 동일 + `certified_at IS NULL` 조건(확정되면 거부, 409).
- **Body**: `CertificateOfFitnessRequest` — 위 10개 입력 필드(certified_by/at/version 제외).
- **동작**: CoF 레코드가 없으면 insert, 있으면 업데이트. `draft_saved_at = now()`, `version` 증가.
- **검증**: MSSL 정규식 `^\d{3}-\d{2}-\d{4}-\d$`, `has_generator=true` → `generator_capacity_kva` 필수, Contestable → retailer 필수.
- **감사 로그**: `CERTIFICATE_OF_FITNESS_CREATED` / `CERTIFICATE_OF_FITNESS_UPDATED` (필드별 마스킹 diff).

### 3.3 `POST /api/lew/applications/{id}/cof/finalize` — CoF 확정
- **인증**: LEW
- **권한**: 동일 + `certified_at IS NULL` + Application status가 `PENDING_REVIEW` 또는 `LEW_REVIEWING`.
- **동작**: 필수 필드 전수 재검증 → `certified_by_lew_seq = currentUser`, `certified_at = now()`, `lew_consent_date = COALESCE(lew_consent_date, today)` → Application `status = PENDING_PAYMENT`로 전이.
- **감사 로그**: `CERTIFICATE_OF_FITNESS_FINALIZED`.

### 3.4 `GET /api/admin/applications/{id}` — 기존 (확장)
- ADMIN/SYSTEM_ADMIN: CoF 섹션을 마스킹 응답으로 포함. 수정 불가. MSSL 전체 조회 시 별도 `GET /api/admin/applications/{id}/mssl/unmask` (reason 필수) 별도 엔드포인트, `MSSL_UNMASKED_VIEW` 로깅.

### 3.5 `GET /api/applications/{id}` — 신청자 (확장)
- APPLICANT 본인: CoF가 `certified_at != null`일 때만 읽기. MSSL은 마스킹, Supply Voltage/Approved Load/Consumer Type/Retailer/Inspection Interval은 표시, Generator 용량은 숨김(LEW 업무 영역).

## 4. 신청 상태 머신 변경

두 선택지가 있으며 **선택안 A**를 권고한다.

- **선택안 A (권고)**: 별도 상태 추가 없이, Application.status는 `PENDING_REVIEW` 그대로 유지하고 **CoF의 `certified_at` null 여부로 LEW 작업 진행도를 표현**한다. finalize 시점에 `PENDING_REVIEW → PENDING_PAYMENT`로 전이. 이유: (a) 상태 머신 복잡도가 늘지 않고, (b) "반려(REVISION_REQUESTED)" 분기가 CoF Draft 중에도 그대로 동작, (c) Admin 대시보드 카운트/필터가 기존대로 유지.
- **선택안 B**: 신규 상태 `LEW_REVIEWING` 추가. `PENDING_REVIEW → LEW_REVIEWING → PENDING_PAYMENT`. 이유는 명시적 가시성이나, REVISION 경로·LEW 재배정 경로와의 조합 폭발이 생긴다. 향후 LEW 재배정 기능이 도입되면 그때 도입을 재검토한다.

**REVISION 상호작용**: LEW가 Draft 저장 중 관리자가 REVISION_REQUESTED를 트리거할 수 없게 하거나, 트리거 시 CoF Draft는 보존하되 UI에서 "신청자 수정 대기 중"으로 Read-only 표시. finalize는 `PENDING_REVIEW` 상태에서만 허용.

## 5. LEW Review Form UI 설계

**접근 경로**: `/lew/applications/:id/review`. LEW 대시보드 "내 배정 신청" 리스트에서 항목 클릭 시 진입. 3-step 스텝퍼.

### Step 1 — Application Summary (Read-only)
- 신청자 입력 요약 카드(Installation Name/Address 5-part, Premises Type, Applicant Type, Applicant/Company, Correspondence Address, 선택 kVA, SLD Option, Renewal 여부).
- Landlord EI Licence는 LEW에게만 평문 노출.
- "다음" 클릭 시 Step 2로.

### Step 2 — Certificate of Fitness Inputs (CoF 10 필드)
- **MSSL Account No**: 4개 개별 input (`3-2-4-1`자리) + 자동 포맷팅 + 공란 허용("모른다" 토글: 확정 시 에러). 기존에 신청자가 선택적으로 입력한 `spAccountNo`가 있으면 파싱하여 prefill.
- **Consumer Type**: 라디오 2개(Non-contestable 기본). (복잡도 L)
- **Retailer**: 드롭다운. Non-contestable 선택 시 `SP_SERVICES_LIMITED` 고정·비활성. Contestable 선택 시 마스터(SP Services, Keppel Electric, Tuas Power Supply, Sembcorp Power, Geneco, Senoko Energy Supply, Best Electricity, PacificLight Energy, Diamond Electric, Union Power, Sunseap Energy, 기타) 확장.
- **Supply Voltage**: 드롭다운(230V / 400V / 6.6kV / 22kV).
- **Approved Load kVA**: 숫자 입력. 신청자 `selectedKva` prefill, 변경 시 "신청자 추정값과 다름" 인라인 배지.
- **Generator**: 토글 + 조건부 capacity 입력(kVA). (복잡도 L)
- **Inspection Interval**: 드롭다운 6/12/24/36/60 개월.
- **LEW Appointment Date**: Date picker, 기본값 today.
- **LEW Consent Date**: Date picker, 기본값 비움(finalize 시 자동 today). 수기 과거 일자 기입 허용 (LEW가 이전에 서명한 경우).
- "임시 저장" 버튼: `PUT .../cof`, 토스트 후 현재 화면 유지.
- "다음" 버튼: 클라이언트 검증 통과 시 Step 3로.

### Step 3 — Review & Finalize
- Step 2 입력값 요약(마스킹 없이 LEW에게 그대로 노출, 단 MSSL은 입력 직후 평문 노출).
- "신청자 화면에는 다음 정보가 공개됩니다" 미리보기 박스(APPLICANT 관점 마스킹 시뮬레이션).
- 확인 체크박스: "본 CoF 내용은 EMA Regulation 준수를 확인하며 본인이 LEW로서 서명합니다."
- "Finalize & Submit" 버튼: `POST .../cof/finalize` → 성공 시 완료 토스트 + 배정 목록 복귀. Application 상태는 `PENDING_PAYMENT`로 전이됨을 안내.

### 주요 UX 규칙
- 각 필드 옆에 "신청자가 확인 요청" 배지: ema-field-jit-plan.md §7 "모르겠어요 토글 배지" 요구에 따라 신청자가 모르겠음으로 남긴 필드(예: MSSL prefill null)가 있으면 시각 경고.
- Draft Save는 Step 2 내 어느 시점에서도 가능(모든 필드 선택적).
- Finalize는 필수 필드 누락 시 비활성(버튼 disabled + 사유 툴팁).

## 6. PDPA·보안 고려사항

- **MSSL 저장 패턴**: `ema-pdpa-assessment.md` §9(①) — AES-256-GCM 암호문(앞 12자리) + HMAC-SHA256 검색 해시 + 뒤 4자리 평문. `@Convert(converter = EncryptedStringConverter.class)` 재사용. FIELD_ENCRYPTION_KEY 별도 키.
- **Access Control**: `/api/lew/**` 전용 경로로 분리하여 `/api/admin/**` 공유 혼선 차단. `@appSec.isAssignedLew(#id, auth)` 공통 SpEL 컴포넌트 신설(ConciergeOwnershipValidator 패턴 재사용).
- **DTO 분리**: `CertificateOfFitnessRequest`(LEW 입력), `LewApplicationResponse`(LEW 조회, 전체), `ApplicationResponse`(신청자, CoF 일부 마스킹), `AdminApplicationResponse`(Admin, MSSL 마스킹) 4종.
- **감사 로그**: `AuditAction`에 `APPLICATION_VIEWED_BY_LEW`, `CERTIFICATE_OF_FITNESS_CREATED`, `CERTIFICATE_OF_FITNESS_UPDATED`, `CERTIFICATE_OF_FITNESS_FINALIZED`, `MSSL_UNMASKED_VIEW` 추가. `before_value`/`after_value`는 반드시 마스킹 값.
- **낙관적 락**: 동일 신청을 다른 기기에서 동시 편집 시 `version` 충돌 시 409 + 클라이언트 재로드 유도.
- **소프트 삭제**: 신청 삭제 시 CoF는 함께 소프트 삭제하되, 감사 로그로 사유 기록.

## 7. Phase 계획

- **P1 (Backend Core, ~1 sprint, 복잡도 중)**: `CertificateOfFitness` 엔티티 + schema migration + `AuditAction` 확장 + `AppSecurity.isAssignedLew` 공통 컴포넌트 + GET/PUT/finalize API + DTO 3종 + `Application.changeStatusToPendingPayment()` 재사용.
- **P2 (LEW UI, ~1 sprint, 복잡도 중)**: LEW Review Form 3-step + LEW 배정 대시보드 링크 + 상태 머신 선택안 A 적용 + 신청자 조회 화면에 "CoF 발급됨" 배지.
- **P3 (폴리시/마스터, ~0.5 sprint, 복잡도 하)**: Retailer 마스터 데이터 (ENUM 상수 + 관리자 조회 UI), Consumer Type 자동 추정(45kVA 이상→Contestable 기본값), MSSL unmask 로그 뷰어, Generator 용량 가이드 툴팁.

## 8. Acceptance Criteria

1. APPLICANT가 보는 어떤 화면에도 Supply Voltage/Consumer Type/Retailer/Inspection Interval/LEW Consent Date/LEW Appointment Date/Generator 용량 입력 컨트롤이 존재하지 않는다.
2. `PUT /api/applications/{id}` (신청자 PATCH)로 CoF 필드 중 어느 것도 수정될 수 없다(DTO 화이트리스트에 부재).
3. LEW가 배정되지 않은 다른 신청 `{id}`에 `GET/PUT/POST /api/lew/applications/{id}/cof` 시도 시 403.
4. ADMIN이 `/api/admin/applications/{id}`를 호출하면 MSSL이 `***-**-****-NNNN` 형태로 마스킹되고, 평문 조회는 `mssl/unmask` 경로에서만 가능하며 `MSSL_UNMASKED_VIEW` 감사 로그가 기록된다.
5. CoF finalize 이후 동일 엔드포인트 재호출 시 409 반환하고 `certified_at`·`certified_by_lew_seq`는 변경되지 않는다.
6. `has_generator=true`이면서 `generator_capacity_kva`가 null일 때 finalize는 400을 반환한다.
7. Consumer Type이 Contestable이면 retailer_code 필수(누락 시 400).
8. Supply Voltage는 230/400/6600/22000만 허용(DB CHECK + Java Enum 검증 이중화).
9. LEW Review Form의 MSSL 입력은 4-part 자동 포맷팅이 되고, 공란 제출 시 Draft Save는 성공하되 Finalize는 실패한다.
10. finalize 성공 시 Application.status가 `PENDING_PAYMENT`로 전이되고, 감사 로그 4건(CoF_FINALIZED + APPLICATION_STATUS_CHANGED + LEW_CONSENT_DATE_SET + APPROVED_LOAD_CONFIRMED) 이상이 기록된다.
11. CoF 엔티티에 `@SQLRestriction("deleted_at IS NULL")` + `@SQLDelete` soft delete 패턴이 적용된다.
12. 동시 편집 시 `version` 충돌은 409 + 에러 코드 `COF_VERSION_CONFLICT`를 반환한다.
13. ema-field-jit-plan.md §4의 10개 필드 전원이 본 스펙의 CertificateOfFitness에 매핑된다.

## 9. 마이그레이션 전략

- **이미 COMPLETED / IN_PROGRESS / PAID 상태인 legacy Application**: CoF 레코드 없음(null). UI에서 "CoF Pre-LicenseKaki(수기 기록)" 배지 표시. `Application.legacy_ema_submitted = TRUE` 플래그를 새로 추가하여 finalize 우회(이미 외부 시스템에서 확정). P3 이후 optional "재확인" 플로우에서 LEW가 사후 기입 가능하지만 `certified_at`은 수기 입력 허용.
- **PENDING_REVIEW 상태인 inflight Application**: LEW가 배정되어 있으면 CoF Draft를 새로 만들어 채우도록 강제. PENDING_PAYMENT 전이 조건이 "CoF finalized OR legacy_ema_submitted"로 병합된다.
- **schema.sql 업데이트 + 기존 `resources/db/migration/`의 Flyway 컨벤션 재사용** (예: `V_2026_04_22__add_certificate_of_fitness.sql`).

## 10. 의존 작업

- **LOA 스냅샷 정책**: 기존 `applications.loa_*_snapshot @Column(updatable=false)` 패턴을 유지. CoF는 LOA와 독립이며, LOA 발급 후에도 CoF는 Draft 가능(역순서 발생). 단, finalize 시 LOA가 존재하지 않으면 경고 레벨 로그.
- **OwnershipValidator 재사용**: `ConciergeOwnershipValidator` 구조를 참조하여 `ApplicationAssignmentValidator` (또는 `AppSecurity.isAssignedLew`) 공통 빈 신설. 기존 `@PreAuthorize` 패턴 유지.
- **AuditAction 확장**: `AuditService` 시그니처 변경 없이 enum 상수 추가. 기존 `APPLICATION_UPDATED`의 세분화 정책(ema-pdpa-assessment.md §4)과 일관.
- **SecurityConfig**: `/api/lew/**` 경로가 HttpSecurity matcher에 `hasRole("LEW")` 으로 등록되어 있어야 하며, 현재 `/api/admin/**`가 LEW에게도 허용된 H-4 이슈(kaki-concierge-security-review §H-4)와 별개로 선행 수정 필요.
- **프런트 공통 컴포넌트**: MSSL 4-part input은 신청자 측 "알면 입력" 필드(ema-field-jit-plan.md §6 P2 범위)와 **동일 컴포넌트 재사용**이 가능하도록 `MsslAccountInput.tsx` 독립 컴포넌트로 설계.
