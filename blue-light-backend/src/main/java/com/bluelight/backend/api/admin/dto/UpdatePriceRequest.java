package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 가격 티어 수정 요청 DTO
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class UpdatePriceRequest {

    private String description;

    @Min(value = 1, message = "kVA min must be at least 1")
    private Integer kvaMin;

    @Min(value = 1, message = "kVA max must be at least 1")
    private Integer kvaMax;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be non-negative")
    private BigDecimal price;

    private Boolean isActive;
}
