package com.bluelight.backend.api.price.dto;

import com.bluelight.backend.domain.price.MasterPrice;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * Price tier response DTO
 */
@Getter
@Builder
public class PriceResponse {

    private Long masterPriceSeq;
    private String description;
    private Integer kvaMin;
    private Integer kvaMax;
    private BigDecimal price;
    private BigDecimal renewalPrice;
    /**
     * 공개 API는 is_active=true만 반환하지만, FE가 `tiers.filter(t => t.isActive)`로
     * 재필터링하는 기존 관례를 유지할 수 있도록 명시적으로 true를 포함한다.
     * 필드 누락 시 filter에서 전부 제외되어 kVA 드롭다운이 비게 되는 회귀 방지.
     */
    private Boolean isActive;

    public static PriceResponse from(MasterPrice masterPrice) {
        return PriceResponse.builder()
                .masterPriceSeq(masterPrice.getMasterPriceSeq())
                .description(masterPrice.getDescription())
                .kvaMin(masterPrice.getKvaMin())
                .kvaMax(masterPrice.getKvaMax())
                .price(masterPrice.getPrice())
                .renewalPrice(masterPrice.getRenewalPrice())
                .isActive(Boolean.TRUE.equals(masterPrice.getIsActive()))
                .build();
    }
}
