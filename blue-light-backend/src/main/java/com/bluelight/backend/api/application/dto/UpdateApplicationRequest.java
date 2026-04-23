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

    // ── EMA ELISE 5-part 주소 (Installation + Correspondence) ──
    // 재제출 시에도 applicant 가 5-part 구조를 갱신할 수 있어야 한다. 모두 optional —
    // null 값은 "해당 서브필드를 지움" 의미 (서비스에서 일괄 덮어쓰기).
    // legacy `address`/`postalCode` 는 클라이언트가 5-part 를 concat 해 전달하므로
    // 둘 중 하나만 편집해도 일관성 유지된다.

    @Size(max = 20, message = "Installation block must be 20 characters or less")
    private String installationAddressBlock;

    @Size(max = 20, message = "Installation unit must be 20 characters or less")
    private String installationAddressUnit;

    @Size(max = 200, message = "Installation street must be 200 characters or less")
    private String installationAddressStreet;

    @Size(max = 200, message = "Installation building must be 200 characters or less")
    private String installationAddressBuilding;

    @Size(max = 10, message = "Installation postal code must be 10 characters or less")
    private String installationAddressPostalCode;

    @Size(max = 20, message = "Correspondence block must be 20 characters or less")
    private String correspondenceAddressBlock;

    @Size(max = 20, message = "Correspondence unit must be 20 characters or less")
    private String correspondenceAddressUnit;

    @Size(max = 200, message = "Correspondence street must be 200 characters or less")
    private String correspondenceAddressStreet;

    @Size(max = 200, message = "Correspondence building must be 200 characters or less")
    private String correspondenceAddressBuilding;

    @Size(max = 10, message = "Correspondence postal code must be 10 characters or less")
    private String correspondenceAddressPostalCode;
}
