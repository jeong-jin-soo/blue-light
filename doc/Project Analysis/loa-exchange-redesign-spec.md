# 신청 LoA(Letter of Appointment) 교환 동선 재설계 — 정식 스펙

- 문서 버전: v1.0 (2026-06-14)
- 작성: product-manager 에이전트
- 발주자: 제품 오너 (ringo@contigo.im)
- 상태: **구현 대기** — 본 문서를 developer 핸드오프 정본으로 사용
- 관련 문서: `doc/Project Analysis/lew-review-flow-roadmap.md`(옵션 R/Y), `doc/Project Analysis/ema-field-jit-plan.md`, `CLAUDE.md §설계 원칙`

---

## 요구사항 요약

플랫폼이 LoA 완성본을 자동 생성하고 신청자가 인앱 디지털 서명하던 기존 방식을 폐기하고, **admin이 버전 관리하는 최신 LoA 폼**을 신청자가 내려받아 **오프라인 서명 후 업로드**하면, **LEW가 항목을 보완해 최종본을 업로드하고 EMA에 외부 제출**하는 실제 업무 흐름으로 재설계한다. 동시에 **CoF(Certificate of Fitness) 기능을 완전 제거**한다.

---

## 0. 코드 근거 현황 (착수 전 확인된 사실)

| 항목 | 현황 | 근거 (file:line) |
|---|---|---|
| LoA 자동생성 | `generateLoa`가 NEW는 자동생성(`generateNewLicenceLoa`), RENEWAL은 400 차단 | `LoaService.java:69-140`, `LoaController.java:29-34` |
| 디지털 서명 | **이미 비활성화**(2026-06-13). `signLoa`·`uploadLoaSignature` 컨트롤러가 즉시 `SignatureDisabled.exception()` throw. 서비스 로직은 복구용 보존 | `LoaController.java:40-48, 68-81` |
| Manager 대리 서명 | 서비스 `uploadSignatureByManager` 존재하나 컨트롤러 가드로 차단됨 | `LoaService.java:212-336`, `LoaController.java:68-81` |
| LoA 법적 스냅샷 | `recordLoaSnapshot`(`@Column updatable=false` 4종 + phone/email) — 생성 시점 신원 보존 | `Application.java:247-294, 811-838` |
| 서명 출처 모델 | `loaSignatureSource`(APPLICANT_DIRECT/MANAGER_UPLOAD/REMOTE_LINK), `loaSignatureUrl`, `loaSignedAt` | `Application.java:189-231, 863-899` |
| RENEWAL 업로드 | 프론트가 `adminApi.uploadFile(id, file, 'OWNER_AUTH_LETTER')` 사용 (전용 LoA 업로드 엔드포인트 없음) | `AdminApplicationDetailPage.tsx:312-315`, `AdminLoaSection.tsx:55-59` |
| 상태 전이 맵 | `validateStatusTransition` switch | `AdminApplicationService.java:365-383` |
| 결제 게이트(LEW) | `requestPayment` — kVA CONFIRMED + 미해결 DocumentRequest 0 | `LewReviewService.java:87-152` |
| 결제 게이트(ADMIN) | `approveForPayment` — kVA UNKNOWN 차단 | `AdminApplicationService.java:316-347` |
| 완료 게이트 | `completeApplication` — IN_PROGRESS→COMPLETED, CoF 가드 **이미 롤백됨** | `AdminApplicationService.java:243-279` |
| **CoF 백엔드** | **이미 제거 완료**(main). `domain/cof/` 부재, `schema.sql:399, 1461-1466`에 "(제거됨)" + 운영 DROP 가이드 | `schema.sql:399, 1134, 1461-1466` |
| **CoF 프론트** | **23개 파일 잔존** — `types/cof.ts`, `constants/cof.ts`, `pages/lew/sections/CofStep*.tsx` 3종, `router/index.tsx:314`, `lewReviewApi.ts` 등 | Grep 결과 |
| DocumentRequest 재사용 | `LOA → OWNER_AUTH_LETTER` 매핑 존재, 자발적 업로드 흐름 가동 | `DocumentRequestService.java:74-82` |
| admin 파일 업로드 패턴 | PayNow QR: `fileStorageService.store(file, "settings")` + system_settings 경로 저장 + 기존 파일 delete | `AdminPriceSettingsController.java:105-142` |

