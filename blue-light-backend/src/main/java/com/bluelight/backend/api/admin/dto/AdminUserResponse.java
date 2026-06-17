package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.user.PaynowMasker;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRole;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Admin user list response DTO
 */
@Getter
@Builder
public class AdminUserResponse {

    private Long userSeq;
    private String email;
    private String firstName;
    private String lastName;
    private String phone;
    private UserRole role;
    private String approvedStatus;
    /** 계정 활성화 상태 (ACTIVE / PENDING_ACTIVATION / SUSPENDED …) — 초대됨/활성 배지 계산용. */
    private String status;
    private String lewLicenceNo;
    private String lewGrade;
    /** LEW PayNow 유형 (COMPANY_UEN / MOBILE). */
    private String paynowType;
    /** LEW PayNow 마스킹값 (예: ****1983). 전체값은 reveal 엔드포인트로만(D-PN5). 평문 미노출. */
    private String paynowValueMasked;
    private String companyName;
    private String uen;
    private String designation;
    private String correspondenceAddress;
    private String correspondencePostalCode;
    private LocalDateTime createdAt;

    public static AdminUserResponse from(User user) {
        return AdminUserResponse.builder()
                .userSeq(user.getUserSeq())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .phone(user.getPhone())
                .role(user.getRole())
                .approvedStatus(user.getApprovedStatus() != null
                        ? user.getApprovedStatus().name() : null)
                .status(user.getStatus() != null ? user.getStatus().name() : null)
                .lewLicenceNo(user.getLewLicenceNo())
                .lewGrade(user.getLewGrade() != null ? user.getLewGrade().name() : null)
                .paynowType(user.getPaynowType() != null ? user.getPaynowType().name() : null)
                .paynowValueMasked(PaynowMasker.mask(user.getPaynowValue()))
                .companyName(user.getCompanyName())
                .uen(user.getUen())
                .designation(user.getDesignation())
                .correspondenceAddress(user.getCorrespondenceAddress())
                .correspondencePostalCode(user.getCorrespondencePostalCode())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
