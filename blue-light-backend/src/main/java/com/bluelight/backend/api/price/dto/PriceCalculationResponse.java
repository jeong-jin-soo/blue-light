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
}