> **중요 정정**: 배경 자료의 "generateLoa·signLoa·CoF 제거 진행 중"은 일부는 **이미 완료**되어 있다. signLoa·uploadSignatureByManager는 컨트롤러 가드로 비활성화됐고(서비스 로직 보존), CoF 백엔드 도메인은 main에서 삭제됐다. 따라서 본 스펙의 retire 작업은 **잔존 코드 정리 + 프론트 제거**가 핵심이다.

---

## 1. 목표 동선 + 상태머신 변화

### 1.1 NEW Licence 동선

```
[신청자] 신청 제출 ───────────────────────────────► PENDING_REVIEW
                                                        │
[LEW] 정보(서류) 요청 ── DocumentRequest [재사용] ◄────┤
[신청자] 서류 업로드 ──────────────────────────────────┤
[LEW] kVA 확정 ── confirmKva [재사용] ─────────────────┤
[LEW] ★ active LoA 폼 신청자에게 전달(노출)  [신규]      │
[신청자] ★ 폼 다운로드 → 오프라인 서명 → 서명본 업로드 [신규] (디지털 서명 안 함)
                                                        │
[LEW/ADMIN] 결제 요청 ── 게이트에 "서명 LoA 수령" 추가 [신규 게이트]
                                                        ▼
                                                  PENDING_PAYMENT
[ADMIN] 입금 확인 [재사용] ─────────────────────────────►  PAID
                                                        ▼
[LEW] 신청자 LoA 다운로드 → 보완 → ★ 최종본 업로드 [신규] → IN_PROGRESS
[LEW] EMA 외부 제출 (플랫폼 외부 행위, 기록만)
                                                        ▼
[LEW/ADMIN] 라이선스 발급 [재사용] ────────────────────►  COMPLETED → 신청자 알림
```

### 1.2 RENEWAL 동선 (플랫폼이 폼 미제공)

```
[신청자] 신청 제출 (+ LoA 있으면 첨부) ───────────────► PENDING_REVIEW
[LEW] LoA 미첨부 시 DocumentRequest로 요청 [재사용] ──── (신청자 업로드)
[LEW] kVA 확정 → 결제 요청 → [ADMIN] 입금 확인 [재사용] ► PAID
[LEW] LoA 다운로드 → 항목 입력 → ★ 최종본 업로드 [신규, NEW 8단계와 공통] → IN_PROGRESS
[LEW/ADMIN] 라이선스 발급 [재사용] ────────────────────► COMPLETED
```

### 1.3 상태머신 변화

`Application.status` enum **변경 없음**. 전이 맵(`validateStatusTransition`)도 **변경 없음**. 본 재설계는 상태 enum이 아니라 **LoA 진행 단계(서브-상태)를 별도 필드로 추적**하고, 기존 상태 전이의 **게이트(전제조건)만 보강**한다.

- 결제 진입 게이트(`PENDING_REVIEW/REVISION_REQUESTED → PENDING_PAYMENT`)에 "서명 LoA 수령" 조건 추가 — **NEW 전용**.
- IN_PROGRESS 진입 또는 COMPLETED 전이의 선행조건으로 "최종 LoA 업로드 완료"를 추가 (→ §4.4 결정 D-2 참조).

---

## 2. 데이터 모델

### 2.1 신규 테이블 `loa_form_templates` (NEW 전용, 버전 관리)

설정 우선 원칙(SSOT)에 따라 admin이 관리하는 LoA 폼을 버전으로 보존하고, 신청별로 사용 폼 버전을 추적한다.

```sql
CREATE TABLE loa_form_templates (
    loa_form_template_seq   BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    label                   VARCHAR(150) NOT NULL,         -- 운영용 표시 라벨 (예: "EMA NEW LoA v2026.06")
    file_seq                BIGINT       NOT NULL,         -- files.file_seq FK (저장된 폼 PDF)
    is_active               BOOLEAN      NOT NULL DEFAULT FALSE,
    uploaded_by             BIGINT       NOT NULL,         -- users.user_seq FK
    uploaded_at             DATETIME     NOT NULL,
    created_at              DATETIME     NOT NULL,
    updated_at              DATETIME     NOT NULL,
    created_by              BIGINT,
    updated_by              BIGINT,
    deleted_at              DATETIME     NULL,             -- soft delete
    CONSTRAINT uq_loa_form_one_active                      -- 동시 active 1건 보장 (아래 주석)
        ... (애플리케이션 레벨 단일화, MySQL partial unique 미지원)
);
```

