package com.bluelight.backend.api.concierge.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Concierge 신청 폼 요청 DTO (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * PRD §2.2 5종 동의 체크박스 매핑:
 * - 필수 4종: PDPA / Terms / Signup / Delegation — {@code @AssertTrue}로 DTO 레벨 강제
 * - 선택 1종: Marketing
 */
@Getter
@Setter
@NoArgsConstructor
public class ConciergeRequestCreateRequest {

    @NotBlank(message = "Name is required")
    @Size(min = 1, max = 100)
    private String fullName;

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    @Size(max = 100)
    private String email;

    @NotBlank(message = "Mobile number is required")
    @Size(min = 7, max = 20)
    private String mobileNumber;

    /**
     * 선택 사항. XSS 방어는 렌더링 시 {@code HtmlUtils.htmlEscape} 적용.
     */
    @Size(max = 2000)
    private String memo;

    // ── v1.3 5종 동의 (필수 4 + 선택 1) ──

    @AssertTrue(message = "PDPA consent is required")
    private boolean pdpaConsent;

    @AssertTrue(message = "Terms agreement is required")
    private boolean termsAgreed;

    @AssertTrue(message = "Signup consent is required")
    private boolean signupConsent;

    @AssertTrue(message = "Delegation consent is required")
    private boolean delegationConsent;

    /**
     * 선택 — AssertTrue 미적용. 서비스 레이어에서 true일 때만 {@code User.optInMarketing()} + 감사 로그.
     */
    private boolean marketingOptIn;

    /**
     * 클라이언트 표시 약관 버전. null 시 서버 측 {@code TermsVersion.CURRENT} 사용.
     */
    @Size(max = 30)
    private String termsVersion;
}
