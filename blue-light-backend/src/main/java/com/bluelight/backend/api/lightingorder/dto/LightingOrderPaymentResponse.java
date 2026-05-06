package com.bluelight.backend.api.lightingorder.dto;

import com.bluelight.backend.domain.lightingorder.LightingOrderPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lighting Layout 주문 결제 정보 응답 DTO
 */
@Getter
@Builder
public class LightingOrderPaymentResponse {

    private Long lightingOrderPaymentSeq;
    private Long lightingOrderSeq;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paidAt;
    private String transactionId;

    public static LightingOrderPaymentResponse from(LightingOrderPayment payment) {
        return LightingOrderPaymentResponse.builder()
                .lightingOrderPaymentSeq(payment.getLightingOrderPaymentSeq())
                .lightingOrderSeq(payment.getLightingOrder().getLightingOrderSeq())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .transactionId(payment.getTransactionId())
                .build();
    }
}