- **active 단일성**: MySQL 8.0은 부분 유니크 인덱스 미지원 → "신규 폼 활성화 시 기존 active를 트랜잭션 내에서 비활성화"하는 서비스 레벨 보장. (PayNow QR의 "기존 삭제 후 신규" 패턴과 동형 — `AdminPriceSettingsController.java:118-138`).
- **soft delete**: 프로젝트 표준(`@SQLDelete + @SQLRestriction`) 적용. 단, 이미 신청에 참조된 버전은 hard delete 금지 — 법적 추적성.
- **파일 저장**: `fileStorageService.store(file, "loa-form-templates")` (S3 + AES-256). PayNow QR과 동일 패턴.

> **트레이드오프 (단일 덮어쓰기 대안)**: `system_settings`에 단일 경로만 저장(`loa_form_template_path`)하는 방식이 가장 단순하나, (1) 폼이 자주 바뀌고 (2) LoA가 법적 문서라 "신청 X가 어느 버전 폼으로 서명됐는가" 추적이 불가능해진다. → **버전 테이블 채택**. 운영 부담은 admin UI에서 "현재 active만 표시 + 과거 버전 접기"로 흡수.

### 2.2 신청별 사용 폼 버전 추적 (Application 신규 컬럼)

```
loa_form_template_seq   BIGINT NULL    -- 신청자에게 전달된 LoA 폼 버전 (NEW 전용, FK loa_form_templates)
```

- LEW가 "폼 전달" 액션을 수행한 시점의 active 버전을 **스냅샷으로 고정**(이후 폼이 교체돼도 이 신청은 전달받은 버전 유지). `updatable` 허용(전달은 신청 생성 후 발생) + 도메인 가드로 최초 1회 고정.

### 2.3 LoA 진행 상태 필드 재정의 (Application)

기존 `loaSignatureUrl`/`loaSignedAt`/`loaSignatureSource`(디지털 서명 모델)는 **신규 동선과 의미가 어긋난다**(인앱 서명이 사라짐). 다음 명시적 상태 머신으로 재정의한다.

**신규 enum `LoaStage`** (`Application.loaStage`, `@Enumerated(STRING)`, NOT NULL, 기본 `NOT_STARTED`):

| 값 | 의미 | 전이 트리거 |
|---|---|---|
| `NOT_STARTED` | 초기 | 신청 생성 |
| `FORM_SENT` | LEW가 폼 전달 (NEW 전용) | LEW "폼 전달" 액션 |
| `APPLICANT_UPLOADED` | 신청자가 서명본 업로드 | 신청자 LoA 업로드 |
| `FINAL_UPLOADED` | LEW 최종본 업로드 완료 | LEW 최종본 업로드 |

- RENEWAL은 `FORM_SENT`를 건너뛸 수 있음(신청자 지참 첨부 시 바로 `APPLICANT_UPLOADED`, 또는 DocumentRequest로 업로드).
- 결제 게이트(NEW)는 `loaStage ∈ {APPLICANT_UPLOADED, FINAL_UPLOADED}` 요구.
- 완료/IN_PROGRESS 게이트는 `loaStage = FINAL_UPLOADED` 요구 (→ D-2).

**보존 필드**: `loaApplicantNameSnapshot` 등 스냅샷 6종은 **유지**(법적 무결성). 단 트리거를 "generateLoa 시점"에서 "신청자 LoA 업로드 시점"으로 이동(생성이 사라지므로). `recordLoaSnapshot` 멱등 가드(`Application.java:827`)는 그대로 활용.

**디프리케이트 필드**: `loaSignatureUrl`, `loaSignedAt`, `loaSignatureSource`, `loaSignatureUploadedBy`, `loaSignatureUploadedAt`, `loaSignatureSourceMemo` — 신규 동선에서 미사용. **즉시 DROP하지 말고** §6 PR5(컨시어지)에서 처리 결정. 운영 데이터가 purge되어 마이그레이션 부담은 낮음.

### 2.4 CoF 제거 항목

- 백엔드: **이미 제거됨**(확인). `schema.sql:1461-1466` 운영 DROP 가이드대로 운영 DB에 `DROP TABLE certificate_of_fitness; ALTER TABLE kva_adjustment_record DROP COLUMN cof_reissue_triggered;` 적용 필요(운영자 작업).
- 프론트: 23개 파일 잔존 → §6 PR1에서 제거 (목록은 §7.2).

---

## 3. API 엔드포인트 명세

### 3.1 신규 — admin LoA 폼 템플릿 CRUD (`/api/admin/loa-form-templates`)

