package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 배치 가격 티어 항목 DTO (생성/수정 겸용)
 * - masterPriceSeq가 null이면 신규 생성
 * - masterPriceSeq가 있으면 기존 티어 수정
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BatchPriceTierItem {

    private Long masterPriceSeq;

    @Size(max = 50, message = "Description must be 50 characters or less")
    private String description;

    @NotNull(message = "kVA min is required")
    @Min(value = 1, message = "kVA min must be at least 1")
    private Integer kvaMin;

    @NotNull(message = "kVA max is required")
    @Min(value = 1, message = "kVA max must be at least 1")
    private Integer kvaMax;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be non-negative")
    private BigDecimal price;

    @NotNull(message = "SLD price is required")
    @DecimalMin(value = "0.00", message = "SLD price must be non-negative")
    private BigDecimal sldPrice;

    @NotNull(message = "Active status is required")
    private Boolean isActive;
}
