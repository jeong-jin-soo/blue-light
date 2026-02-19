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

    // SLD 작성 비용 (REQUEST_LEW 시에만 포함)
    private BigDecimal sldFee;
    private BigDecimal totalAmount;

    // EMA 수수료 (총액에 포함)
    private BigDecimal emaFee;
}
