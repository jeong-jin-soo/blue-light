# 결제 후 kVA 사후 변경 + 수기 정산 기록 — 정식 스펙

작성일: 2026-05-01
작성자: product-manager 에이전트
상태: **검토 완료 — 5개 결정 확정 (2026-05-01)**, PR-1 발주 진행 중
출처:
- 1차안 메모리 — `memory/lew-review-flow-roadmap.md` §"후속 항목 1"
- 직전 4-PR 시리즈(2026-04-30 develop 머지, commits 16493ba/fcce9d1/3e81d0e/978500e)
- CLAUDE.md §설계 원칙 1·2 (설정 우선 / JIT 정보 수집)

---

## 1. 요구사항 요약

결제 완료(`PAID` 이상) 이후에도 **ADMIN이 직접 kVA를 변경**할 수 있고, **LEW는 변경 요청만** 보낼 수 있도록 한다. 시스템은 자동 차액 청구·환불을 수행하지 않으며, ADMIN이 외부 채널(PayNow/계좌이체 등)로 수기 정산한 결과만 `KvaAdjustmentRecord`에 기록한다. CoF가 이미 finalized인 경우 자동 unfinalize + LEW 재서명 알림을 트리거한다.

본 스펙은 **금융 트랜잭션이 아닌 감사·수기 정산 기록 시스템**이다. 회계 자동화는 범위 외.

---

## 2. 도메인 배경

### 2.1 현재 시스템의 가드와 한계

`ApplicationKvaService.confirm()` (라인 99-105) 의 `isLockedStatus` 가드는 결제 후 kVA 변경을 **완전 차단**한다:

```java
// PAID/IN_PROGRESS/COMPLETED/EXPIRED ⇒ 409 KVA_LOCKED_AFTER_PAYMENT
```

이 가드는 결제 후 가격이 변하면 정산이 깨지는 것을 막기 위한 의도된 설계였다. 그러나 현장 운영(2026-04-30 이후 LEW 검토 동선 개편 후)에서:

- LEW가 시공 단계에서 실제 부하를 측정해보니 신청 kVA가 부적합한 케이스 발생
- ADMIN이 외부 채널 환불·차액 청구로 정산 가능하지만, 시스템에 그 사실을 기록할 곳이 없음

### 2.2 SS 638 §13 정합성

CoF는 시공·시험 후 LEW가 발행한다. 시공 단계에서 kVA가 변하면 CoF의 `approvedLoadKva` 스냅샷도 갱신되어야 하므로, **CoF unfinalize → 재서명** 플로우가 필요하다. (현재 `Application.reopenForCofReissue()` + `CertificateOfFitness.reopenForReissue()` 메서드는 PR3 이전 모델용이며, 본 스펙에서는 결제 후 컨텍스트로 확장한다.)

### 2.3 설계 원칙(CLAUDE.md) 준수

- **설정 우선 원칙**: kVA tier·가격은 `master_prices` 단일 정본. 사후 변경 시 어느 시점 가격을 쓸지 §6.4에서 명시.
- **JIT 정보 수집**: 신청자에게 kVA 재확인을 묻지 않는다(이미 결제까지 마쳤으므로 본인 의사 확인은 불필요). LEW 요청·ADMIN 변경은 모두 신청자 입력 없이 처리.

---

## 3. 사용자 시나리오

### 3.1 액터별 권한 매트릭스

| 액션 | APPLICANT | LEW | ADMIN |
|---|---|---|---|
| 사후 kVA 변경 트리거 (직접) | ❌ | ❌ | ✅ |
| 사후 kVA 변경 요청 (제안) | ❌ | ✅ (배정된 신청만) | — |
| 정산(PAID_DIFFERENCE/REFUNDED/WAIVED) 마킹 | ❌ | ❌ | ✅ |
| 변경 이력 조회 | ✅ (자기 신청, 일부 마스킹) | ✅ (배정된 신청) | ✅ (전체) |
| 첨부 영수증 업로드 | ❌ | ❌ | ✅ |

### 3.2 시나리오 S1 — LEW 요청 → ADMIN 승인

**전제**: 신청 status = `PAID`, kVA = 100, CoF finalized = false.

1. LEW가 시공 중 실제 부하 측정 결과 200 kVA 필요함을 발견
2. LEW가 `/lew/applications/:id/review` 페이지 KVA 카드에서 "Request kVA adjustment" 버튼 클릭
3. 모달에서 `proposedKva=200`, `reason="Site survey: actual load 180 kVA, recommend 200 kVA tier"` 입력 → POST `/api/lew/applications/{id}/kva-adjustment-request`
4. 시스템: `KvaAdjustmentRecord` 생성 (status=`PENDING_ADMIN_REVIEW`, requestedByLew=현재 LEW). ADMIN에게 인앱 + 이메일 알림 (`KVA_ADJUSTMENT_REQUESTED_ADMIN`).
5. ADMIN이 LEW 요청을 검토 후 ADMIN 모달 열기 → newKva=200(LEW 제안 그대로) 또는 다른 값 입력 + adminMemo + paymentAdjustment(예: PAID_DIFFERENCE) 선택
6. POST `/api/admin/applications/{id}/kva-override-postpayment` → `KvaAdjustmentRecord` 새 row 생성, **이전 LEW 요청 row의 status를 `RESOLVED_BY_ADMIN_OVERRIDE`로 마킹**(요청과 실제 변경을 별개의 row로 보관)
7. LEW에게 알림 (`KVA_ADJUSTED_BY_ADMIN_LEW`). CoF가 finalized였다면 unfinalize + 재서명 알림.

### 3.3 시나리오 S2 — ADMIN 단독 변경 (LEW 요청 없이)

**전제**: 신청 status = `IN_PROGRESS`, kVA = 50, CoF finalized = true (이미 LEW 서명 완료).

