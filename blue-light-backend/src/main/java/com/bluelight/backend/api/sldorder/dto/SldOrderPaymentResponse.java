package com.bluelight.backend.api.sldorder.dto;

import com.bluelight.backend.domain.sldorder.SldOrderPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SLD 주문 결제 정보 응답 DTO
 */
@Getter
@Builder
public class SldOrderPaymentResponse {

    private Long sldOrderPaymentSeq;
    private Long sldOrderSeq;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paidAt;
    private String transactionId;

    public static SldOrderPaymentResponse from(SldOrderPayment payment) {
        return SldOrderPaymentResponse.builder()
                .sldOrderPaymentSeq(payment.getSldOrderPaymentSeq())
                .sldOrderSeq(payment.getSldOrder().getSldOrderSeq())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .transactionId(payment.getTransactionId())
                .build();
    }
}
