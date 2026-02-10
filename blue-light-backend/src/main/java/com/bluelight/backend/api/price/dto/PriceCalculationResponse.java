package com.bluelight.backend.api.price.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Price calculation result DTO
 */
@Getter
@Builder
public class PriceCalculationResponse {

    private Integer kva;
    private String tierDescription;
    private BigDecimal price;

    // ── Phase 18: 서비스 수수료 분리 ──
    private BigDecimal serviceFee;
    private BigDecimal totalAmount;
}
