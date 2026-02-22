package com.bluelight.backend.api.user.dto;

import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * User profile response DTO
 */
@Getter
@Builder
public class UserResponse {

    private Long userSeq;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private UserRole role;
    private boolean approved;
    private String lewLicenceNo;
    private String lewGrade;
    private String companyName;
    private String uen;
    private String designation;
    private String correspondenceAddress;
    private String correspondencePostalCode;
    private boolean hasSignature;
    private LocalDateTime pdpaConsentAt;
    private LocalDateTime createdAt;

    public static UserResponse from(User user) {
        return UserResponse.builder()
                .userSeq(user.getUserSeq())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole())
                .approved(user.isApproved())
                .lewLicenceNo(user.getLewLicenceNo())
                .lewGrade(user.getLewGrade() != null ? user.getLewGrade().name() : null)
                .companyName(user.getCompanyName())
                .uen(user.getUen())
                .designation(user.getDesignation())
                .correspondenceAddress(user.getCorrespondenceAddress())
                .correspondencePostalCode(user.getCorrespondencePostalCode())
                .hasSignature(user.getSignatureUrl() != null)
                .pdpaConsentAt(user.getPdpaConsentAt())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