권한: `hasAnyRole('ADMIN','SYSTEM_ADMIN')` (LEW 제외 — 폼 관리는 admin 책임).

| 메서드 | 경로 | 설명 | 비고 |
|---|---|---|---|
| GET | `/api/admin/loa-form-templates` | 전체 버전 목록(active 표시) | soft-deleted 제외 |
| POST | `/api/admin/loa-form-templates` | 신규 폼 업로드(multipart: `file`, `label`) → FileEntity + 레코드 생성. 옵션 `activate=true` | PayNow 업로드 패턴 재사용 |
| PATCH | `/api/admin/loa-form-templates/{seq}/activate` | 해당 버전 활성화 + 기존 active 비활성화(동일 트랜잭션) | active 단일성 보장 |
| DELETE | `/api/admin/loa-form-templates/{seq}` | soft delete (신청에 참조된 버전은 409 `LOA_FORM_IN_USE`) | |
| GET | `/api/admin/loa-form-templates/{seq}/download` | 폼 파일 다운로드(admin 검수용) | |

### 3.2 신규 — active 폼 소비 (LEW/신청자, 설정 우선)

| 메서드 | 경로 | 권한 | 설명 |
|---|---|---|---|
| GET | `/api/applications/{id}/loa/active-form` | Owner / 담당 LEW / ADMIN | 현재 신청에 적용 가능한 active LoA 폼 메타(파일 seq, label) 반환. NEW 전용; RENEWAL은 404 `LOA_FORM_NOT_APPLICABLE` |
| GET | `/api/applications/{id}/loa/active-form/download` | 동일 | 신청자가 폼 PDF 다운로드 |

> 하드코딩 금지(설계 원칙): 신청자/LEW UI는 폼 URL을 코드에 박지 않고 위 엔드포인트로 active 버전을 로드한다.

### 3.3 신규/변경 — LoA 파일 업로드

| 메서드 | 경로 | 권한 | 설명 | 상태 효과 |
|---|---|---|---|---|
| POST | `/api/lew/applications/{id}/loa/send-form` | 담당 LEW (`@appSec.isAssignedLew`) | NEW: active 폼 버전을 신청에 고정 + 신청자 알림 | `loaStage → FORM_SENT`, `loaFormTemplateSeq` 고정 |
| POST | `/api/applications/{id}/loa/applicant-upload` | Owner | 신청자가 **오프라인 서명본** 업로드(multipart `file`). MIME PDF/JPG/PNG, 크기 제한. FileEntity(OWNER_AUTH_LETTER) 생성 + 스냅샷 기록 | `loaStage → APPLICANT_UPLOADED` |
| POST | `/api/lew/applications/{id}/loa/final-upload` | 담당 LEW | LEW가 보완한 **최종본** 업로드. 별도 FileType `LOA_FINAL` 신설(신청자 서명본과 구분) | `loaStage → FINAL_UPLOADED` |
| GET | `/api/applications/{id}/loa/status` | Owner/LEW/ADMIN | **변경**: `loaGenerated/loaSigned` → `loaStage` + 파일 seq 2종(applicant/final) 반환 | — |

> **신규 FileType `LOA_FINAL`**: 신청자 서명본(`OWNER_AUTH_LETTER`)과 LEW 최종본을 구분해 다운로드/감사에서 혼동 방지. `FileType.java`에 추가.

### 3.4 변경 — 결제 게이트 ("서명 LoA 수령" 추가)

- `LewReviewService.requestPayment` (`LewReviewService.java:87`) + `AdminApplicationService.approveForPayment` (`AdminApplicationService.java:317`) 두 경로 모두에 게이트 추가:
  - **NEW**: `loaStage ∈ {APPLICANT_UPLOADED, FINAL_UPLOADED}` 아니면 409 `LOA_NOT_RECEIVED`.
  - **RENEWAL**: LoA가 첨부됐거나 DocumentRequest로 업로드됐는지(= OWNER_AUTH_LETTER 파일 존재) 확인. 미충족 시 동일 코드. (단 RENEWAL은 결제 후 LEW 입력 흐름이므로 → D-1에서 "결제 전/후 어느 시점에 LoA 필수인가" 확정 필요.)
- 신규 에러코드 `LOA_NOT_RECEIVED`를 `LewReviewErrorCode`에 추가(409).

### 3.5 변경 — 완료/IN_PROGRESS 게이트