1. 신청자로부터 외부 채널 클레임("실제로 75 kVA가 필요했다"). ADMIN이 정합성 확인.
2. ADMIN이 `/admin/applications/:id` 페이지 KVA 카드 → "Override kVA (post-payment)" 버튼 클릭
3. 모달: newKva=75, reason="Applicant claim verified by phone 2026-05-12", adminMemo="Refund $X processed via PayNow ref ABC123", paymentAdjustment=`REFUNDED`, settledAmount=200.00, receiptReferenceNumber="ABC123"
4. POST `/api/admin/applications/{id}/kva-override-postpayment` →
   - `KvaAdjustmentRecord` row 생성 (changedByRole=`ADMIN`, lewRequestSeq=`null`)
   - **CoF 자동 unfinalize**: `certifiedAt=null`, `approvedLoadKva=75`로 스냅샷, `lewConsentDate=null`. CoF 재서명 토큰/상태 갱신은 `LewReviewService.finalizeCof` 가드가 그대로 처리.
   - LEW에게 인앱+이메일 알림 (`COF_REISSUED_BY_KVA_OVERRIDE` 재사용)
   - 신청자에게 인앱 알림 ("Your kVA was updated by admin to 75. License is being re-issued.")

### 3.4 시나리오 S3 — Settlement 사후 마킹

**전제**: §3.3 직후. ADMIN이 즉시 정산 처리하지 못해 `paymentAdjustment=PENDING`으로 변경 row 생성. 며칠 후 환불 완료.

1. ADMIN이 `/admin/applications/:id/kva-history` 카드에서 해당 row의 "Mark settled" 클릭
2. 모달: paymentAdjustment=`REFUNDED`, settledAmount=200.00, receiptReferenceNumber="ABC123", settlementMemo="Refunded via PayNow on 2026-05-15"
3. PATCH `/api/admin/applications/{id}/kva-adjustments/{adjustmentSeq}/settlement`
4. 시스템: 같은 `KvaAdjustmentRecord` row의 settlement 필드만 update. **이력은 별도 audit log로 남음** (PENDING→REFUNDED 전이 자체가 감사 이벤트).
5. 옵션: LEW에게 `KVA_ADJUSTMENT_SETTLED_LEW` 알림 (선택, ADMIN이 모달에서 체크).

### 3.5 시나리오 S4 — CoF finalized 상태에서 kVA 변경 (옵션 A)

**전제**: `IN_PROGRESS`, CoF.certifiedAt 존재.

1. ADMIN의 kva-override-postpayment 호출 시점에 `CertificateOfFitness.isFinalized()=true` 감지
2. **단일 트랜잭션**으로 다음 처리:
   - `KvaAdjustmentRecord` 생성
   - `Application.selectedKva` / `quoteAmount` 갱신
   - `CertificateOfFitness.reopenForReissue(newKva)` 호출 (기존 메서드 재사용)
   - `Application.status` 전이 정책: **현재 `PAID`이면 그대로 유지, `IN_PROGRESS`이면 그대로 유지**. (PR3 모델에서는 status가 결제 게이트와 분리되었으므로 reopen하지 않는다.)
3. LEW에게 `COF_REISSUED_BY_KVA_OVERRIDE` 알림. LEW는 `/lew/applications/:id/review`에서 CoF 재확인 후 finalize 재호출.
4. 트랜잭션 실패 시 전체 롤백 (§7.4).

---

## 4. Given-When-Then 수용 기준

### 4.1 POST `/api/admin/applications/{id}/kva-override-postpayment`

#### AC-A1 정상 흐름 (CoF 미finalize 상태)

- **GIVEN** `application.status=PAID`, `selectedKva=100`, `kvaStatus=CONFIRMED`, CoF finalized=false
- **AND** ADMIN 인증, request `{newKva: 200, reason: "...", adminMemo: "...", paymentAdjustment: "PAID_DIFFERENCE", settledAmount: 500.00, receiptReferenceNumber: "P-2026-051"}`
- **WHEN** POST 호출
- **THEN** 200 OK, `KvaAdjustmentRecord` row 1개 생성
- **AND** `Application.selectedKva=200`, `quoteAmount=재계산값`(§6.4)
- **AND** `AuditLog`에 `KVA_OVERRIDE_POSTPAYMENT` 기록 (REQUIRES_NEW)
- **AND** 배정 LEW에게 `KVA_ADJUSTED_BY_ADMIN_LEW` 인앱 + 이메일 알림 (AFTER_COMMIT)

#### AC-A2 결제 전 상태에서 거부

- **GIVEN** `application.status=PENDING_REVIEW` (또는 REVISION_REQUESTED, PENDING_PAYMENT)
- **WHEN** POST 호출
- **THEN** 409 `KVA_NOT_POSTPAYMENT` ("Use /api/admin/applications/{id}/kva with force=true for pre-payment changes")
- **AND** 기존 `ApplicationKvaService.confirm(force=true)` 경로로 안내. **본 엔드포인트는 PAID/IN_PROGRESS/COMPLETED만 허용**.
- **참고**: COMPLETED·EXPIRED 처리는 §4.1 AC-A3 참조.

#### AC-A3 COMPLETED / EXPIRED 상태 처리

- **GIVEN** `application.status=COMPLETED` (라이선스 발급됨)
- **WHEN** POST 호출
- **THEN** 검토자 결정 필요(§10 D5):
  - 옵션 A: 200 OK 허용. 단 라이선스 정정은 별도 운영 절차 — adminMemo에 "License correction in progress" 기록 의무
  - 옵션 B: 409 거부. EXPIRED는 항상 거부.
- **(추천)** 옵션 A — COMPLETED 허용, EXPIRED는 거부. 만료된 신청은 회계상 closed.

#### AC-A4 newKva가 master_prices에 없는 경우

- **GIVEN** `master_prices`에 newKva=999 row 없음
- **WHEN** POST `{newKva: 999, ...}`
- **THEN** 400 `INVALID_KVA_TIER`. 트랜잭션 롤백, audit log `KVA_OVERRIDE_POSTPAYMENT` 미기록.

#### AC-A5 동일 newKva (no-op) 거부

- **GIVEN** `application.selectedKva=100`
- **WHEN** POST `{newKva: 100, ...}`
- **THEN** 400 `KVA_NO_CHANGE` ("New kVA is identical to current value"). 의미 없는 ledger 오염 방지.

#### AC-A6 동시성 — @Version 충돌

