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

    /** CoF unfinalize 가 본 변경에 의해 트리거되었는지 (이력 카드 배지용). */
    @Column(name = "cof_reissue_triggered", nullable = false)
    private Boolean cofReissueTriggered = false;

    @Builder
    public KvaAdjustmentRecord(Application application,
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
                               Boolean cofReissueTriggered) {
        this.application = application;
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
        this.cofReissueTriggered = cofReissueTriggered != null ? cofReissueTriggered : false;
    }

    /**
     * CoF unfinalize 가 본 변경에 의해 트리거되었음을 마킹.
     * {@code KvaPostPaymentService} 가 단일 트랜잭션 내에서 호출.
     */
    public void markCofReissueTriggered() {
        this.cofReissueTriggered = true;
    }
}
