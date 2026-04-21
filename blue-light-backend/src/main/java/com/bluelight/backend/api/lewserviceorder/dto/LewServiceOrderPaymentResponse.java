package com.bluelight.backend.api.lewserviceorder.dto;

import com.bluelight.backend.domain.lewserviceorder.LewServiceOrderPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Request for LEW Service 주문 결제 정보 응답 DTO
 */
@Getter
@Builder
public class LewServiceOrderPaymentResponse {

    private Long lewServiceOrderPaymentSeq;
    private Long lewServiceOrderSeq;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paidAt;
    private String transactionId;

    public static LewServiceOrderPaymentResponse from(LewServiceOrderPayment payment) {
        return LewServiceOrderPaymentResponse.builder()
                .lewServiceOrderPaymentSeq(payment.getLewServiceOrderPaymentSeq())
                .lewServiceOrderSeq(payment.getLewServiceOrder().getLewServiceOrderSeq())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .transactionId(payment.getTransactionId())
                .build();
    }
}