- **GIVEN** ADMIN 두 명이 동시에 모달 열고 각자 different newKva 제출
- **WHEN** 두 번째 호출이 첫 번째 commit 이후 도착
- **THEN** 두 번째 호출은 409 `STALE_STATE` (기존 GlobalExceptionHandler가 OptimisticLockException 변환)
- **AND** `KvaAdjustmentRecord`는 **첫 번째 호출의 row 1개만 존재**.

### 4.2 POST `/api/lew/applications/{id}/kva-adjustment-request`

#### AC-L1 정상 흐름

- **GIVEN** `application.status=PAID`, `assignedLew = current LEW`, 기존 `PENDING_ADMIN_REVIEW` 상태 LEW 요청 없음
- **WHEN** POST `{proposedKva: 200, reason: "..."}`
- **THEN** 200 OK, `KvaAdjustmentRecord` row 1개 생성 (`changedByRole=LEW_REQUEST`, `status=PENDING_ADMIN_REVIEW`, `previousKva=현재 selectedKva`, `newKva=null`, `proposedKva=200`)
- **AND** **`Application.selectedKva` 변경 없음** (LEW 요청은 단순 제안)
- **AND** ADMIN에게 `KVA_ADJUSTMENT_REQUESTED_ADMIN` 알림.

#### AC-L2 권한 거부

- **GIVEN** LEW가 본인 미배정 신청에 호출
- **WHEN** POST
- **THEN** 403 `APPLICATION_NOT_ASSIGNED` (기존 `OwnershipValidator` 패턴 재사용).

#### AC-L3 결제 전 거부

- **GIVEN** `application.status=PENDING_REVIEW`
- **WHEN** POST
- **THEN** 409 `KVA_NOT_POSTPAYMENT` ("Use Phase 1 kVA confirmation flow for pre-payment changes"). 기존 `PATCH /api/admin/applications/{id}/kva` 경로로 안내.

#### AC-L4 LEW가 다른 newKva로 ADMIN 변경된 후 LEW 요청은?

- **GIVEN** LEW가 proposedKva=200 요청 → ADMIN이 newKva=150으로 다른 값 변경
- **WHEN** ADMIN의 kva-override-postpayment 트랜잭션 commit
- **THEN** LEW 요청 row는 `status=RESOLVED_BY_ADMIN_OVERRIDE` (newKva=150이 proposedKva=200과 다름을 metadata에 기록)
- **AND** ADMIN의 새 row는 별도 (`changedByRole=ADMIN_DIRECT`, `lewRequestSeq=원 요청 row 참조`)
- **AND** LEW에게 알림 시 메시지: "Admin updated kVA to 150 (your suggestion: 200)"

#### AC-L5 중복 PENDING 요청 방지

- **GIVEN** 동일 신청에 LEW의 `PENDING_ADMIN_REVIEW` 요청이 이미 존재
- **WHEN** LEW가 또 다른 proposedKva 요청
- **THEN** 409 `KVA_ADJUSTMENT_REQUEST_PENDING` (기존 요청 보여주는 정보 포함).

### 4.3 PATCH `/api/admin/applications/{id}/kva-adjustments/{adjustmentSeq}/settlement`

#### AC-S1 정상 흐름

- **GIVEN** `KvaAdjustmentRecord` row 존재, `paymentAdjustment=PENDING`
- **WHEN** PATCH `{paymentAdjustment: "REFUNDED", settledAmount: 200.00, receiptReferenceNumber: "ABC", settlementMemo: "..."}`
- **THEN** 200 OK, 동일 row의 settlement 필드 update, `settledAt=now()`
- **AND** `AuditLog`에 `KVA_SETTLEMENT_MARKED` 기록.

#### AC-S2 PENDING이 아닌 row에 PATCH 거부

- **GIVEN** row의 `paymentAdjustment=PAID_DIFFERENCE`
- **WHEN** PATCH
- **THEN** 409 `SETTLEMENT_ALREADY_RECORDED` ("Use audit-correct flow: create new row, link to original via correctedBy field"). 검토자 결정 필요(§10 D6): 정정 row 허용 여부.

#### AC-S3 다른 신청의 row 거부

- **GIVEN** path의 applicationSeq와 adjustmentSeq의 application이 불일치
- **THEN** 404 `KVA_ADJUSTMENT_NOT_FOUND` (정보 노출 방지).

### 4.4 CoF Unfinalize 상호작용

#### AC-C1 CoF finalized 상태에서 kVA override

- **GIVEN** `application.status=IN_PROGRESS`, `cof.certifiedAt != null`, `cof.approvedLoadKva=100`
- **WHEN** POST kva-override-postpayment `{newKva: 75}`
- **THEN** 단일 트랜잭션 내에서:
  1. `KvaAdjustmentRecord` insert
  2. `Application.selectedKva=75`, `quoteAmount` 재계산
  3. `cof.reopenForReissue(75)` 호출 → `certifiedAt=null`, `approvedLoadKva=75`, `lewConsentDate=null`
- **AND** `Application.status`는 변경 없음 (`IN_PROGRESS` 유지). PR3 옵션 R 모델에서 CoF는 결제 후 단계이므로 status reopen 불필요.
- **AND** 배정 LEW에게 `COF_REISSUED_BY_KVA_OVERRIDE` 알림 + `KVA_ADJUSTED_BY_ADMIN_LEW` 알림 (둘 다 발송, AFTER_COMMIT)
- **AND** AuditLog: `KVA_OVERRIDE_POSTPAYMENT` + `COF_REISSUED_BY_KVA_OVERRIDE` 두 이벤트.

#### AC-C2 CoF unfinalize 후 LEW 재서명

- **GIVEN** AC-C1 직후. LEW가 `/lew/applications/:id/review` 진입
- **WHEN** LEW가 `LewReviewService.finalizeCof()` 재호출
- **THEN** 정상 finalize 동작 (기존 가드 재사용 — `application.status ∈ {PAID, IN_PROGRESS}` 통과)
- **AND** `cof.certifiedAt`이 새 timestamp로 기록.

### 4.5 master_prices 가격 시점 (§6.4 상세)

#### AC-P1 master_prices 가격 변경 후 사후 변경

