package com.bluelight.backend.api.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Payment confirmation request DTO (admin confirms offline payment)
 */
@Getter
@NoArgsConstructor
public class PaymentConfirmRequest {

    private String transactionId;
    private String paymentMethod;
}
