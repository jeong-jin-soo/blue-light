# Phase 1 Plan — Kaki Concierge Service MVP

**PRD 근거**: `/Users/ringo/Projects/blue-light/doc/Project Analysis/kaki-concierge-service-prd.md` v1.4
**보안 사전 검토**: `/Users/ringo/Projects/blue-light/doc/Project Analysis/kaki-concierge-security-review.md`
**UX 플로우**: `/Users/ringo/Projects/blue-light/doc/Project Analysis/kaki-concierge-ux-flows.md`
**디자인 시스템**: `/Users/ringo/Projects/blue-light/doc/Project Analysis/kaki-concierge-design-system.md`
**참조 패턴**: Phase 3 PR#1~#4 (특히 `9584c6c`, `3301995`), Phase 2 PR#4 (LOA 스냅샷 불변), Phase 5 PR#1 (enum 상태 도입 + 마이그레이션)

---

## 0. 플래닝 원칙 & 사이즈 기준

| 항목 | 기준 (Phase 3 회귀 분석 기반) |
|------|------------------------------|
| PR당 추가 LOC | 500 ~ 900 (테스트 포함 시 1200 상한) |
| DB 스키마 변경 | `src/main/resources/schema.sql` 직접 확장 (Flyway 미사용) + `ALTER TABLE ... IF NOT EXISTS` 패턴 |
| 수정 파일 개수 | ≤ 15 (순수 feat), ≤ 25 (+리팩터) |
| 커밋 메시지 | 한국어, "feat/fix: ...(Phase 1 PR#N)" |
| 알림 | `afterCommit` + `REQUIRES_NEW + saveAndFlush` (9584c6c 교훈) |
| 상태 전이 | 도메인 메서드 내부 `assertCanTransitionTo` (DocumentRequest 패턴) |

## 0-1. 🔴 PR#1 착수 전 필수 선결 조건 (보안 리뷰 H-1 ~ H-5)

| # | 차단 이슈 | 선결 조치 |
|---|---------|---------|
| **H-1** | `/api/auth/login`의 status 분기가 이메일 enumeration 허용 (AC-29 설계 모순) | PRD v1.5로 AC-29 재작성 필요 — 비번 검증 선행 후 status 체크, dummy BCrypt 삽입 |
| **H-2** | URL path 토큰이 access log에 평문 기록 | PR#1에 `TokenLogMaskingFilter` 포함 |
| **H-3** | AccountSetupToken 실패 시도 제한 없음 | PR#1 엔티티에 `failedAttempts`/`lockedAt` 포함 |
| **H-4** | `SecurityConfig.java:83`이 `/api/admin/**`을 LEW에게도 허용 → 경로 A LOA 업로드를 LEW가 호출 가능 | **URL 매처 유지 + 컨트롤러 `@PreAuthorize` 개별 제어** (A안 결정) — 기존 LEW 기능 회귀 방지. 신규 CONCIERGE_MANAGER 엔드포인트는 `/api/concierge-manager/**` 별도 prefix로 추가하고, LOA 경로 A 업로드는 `@PreAuthorize("hasAnyRole('CONCIERGE_MANAGER','ADMIN')")`로 LEW 차단. AC-15b 테스트로 강제. |
| **H-5** | 옵션 A(임시 비밀번호 이메일)는 PDPA §24 + OWASP ASVS V2.1.6 위반 | **O-21 서면 결정 없이 PR#3 착수 금지**. 현 계획은 옵션 B 전제 |

---

## 1. PR 분해 (7건)

