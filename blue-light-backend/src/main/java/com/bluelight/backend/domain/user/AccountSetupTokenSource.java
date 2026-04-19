package com.bluelight.backend.domain.user;

/**
 * AccountSetupToken 발급 경로 (★ Kaki Concierge v1.5)
 * <p>
 * 발급 맥락을 로깅·분석용으로 구분한다.
 *
 * - CONCIERGE_ACCOUNT_SETUP: Phase 1 컨시어지 자동 가입 후 발급 (N1 이메일)
 * - LOGIN_ACTIVATION: Phase 1 옵션 B - PENDING 계정이 로그인 시도 시 재발급 (N-Activation)
 */
public enum AccountSetupTokenSource {
    CONCIERGE_ACCOUNT_SETUP,
    LOGIN_ACTIVATION
}