- 기존 CoF 가드(`COF_NOT_FINALIZED`)는 **이미 롤백됨**. 이를 "최종 LoA 업로드 완료" 게이트로 대체:
  - 게이트 위치는 D-2 결정에 따라 `PAID → IN_PROGRESS` 전이 또는 `IN_PROGRESS → COMPLETED`(`completeApplication`, `AdminApplicationService.java:243`)에 배치.
  - 위반 시 409 `LOA_FINAL_NOT_UPLOADED`.

### 3.6 삭제(retire) 엔드포인트

| 경로 | 처리 |
|---|---|
| `POST /api/admin/applications/{id}/loa/generate` (`LoaController.java:29`) | **삭제** — 자동생성 폐기 |
| `POST /api/applications/{id}/loa/sign` (`LoaController.java:40`) | **삭제** — 이미 비활성, 정식 제거 |
| `POST /api/admin/applications/{id}/loa/upload-signature` (`LoaController.java:68`) | **삭제 또는 재용도** — 컨시어지 영향(§5.1) |
| CoF 관련 LEW 엔드포인트 | 백엔드 이미 제거, 프론트 호출부 제거 |

---

## 4. 화면(Frontend)

### 4.1 admin — Settings > LoA Forms (신규)

- 위치: admin Settings 영역 (PayNow QR 설정과 동일 레벨).
- 구성: active 폼 카드(라벨/업로드일/업로더/다운로드) + "Upload new version"(파일+라벨) + 과거 버전 목록(접기, activate/soft-delete). PayNow QR 업로드 UI 컴포넌트 패턴 재사용.

### 4.2 신청자 상세 — LoA 섹션 (재작성)

- 기존 디지털 서명/캔버스 제거.
- NEW: `loaStage`에 따라 (1) `FORM_SENT` 전: "LEW 검토 대기" 안내 (2) `FORM_SENT`: "LoA 폼 다운로드" + "서명 후 업로드" (3) `APPLICANT_UPLOADED+`: 업로드 완료 표시 + 재업로드.
- RENEWAL: 신청 단계에서 "보유 LoA 첨부(선택)" + 상세에서 DocumentRequest 응답 업로드.

### 4.3 LEW 검토 — LOA 탭 (재작성, `AdminLoaSection.tsx`)

- 현재 generate/RENEWAL-upload 분기 제거.
- 신규 액션: NEW에서 "Send LoA form to applicant"(active 폼 전달) → 신청자 업로드 대기 표시 → "Download applicant LoA" → "Upload final LoA"(보완본). RENEWAL은 폼 전달 없이 다운로드+최종본 업로드만.
- "EMA submitted" 체크/메모(외부 제출 사실 기록용, 선택) — D-3.

### 4.4 LEW — CoF 3-step 화면 제거

- `pages/lew/sections/CofStep*.tsx` 3종, `router/index.tsx:314` 라우트, `LewReviewFormPage` 내 CoF 단계 제거. LEW Review Form은 "검토 + 서류 + kVA" 흐름만 남김.

---

## 5. 역할 × 액션 권한표

| 액션 | APPLICANT | LEW(담당) | ADMIN | SYSTEM_ADMIN | CONCIERGE_MGR |
|---|:--:|:--:|:--:|:--:|:--:|
| LoA 폼 템플릿 CRUD | ✗ | ✗ | ✓ | ✓ | ✗ |
| active 폼 조회/다운로드(신청) | ✓(본인) | ✓ | ✓ | ✓ | △(담당) |
| 폼 전달(send-form) | ✗ | ✓ | ✓ | ✓ | △(D-4) |
| 신청자 서명본 업로드 | ✓(본인) | ✗ | ✓(대리) | ✓ | △(D-4) |
| LEW 최종본 업로드 | ✗ | ✓ | ✓ | ✓ | ✗ |
| 결제 요청 | ✗ | ✓ | ✓ | ✓ | ✗ |
| 입금 확인 | ✗ | ✗ | ✓ | ✓ | ✗ |
| 라이선스 발급(완료) | ✗ | ✓ | ✓ | ✓ | ✗ |

△ = 컨시어지 대체안(§5.1, D-4) 확정 후 결정.

---

## 5.1 영향분석 — 컨시어지

`uploadSignatureByManager`(`LoaService.java:212-336`)는 (1) 자동생성된 LoA PDF에 (2) Manager가 받은 서명 이미지를 임베드하고 (3) `ConciergeRequest`를 `AWAITING_APPLICANT_LOA_SIGN → markLoaSigned()`로 전이시킨다. 자동생성·이미지 임베드가 폐기되면 이 경로 전체가 깨진다.

