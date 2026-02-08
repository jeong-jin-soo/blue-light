package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Payment response DTO
 */
@Getter
@Builder
public class PaymentResponse {

    private Long paymentSeq;
    private Long applicationSeq;
    private String transactionId;
    private BigDecimal amount;
    private String paymentMethod;
    private PaymentStatus status;
    private LocalDateTime paidAt;

    public static PaymentResponse from(Payment payment) {
        return PaymentResponse.builder()
                .paymentSeq(payment.getPaymentSeq())
                .applicationSeq(payment.getApplication().getApplicationSeq())
                .transactionId(payment.getTransactionId())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .build();
    }
}
