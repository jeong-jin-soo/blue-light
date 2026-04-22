package com.bluelight.backend.api.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Update application request DTO (보완 후 재제출)
 */
@Getter
@NoArgsConstructor
public class UpdateApplicationRequest {

    @NotBlank(message = "Address is required")
    @Size(max = 255, message = "Address must be 255 characters or less")
    private String address;

    @NotBlank(message = "Postal code is required")
    @Size(max = 10, message = "Postal code must be 10 characters or less")
    private String postalCode;

    @Size(max = 50, message = "Building type must be 50 characters or less")
    private String buildingType;

    @NotNull(message = "Selected kVA is required")
    @Positive(message = "Selected kVA must be a positive number")
    private Integer selectedKva;

    /**
     * SP Group 계정 번호 (선택)
     */
    @Size(max = 30, message = "SP Account No must be 30 characters or less")
    private String spAccountNo;

    /**
     * 갱신 기간 수정 (Admin/LEW, 3 or 12)
     */
    private Integer renewalPeriodMonths;

    // ── P1.B: LEW Review Form hint 필드 (스펙 §5.3·§5.5) ──
    // 재제출 시에도 신청자는 hint를 수정할 수 있다. 모두 optional + warning-only 검증.
    private String msslHint;
    private Integer supplyVoltageHint;
    private String consumerTypeHint;
    private String retailerHint;
    private Boolean hasGeneratorHint;
    private Integer generatorCapacityHint;
}