- **GIVEN** 결제 당시 100 kVA 가격 = $300, master_prices가 $350으로 변경됨, 신청은 `PAID` 상태
- **WHEN** ADMIN이 newKva=200으로 override (200 kVA 현재가 $500, 결제 당시 $450)
- **THEN** 검토자 결정 필요(§10 D1):
  - 옵션 A (현재가): newQuote = 현재 master_prices의 200 kVA 가격 = $500
  - 옵션 B (원가 동결): `Application.quoteAmount`를 변경하지 않음. amountDifference만 metadata에 기록(현재가 기준).
  - 옵션 C (스냅샷): `Application` 생성 시점의 master_prices 가격을 따로 보관해 그것을 적용 — **현재 시스템에 priceAt-PAID 스냅샷이 없으므로 신규 컬럼/테이블 필요**.
- **(추천)** 옵션 A. 이유: ADMIN이 수기 정산하므로 ledger에 표시되는 newQuote는 "변경 시점 시스템상 견적가". 실제 송금/환불 금액은 `settledAmount`로 별도 기록. 설정 우선 원칙(현재 master_prices가 단일 정본)과 정합.

---

## 5. 데이터 모델

### 5.1 KvaAdjustmentRecord (신규 엔티티)

**원칙**: soft delete 금지 (감사 무결성). `BaseEntity`의 `deleted_at` 컬럼 유지하되 사용 안 함. `@SQLDelete`/`@SQLRestriction` 미적용.

```java
@Entity
@Table(name = "kva_adjustment_records")
public class KvaAdjustmentRecord extends BaseEntity {

    @Id
    @GeneratedValue
    private Long adjustmentSeq;

    @ManyToOne(fetch = LAZY, optional = false)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /** 변경 종류. ADMIN_DIRECT = ADMIN이 즉시 변경한 row, LEW_REQUEST = LEW의 요청 row(아직 변경 미적용). */
    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role", nullable = false, length = 20)
    private KvaAdjustmentChangedByRole changedByRole; // ADMIN_DIRECT | LEW_REQUEST

    @ManyToOne(fetch = LAZY)
    @JoinColumn(name = "changed_by_user_seq")
    private User changedByUser; // ADMIN 또는 LEW

    /** LEW_REQUEST → ADMIN_DIRECT 연결 시 원 요청 row 참조. 단독 ADMIN 변경은 null. */
    @Column(name = "lew_request_seq")
    private Long lewRequestSeq;

    /** 요청/변경 상태. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private KvaAdjustmentStatus status;
    // ADMIN_DIRECT일 때: APPLIED
    // LEW_REQUEST일 때: PENDING_ADMIN_REVIEW | RESOLVED_BY_ADMIN_OVERRIDE | REJECTED_BY_ADMIN | CANCELLED_BY_LEW

    /** 변경 직전 Application.selectedKva. */
    @Column(name = "previous_kva", nullable = false)
    private Integer previousKva;

    /** ADMIN이 적용한 newKva. LEW_REQUEST row는 null. */
    @Column(name = "new_kva")
    private Integer newKva;

    /** LEW가 제안한 kVA. ADMIN_DIRECT row는 null (또는 lewRequestSeq를 통해 join). */
    @Column(name = "proposed_kva")
    private Integer proposedKva;

    /** 변경 사유 (LEW가 입력한 reason 또는 ADMIN의 reason). 필수. */
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    /** 변경 직전 quoteAmount. */
    @Column(name = "previous_quote_amount", precision = 10, scale = 2)
    private BigDecimal previousQuoteAmount;

    /** 변경 직후 quoteAmount (시스템 재계산 결과, master_prices 현재가 기반). */
    @Column(name = "new_quote_amount", precision = 10, scale = 2)
    private BigDecimal newQuoteAmount;

    /** newQuote - previousQuote (signed). */
    @Column(name = "amount_difference", precision = 10, scale = 2)
    private BigDecimal amountDifference;

    /** 사용한 master_prices 정본 row 참조 (가격 정합성 추적). */
    @Column(name = "master_price_seq_used")
    private Long masterPriceSeqUsed;

    /** ADMIN의 운영 메모 (수기 정산 처리 내역). */
    @Column(name = "admin_memo", length = 2000)
    private String adminMemo;

    /** 정산 상태. */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_adjustment", length = 20)
    private PaymentAdjustment paymentAdjustment;
    // PENDING | PAID_DIFFERENCE | REFUNDED | WAIVED | NOT_APPLICABLE

    /** 실제 송금/환불 금액 (양수, 절댓값). amountDifference와 분리 — 환율·수수료 차감 후 실제 금액. */
    @Column(name = "settled_amount", precision = 10, scale = 2)
    private BigDecimal settledAmount;

    /** 외부 결제 채널 참조번호 (PayNow ref, 송금증 번호 등). */
    @Column(name = "receipt_reference_number", length = 100)
    private String receiptReferenceNumber;

    /** 정산 마킹 메모 (settlement 갱신 시 별도 메모). */
    @Column(name = "settlement_memo", length = 1000)
    private String settlementMemo;

    /** ADMIN이 직접 변경 또는 settlement 마킹한 시각. status 전이마다 갱신. */
    @Column(name = "admin_action_at")
    private LocalDateTime adminActionAt;

    /** Settlement final 처리 시각. PAID_DIFFERENCE/REFUNDED/WAIVED 마킹 시 기록. */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    /** CoF unfinalize 발생 여부 (감사·이력 표시용). */
    @Column(name = "cof_reissue_triggered", nullable = false)
    private Boolean cofReissueTriggered = false;

    /** 첨부 영수증 FileEntity 참조 (선택). 옵션 §10 D2 — 이번 PR에서 포함할지. */
    @Column(name = "receipt_file_seq")
    private Long receiptFileSeq;
}
```

### 5.2 1차안 대비 추가/검토된 필드