| # | 제목 | 범위 요약 | 의존성 | 예상 LOC | Flyway |
|---|------|-----------|--------|---------|--------|
| **PR#1** | Backend 도메인 + UserStatus + 통합 가입 인프라 | `ConciergeRequest`, `ConciergeNote`, 상태 머신 enum, `UserStatus`/`SignupSource`/`UserConsentLog` + `User` 필드 확장, `UserRole.CONCIERGE_MANAGER`, `Application`에 LOA 서명 출처 컬럼 4종 + `LoaSignatureSource`, `NotificationType`/`AuditAction` enum 확장, 시드, **SecurityConfig 매처 분리 (H-4)**, **TokenLogMaskingFilter (H-2)** | — | ~900 | V_01 concierge_tables, V_02 application_add_loa_signature_source, V_03 user_add_signup_and_status |
| **PR#2** | Public 접수 API + ConciergeNotifier + Account Setup + 로그인 status 분기 | `POST /api/public/concierge/request` (트랜잭션 원자성), `AccountSetupTokenService`, `GET/POST/resend /api/public/account-setup/{token}`, `ConciergeNotifier` (N1/N2/N1-Alt), `AuthService` 비번 검증 선행 + status 분기 (H-1 해결), `LoginActivationService` (고정 응답+타이밍 동등성) | PR#1 | ~850 | — |
| **PR#3** | Frontend Landing + 신청 모달 + Account Setup + 로그인 활성화 | LandingPage Concierge CTA 섹션, ConciergeRequestPage 모달/풀페이지(5종 동의), 성공 페이지, `/setup-account/:token`, LoginPage `ACCOUNT_PENDING_ACTIVATION` 분기, `conciergeApi.ts`/`accountSetupApi.ts` | PR#2 | ~800 | — |
| **PR#4** | Concierge Manager 대시보드 (Backend API + Frontend) | `/api/concierge-manager/requests` (목록/상세/상태전이/노트/cancel/resend-setup-email), `ConciergeOwnershipValidator`, ConciergeManagerDashboardPage + List/Detail + Timeline/NotesPanel/ActionBar/AccountStatusPanel | PR#2 | ~900 | — |
| **PR#5** | Application 대리 생성 (On-Behalf-Of) | `ApplicationService.createOnBehalfOf(targetUserSeq, managerSeq, req)`, `POST /api/concierge-manager/requests/{id}/applications`, `Application.via_concierge_request_seq` 컬럼, Actor/Ownership 분리, 감사 `APPLICATION_CREATED_ON_BEHALF`, `CONTACTING → APPLICATION_CREATED` 훅, FE "Create on behalf" 버튼 | PR#4 | ~700 | V_04 application_add_via_concierge_request |
| **PR#6** | LOA 서명 수집 — 경로 A (Manager 대리 업로드) | `POST /api/admin/applications/{id}/loa/upload-signature` (multipart, `MimeTypeValidator` + `ImageSanitizer` EXIF 제거), `LoaService.uploadSignature(...)` (기존 `signLoa`와 분리, `embedSignatureIntoPdf()` 공통화), `LoaSignatureNotifier` (N5-UploadConfirm, afterCommit), 감사 `LOA_SIGNATURE_UPLOADED_BY_MANAGER`, FE `LoaSignatureCollectionPanel` 탭 1·2만 활성화 | PR#5 | ~650 | — |
| **PR#7** | Payment referenceType 확장 + Admin Concierge + 동기화 리스너 | `Payment.referenceType/referenceSeq` + `PaymentReferenceType` enum + `application_seq` nullable + backfill, `Payment.of(...)` 팩토리, `AdminConciergeController` (전체 목록/수동 배정/SLA), `Application` 상태 → ConciergeRequest 자동 동기화 이벤트 리스너 | PR#5 | ~700 | V_05 payment_add_reference_type |

**합계**: ~5,500 LOC, Flyway 5건.

---

## 2. 구현 순서 & 의존성 그래프

