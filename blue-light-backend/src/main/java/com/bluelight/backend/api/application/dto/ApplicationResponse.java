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
    private String assignedLewName;
    private String assignedLewLicenceNo;

    // SP Group 계정 번호
    private String spAccountNo;

    // ── Phase 18: 갱신 + 견적 개선 필드 ──
    private String applicationType;
    private BigDecimal serviceFee;
    private Long originalApplicationSeq;
    private String existingLicenceNo;
    private String renewalReferenceNo;
    private LocalDate existingExpiryDate;
    private Integer renewalPeriodMonths;
    private BigDecimal emaFee;

    // SLD 제출 방식
    private String sldOption;

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
                .assignedLewName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getName() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
                // SP Account
                .spAccountNo(application.getSpAccountNo())
                // Phase 18 fields
                .applicationType(application.getApplicationType().name())
                .serviceFee(application.getServiceFee())
                .originalApplicationSeq(application.getOriginalApplication() != null
                        ? application.getOriginalApplication().getApplicationSeq() : null)
                .existingLicenceNo(application.getExistingLicenceNo())
                .renewalReferenceNo(application.getRenewalReferenceNo())
                .existingExpiryDate(application.getExistingExpiryDate())
                .renewalPeriodMonths(application.getRenewalPeriodMonths())
                .emaFee(application.getEmaFee())
                .sldOption(application.getSldOption() != null ? application.getSldOption().name() : null)
                .build();
    }
}