| 필드 | 1차안 | 본 스펙 | 이유 |
|---|---|---|---|
| `lewRequestSeq` | ❌ | ✅ | LEW 요청과 ADMIN 변경을 별 row로 보관 + 연결. AC-L4 처리 |
| `proposedKva` | ❌ | ✅ | LEW 요청 시 newKva와 분리. ADMIN이 다른 값 적용 가능 (AC-L4) |
| `status` enum | ❌ | ✅ | PENDING_ADMIN_REVIEW / APPLIED / RESOLVED_BY_ADMIN_OVERRIDE / REJECTED / CANCELLED |
| `masterPriceSeqUsed` | ❌ | ✅ | 사용한 가격 정본 row 추적 (§6.4 옵션 결정과 무관하게 유용) |
| `settledAmount` | ❌ | ✅ | amountDifference(이론값)와 분리. 실제 송금 금액. **검토자 요청 사항** |
| `receiptReferenceNumber` | ❌ | ✅ | PayNow ref 등 외부 채널 추적. **검토자 요청 사항** |
| `settlementMemo` | ❌ | ✅ | adminMemo와 분리 — kVA 변경 시 메모 vs 정산 마킹 시 메모 |
| `cofReissueTriggered` | ❌ | ✅ | 이력 카드에서 "CoF 재서명 트리거됨" 배지 표시용 |
| `receiptFileSeq` | ❌ | △ (옵션) | 영수증 파일 첨부 — 옵션 §10 D2 |

### 5.3 마이그레이션

`V09__create_kva_adjustment_records.sql` 신규 추가. 기존 PAID/IN_PROGRESS 신청에 대한 backfill 불필요 — 빈 row 상태가 자연스럽게 "변경 없음"을 의미. **데이터 보정 작업 없음**.

### 5.4 Notification & AuditAction 확장

**Notification (NotificationType enum)**:
- `KVA_ADJUSTED_BY_ADMIN_LEW` (ADMIN→LEW)
- `KVA_ADJUSTMENT_REQUESTED_ADMIN` (LEW→ADMIN)
- `KVA_ADJUSTMENT_REJECTED_LEW` (옵션 — ADMIN이 LEW 요청 거부 시)
- `KVA_ADJUSTMENT_SETTLED_LEW` (옵션, P2)

**AuditAction enum**:
- `KVA_OVERRIDE_POSTPAYMENT`
- `KVA_SETTLEMENT_MARKED`
- `KVA_ADJUSTMENT_REQUESTED_BY_LEW`
- `KVA_ADJUSTMENT_REJECTED_BY_ADMIN` (옵션)
- `COF_REISSUED_BY_KVA_OVERRIDE` (기존 재사용)

---

## 6. 기술적 고려사항

### 6.1 영향받는 파일/모듈

**신규**:
- `domain/kva/KvaAdjustmentRecord.java`, `KvaAdjustmentRecordRepository.java`
- `domain/kva/KvaAdjustmentChangedByRole.java`, `KvaAdjustmentStatus.java`, `PaymentAdjustment.java` (enum)
- `service/kva/KvaPostPaymentService.java` — 결제 후 변경 전담 (ApplicationKvaService와 분리)
- `api/admin/AdminKvaAdjustmentController.java`
- `api/lew/LewKvaAdjustmentController.java`
- `db/migration/V09__create_kva_adjustment_records.sql`
- 프론트: `pages/admin/sections/AdminKvaAdjustmentSection.tsx`, `pages/admin/modals/KvaPostPaymentOverrideModal.tsx`, `pages/admin/modals/KvaSettlementModal.tsx`, `pages/lew/modals/LewKvaAdjustmentRequestModal.tsx`, `api/kvaAdjustmentApi.ts`

**확장**:
- `ApplicationKvaService.java:99` — `isLockedStatus` 가드는 그대로 유지 (`/api/admin/applications/{id}/kva`는 결제 전 전용). 본 스펙의 신규 엔드포인트는 별도 서비스(`KvaPostPaymentService`)에 위치.
- `LewReviewService.java` — 변경 없음. 단, finalizeCof의 가드는 CoF unfinalize 후 재호출 시 그대로 작동해야 하므로 기존 4가드 점검만.
- `Application.java` — kVA confirmKva 도메인 메서드는 그대로. 사후 변경 전용 도메인 메서드 신규 추가:
  ```java
  public void overrideKvaPostPayment(Integer newKva, BigDecimal newQuote, User overrider) {
      if (!isPostPaymentStatus()) throw new IllegalStateException(...);
      this.selectedKva = newKva;
      this.quoteAmount = newQuote;
      this.kvaConfirmedBy = overrider;
      this.kvaConfirmedAt = LocalDateTime.now();
      // kvaStatus는 CONFIRMED 유지, kvaSource는 LEW_VERIFIED 유지 (또는 ADMIN_OVERRIDE 신규?)
  }
  ```
- `AuditAction.java` — 신규 enum 4개 추가
- `NotificationType.java` — 신규 3개 추가
- `lewActionUtils.ts` — `kvaPostPaymentAdjustable` 가드 추가 (LEW가 요청 버튼 보일 조건)
- `LewApplicationDetailPage.tsx` — 사이드바에 "Request kVA adjustment" 버튼 (PAID/IN_PROGRESS 상태에서만)
- `AdminApplicationDetailPage.tsx` 또는 review 페이지 — KVA 카드에 "Override kVA (post-payment)" 버튼 + 이력 카드

### 6.2 기존 패턴과의 일관성

- **AFTER_COMMIT 알림 패턴**: PR4(commit 3e81d0e)의 `ApplicationEventPublisher` + `@TransactionalEventListener(phase=AFTER_COMMIT)` 재사용. 알림 실패가 메인 트랜잭션을 깨뜨리지 않음.
- **OwnershipValidator**: LEW 요청 시 `validateOwnerOrAdminOrAssignedLew` 재사용.
- **REQUIRES_NEW audit log**: `auditLogService.logAsync` 그대로 사용 — 거부 케이스도 metadata 포함 기록.
- **DTO 패턴**: Request/Response 분리. `OverrideKvaPostPaymentRequest`, `OverrideKvaPostPaymentResponse`, `LewKvaAdjustmentRequestRequest`(naming 개선 필요), `MarkKvaSettlementRequest`, `KvaAdjustmentResponse`(이력 카드용).
- **소프트삭제**: `KvaAdjustmentRecord`는 적용 안 함(감사 무결성). 명시적으로 javadoc에 기록.

### 6.3 Application.kvaStatus / kvaSource 처리

기존 `KvaSource` enum: `USER_INPUT | LEW_VERIFIED`. ADMIN의 사후 변경은 어디로 분류?

