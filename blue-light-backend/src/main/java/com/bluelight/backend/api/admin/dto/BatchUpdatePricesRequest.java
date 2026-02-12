package com.bluelight.backend.api.admin.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 가격 티어 일괄 수정 요청 DTO
 * - 전체 가격 티어 세트를 한번에 저장
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BatchUpdatePricesRequest {

    @NotEmpty(message = "At least one price tier is required")
    @Valid
    private List<BatchPriceTierItem> tiers;
}
