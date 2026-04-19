package com.bluelight.backend.api.auth.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Account Setup 토큰 상태 응답 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * {@code GET /api/public/account-setup/{token}} 응답.
 * 로그인 전 단계이므로 이메일은 마스킹된 형태로만 노출 ("a***@example.com").
 */
@Getter
@Builder
public class AccountSetupStatusResponse {

    /**
     * 마스킹된 이메일 (앞 1자 + "***" + "@도메인")
     */
    private String maskedEmail;

    /**
     * 토큰 만료 시각 (프론트엔드에 "48시간 내에 완료" 표시용)
     */
    private LocalDateTime expiresAt;
}