**옵션**:
- A. `LEW_VERIFIED` 유지 (사후 변경도 LEW 합의 기반 가정)
- B. `KvaSource.ADMIN_OVERRIDE` 신규 추가
- C. kvaSource 변경 안 하고 `KvaAdjustmentRecord.changedByRole`에서만 추적

**(추천)** C. kvaSource는 "최초 확정 시 출처"의 의미를 유지하고, 변경 이력은 별도 테이블에서 본다. 마이그레이션·기존 쿼리 영향 0.

### 6.4 가격 시점 결정 (설정 우선 원칙 vs JIT 정본 보존)

§4.5 AC-P1과 연결. **검토자 결정 필요(§10 D1)** — 본 스펙은 옵션 A(현재가)를 추천하되 옵션 C(결제 시점 스냅샷)를 위한 데이터 모델만 사전 설계해 둔다:

- `KvaAdjustmentRecord.masterPriceSeqUsed`: 사용한 master_prices row seq 기록 → 추후 어떤 가격이 적용됐는지 추적 가능.
- 옵션 C로 향후 전환 시: `Application`에 `paid_at_price_snapshot` JSON 컬럼 추가 마이그레이션 필요. **이번 PR 범위 외**.

---

## 7. 위험 분석

### 7.1 결제 데이터 정합성 리스크

`Application.quoteAmount`는 결제 시점의 정본이며, 본 스펙에서 사후 변경 시 **이 값을 덮어쓴다**. 영향:
- 송장(Invoice) 재생성 시 새 quoteAmount 사용 — **검토자 결정(§10 D3)**: 기존 송장 archive vs 덮어쓰기.
- 이메일·PDF 영수증의 amount는 발송 당시 snapshot이므로 영향 없음.
- 회계 보고(`/api/admin/reports`)에서 PAID 시점 누계와 현재 quoteAmount 누계가 **달라질 수 있음**. 이 경우 KvaAdjustmentRecord 합산을 통해 정합 가능.

**완화**:
- AdminApplicationInfo 카드에 "kVA was adjusted post-payment" 배지 + KvaAdjustmentRecord 이력 링크.
- 회계 보고에 paymentAdjustment 합계 별도 행.

### 7.2 감사 로그 누락 영향

KvaAdjustmentRecord와 AuditLog 둘 다 기록 — **이중 기록**. AuditLog가 `REQUIRES_NEW` 트랜잭션이므로 메인 트랜잭션 롤백 시에도 거부 로그는 남는다. 감사 무결성 강화.

### 7.3 동시성 — ADMIN 둘이 동시에 변경

- **1차 방어**: `Application.@Version` 낙관적 락 → 두 번째 commit 시 `OptimisticLockException` → 409 STALE_STATE.
- **2차 방어**: AC-L5 — LEW 요청도 동일 신청에 PENDING이 둘 이상 있을 수 없음 (UNIQUE constraint? 또는 service 레벨 체크).
- **결정 필요(§10 D4)**: DB UNIQUE constraint vs 서비스 레이어 체크. UNIQUE는 마이그레이션 부담, 서비스는 race condition 가능성. **(추천)** UNIQUE partial index `WHERE status='PENDING_ADMIN_REVIEW'` (PostgreSQL 지원, MySQL 8.0은 가상 컬럼 우회 필요 — 본 프로젝트는 MySQL이므로 service 레이어 + `SELECT ... FOR UPDATE` 권장).

### 7.4 CoF unfinalize 트랜잭션 실패 시 보상 처리

§3.5 시나리오에서 CoF unfinalize와 KvaAdjustmentRecord 생성이 단일 트랜잭션. 실패 케이스:

| 단계 | 실패 원인 | 처리 |
|---|---|---|
| KvaAdjustmentRecord insert | DB 제약 위반 | 전체 롤백, 400 응답 |
| Application.overrideKvaPostPayment | OptimisticLockException | 전체 롤백, 409 STALE_STATE |
| CoF.reopenForReissue | IllegalStateException (이미 unfinalized) | 전체 롤백, 500 (race condition — drone log 후 재시도 안내) |
| AFTER_COMMIT 알림 | 알림 서비스 실패 | swallow (commit은 성공). PR4 패턴과 동일 |

**완화**: ADMIN 모달에서 호출 직전 "Are you sure? CoF will require re-signature." 명시적 confirm dialog.

### 7.5 LEW가 요청한 후 ADMIN이 무시하면?

LEW의 `PENDING_ADMIN_REVIEW` row가 영구히 남는 케이스. 완화:
- ADMIN 대시보드에 미처리 요청 카운터 (NotificationCenter)
- ADMIN이 "Reject" 버튼으로 명시적 거부 가능 (추가 엔드포인트 — P1 범위)
- 또는 ADMIN이 무시하고 직접 변경 시 자동으로 `RESOLVED_BY_ADMIN_OVERRIDE` 마킹 (AC-L4)

---

## 8. PR 분할

### PR-1 (P0, M, 2~3일) — 데이터 모델 + ADMIN 직접 변경

**범위**:
- `KvaAdjustmentRecord` 엔티티 + Repository + 마이그레이션 V09
- enum 3종 (ChangedByRole, Status, PaymentAdjustment)
- `KvaPostPaymentService.overrideKva()` (CoF unfinalize 포함)
- `Application.overrideKvaPostPayment()` 도메인 메서드
- `POST /api/admin/applications/{id}/kva-override-postpayment` 엔드포인트
- AdminKvaAdjustmentController + DTOs
- AuditAction enum 추가 + audit logging
- 단위·통합 테스트 (정상 흐름, 결제 전 거부, 동일 newKva 거부, master_prices 미존재, CoF unfinalize)

**프론트**:
- `KvaPostPaymentOverrideModal.tsx`
- AdminApplicationDetailPage 사이드바에 버튼 추가

**롤백 가능성**: KvaAdjustmentRecord 테이블 drop, 도메인 메서드 제거, 컨트롤러 제거. 기존 신청 데이터에 영향 없음.

### PR-2 (P1, S, 1일) — 알림 + 이메일

**범위**:
- NotificationType enum 추가 (KVA_ADJUSTED_BY_ADMIN_LEW)
- AFTER_COMMIT 이벤트 + 이메일 템플릿
- 인앱 알림 라우팅 (LewApplicationDetailPage → notification deeplink)

