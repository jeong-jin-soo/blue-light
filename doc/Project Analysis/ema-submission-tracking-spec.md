# EMA 제출 추적 (EMA Submission Tracking) — 정식 스펙

> **작성일**: 2026-06-14
> **작성**: product-manager 에이전트
> **상태**: Spec v1.0 — 구현 착수 전 정본
> **선행/관련 문서**:
> - LoA 교환 동선 재설계: [`loa-exchange-redesign-spec.md`](./loa-exchange-redesign-spec.md)
> - 알림 이벤트 와이어링: [`notification-event-wiring-design.md`](./notification-event-wiring-design.md)
> - EMA 필드 JIT 계획: [`ema-field-jit-plan.md`](./ema-field-jit-plan.md)
> - 관련 커밋: `10d36f8` (CoF 게이트 제거)
> **요약**: 신청 종료(라이선스 발급) 직전 단계에 "EMA ELISE 제출 추적"이라는 명시적 서브-상태 기계를 도입한다. EMA ELISE는 공개 API가 없는 수작업 정부 포털이므로, 담당 LEW가 ELISE에서 실제로 한 행동을 우리 DB에 **수동으로 미러링**한다. `ema=APPROVED` + 라이선스 PDF 첨부를 `completeApplication`의 전제 게이트로 삼아, 현재 신청 종료 흐름의 두 가지 구조적 공백(번호만 입력하고 파일 누락 가능 / IN_PROGRESS이기만 하면 무검증 종료)을 동시에 막는다.

---

## 0. 코드 검증으로 확인한 현재 상태 (사실 베이스)

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| 종료 엔드포인트 | `POST /api/admin/applications/{id}/complete`, 권한 `hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew` | `AdminApplicationController.java:161-170` |
| 종료 요청 본문 | `licenseNumber`(@NotBlank), `licenseExpiryDate`(@NotNull) **2개뿐** | `CompleteApplicationRequest.java:15-22` |
| 종료 동작 | IN_PROGRESS만 허용 → `Application.issueLicense()` → status=COMPLETED + 필드 저장 → `ApplicationStatusChangedEvent`(Concierge 동기화) → `sendLicenseIssuedEmail`(레거시 직접 발송) | `AdminApplicationService.java:244-279`, `Application.java:753-757` |
| 라이선스 PDF 전달 | `POST /api/admin/applications/{id}/files`, fileType 기본값 `LICENSE_PDF`, 권한 ADMIN/LEW. **종료 액션과 강제 연결 없음** | `FileController.java:67-77` |
| CoF 게이트 | **제거됨**(10d36f8). IN_PROGRESS면 무검증 종료 가능 | — |
| EMA 추적 필드 | **없음**. ELISE 필드(installationName/premisesType/5-part 주소 등)는 P1.1로 수집·저장만, 제출 상태·접수번호·결정 추적 필드 전무 | `Application.java:348-410` |
| 상태 enum | PENDING_REVIEW → REVISION_REQUESTED ↔ PENDING_REVIEW → PENDING_PAYMENT → PAID → IN_PROGRESS → COMPLETED / EXPIRED | `ApplicationStatus.java` |
| FileType enum | LICENSE_PDF 포함 18종. EMA 접수증용 타입 없음 | `FileType.java` |
| 파일 조회 | `findByApplicationApplicationSeqAndFileType(appSeq, fileType)` 존재 → LICENSE_PDF 존재 검증에 재사용 가능 | `FileRepository.java:22` |
| 마이그레이션 패턴 | `DatabaseMigrationRunner.migrateAll()`에서 `migrate*` 메서드 순차 호출, 각 컬럼 `columnExists` 가드로 멱등 | `DatabaseMigrationRunner.java:91`, `1417-1453` |
| 알림 신경로 | `NotificationDispatchEvent` → `NotificationOrchestrator`(AFTER_COMMIT) → 템플릿 렌더 → outbox → 채널. 카나리만 연결, 다수 레거시 직접발송 | `notification-event-wiring-design.md §1-2` |

---

## 1. 목표 / 비목표

### 1.1 목표 (Goals)

1. **G1 — 제출 가시성**: 담당 LEW와 ADMIN이 "이 신청이 EMA ELISE에 제출되었는지, 어느 단계인지"를 한 화면에서 확인한다.
2. **G2 — 종료 게이트 강화**: `completeApplication`(라이선스 발급)을 `emaSubmissionStatus=APPROVED` **그리고** `LICENSE_PDF` 첨부가 모두 충족될 때만 허용한다. 현재의 "번호만 입력·파일 누락" 및 "무검증 종료" 공백을 동시에 제거한다.
3. **G3 — 추적 신뢰성**: 모든 상태 전이를 감사로그(@Auditable)로 남기고, 제출 증빙(ELISE 접수증)을 첨부하며, SUBMITTED 후 N일 무변동 시 담당 LEW에게 리마인더를 보낸다.
4. **G4 — 책임 명확화**: 제출 주체는 담당 LEW(자기 ELISE 계정). ADMIN은 모니터링/대행(override).

### 1.2 비목표 (Non-goals — 명시적 배제)

1. **NG1 — EMA 자동 연동 안 함**: ELISE는 공개 API가 없다. 시스템이 EMA에 자동 제출하거나 상태를 자동 폴링하지 않는다.
2. **NG2 — EMA 승인 자동 검증 안 함**: `APPROVED`는 LEW가 ELISE 화면을 보고 수동으로 표기하는 값이다. 시스템은 EMA가 실제로 승인했는지 검증하지 않는다(증빙 첨부로 신뢰 보강만).
3. **NG3 — 신규 최상위 상태 추가 안 함**: EMA 추적은 `IN_PROGRESS`의 **서브-상태**다. `ApplicationStatus` enum에 값을 추가하지 않는다(COMPLETED로의 단일 전이만 게이팅).
4. **NG4 — EMA 필드 재수집 안 함**: 제출 본문(installationName 등)은 이미 P1.1로 수집됨. JIT 원칙상 재요청 금지(§9 참조).
5. **NG5 — 시공 후 Inspection Report 단계 신설 안 함**: 별도 트랙(시장 신호 대기). 본 스펙 범위 밖.

---

## 2. 사용자 스토리

### 2.1 담당 LEW (핵심)

- **US-L1**: LEW로서, 검토가 끝난 신청(IN_PROGRESS)에 대해 내 ELISE 계정으로 제출한 뒤, 시스템에 "제출함 + 접수번호 + 접수증"을 기록해 진행상황을 남기고 싶다.
- **US-L2**: LEW로서, EMA가 질의(query)를 걸면 그 사실과 질의 내용을 기록하고, 보완 후 "재제출"로 다시 진행 상태로 되돌리고 싶다.
- **US-L3**: LEW로서, EMA 승인이 나면 "승인"으로 표기하고 라이선스 PDF를 올린 뒤 단일 동작으로 라이선스 발급(신청 종료)까지 마치고 싶다.
- **US-L4**: LEW로서, 제출 후 오래 방치된 건이 있으면 리마인더 알림을 받아 누락을 막고 싶다.

### 2.2 ADMIN / SYSTEM_ADMIN (모니터링)

- **US-A1**: ADMIN으로서, 전체 신청의 EMA 제출 단계를 한눈에 보고(특히 SUBMITTED/QUERY_RAISED에 정체된 건), 지연 건을 식별하고 싶다.
- **US-A2**: ADMIN으로서, 담당 LEW가 부재하거나 비협조적일 때 대행으로 상태를 갱신/종료할 수 있어야 한다(감사로그에 "by ADMIN" 기록).
- **US-A3**: ADMIN으로서, 잘못 표기된 상태를 정정(예: APPROVED 오기입 → 되돌리기)할 수 있어야 한다(권한 분리).

