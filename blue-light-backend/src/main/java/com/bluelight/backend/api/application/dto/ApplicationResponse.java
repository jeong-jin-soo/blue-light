package com.bluelight.backend.api.application.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Application response DTO
 */
@Getter
@Builder
public class ApplicationResponse {

    private Long applicationSeq;
    private String address;
    private String postalCode;
    private String buildingType;
    private Integer selectedKva;
    private BigDecimal quoteAmount;
    private ApplicationStatus status;
    private String licenseNumber;
    private LocalDate licenseExpiryDate;
    private String reviewComment;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── Phase 19: Assigned LEW info (신청자용 — 이름+면허번호만 노출) ──
    private String assignedLewFirstName;
    private String assignedLewLastName;
    private String assignedLewLicenceNo;

    // SP Group 계정 번호
    private String spAccountNo;

    // Phase 1: 신청자 유형 (INDIVIDUAL | CORPORATE)
    private String applicantType;

    // ── 갱신 + 견적 필드 ──
    private String applicationType;
    private BigDecimal sldFee;
    private Long originalApplicationSeq;
    private String existingLicenceNo;
    private String renewalReferenceNo;
    private LocalDate existingExpiryDate;
    private Integer renewalPeriodMonths;
    private BigDecimal emaFee;

    // SLD 제출 방식
    private String sldOption;

    // LOA 서명 정보
    private String loaSignatureUrl;
    private LocalDateTime loaSignedAt;

    // ── Phase 5: kVA 확정 상태 ──
    private String kvaStatus;           // UNKNOWN | CONFIRMED
    private String kvaSource;           // USER_INPUT | LEW_VERIFIED | null
    private LocalDateTime kvaConfirmedAt;

    // ── P1.2: EMA ELISE 필드 ──
    private String installationName;
    private String premisesType;
    private Boolean isRentalPremises;
    /** Landlord EI Licence 는 마스킹된 표시값만 노출 (앞 5자 *) — 원본은 LEW 전용 응답에서만 제공 예정. */
    private String landlordEiLicenceMasked;
    private Boolean renewalCompanyNameChanged;
    private Boolean renewalAddressChanged;
    private String installationAddressBlock;
    private String installationAddressUnit;
    private String installationAddressStreet;
    private String installationAddressBuilding;
    private String installationAddressPostalCode;
    private String correspondenceAddressBlock;
    private String correspondenceAddressUnit;
    private String correspondenceAddressStreet;
    private String correspondenceAddressBuilding;
    private String correspondenceAddressPostalCode;

    /** Landlord EI Licence 를 본인 입력값 확인 용도로 앞 5자만 마스킹. null/blank 이면 null 반환. */
    private static String maskLandlord(String value) {
        if (value == null || value.isBlank()) return null;
        int n = value.length();
        if (n <= 4) return "*".repeat(n);
        return "*".repeat(Math.max(0, n - 4)) + value.substring(n - 4);
    }

    public static ApplicationResponse from(Application application) {
        return ApplicationResponse.builder()
                .applicationSeq(application.getApplicationSeq())
                .address(application.getAddress())
                .postalCode(application.getPostalCode())
                .buildingType(application.getBuildingType())
                .selectedKva(application.getSelectedKva())
                .quoteAmount(application.getQuoteAmount())
                .status(application.getStatus())
                .licenseNumber(application.getLicenseNumber())
                .licenseExpiryDate(application.getLicenseExpiryDate())
                .reviewComment(application.getReviewComment())
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                // Phase 19: Assigned LEW info
                .assignedLewFirstName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getFirstName() : null)
                .assignedLewLastName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLastName() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
                // SP Account
                .spAccountNo(application.getSpAccountNo())
                // Phase 1: applicantType
                .applicantType(application.getApplicantType() != null
                        ? application.getApplicantType().name() : null)
                // Phase 18 fields
                .applicationType(application.getApplicationType().name())
                .sldFee(application.getSldFee())
                .originalApplicationSeq(application.getOriginalApplication() != null
                        ? application.getOriginalApplication().getApplicationSeq() : null)
                .existingLicenceNo(application.getExistingLicenceNo())
                .renewalReferenceNo(application.getRenewalReferenceNo())
                .existingExpiryDate(application.getExistingExpiryDate())
                .renewalPeriodMonths(application.getRenewalPeriodMonths())
                .emaFee(application.getEmaFee())
                .sldOption(application.getSldOption() != null ? application.getSldOption().name() : null)
                .loaSignatureUrl(application.getLoaSignatureUrl())
                .loaSignedAt(application.getLoaSignedAt())
                // Phase 5
                .kvaStatus(application.getKvaStatus() != null ? application.getKvaStatus().name() : null)
                .kvaSource(application.getKvaSource() != null ? application.getKvaSource().name() : null)
                .kvaConfirmedAt(application.getKvaConfirmedAt())
                // ── P1.2: EMA ELISE 필드 ──
                .installationName(application.getInstallationName())
                .premisesType(application.getPremisesType() != null ? application.getPremisesType().name() : null)
                .isRentalPremises(application.getIsRentalPremises())
                .landlordEiLicenceMasked(maskLandlord(application.getLandlordEiLicenceNo()))
                .renewalCompanyNameChanged(application.getRenewalCompanyNameChanged())
                .renewalAddressChanged(application.getRenewalAddressChanged())
                .installationAddressBlock(application.getInstallationAddressBlock())
                .installationAddressUnit(application.getInstallationAddressUnit())
                .installationAddressStreet(application.getInstallationAddressStreet())
                .installationAddressBuilding(application.getInstallationAddressBuilding())
                .installationAddressPostalCode(application.getInstallationAddressPostalCode())
                .correspondenceAddressBlock(application.getCorrespondenceAddressBlock())
                .correspondenceAddressUnit(application.getCorrespondenceAddressUnit())
                .correspondenceAddressStreet(application.getCorrespondenceAddressStreet())
                .correspondenceAddressBuilding(application.getCorrespondenceAddressBuilding())
                .correspondenceAddressPostalCode(application.getCorrespondenceAddressPostalCode())
                .build();
    }
}
