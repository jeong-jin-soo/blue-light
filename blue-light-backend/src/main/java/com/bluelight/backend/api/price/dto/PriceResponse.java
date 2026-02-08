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

    public static PriceResponse from(MasterPrice masterPrice) {
        return PriceResponse.builder()
                .masterPriceSeq(masterPrice.getMasterPriceSeq())
                .description(masterPrice.getDescription())
                .kvaMin(masterPrice.getKvaMin())
                .kvaMax(masterPrice.getKvaMax())
                .price(masterPrice.getPrice())
                .build();
    }
}
