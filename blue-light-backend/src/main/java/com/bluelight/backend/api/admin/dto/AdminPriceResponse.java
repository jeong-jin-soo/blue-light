package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.price.MasterPrice;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Admin 가격 티어 응답 DTO
 * - isActive, updatedAt 포함
 */
@Getter
@Builder
public class AdminPriceResponse {
    private Long masterPriceSeq;
    private String description;
    private Integer kvaMin;
    private Integer kvaMax;
    private BigDecimal price;
    private BigDecimal sldPrice;
    private Boolean isActive;
    private LocalDateTime updatedAt;

    public static AdminPriceResponse from(MasterPrice masterPrice) {
        return AdminPriceResponse.builder()
                .masterPriceSeq(masterPrice.getMasterPriceSeq())
                .description(masterPrice.getDescription())
                .kvaMin(masterPrice.getKvaMin())
                .kvaMax(masterPrice.getKvaMax())
                .price(masterPrice.getPrice())
                .sldPrice(masterPrice.getSldPrice())
                .isActive(masterPrice.getIsActive())
                .updatedAt(masterPrice.getUpdatedAt())
                .build();
    }
}
