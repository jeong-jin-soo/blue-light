package com.bluelight.backend.api.sldorder.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * SLD Manager 견적 제안 요청 DTO
 * - sldFee: SLD 도면 작성 비용
 * - endorsementFee: LEW 인증 도장 비용 (endorsementRequested=false면 무시되어 0 저장)
 * - 총 quoteAmount = sldFee + endorsementFee (서버에서 계산)
 */
@Getter
@NoArgsConstructor
public class ProposeQuoteRequest {

    @NotNull(message = "SLD fee is required")
    @DecimalMin(value = "0.00", message = "SLD fee must be non-negative")
    private BigDecimal sldFee;

    @NotNull(message = "Endorsement fee is required")
    @DecimalMin(value = "0.00", message = "Endorsement fee must be non-negative")
    private BigDecimal endorsementFee;

    @Size(max = 2000, message = "Quote note must be 2000 characters or less")
    private String quoteNote;
}
