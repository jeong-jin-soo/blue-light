package com.bluelight.backend.api.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Phase 2 PR#3: 법인 신청 시 JIT 모달로 수집하는 회사 정보.
 * CreateApplicationRequest에 중첩 포함.
 */
@Getter
@Setter
@NoArgsConstructor
public class CompanyInfoRequest {

    @NotBlank(message = "Company name is required")
    @Size(max = 100, message = "Company name must be 100 characters or less")
    private String companyName;

    /**
     * 싱가포르 UEN 형식:
     * - 9자리 숫자 + 1 알파벳 (Business: 12345678X / Local Company: 201812345A)
     * - 또는 10자리 (T/S/R prefix 사업체 등록 코드 포함)
     *
     * 정규식: /^(\d{8}[A-Z]|\d{9}[A-Z]|[TSR]\d{2}[A-Z]{2}\d{4}[A-Z])$/
     */
    @NotBlank(message = "UEN is required")
    @Size(max = 20, message = "UEN must be 20 characters or less")
    @Pattern(
            regexp = "^(\\d{8}[A-Z]|\\d{9}[A-Z]|[TSR]\\d{2}[A-Z]{2}\\d{4}[A-Z])$",
            message = "Invalid UEN format"
    )
    private String uen;

    @NotBlank(message = "Designation is required")
    @Size(max = 50, message = "Designation must be 50 characters or less")
    private String designation;

    /**
     * true(default) 이면 User 프로필에 저장(이후 신청 prefill).
     * false 이면 이번 신청에만 사용하고 User 프로필 변경 없음.
     */
    private Boolean persistToProfile = Boolean.TRUE;

    public boolean shouldPersistToProfile() {
        return persistToProfile == null || persistToProfile;
    }
}
