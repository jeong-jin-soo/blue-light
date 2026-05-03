package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import com.bluelight.backend.domain.kva.ChangedByRole;
import com.bluelight.backend.domain.kva.KvaAdjustmentRecord;
import com.bluelight.backend.domain.kva.KvaAdjustmentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * PR-4: 결제 후 kVA 사후 변경 이력 카드/Settlement 응답 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §8 PR-4.</p>
 *
 * <p>{@link KvaAdjustmentRecord} 의 모든 가시 필드를 그대로 노출하되, {@code application},
 * {@code masterPriceSeqUsed} 등 내부 트래킹 필드는 별도 audit log/메타데이터에서 조회한다.
 * 프론트는 {@code lewRequestSeq} 로 LEW 요청 row 와 ADMIN 변경 row 를 시각적으로 묶는다.</p>
 *
 * <h3>changedByUserName</h3>
 * 백엔드 서비스가 {@code changedByUserSeq} 로 user 를 lookup 후 {@code firstName + lastName}
 * 또는 {@code email} 로 채워준다 (PDPA 최소화 — 외부에서 식별번호로 user 추적 불가).
 */
@Getter
@Builder
public class KvaAdjustmentHistoryItem {

    private Long adjustmentSeq;

    /** PENDING_ADMIN_REVIEW | APPLIED | RESOLVED_BY_ADMIN_OVERRIDE | REJECTED | CANCELLED. */
    private KvaAdjustmentStatus status;

    /** ADMIN | LEW. */
    private ChangedByRole changedByRole;

    /** 표시용 이름 (firstName + lastName 또는 email). nullable — 사용자 row 가 사라졌을 수 있음. */
    private String changedByUserName;

    private Integer previousKva;
    private Integer newKva;
    private Integer proposedKva;

    private BigDecimal previousQuoteAmount;
    private BigDecimal newQuoteAmount;
    private BigDecimal amountDifference;

    private String reason;
    private String adminMemo;

    /** PENDING | PAID_DIFFERENCE | REFUNDED | WAIVED. nullable (LEW 요청 row 등). */
    private AdminPaymentAdjustment paymentAdjustment;

    private BigDecimal settledAmount;
    private String receiptReferenceNumber;
    private String settlementMemo;
    private LocalDateTime settledAt;

    /** CoF unfinalize 가 본 변경에 의해 트리거되었는지. */
    private Boolean cofReissueTriggered;

    /** ADMIN 변경 row 가 어떤 LEW 요청 row 에 응답한 것인지 (self-FK). nullable. */
    private Long lewRequestSeq;

    /** row 작성 시각 (BaseEntity.createdAt). */
    private LocalDateTime createdAt;

    /** ADMIN 액션 시각 (직접 변경 시 row 생성 시각과 같음, settlement 마킹 시 그 시각). */
    private LocalDateTime adminAdjustmentAt;

    /**
     * Entity → DTO 변환. {@code changedByUserName} 은 lookup 결과를 별도 인자로 받는다.
     * 사용자 row 가 삭제되었거나 lookup 실패 시 {@code null} 을 그대로 통과.
     */
    public static KvaAdjustmentHistoryItem from(KvaAdjustmentRecord r, String changedByUserName) {
        return KvaAdjustmentHistoryItem.builder()
                .adjustmentSeq(r.getAdjustmentSeq())
                .status(r.getStatus())
                .changedByRole(r.getChangedByRole())
                .changedByUserName(changedByUserName)
                .previousKva(r.getPreviousKva())
                .newKva(r.getNewKva())
                .proposedKva(r.getProposedKva())
                .previousQuoteAmount(r.getPreviousQuoteAmount())
                .newQuoteAmount(r.getNewQuoteAmount())
                .amountDifference(r.getAmountDifference())
                .reason(r.getReason())
                .adminMemo(r.getAdminMemo())
                .paymentAdjustment(r.getAdminPaymentAdjustment())
                .settledAmount(r.getSettledAmount())
                .receiptReferenceNumber(r.getReceiptReferenceNumber())
                .settlementMemo(r.getSettlementMemo())
                .settledAt(r.getSettledAt())
                .cofReissueTriggered(r.getCofReissueTriggered())
                .lewRequestSeq(r.getLewRequestSeq())
                .createdAt(r.getCreatedAt())
                .adminAdjustmentAt(r.getAdminAdjustmentAt())
                .build();
    }
}
