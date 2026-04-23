package com.bluelight.backend.api.expiredlicenseorder.dto;

import com.bluelight.backend.domain.expiredlicenseorder.ExpiredLicenseOrderPayment;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
public class ExpiredLicenseOrderPaymentResponse {

    private Long expiredLicenseOrderPaymentSeq;
    private Long expiredLicenseOrderSeq;
    private BigDecimal amount;
    private String paymentMethod;
    private String status;
    private LocalDateTime paidAt;
    private String transactionId;

    public static ExpiredLicenseOrderPaymentResponse from(ExpiredLicenseOrderPayment payment) {
        return ExpiredLicenseOrderPaymentResponse.builder()
                .expiredLicenseOrderPaymentSeq(payment.getExpiredLicenseOrderPaymentSeq())
                .expiredLicenseOrderSeq(payment.getExpiredLicenseOrder().getExpiredLicenseOrderSeq())
                .amount(payment.getAmount())
                .paymentMethod(payment.getPaymentMethod())
                .status(payment.getStatus())
                .paidAt(payment.getPaidAt())
                .transactionId(payment.getTransactionId())
                .build();
    }
}