```
                          ┌──────────────────────────────────────────┐
                          │ PR#1  Domain + UserStatus + Consent       │
                          │   (V_01 / V_02 / V_03 + H-2/H-3/H-4)      │
                          └──────────────────────┬───────────────────┘
                                                 │
                            ┌────────────────────┼───────────────────┐
                            ▼                    ▼                   ▼
                 ┌──────────────────┐
                 │ PR#2  Public API │
                 │  + Notifier      │
                 │  + Account Setup │
                 │  + Auth 분기(H-1)│
                 └─────┬────────────┘
                       │
        ┌──────────────┼──────────────┐
        ▼              ▼              ▼
   ┌─────────┐    ┌────────────┐
   │ PR#3 FE │    │ PR#4 Mgr    │   (FE·BE 병렬)
   │ Landing │    │ Dashboard   │
   └─────────┘    └─────┬──────┘
                        │
                        ▼
                  ┌─────────────┐
                  │ PR#5 OBO App │  (V_04)
                  └─────┬────────┘
                        │
              ┌─────────┼──────────┐
              ▼                    ▼
         ┌──────────┐        ┌──────────────┐
         │ PR#6 LOA │        │ PR#7 Admin + │  (V_05)
         │ 경로 A    │        │ Payment ext  │
         └──────────┘        └──────────────┘
```

**크리티컬 패스**: PR#1 → PR#2 → PR#4 → PR#5 → PR#6 (5건 직렬).
**병렬 가능**: PR#3 (PR#2 완료 후 FE 팀이 PR#4와 동시에), PR#7 (PR#5 완료 후 PR#6과 동시에).

---

## 3. 각 PR별 핵심 설계 결정

### PR#1 — Domain + UserStatus + Consent

**신규 엔티티**

| 엔티티 | 테이블 | 핵심 필드 | 주석 |
|--------|--------|----------|------|
| `ConciergeRequest` | `concierge_requests` | `conciergeRequestSeq PK`, `publicCode UK` (`C-YYYY-NNNN`), `submitterName/Email/Phone/Memo`, `applicantUser FK → users`, `assignedManager FK → users`, `application FK → applications (nullable)`, `payment FK → payments (nullable)`, `status ConciergeRequestStatus`, 동의 3종 timestamp, 상태 전이 timestamp | `BaseEntity`, `@SQLDelete/@SQLRestriction`, `@Version` |
| `ConciergeNote` | `concierge_notes` | `noteSeq`, FK, `author`, `channel`, `content(≤2000)` | `BaseEntity` |
| `UserConsentLog` | `user_consent_logs` | `logSeq`, `user FK`, `consentType`, `action`, `version`, `sourceContext`, `ipAddress`, `userAgent`, `createdAt (불변)` | `@Column(updatable=false)`, soft delete 미적용 |
| `AccountSetupToken` | `account_setup_tokens` | `tokenUuid UK`, `user FK`, `expiresAt`, `usedAt`, **`failedAttempts`**, **`lockedAt`** (H-3) | 48h TTL |

**enum 추가**
- `ConciergeRequestStatus` 9종 + `canTransitionTo(next)`
- `NoteChannel`: PHONE / EMAIL / WHATSAPP / IN_PERSON / OTHER
- `UserStatus`: PENDING_ACTIVATION / ACTIVE / SUSPENDED / DELETED
- `SignupSource`: DIRECT_SIGNUP / CONCIERGE_REQUEST / ADMIN_INVITE
- `LoaSignatureSource`: APPLICANT_DIRECT / MANAGER_UPLOAD / REMOTE_LINK
- `UserRole`에 `CONCIERGE_MANAGER`

**기존 엔티티 변경점**

| 대상 | 변경 | 정책 |
|------|------|------|
| `User` | `status`, `activatedAt` (`updatable=false`), `firstLoggedInAt`, `signupSource`, `signupConsentAt`, `termsVersion`, `marketingOptIn`, `marketingOptInAt` | 전이는 도메인 메서드만 (`activate()`, `suspend()`, `softDelete()`) |
| `Application` | LOA 4종: `loaSignatureSource`, `loaSignatureUploadedBy FK`, `loaSignatureUploadedAt`, `loaSignatureSourceMemo` | `updatable=false` (스냅샷 불변 정책 준용) |
| `SecurityConfig` (H-4) | `/api/admin/**` 매처를 ADMIN 전용으로, LEW/CONCIERGE_MANAGER는 각자 경로 prefix | `SecurityConfigTest` 전체 role × 엔드포인트 매트릭스 검증 |

