package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.kva.KvaAdjustmentRecord;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 결제 후 kVA 사후 변경 응답 DTO.
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.1 AC-A1.</p>
 */
@Getter
@Builder
public class KvaPostPaymentOverrideResponse {

    /** 생성된 KvaAdjustmentRecord PK. 이력 카드/audit 추적에 사용. */
    private Long adjustmentSeq;

    private Integer previousKva;
    private Integer newKva;
    private BigDecimal previousQuoteAmount;
    private BigDecimal newQuoteAmount;
    private BigDecimal amountDifference;

    /** CoF 가 본 변경에 의해 unfinalize 되었는지. */
    private Boolean cofReissueTriggered;

    public static KvaPostPaymentOverrideResponse from(KvaAdjustmentRecord record) {
        return KvaPostPaymentOverrideResponse.builder()
                .adjustmentSeq(record.getAdjustmentSeq())
                .previousKva(record.getPreviousKva())
                .newKva(record.getNewKva())
                .previousQuoteAmount(record.getPreviousQuoteAmount())
                .newQuoteAmount(record.getNewQuoteAmount())
                .amountDifference(record.getAmountDifference())
                .cofReissueTriggered(record.getCofReissueTriggered())
                .build();
    }
}
