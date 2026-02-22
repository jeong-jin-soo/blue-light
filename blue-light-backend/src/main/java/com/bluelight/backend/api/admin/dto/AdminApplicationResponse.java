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
    private String userFirstName;
    private String userLastName;
    private String userEmail;
    private String userPhone;
    private String userCompanyName;
    private String userUen;
    private String userDesignation;
    private String userCorrespondenceAddress;
    private String userCorrespondencePostalCode;

    // Assigned LEW info
    private Long assignedLewSeq;
    private String assignedLewFirstName;
    private String assignedLewLastName;
    private String assignedLewEmail;
    private String assignedLewLicenceNo;
    private String assignedLewGrade;
    private Integer assignedLewMaxKva;

    // SP Group 계정 번호
    private String spAccountNo;

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
                .userFirstName(application.getUser().getFirstName())
                .userLastName(application.getUser().getLastName())
                .userEmail(application.getUser().getEmail())
                .userPhone(application.getUser().getPhone())
                .userCompanyName(application.getUser().getCompanyName())
                .userUen(application.getUser().getUen())
                .userDesignation(application.getUser().getDesignation())
                .userCorrespondenceAddress(application.getUser().getCorrespondenceAddress())
                .userCorrespondencePostalCode(application.getUser().getCorrespondencePostalCode())
                .assignedLewSeq(application.getAssignedLew() != null
                        ? application.getAssignedLew().getUserSeq() : null)
                .assignedLewFirstName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getFirstName() : null)
                .assignedLewLastName(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLastName() : null)
                .assignedLewEmail(application.getAssignedLew() != null
                        ? application.getAssignedLew().getEmail() : null)
                .assignedLewLicenceNo(application.getAssignedLew() != null
                        ? application.getAssignedLew().getLewLicenceNo() : null)
                .assignedLewGrade(application.getAssignedLew() != null && application.getAssignedLew().getLewGrade() != null
                        ? application.getAssignedLew().getLewGrade().name() : null)
                .assignedLewMaxKva(application.getAssignedLew() != null && application.getAssignedLew().getLewGrade() != null
                        ? application.getAssignedLew().getLewGrade().getMaxKva() : null)
                // SP Account
                .spAccountNo(application.getSpAccountNo())
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
                .build();
    }
}