**대체안 (권장)**: 컨시어지 Manager도 신규 모델에서는 "신청자 서명본 업로드"(§3.3 applicant-upload)를 **대리 수행**하는 것으로 통일. 즉 Manager가 신청자에게서 받은 **서명된 PDF 파일**을 업로드 → `loaStage → APPLICANT_UPLOADED` + ConciergeRequest 전이. 이미지 임베드 단계가 사라지므로 로직이 단순해진다.

**필요 조정**:
- `ConciergeRequestStatus.AWAITING_APPLICANT_LOA_SIGN` 의미를 "서명 이미지 수집"→"서명본 PDF 수령"으로 재해석(enum 명칭 유지 가능).
- A-36(`CONCIERGE_LOA_UPLOAD_CONFIRM`, 7일 이의 제기) 알림은 그대로 재사용 가능.
- `LoaSignatureSource.MANAGER_UPLOAD` 의미는 보존되나, `loaStage` 모델로 이동 시 매핑 정의 필요.

> **확인 필요(D-4)**: 컨시어지에서 신청자 대신 폼 전달(send-form)·서명본 업로드를 Manager가 모두 수행하는지, 아니면 신청자 직접만 허용하는지. 컨시어지 PRD v1.5 흐름 재확인 필요.

---

## 5.2 영향분석 — CoF 제거

- 백엔드: 이미 완료. 운영 DB DROP은 운영자 수동 작업(`schema.sql:1461-1466`).
- 프론트: §7.2 목록을 PR1에서 일괄 제거. `lewActionUtils.ts`/`LewReviewFormPage.tsx`의 CoF 단계 분기, `types/cof.ts`·`constants/cof.ts` 삭제, `adminApplicationApi.ts`/`lewReviewApi.ts`의 CoF 호출 제거.
- **주의(확인 필요)**: `KvaOverrideNotificationListener.java`·`KvaPostPaymentServiceTest`·`ApplicantHintE2ETest`의 cof 매치는 kva_adjustment 잔재/주석일 가능성 — 제거 전 grep으로 실제 의존인지 개별 확인.

## 5.3 영향분석 — 마이그레이션

- 신규 테이블 `loa_form_templates` 생성, `applications`에 `loa_stage`·`loa_form_template_seq` 컬럼 추가, `FileType`에 `LOA_FINAL` 추가.
- 운영 신청 데이터 purge 상태 → 기존 row 백필 부담 낮음. 그래도 `loa_stage` 기본값 `NOT_STARTED` NOT NULL 적용.
- 디프리케이트 컬럼(§2.3) DROP은 PR5에서 별도 결정(즉시 제거 시 컨시어지 회귀 위험).

---

## 6. PR 분할 실행계획 (의존 순서)

각 PR은 독립 배포 가능 단위. 순서는 의존성 기준.

### PR1 — CoF 프론트 잔존 제거 + 운영 DROP 가이드
- 범위: 프론트 CoF 23파일 정리(§7.2), 라우트/타입/API 호출 제거. 백엔드는 이미 완료.
- 리스크: 낮음(데드코드 제거). LewReviewForm에서 CoF 단계 분기 제거 시 흐름 깨짐 주의 → 단계 전수 테스트.
- 롤백: revert(데이터 영향 없음).
- 선행: 없음.

### PR2 — LoA 폼 템플릿 관리 (admin)
- 범위: `loa_form_templates` 테이블 + 엔티티/리포, CRUD API(§3.1), admin Settings > LoA Forms UI(§4.1), active 폼 소비 API(§3.2).
- 리스크: 중. active 단일성 동시성(트랜잭션 보장 + 테스트), 파일 저장(S3/암호화) 패턴 재사용.
- 롤백: 테이블 미참조 상태면 안전. PR3 이후엔 참조 발생 → 롤백 시 신청 데이터 정합 확인.
- 선행: 없음(PR1과 병렬 가능).

### PR3 — LoA 업로드 모델 (loaStage + 신청자/LEW 업로드)
- 범위: `LoaStage` enum + 컬럼, `LOA_FINAL` FileType, send-form/applicant-upload/final-upload API(§3.3), `loa/status` 응답 변경, 신청자/LEW 화면 재작성(§4.2/4.3), 스냅샷 트리거 이동.
- 리스크: 높음. 기존 generate/sign 제거와 동시 진행 → 기존 LoA 화면/테스트 광범위 수정.
- 롤백: 컬럼 추가는 nullable/기본값으로 안전. UI는 feature flag 권장.
- 선행: PR2(active 폼 소비 의존).

