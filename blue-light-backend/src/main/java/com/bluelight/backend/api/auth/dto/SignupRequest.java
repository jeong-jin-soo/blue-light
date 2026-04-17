package com.bluelight.backend.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원가입 요청 DTO
 *
 * Phase 1 (2026-04-17): phone/companyName/uen/designation 제거.
 * - 회사 정보는 이제 ProfilePage에서 선택적으로 입력한다 (Just-in-Time Disclosure).
 * - 구버전 클라이언트가 제거된 필드를 포함해 호출해도 Jackson 기본값
 *   (FAIL_ON_UNKNOWN_PROPERTIES=false) 덕분에 조용히 무시된다 (AC-S3).
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
}