**롤백 가능성**: 알림 발송 swallow 처리이므로 비활성화만 해도 메인 흐름 정상.

### PR-3 (P1, M, 2일) — LEW 요청 흐름

**범위**:
- `POST /api/lew/applications/{id}/kva-adjustment-request`
- LewKvaAdjustmentController + DTO
- LEW Request → ADMIN 알림 (KVA_ADJUSTMENT_REQUESTED_ADMIN)
- AC-L4 처리: ADMIN 직접 변경 시 PENDING LEW 요청 row 자동 RESOLVED 마킹 로직
- `LewKvaAdjustmentRequestModal.tsx`
- LewApplicationDetailPage 사이드바에 PAID/IN_PROGRESS 시 "Request kVA adjustment" 버튼

**롤백 가능성**: 엔드포인트만 비활성화하면 기존 흐름은 그대로.

### PR-4 (P2, S, 1일) — 이력 카드 + Settlement 마킹

**범위**:
- `GET /api/admin/applications/{id}/kva-adjustments` 이력 조회
- `PATCH /api/admin/applications/{id}/kva-adjustments/{adjustmentSeq}/settlement`
- `AdminKvaAdjustmentSection.tsx` 이력 카드
- `KvaSettlementModal.tsx`
- KVA_ADJUSTMENT_SETTLED_LEW 알림 (옵션)

**롤백 가능성**: 카드/모달만 hide. 이미 생성된 KvaAdjustmentRecord row의 settlement 필드는 그대로 보존.

### PR 분할 합계

총 4 PR, M+S+M+S = 약 6~7일 작업. 각 PR 독립 머지 가능.

---

## 9. 범위 외 (Out of Scope)

- **자동 차액 청구·환불**: Stripe/PayNow API 호출 자동화. ADMIN이 외부 채널 수기 처리.
- **신청자 결제 차액 추가 결제 UI**: applicant가 추가 결제하는 화면 없음. ADMIN이 외부 송금 안내 후 수기 마킹.
- **회계 자동화**: 회계 시스템 연동·VAT 처리·송장 자동 재발행. 모두 수기.
- **결제 시점 가격 스냅샷 (`paid_at_price_snapshot`)**: §6.4 옵션 C. 현재 PR 범위 외.
- **신청자 알림**: 1차안에서는 신청자 알림 명시 없음. 본 스펙은 ADMIN 변경 시 신청자에게 인앱 알림만 추가(이메일은 P2 검토자 결정).
- **kVA tier 점프 가드** (예: 50→500 같은 비현실적 변경 차단): 모든 변경 허용. 검증은 ADMIN 운영 절차.
- **자동 라이선스 정정 (PDF 재발행)**: COMPLETED 상태에서 변경 시 라이선스 PDF는 별도 운영 절차로 재발급. 본 PR은 데이터 갱신만.

---

## 10. 검토자 결정 결과 (2026-05-01 확정)

| ID | 결정 | 선택 | 적용 방향 |
|---|---|---|---|
| D1 | 가격 시점 정책 | **A** | newQuoteAmount = 변경 시점 master_prices 현재가. `masterPriceSeqUsed` 컬럼에 사용된 master_price row id 기록(감사용). |
| D2 | 영수증 파일 첨부 | **B** | 본 스펙 PR-1~4에서는 미포함. 향후 별도 스펙으로 PDPA·법적 보관 요건 검토 후 진행. |
| D3 | 송장 처리 | **B** | quoteAmount 변경 시 기존 Invoice를 INVALIDATED로 마킹 + 신규 Invoice 자동 발행 + `AuditAction.INVOICE_REGENERATED` 기록. |
| D4 | LEW 중복 PENDING 요청 차단 | **B** | 서비스 레이어 `SELECT ... FOR UPDATE` 체크 (`KvaPostPaymentService.requestAdjustmentByLew`). DB UNIQUE INDEX 미사용. |
| D5 | COMPLETED/EXPIRED 변경 허용 | **A** | COMPLETED 허용(사후 회계 보정 가능), EXPIRED 거부(결제 자체가 무효). |

### 결정에 따른 스펙 영향 정리

- **D1**: §6.4(가격 시점) 옵션 A로 확정. 마이그레이션 V09에 `master_price_seq_used BIGINT` 컬럼 포함.
- **D2**: §5.1의 `receiptFileSeq` 필드는 본 스펙에서 **삭제**. PR-4의 사후 정산 마킹은 `settledAmount`+`receiptReferenceNumber`(외부 영수증 번호 텍스트)+`settlementMemo`만 기록. 파일 업로드는 향후 스펙.
- **D3**: §7.1 송장 처리 위험을 PR-1 트랜잭션에 포함. `InvoiceService.invalidateAndRegenerate(applicationSeq, newAmount)` 신규 메서드. 기존 Invoice는 `status=INVALIDATED`+`invalidatedReason="KVA_ADJUSTMENT_<adjustmentSeq>"` 마킹.
- **D4**: §7.3 AC-L5 — `kvaPostPaymentRepository.findByApplicationSeqAndStatusForUpdate(seq, PENDING_ADMIN_REVIEW)`로 비관적 락. 존재 시 409 `KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING`. 마이그레이션에 UNIQUE INDEX 추가 안 함.
- **D5**: §4.1 AC-A3 — `kva-override-postpayment` 허용 상태 = `PAID / IN_PROGRESS / COMPLETED`. EXPIRED는 409 `KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED` 반환. PRE-PAYMENT(PENDING_REVIEW/REVISION_REQUESTED/PENDING_PAYMENT)는 기존 `/api/admin/applications/{id}/kva` 엔드포인트로 처리.

---

## 11. CLAUDE.md 설계 원칙 준수 체크리스트