### PR4 — 게이트 보강 (결제 + 완료)
- 범위: 결제 게이트 "서명 LoA 수령"(§3.4), 완료/IN_PROGRESS 게이트 "최종 LoA 업로드"(§3.5), 신규 에러코드.
- 리스크: 중. 게이트가 기존 결제/완료 흐름을 막으므로 NEW/RENEWAL 분기 정확성 + race(LEW/ADMIN 동시) 테스트 필수.
- 롤백: 게이트만 제거하면 복구.
- 선행: PR3(loaStage 의존).

### PR5 — 컨시어지 조정 + 디프리케이트 정리
- 범위: `uploadSignatureByManager` 재용도(서명본 대리 업로드, §5.1) 또는 retire, ConciergeRequest 전이 매핑, generate/sign 컨트롤러·서비스 정식 제거, 디프리케이트 컬럼 DROP 결정.
- 리스크: 높음. 컨시어지 회귀 + 법적 스냅샷/감사 경로. D-4 확정 선행.
- 롤백: 서비스 로직 보존본이 있어 가드 복구 가능. 컬럼 DROP은 비가역 → 신중.
- 선행: PR3, PR4 + D-4 결정.

---

## 7. 수용기준 (AC)

GIVEN/WHEN/THEN 형식. 시나리오별.

### AC-1 (NEW 정상 흐름)
- GIVEN 담당 LEW가 배정되고 kVA가 CONFIRMED인 NEW 신청, active LoA 폼이 존재
- WHEN LEW가 send-form 실행
- THEN `loaStage=FORM_SENT`, `loaFormTemplateSeq`가 현재 active 버전으로 고정되고, 신청자에게 알림 발송

### AC-2 (신청자 폼 다운로드/업로드)
- GIVEN `loaStage=FORM_SENT`
- WHEN 신청자가 active-form 다운로드 후 서명본을 applicant-upload
- THEN `loaStage=APPLICANT_UPLOADED`, OWNER_AUTH_LETTER 파일 생성, LoA 신원 스냅샷 최초 기록

### AC-3 (NEW 결제 게이트)
- GIVEN NEW 신청, `loaStage=FORM_SENT`(서명본 미수령)
- WHEN LEW/ADMIN이 결제 요청
- THEN 409 `LOA_NOT_RECEIVED`로 거부
- AND `loaStage=APPLICANT_UPLOADED`이면 정상적으로 `PENDING_PAYMENT` 전이

### AC-4 (LEW 최종본 + 완료 게이트)
- GIVEN 입금 확인된 PAID 신청, LEW 최종본 미업로드
- WHEN 완료(또는 IN_PROGRESS 전이) 시도
- THEN 409 `LOA_FINAL_NOT_UPLOADED`
- AND final-upload 후엔 `loaStage=FINAL_UPLOADED`, 완료 정상 진행

### AC-5 (RENEWAL 미첨부 → 요청)
- GIVEN LoA 미첨부 RENEWAL 신청
- WHEN LEW가 DocumentRequest(LOA)로 요청 → 신청자 업로드
- THEN OWNER_AUTH_LETTER 파일 존재, 결제 게이트 통과 가능
- AND 신청자에게 active 폼은 제공되지 않음(active-form 404 `LOA_FORM_NOT_APPLICABLE`)

### AC-6 (폼 버전 교체)
- GIVEN 신청 A가 폼 v1로 send-form 받은 상태, admin이 v2를 활성화
- WHEN 신청 A의 신청자가 폼 다운로드
- THEN 신청 A는 **고정된 v1**을 받는다(교체 영향 없음)
- AND 이후 새 신청 B는 v2를 active로 받는다

### AC-7 (active 단일성)
- GIVEN 폼 v1이 active
- WHEN admin이 v2를 activate
- THEN v1.is_active=false, v2.is_active=true (동시 active 0 또는 2 발생 안 함)

### AC-8 (참조된 폼 삭제 방지)
- GIVEN 폼 v1이 어떤 신청에 `loaFormTemplateSeq`로 참조됨
- WHEN admin이 v1 삭제 시도
- THEN 409 `LOA_FORM_IN_USE`

### AC-9 (디지털 서명 경로 차단 유지)
- WHEN 누구든 구 `loa/sign` 또는 `loa/generate` 호출
- THEN 404/410 (엔드포인트 제거됨) — 인앱 서명 경로 부활 불가