**신규 필터 (H-2)**: `TokenLogMaskingFilter` — `/account-setup/{token}`, `/sign/{token}` 경로의 토큰 부분을 access log/MDC에서 마스킹.

**Flyway**
```
V_01_concierge_request_tables.sql    (concierge_requests, concierge_notes, user_consent_logs, account_setup_tokens)
V_02_application_add_loa_signature_source.sql
V_03_user_add_signup_and_status.sql  (backfill: 기존 User → ACTIVE, deleted_at 일치 → DELETED)
```

### PR#2 — Public API + Notifier + Account Setup + Login 분기

**서비스 레이어**
```
api/concierge/
  ConciergeController (POST /api/public/concierge/request)
  ConciergeService (트랜잭션 원자성)
  ConciergeNotifier (afterCommit, REQUIRES_NEW, saveAndFlush)
  ConciergeCaseResolver (C1~C5 분기)
  dto/ConciergeRequestCreateRequest (@AssertTrue 4종)

api/auth/
  AccountSetupController (/api/public/account-setup/**)
  AccountSetupService
  AccountSetupTokenService
  LoginActivationService (고정 응답 + 타이밍 동등성)

api/auth/AuthService (수정 — H-1: 비번 검증 선행 → status 분기)
```

**H-1 해결 패턴 (로그인 enumeration 방어)**
```
1. 이메일 존재 여부와 관계없이 BCrypt.verify 실행 (dummy hash 포함)
2. 비번 검증 실패 시 → 401 INVALID_CREDENTIALS (status 노출 금지)
3. 비번 검증 성공 시에만 status 분기:
   - ACTIVE → JWT 발급
   - PENDING_ACTIVATION → 401 ACCOUNT_PENDING_ACTIVATION + 활성화 CTA
   - SUSPENDED → 403 ACCOUNT_SUSPENDED
   - DELETED → 401 INVALID_CREDENTIALS (존재 감춤)
4. p95 < 200ms 타이밍 동등성 통합 테스트로 회귀 검증
```

**케이스 분기 (PRD §7.7)**

| 케이스 | 조건 | 처리 |
|--------|------|------|
| C1 | 이메일 미존재 | 신규 User 생성(PENDING_ACTIVATION), N1 |
| C2 | 기존 APPLICANT + ACTIVE | 기존 연결, N1-Alt |
| C3 | 기존 APPLICANT + PENDING_ACTIVATION | 기존 재사용, 토큰 재발급 |
| C4 | 기존 APPLICANT + SUSPENDED/DELETED | 409 ACCOUNT_NOT_ELIGIBLE |
| C5 | 스태프 계정 | 422 STAFF_BLOCKED |

### PR#3 — Frontend (옵션 B 전용, O-21 서면 확정 후 착수)

- `LandingPage` Concierge CTA 섹션 (히어로 직하, outline 버튼 — UX/디자인 섹션 근거)
- `ConciergeRequestPage` — 데스크톱 모달, 모바일 풀페이지, `ConsentChecklist` 컴포넌트 5종
- `AccountSetupPage` (`/setup-account/:token`) — 3-step 마법사 + 비밀번호 강도 미터
- `LoginPage` 401 `ACCOUNT_PENDING_ACTIVATION` 분기 → `ActivationLinkPanel`
- 공통 고정 메시지 ("If eligible, we've sent a link.") + 클라이언트 debounce 3s

### PR#4 — Manager Dashboard

**엔드포인트 prefix 분리**

