package com.bluelight.backend.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Account Setup 비밀번호 설정 요청 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * {@code POST /api/public/account-setup/{token}} 본문.
 * password/passwordConfirm 일치 검증은 Service 레이어에서 수행 (DTO-level constraint로는 표현 어려움).
 */
@Getter
@Setter
@NoArgsConstructor
public class AccountSetupCompleteRequest {

    @NotBlank(message = "Password is required")
    @Size(min = 8, max = 72, message = "Password must be 8~72 characters")
    private String password;

    @NotBlank(message = "Password confirmation is required")
    private String passwordConfirm;

    // ── LEW 초대 토큰(LEW_INVITATION)일 때만 사용 — Service 분기 검증 ──
    // 컨시어지/로그인 활성화 토큰은 아래 필드를 보내지 않으며 무시된다(DTO-level constraint 미부여).

    /** LEW 면허번호 (예: 8/35550). LEW_INVITATION 토큰일 때 필수. */
    @Size(max = 50)
    private String lewLicenceNo;

    /** LEW 등급 (GRADE_7 / GRADE_8 / GRADE_9). LEW_INVITATION 토큰일 때 필수. */
    @Size(max = 20)
    private String lewGrade;

    /** PDPA 동의 — LEW_INVITATION 토큰일 때 true 필수 (D-8 본인 동의). */
    private Boolean pdpaConsent;

    /** PayNow 유형 (COMPANY_UEN / MOBILE). LEW_INVITATION 토큰일 때 필수 (D-PN7). */
    @Size(max = 20)
    private String paynowType;

    /** PayNow 값 (MOBILE 8자리 / COMPANY_UEN 10자). LEW_INVITATION 토큰일 때 필수. */
    @Size(max = 20)
    private String paynowValue;
}