- [x] **설정 우선 원칙**: kVA tier·가격은 모두 `master_prices` 테이블에서 로드 (`KvaPostPaymentService`가 직접 `MasterPriceRepository.findByKva` 호출). 하드코딩 가격 없음.
- [x] **JIT 정보 수집**: 신청자에게 kVA 재확인 묻지 않음. ADMIN 변경 시 신청자 입력 0건. LEW 요청 시 본인 reason만 입력.
- [x] **한국어 커밋 메시지**: 각 PR은 `feat(kva): ...`, `feat(admin): ...` 형식 한국어 본문.
- [x] **소프트삭제 패턴**: KvaAdjustmentRecord는 명시적 예외 (감사 무결성). Javadoc에 사유 기록.
- [x] **DTO 분리**: Request/Response 분리, 각 엔드포인트별 DTO.
- [x] **Audit BaseEntity**: `KvaAdjustmentRecord`는 `BaseEntity` 상속(createdAt/updatedAt/createdBy/updatedBy).
- [x] **API 레이어**: `/api/admin/**` (ADMIN 전용), `/api/lew/**` (LEW 전용). 신청자 직접 호출 경로 없음.

---

## 12. 관련 기존 파일 (스펙 확장 지점)

| 파일 | 라인 | 확장 방식 |
|---|---|---|
| `ApplicationKvaService.java` | :99-105 | 기존 가드 그대로 유지 (결제 전 전용). 본 스펙은 별도 서비스 신설 |
| `LewReviewService.java` | :226-278 | 변경 없음. CoF finalize 가드 4종은 unfinalize 후 재호출에도 그대로 유효 |
| `Application.java` | :686-697 | `confirmKva` 그대로. `overrideKvaPostPayment` 도메인 메서드 신규 추가 |
| `Application.java` | :571-577 | `reopenForCofReissue` — 본 스펙은 status 전이 없음. 신규 메서드 `markKvaPostPaymentOverride` 추가 검토 |
| `CertificateOfFitness.java` | :285-295 | `reopenForReissue` 기존 그대로 재사용 |
| `lewActionUtils.ts` | :48-117 | `kind: 'requestKvaAdjustment'` 케이스 추가 검토 (또는 사이드바 보조 액션으로 분리) |
| `LewApplicationDetailPage.tsx` | — | 사이드바에 PAID/IN_PROGRESS 시 "Request kVA adjustment" 버튼 노출 |
| `AdminApplicationDetailPage.tsx` | — | KVA 카드에 "Override (post-payment)" 버튼 + 이력 카드 노출 |
| `AuditAction.java` | :73-78 | KVA_OVERRIDE_POSTPAYMENT, KVA_SETTLEMENT_MARKED, KVA_ADJUSTMENT_REQUESTED_BY_LEW, KVA_ADJUSTMENT_REJECTED_BY_ADMIN 추가 |

---

## 13. 마이그레이션 영향

### 13.1 기존 PAID/IN_PROGRESS 신청의 데이터 보정

**불필요**. KvaAdjustmentRecord가 비어있는 상태 = 변경 없음으로 자연스럽게 동작:
- `GET /api/admin/applications/{id}/kva-adjustments` → 빈 배열 반환 → 이력 카드에 "No adjustments recorded" 표시
- `Application.quoteAmount` 그대로 결제 당시 정본
- 사후 변경 시점부터 본 시스템에 의한 ledger 누적 시작

### 13.2 데이터 마이그레이션 SQL

```sql
-- V09__create_kva_adjustment_records.sql
CREATE TABLE kva_adjustment_records (
    adjustment_seq BIGINT AUTO_INCREMENT PRIMARY KEY,
    application_seq BIGINT NOT NULL,
    changed_by_role VARCHAR(20) NOT NULL,
    changed_by_user_seq BIGINT NULL,
    lew_request_seq BIGINT NULL,
    status VARCHAR(30) NOT NULL,
    previous_kva INT NOT NULL,
    new_kva INT NULL,
    proposed_kva INT NULL,
    reason VARCHAR(1000) NOT NULL,
    previous_quote_amount DECIMAL(10, 2) NULL,
    new_quote_amount DECIMAL(10, 2) NULL,
    amount_difference DECIMAL(10, 2) NULL,
    master_price_seq_used BIGINT NULL,
    admin_memo VARCHAR(2000) NULL,
    payment_adjustment VARCHAR(20) NULL,
    settled_amount DECIMAL(10, 2) NULL,
    receipt_reference_number VARCHAR(100) NULL,
    settlement_memo VARCHAR(1000) NULL,
    admin_action_at DATETIME NULL,
    settled_at DATETIME NULL,
    cof_reissue_triggered BOOLEAN NOT NULL DEFAULT FALSE,
    receipt_file_seq BIGINT NULL,
    -- BaseEntity audit
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    created_by VARCHAR(50) NULL,
    updated_by VARCHAR(50) NULL,
    deleted_at DATETIME NULL,
    -- FK
    CONSTRAINT fk_kva_adj_application FOREIGN KEY (application_seq) REFERENCES applications(application_seq),
    CONSTRAINT fk_kva_adj_user FOREIGN KEY (changed_by_user_seq) REFERENCES users(user_seq),
    CONSTRAINT fk_kva_adj_lew_request FOREIGN KEY (lew_request_seq) REFERENCES kva_adjustment_records(adjustment_seq),
    CONSTRAINT fk_kva_adj_master_price FOREIGN KEY (master_price_seq_used) REFERENCES master_prices(master_price_seq),
    CONSTRAINT fk_kva_adj_receipt_file FOREIGN KEY (receipt_file_seq) REFERENCES files(file_seq),
    -- 인덱스
    INDEX idx_kva_adj_application (application_seq),
    INDEX idx_kva_adj_status (status),
    INDEX idx_kva_adj_created_at (created_at)
);
```

### 13.3 enum 추가 시 호환성

`AuditAction` / `NotificationType`은 enum 확장만, 기존 row 영향 없음. Java enum과 DB ENUM/VARCHAR 모두 호환.

---

## 14. 후속 모니터링

머지 후 1주일 모니터링 항목:
- `KvaAdjustmentRecord` 생성 빈도 (목표: 주당 0~2건. 5건 이상이면 신청 단계 kVA 추정 정확도 점검)
- `cofReissueTriggered=true` 비율 (CoF 재서명이 LEW 작업 가중. 30% 이상이면 LEW 사전 협의 동선 강화 검토)
- `paymentAdjustment=PENDING` 24시간 이상 지속 row (정산 마킹 누락 알림 시스템 추가 검토)
- AuditLog `KVA_OVERRIDE_POSTPAYMENT` 실패 비율 (시스템 장애 지표)