### 2.3 신청자 (수동적)

- **US-C1**: 신청자로서, 라이선스가 발급되면 통지를 받는다(기존 동작 유지). EMA 중간 상태는 신청자에게 노출하지 않는다(불필요한 혼란 방지, 비목표).

---

## 3. EmaSubmissionStatus 상태 전이표

신규 enum (VARCHAR(30) 저장, Java Enum 검증 — DB ENUM 미사용 컨벤션 준수):

```
NOT_SUBMITTED (기본)
   │ markSubmitted
   ▼
SUBMITTED ──────────────┐
   │ raiseQuery         │ approve
   ▼                    ▼
QUERY_RAISED          APPROVED ──(완료 게이트 통과)──▶ Application.COMPLETED
   │ resubmit            ▲
   ▼                     │ approve
RESUBMITTED ─────────────┘
   ▲ │ raiseQuery → QUERY_RAISED (재질의 루프)
   │ │ reject → REJECTED
   │ │ withdraw → WITHDRAWN
   │ │
   │ └─ REJECTED ─ resubmit (T10) ─┘   ← 반려는 종착 아님, 재작업 후 재진입
   └────────────────────────────────
(WITHDRAWN 은 종착. APPROVED/WITHDRAWN 은 ADMIN revertDecision(T9)으로만 되돌림)
```

`NOT_SUBMITTED · SUBMITTED · QUERY_RAISED · RESUBMITTED · APPROVED · REJECTED · WITHDRAWN` (7개)

### 3.1 전이 정의표

> **허용 권한 표기 규약 (OQ-2 확정)**: "LEW/ADMIN"은 **담당 LEW 본인 + ADMIN/SYSTEM_ADMIN 대행 모두**를 의미하며, 컨트롤러 SpEL `hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)`(completeApplication과 동일)을 재사용한다. 모든 전이는 actor(`emaSubmittedByUserSeq` 등) + actor role을 감사로그에 기록해 "LEW 본인 vs ADMIN 대행"을 구분한다(§3.2).

