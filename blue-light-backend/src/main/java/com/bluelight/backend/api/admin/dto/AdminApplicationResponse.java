package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Admin application response DTO (includes applicant info)
 */
@Getter
@Builder
public class AdminApplicationResponse {

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

    // Applicant info
    private Long userSeq;
    private String userName;
    private String userEmail;
    private String userPhone;
    private String userCompanyName;
    private String userUen;
    private String userDesignation;

    // Assigned LEW info
    private Long assignedLewSeq;
    private String assignedLewName;
    private String assignedLewEmail;
    private String assignedLewLicenceNo;

    // ── Phase 18: 갱신 + 견적 개선 필드 ──
    private String applicationType;
    private BigDecimal serviceFee;
    private Long originalApplicationSeq;
    private String existingLicenceNo;
    private String renewalReferenceNo;
    private LocalDate existingExpiryDate;
    private Integer renewalPeriodMonths;
    private BigDecimal emaFee;

    public static AdminApplicationResponse from(Application application) {
        return AdminApplicationResponse.builder()
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
                .userSeq(application.getUser().getUserSeq())
                .userName(application.getUser().getName())
                .userEmail(application.getUser().getEmail())
                .userPhone(application.getUser().getPhone())
                .userCompanyName(application.getUser().getCompanyName())
                .userUen(application.getUser().getUen())
                .userDesignation(application.getUser().getDesignation())
                .assignedLewSeq(application.getAssignedLew() != null
                        ? application.getAssignedLew().getUserSeq() : null)
                .assignedLewName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getName() : null)
                .assignedLewEmail(application.getAssignedLew() != null
                        ? application.getAssignedLew().getEmail() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
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
                .build();
    }
}