| 경로 | 권한 |
|------|------|
| `GET /api/concierge-manager/requests` | CONCIERGE_MANAGER, ADMIN |
| `GET /api/concierge-manager/requests/{id}` | 본인 담당 + ADMIN |
| `PATCH /api/concierge-manager/requests/{id}/status` | 본인 담당 + ADMIN |
| `POST /api/concierge-manager/requests/{id}/notes` | 본인 담당 + ADMIN |
| `POST /api/concierge-manager/requests/{id}/resend-setup-email` | 본인 담당 + ADMIN |
| `PATCH /api/concierge-manager/requests/{id}/cancel` | 본인 담당 + ADMIN |

`ConciergeOwnershipValidator.assertManagerCanAccess(requestSeq, actorSeq)` — ADMIN 전체, MANAGER는 `assigned_manager_seq == actor`만.

### PR#5 — Application On-Behalf-Of

```java
ApplicationService.createOnBehalfOf(Long targetApplicantSeq, Long managerSeq, CreateApplicationRequest req)
  // Ownership = targetApplicant (Application.user)
  // Actor = Manager (@CreatedBy via SecurityContext)
  // Set Application.viaConciergeRequestSeq (updatable=false)
  // ConciergeRequest.linkApplication(app) → APPLICATION_CREATED 전이
  // Audit APPLICATION_CREATED_ON_BEHALF (actorSeq=manager, subjectSeq=applicant)
```

V_04: `applications.via_concierge_request_seq BIGINT NULL + FK + index`.

### PR#6 — LOA 경로 A (Manager 업로드)

**엔드포인트**: `POST /api/admin/applications/{id}/loa/upload-signature`
- 권한: `CONCIERGE_MANAGER`, `ADMIN` (H-4 분리 후)
- Body(multipart): `signature` (PNG/JPG ≤ 2MB), `memo` (선택)
- 검증: `MimeTypeValidator` (매직바이트) + `ImageSanitizer` (EXIF 제거, 신규 유틸)
- `source = MANAGER_UPLOAD` 서버 세팅 (클라이언트 입력 ❌)

**LoaService 분리**
- 기존 `signLoa(userSeq, appSeq, sig)` — APPLICANT Only, 그대로 유지
- 신규 `uploadSignature(managerSeq, appSeq, file, memo)`:
  - `application.status == PENDING_REVIEW` + `ConciergeRequest.status == AWAITING_APPLICANT_LOA_SIGN` 검증
  - `embedSignatureIntoPdf()` 공통 호출
  - `application.recordLoaSignatureSource(MANAGER_UPLOAD, managerSeq, memo)` — 최초 1회만 (재호출 시 `IllegalStateException`)
  - `ConciergeRequest.markLoaSigned()` → `AWAITING_LICENCE_PAYMENT`

**알림**: `LoaSignatureNotifier.notifyUploadConfirm(...)` — N5-UploadConfirm + 이의 제기 CTA, `HtmlUtils.htmlEscape` 강제.

### PR#7 — Payment Ref Type + Admin + Sync

```java
@Column(name = "reference_type", length = 30, nullable = false)
@Enumerated(EnumType.STRING)
private PaymentReferenceType referenceType;  // APPLICATION / CONCIERGE_REQUEST / SLD_ORDER

@Column(name = "reference_seq", nullable = false)
private Long referenceSeq;
```

V_05:
```sql
ALTER TABLE payments ADD COLUMN reference_type VARCHAR(30), ADD COLUMN reference_seq BIGINT;
UPDATE payments SET reference_type='APPLICATION', reference_seq=application_seq WHERE reference_type IS NULL;
ALTER TABLE payments MODIFY reference_type VARCHAR(30) NOT NULL, MODIFY reference_seq BIGINT NOT NULL,
  MODIFY application_seq BIGINT NULL;
CREATE INDEX idx_payment_reference ON payments(reference_type, reference_seq);
```

Application status → ConciergeRequest 동기화: Spring `ApplicationEventPublisher` 기반.
- `PENDING_REVIEW` → `AWAITING_LICENCE_PAYMENT` 유지
- `PAID`/`IN_PROGRESS` → `markLicencePaid()` → `IN_PROGRESS`
- `COMPLETED` → ConciergeRequest `COMPLETED` + N7

