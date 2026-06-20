package com.bluelight.backend.domain.kva;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.common.BaseEntity;
import com.bluelight.backend.domain.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 결제 후 kVA 사후 변경 + 수기 정산 기록 (감사용 ledger).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §5.1.</p>
 *
 * <h2>Soft Delete 미적용 정책 (§5.1, §10 D2)</h2>
 * <ul>
 *   <li>본 엔티티는 감사 무결성 요건이 가장 높은 ledger 다.
 *       {@code BaseEntity} 의 {@code deleted_at} 컬럼은 보존되지만 <b>사용 금지</b>.</li>
 *   <li>{@code @SQLDelete} / {@code @SQLRestriction} 미적용 — 물리적 삭제는 운영 절차로 차단.</li>
 *   <li>오기재 정정 시에도 row 삭제·수정 대신 별도 정정 row(향후 PR-4) 를 추가한다.</li>
 * </ul>
 *
 * <h2>PR-1 범위</h2>
 * <ul>
 *   <li>ADMIN 직접 변경({@code changedByRole=ADMIN}, {@code status=APPLIED}) 만 작성된다.</li>
 *   <li>LEW 요청({@code changedByRole=LEW}, {@code status=PENDING_ADMIN_REVIEW}) 은 PR-3 에서 추가.</li>
 * </ul>
 */
