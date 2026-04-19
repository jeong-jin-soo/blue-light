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
}