---

## 4. 리스크 & 완화책 (보안 리뷰 통합)

| # | 리스크 | 완화책 |
|---|--------|--------|
| R1 | 이메일 enumeration (H-1) | AC-29 재작성 + dummy BCrypt + 타이밍 동등 테스트 (p95 <200ms 회귀 검증) |
| R2 | 동일 이메일 동시 신청 race | DB UNIQUE + `DataIntegrityViolation` catch → C2/C3 재분기 + 이메일 정규화 |
| R3 | `publicCode` 충돌 | DB UK + 동일 트랜잭션 재시도 1회 |
| R4 | User backfill 마이그레이션 | DEFAULT='ACTIVE' ADD → Backfill → NOT NULL 3단계, staging prod snapshot 리플레이 |
| R5 | afterCommit 영속화 실패 (9584c6c) | `REQUIRES_NEW + saveAndFlush`, `DocumentRequestNotifierTest` 패턴 회귀 |
| R6 | XSS (submitter_memo, note.content, loa.memo) | `HtmlUtils.htmlEscape`, FE는 `dangerouslySetInnerHTML` 금지 |
| R7 | Ownership 우회 | `ConciergeOwnershipValidator` + Manager A → B 접근 403 테스트 3종 |
| R8 | `createdBy` 오염 | `AuditorAware` 통합 테스트 + `APPLICATION_CREATED_ON_BEHALF` 감사에 actor/subject 명시 |
| R9 | `activatedAt` updatable=false 누수 | JPA enforce + save 후 reflection 재설정 검증 |
| R10 | CONCIERGE_MANAGER 역할 추가로 권한 회귀 (H-4) | `SecurityConfigTest` 전수 매트릭스 |
| R11 | 토큰 access log 유출 (H-2) | `TokenLogMaskingFilter` + MDC 마스킹 |
| R12 | 토큰 무제한 시도 (H-3) | `failedAttempts`, `lockedAt` + `markAsUsedIfNotUsed` 원자적 UPDATE |
| R13 | 업로드 파일 취약점 (경로 A) | `MimeTypeValidator` 재사용 + `ImageSanitizer` (EXIF 제거) 신규 |

---

## 5. Phase 1 제외 범위 (스코프 경계)

| 항목 | 이관 |
|------|------|
| 경로 B (원격 서명 링크/QR, SMS, ZXing, OTP) | Phase 2 |
| 옵션 A (임시 비밀번호) | Phase 2 선택과제 (O-21) |
| Manager 캐파시티 체크 | Phase 4 |
| 실시간 이메일 체크 UX | Phase 2 |
| 리마인더 스케줄러 (N1-R/N5-R/N9) | Phase 2 |
| 약관 CMS / 마케팅 unsubscribe | Phase 3 |
| signup_completed 컬럼 제거 (R2) | Phase 2 말미 (2주 관측 후) |
| Concierge 서비스요금 실제 결제 플로우 | Phase 2 (Phase 1은 스키마 확장만) |

---

## 6. 참조 파일

- `blue-light-backend/src/main/java/com/bluelight/backend/api/document/DocumentRequestNotifier.java` — afterCommit 레퍼런스
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/user/User.java` — UserStatus 확장 대상
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/application/Application.java` — LOA 서명 출처 컬럼
- `blue-light-backend/src/main/java/com/bluelight/backend/api/loa/LoaService.java` — 경로 A `uploadSignature` 분리
- `blue-light-backend/src/main/java/com/bluelight/backend/domain/payment/Payment.java` — referenceType 확장
- `blue-light-backend/src/main/java/com/bluelight/backend/config/SecurityConfig.java` — H-4 매처 분리
- `blue-light-backend/src/main/java/com/bluelight/backend/common/validator/OwnershipValidator.java` — CONCIERGE_MANAGER 오버로드
- `blue-light-backend/src/main/java/com/bluelight/backend/common/validator/MimeTypeValidator.java` — PR#6 재사용
