package com.bluelight.backend.api.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Create application request DTO
 */
@Getter
@NoArgsConstructor
public class CreateApplicationRequest {

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

    // ── Phase 18: 갱신 관련 필드 ──

    /**
     * 신청 유형: "NEW" (기본) / "RENEWAL" / "SUPPLY_INSTALLATION"
     */
    private String applicationType;

    /**
     * 원본 신청 ID (기존 완료 신청 선택 시)
     */
    private Long originalApplicationSeq;

    /**
     * 기존 면허 번호 (직접 입력 시)
     */
    @Size(max = 50, message = "Licence number must be 50 characters or less")
    private String existingLicenceNo;

    /**
     * 기존 면허 만료일 (직접 입력, "yyyy-MM-dd")
     */
    private String existingExpiryDate;

    /**
     * 갱신 기간 (3 or 12 개월, 갱신 시 필수)
     */
    private Integer renewalPeriodMonths;

    /**
     * 갱신 참조 번호
     */
    @Size(max = 50, message = "Renewal reference number must be 50 characters or less")
    private String renewalReferenceNo;

    /**
     * SLD 제출 방식: "SELF_UPLOAD" (기본) / "REQUEST_LEW"
     */
    private String sldOption;
}