@Entity
@Table(name = "kva_adjustment_record")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KvaAdjustmentRecord extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "adjustment_seq")
    private Long adjustmentSeq;

    /** 대상 신청 (FK). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_seq", nullable = false)
    private Application application;

    /**
     * 조정 유형 (견적 조정 원장 일반화). 기본 {@link AdjustmentType#KVA_CHANGE}.
     * SLD self-upload → LEW 작성 전환 시 {@link AdjustmentType#SLD_ADDED}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 20)
    private AdjustmentType adjustmentType = AdjustmentType.KVA_CHANGE;

    /**
     * LEW 요청 row 와 ADMIN 변경 row 를 연결하는 self-FK (PR-3 에서 사용).
     * PR-1 의 ADMIN 단독 변경은 항상 null.
     */
    @Column(name = "lew_request_seq")
    private Long lewRequestSeq;

    /** 변경 직전 Application.selectedKva. */
    @Column(name = "previous_kva", nullable = false)
    private Integer previousKva;

    /** 변경 후 적용된 kVA. ADMIN 변경 row 는 NOT NULL. (LEW 요청 row 는 null — PR-3) */
    @Column(name = "new_kva")
    private Integer newKva;

    /** LEW 가 제안한 kVA. PR-3 에서 사용. PR-1 ADMIN row 는 항상 null. */
    @Column(name = "proposed_kva")
    private Integer proposedKva;

    /** 변경 사유 (필수). UI 에서 ADMIN 입력 강제. */
    @Column(name = "reason", nullable = false, length = 1000)
    private String reason;

    /** 행 상태. PR-1 의 ADMIN 직접 변경은 항상 {@link KvaAdjustmentStatus#APPLIED}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private KvaAdjustmentStatus status;

    /** 변경 주체 역할. PR-1 은 항상 {@link ChangedByRole#ADMIN}. */
    @Enumerated(EnumType.STRING)
    @Column(name = "changed_by_role", nullable = false, length = 20)
    private ChangedByRole changedByRole;

    /** 변경 주체 user_seq (ADMIN userSeq). FK 미설정 — User soft delete 후에도 감사 row 보존. */
    @Column(name = "changed_by_user_seq")
    private Long changedByUserSeq;

    /** 변경 직전 quoteAmount. */
    @Column(name = "previous_quote_amount", precision = 10, scale = 2)
    private BigDecimal previousQuoteAmount;

    /** 변경 직후 quoteAmount (master_prices 변경 시점 현재가 기반 — D1 옵션 A). */
    @Column(name = "new_quote_amount", precision = 10, scale = 2)
    private BigDecimal newQuoteAmount;

    /** newQuote − previousQuote (signed). 양수=차액 청구 대상, 음수=환불 대상. */
    @Column(name = "amount_difference", precision = 10, scale = 2)
    private BigDecimal amountDifference;

    /** 사용한 master_prices row id (가격 정합성 추적, D1). */
    @Column(name = "master_price_seq_used")
    private Long masterPriceSeqUsed;

    /** ADMIN 운영 메모. 외부 정산 안내문 등. */
    @Column(name = "admin_memo", length = 2000)
    private String adminMemo;

    /** 정산 처리 상태. PR-1 에서 ADMIN 입력 받음. */
    @Enumerated(EnumType.STRING)
    @Column(name = "admin_payment_adjustment", length = 20)
    private AdminPaymentAdjustment adminPaymentAdjustment;

    /**
     * 실제 송금/환불 금액 (양수 절댓값). PR-4 settlement 마킹 시 채움.
     * PR-1 에서는 ADMIN 이 사전 입력 시에만 채워질 수 있다 (선택 입력).
     */
    @Column(name = "settled_amount", precision = 10, scale = 2)
    private BigDecimal settledAmount;

    /** 외부 결제 채널 참조번호 (PayNow ref 등). PR-4 에서 사용. */
    @Column(name = "receipt_reference_number", length = 100)
    private String receiptReferenceNumber;

    /** 정산 마킹 시 별도 메모. PR-4 에서 사용. */
    @Column(name = "settlement_memo", length = 1000)
    private String settlementMemo;

    /** ADMIN 이 직접 변경 또는 settlement 마킹한 시각. */
    @Column(name = "admin_adjustment_at")
    private LocalDateTime adminAdjustmentAt;

    /**
     * PR-4: settlement final 처리 시각.
     * PAID_DIFFERENCE/REFUNDED/WAIVED 로 finalize 마킹할 때 한 번만 기록되며 D6 정책에 의해
     * 동일 row 의 재마킹은 거부된다. PR-3 까지는 컬럼·필드 없이 운영되었으나
     * PR-4 settlement 마킹 엔드포인트({@code PATCH .../settlement}) 도입 시점에 추가.
     */
    @Column(name = "settled_at")
    private LocalDateTime settledAt;

    @Builder
    public KvaAdjustmentRecord(Application application,
                               AdjustmentType adjustmentType,
                               Long lewRequestSeq,
                               Integer previousKva,
                               Integer newKva,
                               Integer proposedKva,
                               String reason,
                               KvaAdjustmentStatus status,
                               ChangedByRole changedByRole,
                               Long changedByUserSeq,
                               BigDecimal previousQuoteAmount,
                               BigDecimal newQuoteAmount,
                               BigDecimal amountDifference,
                               Long masterPriceSeqUsed,
                               String adminMemo,
                               AdminPaymentAdjustment adminPaymentAdjustment,
                               BigDecimal settledAmount,
                               String receiptReferenceNumber,
                               String settlementMemo,
                               LocalDateTime adminAdjustmentAt,
                               LocalDateTime settledAt) {
        this.application = application;
        this.adjustmentType = (adjustmentType != null) ? adjustmentType : AdjustmentType.KVA_CHANGE;
        this.lewRequestSeq = lewRequestSeq;
        this.previousKva = previousKva;
        this.newKva = newKva;
        this.proposedKva = proposedKva;
        this.reason = reason;
        this.status = status;
        this.changedByRole = changedByRole;
        this.changedByUserSeq = changedByUserSeq;
        this.previousQuoteAmount = previousQuoteAmount;
        this.newQuoteAmount = newQuoteAmount;
        this.amountDifference = amountDifference;
        this.masterPriceSeqUsed = masterPriceSeqUsed;
        this.adminMemo = adminMemo;
        this.adminPaymentAdjustment = adminPaymentAdjustment;
        this.settledAmount = settledAmount;
        this.receiptReferenceNumber = receiptReferenceNumber;
        this.settlementMemo = settlementMemo;
        this.adminAdjustmentAt = adminAdjustmentAt;
        this.settledAt = settledAt;
    }

    /**
     * PR-3 / AC-L4: ADMIN 의 직접 변경에 의해 본 LEW 요청 row 가 자동 해소됨.
     *
     * <p>전제: {@code status == PENDING_ADMIN_REVIEW} 이고 {@code changedByRole == LEW} 인 row 만 호출 가능.
     * 그 외 상태에서 호출 시 {@link IllegalStateException}.</p>
     */
    public void markResolvedByAdminOverride() {
        if (this.status != KvaAdjustmentStatus.PENDING_ADMIN_REVIEW) {
            throw new IllegalStateException(
                    "Only PENDING_ADMIN_REVIEW rows can be auto-resolved (current: " + this.status + ")");
        }
        if (this.changedByRole != ChangedByRole.LEW) {
            throw new IllegalStateException(
                    "Only LEW request rows can be auto-resolved (current role: " + this.changedByRole + ")");
        }
        this.status = KvaAdjustmentStatus.RESOLVED_BY_ADMIN_OVERRIDE;
    }

    /**
     * PR-3 / AC-L4: ADMIN 의 새 변경 row 를 작성한 직후, 해소된 LEW 요청 row 를 가리키도록 self-FK 를 설정.
     * builder 시 채울 수도 있으나, ADMIN row 저장 → PENDING 락/마킹 → 연결 순서로 단일 트랜잭션 내 명시 단계가 필요해서 별도 setter 도메인 메서드.
     */
    public void linkLewRequest(Long lewRequestSeq) {
        this.lewRequestSeq = lewRequestSeq;
    }

    /**
     * PR-4: settlement 마킹.
     *
     * <p>D6 (거부) 정책: 이미 finalize 된 row 는 다시 finalize 할 수 없다. 이미
     * {@link AdminPaymentAdjustment#PAID_DIFFERENCE}/{@link AdminPaymentAdjustment#REFUNDED}/
     * {@link AdminPaymentAdjustment#WAIVED} 중 하나라면 {@link IllegalStateException} 을
     * 던지고, 호출 측 서비스가 409 {@code KVA_SETTLEMENT_ALREADY_FINALIZED} 로 변환한다.</p>
     *
     * <p>또한 본 도메인 메서드는 {@link KvaAdjustmentStatus#APPLIED} 또는
     * {@link KvaAdjustmentStatus#RESOLVED_BY_ADMIN_OVERRIDE} 상태의 row 에서만 호출 가능하다.
     * (PR-3 의 PENDING/REJECTED/CANCELLED LEW 요청 row 는 settlement 가 무관하므로 호출 자체가 차단됨.)</p>
     *
     * @param newStatus    마킹할 정산 상태 (PAID_DIFFERENCE / REFUNDED / WAIVED)
     * @param settledAmount  실제 송금/환불 금액 (양수 절댓값, nullable)
     * @param receiptReferenceNumber  외부 채널 참조번호 (PayNow ref 등, nullable)
     * @param settlementMemo  정산 마킹 메모 (nullable)
     * @param now            settled_at 기록 시각 (테스트 가능성 위해 인자로 주입)
     */
    public void markSettlement(AdminPaymentAdjustment newStatus,
                               java.math.BigDecimal settledAmount,
                               String receiptReferenceNumber,
                               String settlementMemo,
                               LocalDateTime now) {
        if (this.status != KvaAdjustmentStatus.APPLIED
                && this.status != KvaAdjustmentStatus.RESOLVED_BY_ADMIN_OVERRIDE) {
            throw new IllegalStateException(
                    "Settlement is only applicable to APPLIED or RESOLVED_BY_ADMIN_OVERRIDE rows "
                            + "(current: " + this.status + ")");
        }
        if (newStatus == null
                || newStatus == AdminPaymentAdjustment.PENDING) {
            // PENDING 으로 되돌리는 것은 finalize 가 아니므로 D6 와 무관하지만, 본 도메인
            // 메서드는 finalize 전용이다. PENDING 재설정은 별도 경로(서비스에서 거부)로 차단.
            throw new IllegalArgumentException(
                    "markSettlement requires a finalize value (PAID_DIFFERENCE / REFUNDED / WAIVED)");
        }
        AdminPaymentAdjustment current = this.adminPaymentAdjustment;
        if (current == AdminPaymentAdjustment.PAID_DIFFERENCE
                || current == AdminPaymentAdjustment.REFUNDED
                || current == AdminPaymentAdjustment.WAIVED) {
            // D6: 이미 finalize 된 row 는 다시 마킹할 수 없다.
            throw new IllegalStateException(
                    "Settlement is already finalized as " + current
                            + " — create a new adjustment record to correct (D6)");
        }
        this.adminPaymentAdjustment = newStatus;
        this.settledAmount = settledAmount;
        this.receiptReferenceNumber = receiptReferenceNumber;
        this.settlementMemo = settlementMemo;
        this.settledAt = (now != null) ? now : LocalDateTime.now();
    }
}
