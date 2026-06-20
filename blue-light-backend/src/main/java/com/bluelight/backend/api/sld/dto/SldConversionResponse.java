package com.bluelight.backend.api.sld.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * SLD self-upload → LEW 작성 전환 결과.
 */
@Getter
@Builder
public class SldConversionResponse {
    private final Long applicationSeq;
    /** 가산된 SLD 작성비 (SGD). */
    private final BigDecimal sldFee;
    /** 가산 후 견적. */
    private final BigDecimal newQuoteAmount;
    /** true 면 결제 후 전환 → 보충 청구(정산 원장 PENDING) 발생. */
    private final boolean postPayment;
    /** 결제 후 전환 시 생성된 조정 원장 id (pre-payment 면 null). */
    private final Long adjustmentSeq;
}