| # | From | To | 트리거 액션 | 허용 권한 | 가드(전제조건) | 부수효과 |
|---|---|---|---|---|---|---|
| T1 | NOT_SUBMITTED | SUBMITTED | `markSubmitted` | LEW/ADMIN | App=IN_PROGRESS · `emaReferenceNo` 필수 · `ema.ack.required=true`면 EMA_ACK 첨부 필수, 아니면 선택(§3.3) | `emaSubmittedAt=now`, `emaSubmittedByUserSeq=actor`, 감사 `EMA_SUBMITTED`(actor role 포함), 리마인더 타이머 시작 |
| T2 | SUBMITTED | QUERY_RAISED | `raiseQuery` | LEW/ADMIN | `emaQueryNote` 필수 | 감사 `EMA_QUERY_RAISED`, 리마인더 타이머 리셋(LEW가 보완해야 함) |
| T3 | QUERY_RAISED | RESUBMITTED | `resubmit` | LEW/ADMIN | (선택) 갱신된 `emaReferenceNo` · `ema.ack.required=true`면 EMA_ACK 재첨부 필수 | `emaSubmittedAt=now`(재제출 시각 갱신), `emaDecisionAt=null`·**`emaQueryNote=null`(직전 결정·사유 클리어 — 허점#4)**, 감사 `EMA_RESUBMITTED`, 리마인더 타이머 재시작 |
| T4 | RESUBMITTED | QUERY_RAISED | `raiseQuery` | LEW/ADMIN | `emaQueryNote` 필수 | 감사 `EMA_QUERY_RAISED` (재질의 루프) |
| T5 | SUBMITTED | APPROVED | `approve` | LEW/ADMIN | (게이트는 완료 시점 검증 — §4) | **`emaStatusBeforeDecision=SUBMITTED`(허점#1)**, `emaDecisionAt=now`, 감사 `EMA_APPROVED`, 리마인더 타이머 종료 |
| T6 | RESUBMITTED | APPROVED | `approve` | LEW/ADMIN | T5와 동일 | **`emaStatusBeforeDecision=RESUBMITTED`**, `emaDecisionAt=now`, 감사 `EMA_APPROVED`, 리마인더 종료 |
| T7 | SUBMITTED / RESUBMITTED | REJECTED | `reject` | LEW/ADMIN | `emaQueryNote`에 사유 권장 | **`emaStatusBeforeDecision=<현재 from 상태>`**, `emaDecisionAt=now`, 감사 `EMA_REJECTED`, 리마인더 종료. **App 상태는 IN_PROGRESS 유지** — REJECTED는 종착이 아니라 재작업 가능 상태(T10) |
| T8 | SUBMITTED / QUERY_RAISED / RESUBMITTED | WITHDRAWN | `withdraw` | LEW/ADMIN | — | **`emaStatusBeforeDecision=<현재 from 상태>`**, `emaDecisionAt=now`, 감사 `EMA_WITHDRAWN`, 리마인더 종료 |
| T9 | APPROVED / WITHDRAWN | **`emaStatusBeforeDecision` 복원값** | `revertDecision` | **ADMIN, SYSTEM_ADMIN 전용** | App≠COMPLETED (종료 후엔 불가) | **`emaStatusBeforeDecision` 으로 정확 복원 → 복원 후 `emaStatusBeforeDecision=null`. 값이 null이면(grandfathered APPROVED 등) SUBMITTED 로 폴백(허점#1)**, `emaDecisionAt=null`, 감사 `EMA_DECISION_REVERTED`. LEW 오기입 정정용 (REJECTED 정정은 T10 재제출로 처리) |
| **T10** | **REJECTED** | **RESUBMITTED** | `resubmit` | LEW/ADMIN | (선택) 갱신된 `emaReferenceNo` · `ema.ack.required=true`면 EMA_ACK 재첨부 필수 | `emaSubmittedAt=now`, `emaDecisionAt=null`·**`emaQueryNote=null`(반려 결정·사유 클리어 — 허점#4)**·**`emaStatusBeforeDecision=null`**, 감사 `EMA_RESUBMITTED`, 리마인더 타이머 재시작. **EMA 반려 사유 반영 후 재진입(OQ-8 확정)** |

### 3.2 전이 가드 공통 규칙

- 모든 전이는 `Application.status == IN_PROGRESS`에서만 가능(NG3). COMPLETED/EXPIRED 신청은 EMA 상태 변경 불가.
- **권한 (OQ-2 확정)**: T1~T8·T10은 컨트롤러 SpEL `hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew(#id, authentication)`로 단일화 — **담당 LEW 본인과 ADMIN/SYSTEM_ADMIN 대행을 처음부터 모두 허용**(기존 complete/revision/approve 패턴 답습, `AdminApplicationController.java:161` 참조).
- T9 `revertDecision`만 LEW 제외(`hasAnyRole('ADMIN','SYSTEM_ADMIN')`).
- **Actor 기록 (OQ-2 확정)**: 모든 전이에서 actor userSeq와 actor role(LEW 본인 vs ADMIN/SYSTEM_ADMIN 대행)을 감사로그에 남긴다. T1/T3/T10의 제출 actor는 `emaSubmittedByUserSeq`에 영속 보관(누가 ELISE에 제출했는지 추적). actor role은 `@Auditable`이 자동 기록하는 인증 주체에서 도출하거나, 명확성을 위해 감사 상세(detail)에 `actorRole` 필드를 명시 기록. LEW 본인/ADMIN 대행을 사후 구분 가능해야 한다.
- 잘못된 from→to 전이는 `BusinessException(HttpStatus.BAD_REQUEST, "INVALID_EMA_TRANSITION")`. 전이 검증은 `Application` 도메인 메서드 내부에서 수행(상태 기계를 엔티티가 소유).
- 낙관적 락(`@Version`) 보호 대상 — 동시 갱신 충돌 시 409 STALE_STATE(기존 `GlobalExceptionHandler` 패턴).

### 3.3 증빙 첨부 정책 (신뢰성 ②) — OQ-3 확정: 설정 플래그로 제어

- 신규 FileType `EMA_ACK` (ELISE 접수증/스크린샷). 제출 계열 전이(T1 `markSubmitted` · T3/T10 `resubmit`)에서 첨부 요구 여부를 **`system_settings`의 `ema.ack.required` 플래그로 제어**한다.
  - **기본값 `false`(선택)** — ELISE 접수증 포맷이 건마다 달라 첫 도입은 soft. 첨부는 권장하되 미첨부여도 전이 허용.
  - **운영이 `true`로 바꾸면** 코드 변경 없이 즉시 필수화 — 제출 전이 가드에서 이 플래그를 읽어, `true`면 EMA_ACK 미첨부 시 `BusinessException(BAD_REQUEST, "EMA_ACK_REQUIRED")`로 차단. (설정 우선 원칙 부합 — §5.4)
  - 플래그 조회는 리마인더 N일(`ema.reminder.days`)과 동일하게 `system_settings`에서 읽는다(`seedSystemSettings`로 시드).
- 한편 **T5/T6 approve 자체는 첨부를 강제하지 않는다** — LICENSE_PDF 필수 검증은 **완료(completeApplication) 시점**의 게이트에서 수행한다(§4). approve와 발급을 분리해 "EMA 승인됨, 발급 대기" 상태를 표현하기 위함.

---

## 4. completeApplication 게이트 변경 설계

현재: IN_PROGRESS이기만 하면 `licenseNumber`/`licenseExpiryDate`만으로 종료.
목표: `emaSubmissionStatus=APPROVED` **AND** LICENSE_PDF 첨부 존재일 때만 종료.

### 4.1 두 가지 구현 방식 비교

**방식 A — 기존 `complete` 시그니처 유지 + 게이트 추가**
- `completeApplication`에 진입 시 두 조건 검증 후 기존 로직 수행.
- LEW UI는 (1) EMA approve 표기 → (2) LICENSE_PDF 업로드 → (3) "Complete & Issue" 별도 클릭, 3단계.

| 장점 | 단점 |
|---|---|
| 기존 엔드포인트/DTO/감사(`APPLICATION_COMPLETED`)/Concierge 동기화 이벤트/이메일 모두 무변경 | approve와 complete가 분리되어 LEW가 approve만 하고 complete를 잊을 수 있음(불완전 종료 잔존) |
| 변경 범위 최소, 회귀 위험 낮음 | UI에서 두 액션 동선 안내 필요 |
| ADMIN이 LEW의 approve 후 별도로 종료 가능(역할 분리 유연) | — |

**방식 B — `approve` 전이가 곧 `completeApplication`을 호출(원자적 발급)**
- T5/T6 approve 요청에 `licenseNumber`/`licenseExpiryDate`를 함께 받아, 같은 트랜잭션에서 EMA=APPROVED 표기 + 라이선스 발급(COMPLETED)을 한 번에 수행.

| 장점 | 단점 |
|---|---|
| "승인=발급" 단일 동작으로 누락 원천 차단 | approve 요청이 무거워짐(번호/만료일/PDF 선업로드 강제) |
| LEW 동선 단순(클릭 1번) | EMA APPROVED인데 아직 라이선스 미발급 상태가 표현 불가 → 추후 "승인됐으나 번호 대기" 케이스 처리 곤란 |
| — | 기존 `complete` 엔드포인트와 책임 중복 → 두 진입점 유지보수 부담 |

### 4.2 권장안 — **하이브리드 (A 기반 + approve에 게이트 검증 내장)**

1. **`completeApplication`에 전제 게이트를 추가**(방식 A 핵심):
   - `application.getEmaSubmissionStatus() != APPROVED` → `BusinessException("EMA submission must be APPROVED before completion", BAD_REQUEST, "EMA_NOT_APPROVED")`
   - `fileRepository.findByApplicationApplicationSeqAndFileType(appSeq, LICENSE_PDF).isEmpty()` → `BusinessException("License PDF must be uploaded before completion", BAD_REQUEST, "LICENSE_PDF_MISSING")`
   - 이 검증은 `issueLicense()` 호출 **전**에 수행.
2. **approve 전이(T5/T6)는 순수 상태 표기만** 담당(별도 종료 강제 안 함) → "EMA 승인됨, 발급 대기" 상태 표현 가능.
3. **UI에서 동선을 묶어** LEW 누락을 방지: EMA 탭에서 `APPROVED`가 되면 "Complete & Issue License" CTA가 활성화되고, LICENSE_PDF 미첨부 시 버튼 disabled + 안내. 즉 **백엔드는 게이트(방식 A)로 안전, 프론트는 단일 흐름처럼 안내**해 B의 UX 장점을 흡수.

**근거**: 방식 A가 기존 자산(감사 액션/Concierge 동기화/발급 이메일)을 건드리지 않아 회귀 위험이 가장 낮고, 게이트는 도메인 검증으로 충분히 강제된다. B의 "누락 방지"는 UI 동선 묶음으로 동등하게 달성한다. "승인됐으나 번호 대기" 분리 가능성은 운영 유연성으로 남겨둔다.

---

## 5. 데이터 모델

### 5.1 신규 enum `EmaSubmissionStatus`

`com.bluelight.backend.domain.application.EmaSubmissionStatus` — `PremisesType`과 동일 위치/스타일.

```java
public enum EmaSubmissionStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    QUERY_RAISED,
    RESUBMITTED,
    APPROVED,
    REJECTED,
    WITHDRAWN
}
```

### 5.2 Application 추가 컬럼 (audit/soft-delete는 BaseEntity 상속으로 자동, 신규 테이블 아님)

| Java 필드 | 컬럼 | 타입 | Null | 비고 |
|---|---|---|---|---|
| `emaSubmissionStatus` | `ema_submission_status` | VARCHAR(30) | NOT NULL DEFAULT `'NOT_SUBMITTED'` | `@Enumerated(EnumType.STRING)`. backfill 기본값 |
| `emaSubmittedAt` | `ema_submitted_at` | DATETIME(6) | NULL | T1/T3/T10 제출·재제출 시각 |
| `emaReferenceNo` | `ema_reference_no` | VARCHAR(60) | NULL | ELISE 접수번호. **PII 아님**(설비 행정번호) → 평문 |
| `emaSubmittedByUserSeq` | `ema_submitted_by_user_seq` | BIGINT | NULL | 제출 실행 actor(FK 강제 아님, 값만 보관 — 기존 `*_by` 컨벤션 따라 단순 컬럼). LEW 본인/ADMIN 대행 구분은 감사로그 actor role로 보강(§3.2) |
| `emaDecisionAt` | `ema_decision_at` | DATETIME(6) | NULL | APPROVED/REJECTED/WITHDRAWN 시각. 재제출(T3/T10)·Revert(T9) 시 `null`로 클리어 |
| `emaQueryNote` | `ema_query_note` | VARCHAR(1000) | NULL | 질의/반려 사유. **재제출(T3/T10) 시 `null`로 클리어**(허점#4 — 옛 사유 화면 잔존 방지, 전체 이력은 감사로그로 무손실 추적). **자유 텍스트에 PII 유입 가능** → 입력 가이드에 "개인정보 기재 금지" 명시(저장은 평문, OQ-4) |
| `emaStatusBeforeDecision` | `ema_status_before_decision` | VARCHAR(30) | NULL | **결정 전 상태 보관(허점#1)**. approve/reject/withdraw(T5~T8) 진입 시 직전 from 상태(SUBMITTED/RESUBMITTED 등)를 저장 → Revert(T9)가 정확 복원. 복원·재제출 시 `null` 클리어. `@Enumerated(EnumType.STRING)` |

- `licenseNumber`/`licenseExpiryDate`는 **기존 필드 재사용**(신규 추가 없음).
- `emaQueryNote`는 질의/반려 공용. 질의 이력이 여러 번이면 **최신 1건만** 컬럼에 보관하고 **재제출 시 클리어**(전체 이력은 감사로그 `EMA_QUERY_RAISED`/`EMA_REJECTED` 항목으로 추적). 이력 테이블 분리는 비목표(OQ-5).
- `emaStatusBeforeDecision` 은 **종결 결정(APPROVED/REJECTED/WITHDRAWN)의 직전 상태만** 보관하는 1-depth 복원 슬롯이다. 다단계 undo는 비목표 — Revert는 결정 직전으로 한 단계만 되돌린다.

### 5.3 신규 FileType `EMA_ACK`

`FileType.java`에 추가:
```java
/** EMA ELISE 제출 접수증/확인 스크린샷 (담당 LEW 업로드). */
EMA_ACK
```
- `files.file_type` 컬럼 폭은 이미 VARCHAR 확장됨(`migrateFilesFileTypeWidth`). 별도 ALTER 불필요(확인 필요 — OQ-6).

### 5.4 설정 우선 원칙(SSOT) 검토 + `system_settings` 키

- EMA 상태값은 **법적·절차적 고정 enum**(EMA ELISE 워크플로 자체가 정의)으로, 관리자가 바꿀 도메인 값이 아니다 → enum 하드코딩 허용 대상(CLAUDE.md §설계 원칙: "법적 고정 값" 예외).
- 아래 두 **운영 가변 값**은 하드코딩 금지 — `system_settings`에 키로 보관하고 서비스가 조회(설정 우선 원칙 준수). 시드는 `seedSystemSettings` 패턴 재사용.

| 키 | 기본값 | 용도 | 소비처 |
|---|---|---|---|
| `ema.reminder.days` | `3` | SUBMITTED/RESUBMITTED 후 무변동 리마인더 임계 N일 | 리마인더 스케줄러(§10) |
| `ema.ack.required` | `false` | EMA_ACK 첨부 강제 여부 (OQ-3 확정 — 코드 변경 없이 설정만으로 필수화) | 제출 전이 가드 T1/T3/T10(§3.3) |

---

## 6. 마이그레이션 계획

`DatabaseMigrationRunner`에 `migrateApplicationsEmaSubmissionTracking(conn)` 추가 — P1.1 `migrateApplicationsEmaFields`(`:1417`)와 동일 패턴(컬럼별 `columnExists` 가드 → 멱등).

```java
private void migrateApplicationsEmaSubmissionTracking(Connection conn) throws SQLException {
    if (!tableExists(conn, "applications")) return;
    String[][] columns = {
        {"ema_submission_status",    "VARCHAR(30) NOT NULL DEFAULT 'NOT_SUBMITTED'"},
        {"ema_submitted_at",         "DATETIME(6)"},
        {"ema_reference_no",         "VARCHAR(60)"},
        {"ema_submitted_by_user_seq","BIGINT"},
        {"ema_decision_at",          "DATETIME(6)"},
        {"ema_query_note",           "VARCHAR(1000)"},
        {"ema_status_before_decision","VARCHAR(30)"}   // 허점#1 — Revert 복원 슬롯
    };
    int added = 0;
    try (Statement stmt = conn.createStatement()) {
        for (String[] c : columns) {
            if (!columnExists(conn, "applications", c[0])) {
                stmt.executeUpdate("ALTER TABLE applications ADD COLUMN " + c[0] + " " + c[1]);
                added++;
            }
        }
    }
    if (added > 0) log.info("Migration [applications-ema-submission]: added {} column(s)", added);

    // ── OQ-1 확정: 배포 호환 backfill ──
    // 이미 IN_PROGRESS 인 기존 신청은 새 종료 게이트(ema=APPROVED 필수)에 걸려 발급 불가가 된다.
    // 이를 막기 위해 컬럼 신규 추가가 발생한 "이번 마이그레이션에서만" IN_PROGRESS 행을 APPROVED 로 일괄 세팅.
    // 멱등성 보장: (a) `added > 0` 인 첫 실행에서만 backfill 을 수행 — 재실행 시 컬럼이 이미 있어 added=0 → backfill 스킵.
    //             (b) backfill UPDATE 자체도 status='IN_PROGRESS' AND ema_submission_status='NOT_SUBMITTED' 조건이라,
    //                 만에 하나 재실행돼도 이미 진행/전이된 행을 덮어쓰지 않는다(이중 가드).
    if (added > 0) {
        try (Statement stmt = conn.createStatement()) {
            int backfilled = stmt.executeUpdate(
                "UPDATE applications " +
                "SET ema_submission_status = 'APPROVED' " +
                "WHERE status = 'IN_PROGRESS' " +
                "  AND ema_submission_status = 'NOT_SUBMITTED' " +
                "  AND deleted_at IS NULL");   // soft-delete 행 제외 (프로젝트 패턴)
            if (backfilled > 0)
                log.info("Migration [applications-ema-submission]: backfilled {} IN_PROGRESS row(s) to APPROVED", backfilled);
        }
    }
}
```

- `migrateAll()` 호출 순서: P1.1 EMA 필드 블록 직후(`:92` 부근)에 등록.
- `schema.sql`의 `applications` CREATE TABLE 정의에도 동일 7컬럼 추가(개발 DB는 schema.sql 로드, 운영/개발 RDS는 runner ALTER가 적용 — 두 경로 일치 유지).
- **신규 생성 신청의 기본값은 `NOT_SUBMITTED`**(NOT NULL DEFAULT). backfill 은 오직 **마이그레이션 시점에 이미 IN_PROGRESS 였던 기존 진행 건**에만 적용된다 — 그 외 상태(PENDING_REVIEW/PAID 등)·신규 생성 행은 정상적으로 `NOT_SUBMITTED` 부터 상태 기계를 탄다.
- **backfill 의미 주의**: 기존 IN_PROGRESS 건을 `APPROVED` 로 표기하는 것은 "EMA 가 실제 승인됐다"는 사실 단언이 아니라 **신규 게이트로부터의 grandfathering**(소급 적용 면제)이다. 이들 건은 LICENSE_PDF 게이트(§4)는 여전히 적용되므로, PDF 미첨부면 종료 시점에 막힌다. (운영 안내 필요 — §11 R3)
- 인덱스: ADMIN 모니터링 필터(상태별 조회)를 위해 `KEY idx_applications_ema_status (ema_submission_status)` 권장(데이터량 적으면 PR-E1에서 생략 후 후속).
- FileType 폭은 기존 마이그레이션으로 충분한지 확인(OQ-6) — 부족하면 같은 PR에 폭 ALTER 동반.

---

## 7. API 엔드포인트 설계

기존 `AdminApplicationController`(LEW가 `/admin/**` 공유) 하위에 EMA 전이 엔드포인트를 추가. 권한 SpEL은 §3.2 규칙.

| 메서드 | 경로 | 전이 | 권한 | 요청 DTO | 감사 액션 |
|---|---|---|---|---|---|
| POST | `/api/admin/applications/{id}/ema/submit` | T1 | LEW/ADMIN | `EmaSubmitRequest{ emaReferenceNo: @NotBlank }` | `EMA_SUBMITTED` |
| POST | `/api/admin/applications/{id}/ema/query` | T2/T4 | LEW/ADMIN | `EmaQueryRequest{ queryNote: @NotBlank }` | `EMA_QUERY_RAISED` |
| POST | `/api/admin/applications/{id}/ema/resubmit` | T3 (QUERY_RAISED→) **및 T10 (REJECTED→)** | LEW/ADMIN | `EmaResubmitRequest{ emaReferenceNo?: String }` | `EMA_RESUBMITTED` |
| POST | `/api/admin/applications/{id}/ema/approve` | T5/T6 | LEW/ADMIN | (본문 없음) | `EMA_APPROVED` |
| POST | `/api/admin/applications/{id}/ema/reject` | T7 | LEW/ADMIN | `EmaRejectRequest{ reason?: String }` | `EMA_REJECTED` |
| POST | `/api/admin/applications/{id}/ema/withdraw` | T8 | LEW/ADMIN | (본문 없음) | `EMA_WITHDRAWN` |
| POST | `/api/admin/applications/{id}/ema/revert` | T9 | **ADMIN/SYSTEM_ADMIN** | (본문 없음) | `EMA_DECISION_REVERTED` |
| GET | `/api/admin/applications/{id}/ema` | 조회 | LEW/ADMIN | — | (없음) |

**공통 응답 DTO** `EmaSubmissionResponse`:
```
{
  emaSubmissionStatus, emaSubmittedAt, emaReferenceNo,
  emaSubmittedByUserSeq, emaSubmittedByName,  // 표시용 이름은 서버 join
  emaDecisionAt, emaQueryNote,
  emaAckPresent: boolean,       // EMA_ACK 첨부 존재 여부
  emaAckRequired: boolean,      // = system_settings.ema.ack.required — UI 필수/선택 라벨 동적 표기 (§3.3)
  emaGrandfathered: boolean,    // 허점#2 — = (status==APPROVED && emaDecisionAt==null && emaReferenceNo==null)
                                //   true면 backfill grandfathered 건 → "Approved (legacy)" 구분 배지
  licensePdfPresent: boolean,   // 게이트 사전 안내용 — completeApplication 활성화 판단
  canComplete: boolean          // = (status==APPROVED && licensePdfPresent) 서버 계산
}
```
- `emaGrandfathered` 는 **서버 계산 필드**(영속 컬럼 아님). 정의 = `emaSubmissionStatus==APPROVED && emaDecisionAt==null && emaReferenceNo==null`. 마이그레이션 backfill(§6)로 APPROVED 가 된 기존 건은 decision/reference 가 비어 있으므로 자연 식별된다. 정상 승인 건은 `emaDecisionAt`/`emaReferenceNo` 가 채워져 있어 false.
- EMA 정보는 `AdminApplicationResponse`에도 inline 포함(목록/상세 한 번에) — 별도 GET은 폴링/탭 갱신용.
- 증빙 첨부는 **기존 파일 업로드 엔드포인트 재사용**(`POST /api/admin/applications/{id}/files`, fileType=`EMA_ACK`) — 신규 업로드 엔드포인트 불필요.

### 7.1 completeApplication 변경 (§4.2 권장안 반영)

- 엔드포인트/DTO 시그니처 **무변경**. 서비스 `completeApplication` 진입부에 게이트 2건 추가(`EMA_NOT_APPROVED`, `LICENSE_PDF_MISSING`).
- 응답에 위 EMA 필드 포함되도록 `AdminApplicationResponse.from` 확장.

---

## 8. 프론트엔드

### 8.1 LEW EMA 탭 (LewReviewFormPage)

현재 탭: Documents / kVA / SLD(조건부) / LOA (`LewReviewFormPage.tsx:51, 292-315`). **EMA 탭 신규 추가** — LOA 다음, 종료 직전 단계라 마지막 탭이 자연스럽다.

- `type TabKey`에 `'ema'` 추가, `tabDefinitions`에 `{ key: 'ema', label: 'EMA', badge: ... }`.
  - badge: NOT_SUBMITTED → 없음 / SUBMITTED·RESUBMITTED → `{text:'Submitted', variant:'info'}` / QUERY_RAISED → `{text:'Query', variant:'warning'}` / APPROVED → `{text:'Approved', variant:'success'}` / REJECTED·WITHDRAWN → `{text:..., variant:'danger'}`.
- 탭 노출 조건: App.status === IN_PROGRESS 일 때 활성. 그 전 단계에서는 탭 보이되 액션 disabled + "검토·결제 완료 후 진행" 안내.

### 8.2 상태별 액션 버튼 (LEW)

| 현재 상태 | 표시 | 가능 액션 |
|---|---|---|
| NOT_SUBMITTED | "아직 ELISE 미제출" + 접수번호 입력 폼 + EMA_ACK 업로드(`ema.ack.required`면 필수 표시) | **Mark Submitted** (접수번호 필수) |
| SUBMITTED | 제출일/접수번호/제출자 표시 | **Raise Query** / **Approve** / **Reject** / **Withdraw** |
| QUERY_RAISED | 질의 내용(emaQueryNote) 강조 | **Resubmit** (접수번호 갱신 옵션) / **Withdraw** |
| RESUBMITTED | 재제출일 표시 | **Raise Query** / **Approve** / **Reject** / **Withdraw** |
| APPROVED (정상) | "EMA 승인됨" + 승인일/접수번호 + LICENSE_PDF 업로드 영역 + **Complete & Issue License** | LICENSE_PDF 첨부 시 CTA 활성, 미첨부 시 disabled + "라이선스 PDF를 업로드하세요" |
| APPROVED (`emaGrandfathered=true`) | **"Approved (legacy/grandfathered)" 구분 배지** + "이 건은 EMA 추적 도입 전 진행 중이던 건으로 자동 승인 처리됨" 안내(허점#2) + LICENSE_PDF 업로드 영역 + **Complete & Issue License** | 동일 (LICENSE_PDF 게이트는 적용). 필요 시 ADMIN 이 Revert(T9)로 정정 — null 폴백으로 SUBMITTED 복원 |
| REJECTED | 반려 사유(emaQueryNote) 강조 + "사유 반영 후 재제출 안내" 배너 | **Resubmit** (T10 — 사유 반영 후 재진입, 접수번호 갱신 옵션) / (ADMIN) Withdraw |
| WITHDRAWN | "철회됨" | (ADMIN) Revert |

- **REJECTED 후속 동선 (OQ-8 확정)**: 반려는 종착이 아니다. REJECTED 화면은 **재작업 가능 상태**로 표현하며 LEW가 ELISE에서 보완 후 **Resubmit(T10)** 으로 RESUBMITTED 로 재진입한다. App.status 는 IN_PROGRESS 에 그대로 머무른다(별도 종료/되돌림 상태로 보내지 않음). EMA 보완에 신청자 추가 서류가 필요하면 기존 Documents 탭(서류 요청)·`requestRevision` 동선을 병행 사용하면 되고, EMA 상태 기계 자체는 추가 분기를 두지 않는다.
- **증빙 첨부**: 기존 파일 업로드 컴포넌트 재사용(fileType=EMA_ACK / LICENSE_PDF). 업로드 후 `licensePdfPresent` 재조회로 CTA 활성 갱신. EMA_ACK 필수 여부는 서버 `ema.ack.required` 값을 응답에 실어 UI가 "필수/선택" 라벨을 동적 표기(설정 우선 원칙).
- **Complete & Issue License** 클릭 → 기존 `completeApplication`(licenseNumber/expiryDate 입력 모달) 호출. 게이트 위반 시 백엔드 에러코드(`EMA_NOT_APPROVED`/`LICENSE_PDF_MISSING`)를 토스트로 매핑.

### 8.3 ADMIN 모니터링 뷰

- 기존 ADMIN 신청 목록(`AdminApplicationDetailPage`/리스트)에 **EMA 상태 컬럼/필터** 추가(`emaSubmissionStatus`).
- IN_PROGRESS + (SUBMITTED·QUERY_RAISED·RESUBMITTED) 건을 "EMA 진행 중"으로 묶어 정체 건 식별. SUBMITTED 후 N일 초과 건은 시각 강조(리마인더와 동일 기준 — 리마인더 알림이 미배포면 이 시각 강조가 **유일한 정체 감지 수단**, §10/§11).
- **`emaGrandfathered=true` 건은 "Approved (legacy)"로 구분 표시**(허점#2) — 진짜 승인 건수와 grandfathered 건수를 통계에서 분리. ADMIN 이 검수 후 실제 ELISE 상태에 맞게 Revert/재제출 정정 가능.
- ADMIN은 상세에서 동일 EMA 액션 + Revert(T9) 수행 가능.

---

## 9. JIT 정보 수집 원칙 검토

- EMA 제출 본문 필드(installationName/premisesType/주소 등)는 **이미 P1.1로 수집·저장됨**(`Application.java:348-410`). 본 스펙은 **재수집하지 않는다**(JIT 위반 없음).
- 신규로 받는 입력은 오직 **제출 행위의 결과**(접수번호, 질의 내용, 접수증, 승인 시각)뿐이며, 이는 "제출 시점에 비로소 존재하는" 정보라 JIT 원칙상 그 시점 수집이 정당.
- 접수번호는 LEW가 ELISE에서 받은 값을 옮기는 것 — 신청자에게 재요청하지 않음.

---

## 10. 알림 트리거 매핑

| 알림 | 트리거 | 수신자 | NotificationType(신규) | 1차 채널 | 2차(후속) | payload |
|---|---|---|---|---|---|---|
| **제출 리마인더** | SUBMITTED/RESUBMITTED 후 N일(`ema.reminder.days`) 무변동 — 스케줄러 | 담당 LEW | `EMA_SUBMISSION_REMINDER_LEW` | **IN_APP** (직접 생성) | EMAIL (와이어링 인프라 준비 후) | applicationCode, emaReferenceNo, submittedAt, ctaUrl=`/lew/applications/{id}/review` |
| **반려 통지** (OQ-8) | reject(T7) 성공 | 담당 LEW | `EMA_REJECTED_LEW` | **IN_APP** (직접 생성) | EMAIL (와이어링 인프라 준비 후) | applicationCode, reason(emaQueryNote), ctaUrl=`/lew/applications/{id}/review` |
| **발급 통지** | completeApplication 성공(COMPLETED) | 신청자 | 기존 `sendLicenseIssuedEmail` 유지 OR 신경로 이관 | EMAIL(+IN_APP) | — | 기존 payload |

### 10.1 알림 설계 결정 — 허점#3 검증: IN_APP 단독 경로 채택 (방향 a)

**코드 검증 결과 (방향 a 확정)**: 인앱 알림은 오케스트레이터/outbox/템플릿 풀세팅 없이 **독립적으로 발행 가능**하다.
- [`NotificationService.createNotification(recipientSeq, type, title, message, referenceType, referenceId)`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/notification/NotificationService.java#L34) 는 `@Transactional(propagation=REQUIRES_NEW)` + `notificationRepository.saveAndFlush()` 로 인앱 알림 row 를 즉시 영속한다. `NotificationOrchestrator`/`templateRegistry`/outbox/채널 어댑터에 **전혀 의존하지 않는다**.
- 실동작 선례: [`LewAssignmentNotificationListener`](../../blue-light-backend/src/main/java/com/bluelight/backend/api/application/LewAssignmentNotificationListener.java)(2026-06-13 배포)가 `@TransactionalEventListener(AFTER_COMMIT)` 안에서 `createNotification(...)` 직접 호출(인앱) + `emailService` 분리 try/catch(이메일) 패턴을 이미 사용 중. EMA 리마인더/반려 통지는 이 패턴을 **그대로 복제**하면 된다.
- 따라서 **EMA 알림은 IN_APP 우선으로 E0~E5 핵심 범위 안에서 1차 구현**한다. 이메일 채널은 신경로 와이어링 인프라(템플릿 본문 적재 + 오케스트레이터 발행)가 준비되는 시점에 후속으로 추가(`notification-event-wiring-design.md §3` 절차). 즉 리마인더/반려 통지가 와이어링 트랙 선행에 **막히지 않는다**.

**알림별 결정**:
- **제출 리마인더**: 스케줄러가 `emaSubmittedAt + ema.reminder.days` 경과 + 여전히 SUBMITTED/RESUBMITTED 인 건을 찾아 담당 LEW 에게 `createNotification(...)` 직접 발행. 기존 만료 알림 스케줄러(`markExpiryNotified` 패턴, `Application.java:769`) 구조 참고 — 중복 발송 가드(예: `ema_reminder_notified_at` 컬럼 또는 1일 1회 멱등)로 폭주 방지.
- **반려 통지(OQ-8 확정)**: reject(T7) 처리 후 AFTER_COMMIT 리스너에서 담당 LEW 에게 "반려됨 — 사유 반영 후 재제출" 인앱 알림. **신청자에게는 EMA 중간/반려 상태 비노출**(US-C1, 비목표 — 신청자는 최종 발급 시에만 통지). EMA 보완에 신청자 서류가 필요하면 별개의 기존 Documents/`requestRevision` 알림 동선이 담당.
- **발급 통지**: 현재 `sendLicenseIssuedEmail`(레거시 직접발송) 유지. 신경로 이관은 **선택**(OQ-7) — 와이어링 트랙에서 일괄 이관 권장.

---

## 11. 한계 · 리스크 · 완화책

| # | 리스크 | 영향 | 완화책 |
|---|---|---|---|
| R1 | **수동 갱신 의존성** — LEW가 ELISE에서 제출/승인하고도 시스템에 표기 안 하면 추적 누락 | 게이트가 막혀 종료 불가, 신청 정체 | 리마인더 인앱 알림(N일, IN_APP 1차로 1차 배포에 포함 — 허점#3 방향 a), ADMIN 모니터링 뷰에서 정체 건 가시화, ADMIN 대행(T1~T8) |
| R2 | **상태 ↔ 실제 EMA 불일치** — LEW가 APPROVED 오기입 | 미승인 건이 발급될 위험 | EMA_ACK 증빙 첨부(`ema.ack.required`로 필수화 가능), T9 Revert(ADMIN 전용), 모든 전이 감사로그 |
| R3 | **게이트 도입에 따른 기존 운영 중단** — 기존 IN_PROGRESS 건이 게이트에 막힐 위험 | 배포 직후 진행 중 건 종료 막힘 | **OQ-1 확정**: 마이그레이션에서 기존 IN_PROGRESS 행을 APPROVED 로 자동 backfill(grandfathering, §6) → ema 게이트는 통과. 단 **LICENSE_PDF 게이트는 여전히 적용**되므로 PDF 미첨부 grandfathered 건은 종료 시 막힌다 — 배포 공지 + 해당 건 PDF 업로드 안내 필요 |
| R4 | **EMA_ACK 강제 안 함(초기)** — 증빙 없이 상태만 진행 가능 | 신뢰도 약화 | **OQ-3 확정**: `ema.ack.required` 설정 플래그(기본 false). 운영 데이터 보고 코드 변경 없이 `true` 로 즉시 필수화 가능(§3.3) |
| R5 | **emaQueryNote에 PII 유입** | PDPA 노출 | 입력 가이드 명시 + 향후 암호화 컬럼 전환 옵션(OQ-4) |
| R6 | **LEW 부재/퇴사** | 해당 건 EMA 진행 불가 | ADMIN 대행 권한(OQ-2 확정 — 전 전이 ADMIN 허용)으로 흡수 |
| R7 | **backfill grandfathering 의 정보 왜곡** — APPROVED 가 "실제 승인"이 아닌 행이 섞임 | 사후 통계/감사에서 진짜 승인과 구분 불가 | **허점#2 해결**: 응답 DTO 계산 필드 `emaGrandfathered`(= APPROVED && decisionAt==null && referenceNo==null)로 LEW/ADMIN UI 에서 "Approved (legacy)" 구분 배지 표시. 통계도 이 식으로 진짜 승인과 분리(§7/§8) |
| R8 | **Revert 복원 정확성**(허점#1) — APPROVED/WITHDRAWN 되돌릴 때 직전 상태 모름 | 잘못된 상태로 복원 | **해결**: `ema_status_before_decision` 슬롯에 결정 직전 상태 보관 → T9 가 정확 복원. 슬롯 null(grandfathered 등)이면 SUBMITTED 폴백(§3.1/§5.2) |
| R9 | **리마인더 미배포 시 정체 미감지**(허점#3) — 만약 IN_APP 마저 막히면 정체 건 알 수 없음 | 정체 신청 방치 | **검증으로 해소(방향 a)**: IN_APP 은 오케스트레이터 무관하게 독립 발행 가능(§10.1) → 1차 배포에 포함. 만일의 임시 완화책: ADMIN 모니터링 뷰의 "SUBMITTED 후 N일 초과 시각 강조"가 알림 없이도 정체 건을 노출(§8.3) |

---

## 12. PR 분해 제안

| PR | 범위 | 산출물 | 의존 |
|---|---|---|---|
| **PR-E0** | 데이터 모델 + 마이그레이션 | `EmaSubmissionStatus` enum, Application **7컬럼**(`ema_status_before_decision` 포함 — 허점#1) + getter, FileType `EMA_ACK`, `migrateApplicationsEmaSubmissionTracking` (**+OQ-1 backfill UPDATE**), schema.sql, `system_settings` `ema.reminder.days`·**`ema.ack.required` 2키 시드** | — |
| **PR-E1** | 도메인 상태 기계 + 서비스 | `Application`에 전이 메서드(T1~T10, **T10 REJECTED→RESUBMITTED 포함**) + 가드, **결정 진입 시 `emaStatusBeforeDecision` 저장 / T9 복원·null폴백(허점#1)**, **재제출 시 `emaQueryNote`/`emaDecisionAt`/슬롯 클리어(허점#4)**, `ema.ack.required` 플래그 분기, actor role 감사 기록, 감사 액션(`EMA_*` AuditAction 7종) | E0 |
| **PR-E2** | API + DTO | EMA 전이 엔드포인트 7종 + GET, 요청/응답 DTO(`emaAckRequired`/`emaAckPresent` + **`emaGrandfathered` 계산 필드(허점#2)** 포함), SpEL 권한(LEW 본인+ADMIN 대행), `AdminApplicationResponse`에 EMA 필드 inline | E1 |
| **PR-E3** | completeApplication 게이트 | 서비스 게이트 2건(`EMA_NOT_APPROVED`/`LICENSE_PDF_MISSING`), `GlobalExceptionHandler` 매핑 확인, 회귀 테스트 | E1 |
| **PR-E4** | 프론트 LEW EMA 탭 | LewReviewFormPage 탭 추가, 상태별 액션 UI(REJECTED→Resubmit 동선, EMA_ACK 필수/선택 동적 라벨, **`emaGrandfathered` "Approved (legacy)" 구분 배지(허점#2)**), EMA_ACK/LICENSE_PDF 업로드, Complete CTA 게이팅, ADMIN 모니터링 컬럼/필터 | E2, E3 |
| **PR-E5** | 알림(리마인더 + 반려 통지) — **IN_APP 1차** | `EMA_SUBMISSION_REMINDER_LEW`·`EMA_REJECTED_LEW` NotificationType + **인앱 직접 발행**(`NotificationService.createNotification`, `LewAssignmentNotificationListener` 패턴 복제 — 허점#3 방향 a), 리마인더 스케줄러(N일 멱등 + 중복 가드). 이메일 채널은 후속(와이어링 인프라 준비 후) | **E1만** (오케스트레이터 비의존) |
| **PR-E6** (선택) | 이메일 채널 + 발급 통지 신경로 이관 | EMA 알림 EMAIL 채널 추가, `sendLicenseIssuedEmail` → 오케스트레이터 이관, 매뉴얼/문서 갱신 | E5, 와이어링 인프라 |

- PR-E0~E3는 백엔드 한 흐름으로 묶어도 무방(리뷰 단위 판단). E4는 백엔드 머지 후 착수.
- **PR-E5 의존성 변경 (허점#3 방향 a)**: 코드 검증 결과 인앱 알림이 오케스트레이터 무관하게 독립 발행 가능 → PR-E5 가 **와이어링 인프라 선행에 막히지 않고 E1만 의존**한다. 따라서 리마인더·반려 통지는 **1차 배포(E0~E5) 핵심 범위에 포함**. 이메일 채널만 PR-E6(선택, 와이어링 인프라 의존)으로 분리. (강등 방향 b 불채택)
- **허점 4건 반영에 따른 조정**: ① PR-E0 에 `ema_status_before_decision` 7번째 컬럼(허점#1), ② PR-E1 에 결정 슬롯 저장/복원·null폴백(허점#1) + 재제출 시 queryNote 클리어(허점#4), ③ PR-E2 에 `emaGrandfathered` 계산 필드(허점#2), ④ PR-E4 에 grandfathered 구분 배지(허점#2), ⑤ PR-E5 를 IN_APP 1차로 범위 확정(허점#3 a). **새 PR 신설 없음** — 모든 결정이 기존 E0~E6 범위 안에서 흡수된다.

---

## 13. 핵심 결정사항 요약

1. EMA 추적 = `IN_PROGRESS`의 서브-상태 기계(`ApplicationStatus`에 값 추가 안 함). 신규 enum `EmaSubmissionStatus` 7개. 전이 T1~T10(**REJECTED→RESUBMITTED 재진입 포함 — OQ-8 확정: 반려는 종착 아님**).
2. 종료 게이트 = **방식 A 하이브리드**: `completeApplication`에 `emaSubmissionStatus=APPROVED` + LICENSE_PDF 존재 검증 추가. approve 전이는 발급과 분리(상태만 표기), UI 동선 묶음으로 누락 방지.
3. **권한 (OQ-2 확정)**: 제출 등 전 전이를 **담당 LEW 본인 + ADMIN/SYSTEM_ADMIN 대행 모두** 허용(completeApplication과 동일 SpEL). actor + actor role 을 감사로그에 기록해 LEW 본인/대행 구분. Revert(오기입 정정)만 ADMIN 전용.
4. 신뢰성 3요소: 감사로그(`EMA_*` 7종) / 증빙 첨부(신규 FileType `EMA_ACK`, **`ema.ack.required` 플래그로 선택↔필수 전환 — OQ-3 확정**) / 리마인더(`ema.reminder.days` 설정값 기반, **IN_APP 직접 발행 1차 — 허점#3 방향 a**).
5. **배포 호환 (OQ-1 확정)**: 마이그레이션에서 기존 IN_PROGRESS 행을 APPROVED 로 멱등 backfill(grandfathering) → 진행 건이 새 게이트에 막히지 않음. grandfathered 건은 `emaGrandfathered` 계산 필드로 진짜 승인과 구분(허점#2).
6. **검토 허점 4건 반영**: Revert 복원 슬롯 `ema_status_before_decision`(허점#1, 컬럼 7개), grandfathered 구분 배지(허점#2), 알림 IN_APP 1차(허점#3 a), 재제출 시 queryNote 클리어(허점#4). 새 PR 신설 없이 E0~E6 흡수.
7. SSOT/JIT 위반 없음: EMA 상태 enum은 법적 고정값(예외 허용), 리마인더 N일·ack 필수 플래그는 `system_settings`로 설정 우선, 제출 본문은 P1.1 기수집분 재사용.
8. 마이그레이션은 P1.1 패턴(컬럼별 `columnExists` 멱등) 답습 + schema.sql 동기화 + 멱등 backfill UPDATE.

---

## 14. 미해결 질문 (Open Questions) + 검토 허점 처리 결과

### 14.0 스펙 검토 허점 4건 — 모두 해결됨 (2026-06-14)

- **허점#1 ✅ 해결됨 (Revert 복원 슬롯)**: T9 가 APPROVED/WITHDRAWN 을 되돌릴 때 직전 상태를 알 수 없던 설계 공백 → 신규 컬럼 `ema_status_before_decision`(7번째) 추가. approve/reject/withdraw(T5~T8) 진입 시 from 상태를 저장, T9 가 정확 복원(복원 후 null 클리어). 슬롯 null(grandfathered 등)이면 SUBMITTED 폴백. 반영: §3.1(T5~T9), §5.2, §6(컬럼 7개), §11 R8, PR-E0/E1.
- **허점#2 ✅ 해결됨 (grandfathered 거짓 표시 방지)**: backfill 된 APPROVED 가 진짜 승인과 구분 불가하던 문제 → 응답 DTO 계산 필드 `emaGrandfathered = (APPROVED && emaDecisionAt==null && emaReferenceNo==null)` 추가. LEW/ADMIN UI 에서 "Approved (legacy/grandfathered)" 구분 배지로 표기, 통계 분리. 반영: §7, §8.2/§8.3, §11 R7, PR-E2/E4.
- **허점#3 ✅ 해결됨 (알림 인프라 의존 — 방향 a 채택)**: **코드 검증 결과** `NotificationService.createNotification`(REQUIRES_NEW + saveAndFlush)이 오케스트레이터/outbox/템플릿 무관하게 인앱 알림을 독립 발행 가능하고, `LewAssignmentNotificationListener`(2026-06-13 배포)가 동일 패턴을 이미 사용 중임을 확인. → 리마인더·반려 통지를 **IN_APP 우선으로 1차 배포(E0~E5) 핵심 범위에 포함**(이메일만 PR-E6 후속). PR-E5 의존성을 "와이어링 인프라"에서 "E1만"으로 완화. 강등(방향 b) 불채택. 반영: §10/§10.1, §11 R1/R9, PR-E5/E6.
- **허점#4 ✅ 해결됨 (reject 사유 잔존)**: 재제출(T3/T10) 시 `emaDecisionAt`만 클리어하고 `emaQueryNote`(공용 컬럼)는 잔존해 화면에 옛 반려/질의 사유가 남던 문제 → **재제출 계열 전이에서 `emaQueryNote=null` 도 함께 클리어**(전체 이력은 감사로그로 무손실 추적). 반영: §3.1(T3/T10), §5.2.

### 14.1 잔존 미해결 질문

- **OQ-1 ✅ 해결됨 (마이그레이션 backfill)**: 이미 IN_PROGRESS 인 기존 신청을 마이그레이션에서 `ema_submission_status=APPROVED` 로 일괄 멱등 backfill(grandfathering). `added>0` 첫 실행 + `status='IN_PROGRESS' AND ema_submission_status='NOT_SUBMITTED'` 조건의 이중 멱등 가드. 신규 생성·그 외 상태는 `NOT_SUBMITTED` 기본값. 단 LICENSE_PDF 게이트는 여전히 적용(§6, §11 R3/R7).
- **OQ-2 ✅ 해결됨 (LEW 본인 + ADMIN 대행 모두 허용)**: 전 EMA 전이를 `hasAnyRole('ADMIN','SYSTEM_ADMIN') or @appSec.isAssignedLew`(completeApplication 동일 SpEL)로 허용. 단 감사로그에 actor userSeq + actor role 기록해 "LEW 본인 vs ADMIN 대행" 사후 구분 가능(§3.2). Revert(T9)만 ADMIN 전용 유지.
- **OQ-3 ✅ 해결됨 (설정 플래그로 제어)**: EMA_ACK 첨부는 초기 선택. `system_settings.ema.ack.required`(기본 `false`)로 제어 — 운영이 `true` 로 바꾸면 코드 변경 없이 제출 전이 가드(T1/T3/T10)에서 즉시 필수화(설정 우선 원칙, §3.3/§5.4).
- **OQ-8 ✅ 해결됨 (REJECTED 재진입)**: REJECTED 는 종착이 아니라 재작업 가능 상태. 상태 기계에 **T10 `REJECTED → RESUBMITTED`(`resubmit`)** 추가 — LEW 가 반려 사유 반영 후 재진입. App.status 는 IN_PROGRESS 유지. 담당 LEW 에게 반려 통지 알림(`EMA_REJECTED_LEW`), 신청자에게는 EMA 중간/반려 상태 비노출(§8.2, §10).
- **OQ-4 (PII) — 미해결**: `ema_query_note` 자유 텍스트를 평문으로 둘지, EncryptedStringConverter 적용할지(PDPA). 검색 불필요하면 암호화가 안전.
- **OQ-5 (질의 이력) — 미해결**: 질의가 여러 번 오가는 케이스에서 컬럼 1건 + 감사로그로 충분한지, 별도 `ema_query_logs` 테이블이 필요한지(운영 빈도 의존).
- **OQ-6 (스키마) — 미해결**: `files.file_type` VARCHAR 폭이 `EMA_ACK` 추가에 충분한지 실제 컬럼 길이 확인 필요(부족 시 폭 ALTER 동반).
- **OQ-7 (발급 통지) — 미해결**: 기존 `sendLicenseIssuedEmail` 레거시 직접발송을 본 트랙에서 신경로로 이관할지, 와이어링 트랙에 위임할지.
