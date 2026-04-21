package com.bluelight.backend.api.powersocketorder.dto;

import com.bluelight.backend.domain.powersocketorder.PowerSocketOrderPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Power Socket 주문 결제 정보 응답 DTO
 */
@Getter
@Builder
public class PowerSocketOrderPaymentResponse {

    private Long powerSocketOrderPaymentSeq;
    private Long powerSocketOrderSeq;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paidAt;
    private String transactionId;

    public static PowerSocketOrderPaymentResponse from(PowerSocketOrderPayment payment) {
        return PowerSocketOrderPaymentResponse.builder()
                .powerSocketOrderPaymentSeq(payment.getPowerSocketOrderPaymentSeq())
                .powerSocketOrderSeq(payment.getPowerSocketOrder().getPowerSocketOrderSeq())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .transactionId(payment.getTransactionId())
                .build();
    }
}
