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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Applicant info
    private Long userSeq;
    private String userName;
    private String userEmail;
    private String userPhone;

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
                .createdAt(application.getCreatedAt())
                .updatedAt(application.getUpdatedAt())
                .userSeq(application.getUser().getUserSeq())
                .userName(application.getUser().getName())
                .userEmail(application.getUser().getEmail())
                .userPhone(application.getUser().getPhone())
                .build();
    }
}