### AC-10 (권한)
- WHEN LEW가 LoA 폼 템플릿 CRUD 호출
- THEN 403 (admin 전용)
- AND 비담당 LEW가 send-form/final-upload 호출 시 403 `APPLICATION_NOT_ASSIGNED`

### AC-11 (컨시어지 대체, D-4 확정 후)
- GIVEN viaConcierge 신청, Manager가 신청자 서명본 PDF 수령
- WHEN Manager가 대리 업로드
- THEN `loaStage=APPLICANT_UPLOADED`, ConciergeRequest 전이, A-36 알림(7일 이의)

---

## 8. 엣지 케이스

1. **NEW인데 active 폼이 하나도 없음** → send-form 시 409 `NO_ACTIVE_LOA_FORM`. admin에게 운영 알림.
2. **신청자가 서명본을 여러 번 재업로드** → 최신본으로 교체(기존 OWNER_AUTH_LETTER 삭제), 스냅샷은 최초 1회 유지.
3. **LEW가 최종본 업로드 후 결제 전 단계로 reopen** → loaStage 역행 정책 미정(현 모델은 단방향). 보완 요청(REVISION_REQUESTED) 시 loaStage 유지할지 확인 필요.
4. **RENEWAL에서 신청자가 active-form 다운로드 시도** → 404 `LOA_FORM_NOT_APPLICABLE`.
5. **폼 교체 후 신청 A가 아직 send-form 전(FORM 미고정)** → 다운로드 시 현재 active(v2)를 받는다(고정은 send-form 시점).
6. **동시성**: LEW final-upload와 ADMIN 완료가 동시 → 낙관적 락(`Application.version`, `Application.java:344`)으로 한 건만 성공, 409 `STALE_STATE`.
7. **OWNER_AUTH_LETTER 파일 타입 중복**: RENEWAL 신청자 첨부 + 컨시어지 대리 업로드가 같은 FileType을 써 "최신 1건" 판정에 의존 → final(`LOA_FINAL`)과 분리되어 충돌 없음.

---

## 9. 미결 결정사항 → ✅ 확정 (2026-06-14)

- **D-1 (RENEWAL LoA 필수 시점)**: ✅ **결제 전 게이트로 강제**. RENEWAL도 NEW와 동일하게 "신청자 LoA(신청 첨부 또는 DocumentRequest 수령) 확보"를 **결제 요청 선행조건**으로 둔다. → §3.4 결제 게이트는 NEW/RENEWAL 공통 "LoA 수령" 조건.
- **D-2 (최종 LoA 게이트 위치)**: ✅ **`PAID → IN_PROGRESS` 진입 조건**. LEW 최종본 업로드 완료가 IN_PROGRESS 진입을 연다. IN_PROGRESS = "EMA 제출/처리 진행 중"으로 도메인 의미 재정의(기존 "점검 시작" 의미 폐기).
- **D-3 (EMA 제출 기록)**: ✅ **단순 체크/메모**. 별도 상태머신/SLA 필드 없이 boolean(emaSubmitted) + 선택 메모/타임스탬프 정도로 기록.
- **D-4 (컨시어지 범위)**: ✅ **둘 다 가능**. Manager가 폼 전달·서명본 대리 업로드 **모두 수행 가능**(신청자 직접도 허용). 권한표에 Manager 행 추가.
- **D-5 (디프리케이트 컬럼 처리)**: ✅ **즉시 DROP**. `loaSignatureUrl` 등 6종을 PR5에서 보존 없이 DROP. (운영 신청 데이터 purge 상태라 부담 없음)
- **D-6 (용어 리네이밍)**: ✅ **불요**. CoF 전체 제거로 남는 "CoF" 라벨이 없으므로 리네이밍 작업 자체가 사라짐.

> 위 확정에 따른 영향: PR4 결제 게이트는 NEW/RENEWAL 공통(D-1), 완료 직전이 아닌 PAID→IN_PROGRESS에 최종 LoA 게이트(D-2). PR5는 즉시 DROP(D-5) + 컨시어지 Manager 전달/대리업로드 포함(D-4).

---

## 범위 외 (Out of Scope)

- 진짜 완성검사(Inspection Report) 트랙 신설 — COMPLETED 이후 별도 트랙(미구현).
- WhatsApp 알림 채널 연동(별도 트랙).
- S3 전환 자체(파일 저장은 기존 FileStorageService 인터페이스 그대로 사용).
- EMA ELISE API 직접 연동(LEW의 EMA 제출은 플랫폼 외부 수동 행위).
