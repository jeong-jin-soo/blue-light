package com.bluelight.backend.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원가입 요청 DTO
 */
@Getter
@NoArgsConstructor
public class SignupRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 20, message = "Password must be between 8 and 20 characters")
    private String password;

    @NotBlank(message = "First name is required")
    @Size(max = 50, message = "First name must be 50 characters or less")
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 50, message = "Last name must be 50 characters or less")
    private String lastName;

    @Size(max = 20, message = "Phone number must be 20 characters or less")
    private String phone;

    @NotNull(message = "PDPA consent is required")
    private Boolean pdpaConsent;

    /**
     * 역할 선택 (APPLICANT / LEW, nullable — 미입력 시 APPLICANT)
     */
    private String role;

    /**
     * LEW 면허번호 (LEW 역할 선택 시 필수)
     */
    @Size(max = 50, message = "Licence number must be 50 characters or less")
    private String lewLicenceNo;

    /**
     * LEW 등급 (LEW 역할 선택 시 필수: GRADE_7, GRADE_8, GRADE_9)
     */
    @Size(max = 20, message = "LEW grade must be 20 characters or less")
    private String lewGrade;

    /**
     * 회사명 (APPLICANT 선택 시 입력 가능)
     */
    @Size(max = 100, message = "Company name must be 100 characters or less")
    private String companyName;

    /**
     * UEN (싱가포르 사업자등록번호, 선택)
     */
    @Size(max = 20, message = "UEN must be 20 characters or less")
    private String uen;

    /**
     * 직위 (Director, Manager 등, 선택)
     */
    @Size(max = 50, message = "Designation must be 50 characters or less")
    private String designation;
}
