package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * LewService Manager 견적 제안 요청 DTO
 */
@Getter
@NoArgsConstructor
public class ProposeQuoteRequest {

    @NotNull(message = "Quote amount is required")
    @DecimalMin(value = "0.01", message = "Quote amount must be greater than 0")
    private BigDecimal quoteAmount;

    @Size(max = 2000, message = "Quote note must be 2000 characters or less")
    private String quoteNote;
}
