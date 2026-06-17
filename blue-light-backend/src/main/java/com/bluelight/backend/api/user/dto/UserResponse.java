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
    /**
     * LEW 본인 PayNow — 전체값(평문) 노출(D-PN5).
     * ⚠️ 이 DTO는 <b>본인 전용</b>(GET /api/users/me)에만 사용한다. 타 사용자를 렌더하는
     * admin/LEW 화면은 절대 이 DTO를 재사용하지 말 것 — 마스킹+감사 reveal 흐름을 우회해
     * 평문 PayNow가 누출된다. 타인 노출은 {@code AdminUserResponse}(paynowValueMasked) +
     * {@code GET /api/admin/users/{id}/paynow/reveal}(LEW_PAYNOW_VIEWED 감사)만 사용.
     */
    private String paynowType;
    private String paynowValue;
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
                .paynowType(user.getPaynowType() != null ? user.getPaynowType().name() : null)
                .paynowValue(user.getPaynowValue())
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
