package com.bluelight.backend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JwtTokenProvider 다중 역할 claim 단위 테스트
 * (★ Concierge 강화 + 별도 수금 PR-1, D1=B).
 * <p>
 * Spring Boot 컨텍스트 없이 ReflectionTestUtils 로 secretKeyString/expiration 주입 후 init().
 */
@DisplayName("JwtTokenProvider roles claim - PR-1 (D1=B)")
class JwtTokenProviderRolesTest {

    private JwtTokenProvider provider;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider();
        // 256bit (32 bytes) 이상 — HS256 요구
        ReflectionTestUtils.setField(provider, "secretKeyString",
            "test-secret-key-for-jwt-multi-role-pr1-must-be-long-enough-256bits");
        ReflectionTestUtils.setField(provider, "expiration", 60L * 60L * 1000L);
        ReflectionTestUtils.invokeMethod(provider, "init");
    }

    // ============================================================
    // 발급 + 파싱 — 다중 역할 라운드트립
    // ============================================================

    @Test
    @DisplayName("createToken(List<String> roles) → getRoles() 라운드트립")
    void createTokenWithRoles_roundTrip() {
        String token = provider.createToken(
            42L, "user@example.com", "CONCIERGE_MANAGER",
            List.of("CONCIERGE_MANAGER", "LEW"),
            true, true);

        assertThat(provider.validateToken(token)).isTrue();

        List<String> parsed = provider.getRoles(token);
        assertThat(parsed).containsExactlyInAnyOrder("CONCIERGE_MANAGER", "LEW");

        // primary role claim 도 함께 보존됨 (legacy 호환)
        assertThat(provider.getRole(token)).isEqualTo("CONCIERGE_MANAGER");
        assertThat(provider.getApproved(token)).isTrue();
        assertThat(provider.getEmailVerified(token)).isTrue();
    }

    @Test
    @DisplayName("createToken(legacy 단일 role) → getRoles() 가 1원소 리스트로 hydrate")
    void createTokenLegacy_getRolesReturnsSingleElementList() {
        String token = provider.createToken(
            42L, "user@example.com", "ADMIN", true, true);

        assertThat(provider.validateToken(token)).isTrue();

        List<String> parsed = provider.getRoles(token);
        assertThat(parsed).containsExactly("ADMIN");
        assertThat(provider.getRole(token)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("createToken(roles=null) — primary role 단일 fallback")
    void createTokenRolesNull_fallbackToPrimary() {
        String token = provider.createToken(
            42L, "user@example.com", "APPLICANT",
            null,
            false, false);

        assertThat(provider.getRoles(token)).containsExactly("APPLICANT");
    }

    @Test
    @DisplayName("createToken(roles=empty) — primary role 단일 fallback")
    void createTokenRolesEmpty_fallbackToPrimary() {
        String token = provider.createToken(
            42L, "user@example.com", "APPLICANT",
            List.of(),
            false, false);

        assertThat(provider.getRoles(token)).containsExactly("APPLICANT");
    }

    @Test
    @DisplayName("createToken(다중 role) — primary 가 roles 에 포함되지 않아도 토큰 발급은 성공 (호출자 책임)")
    void createTokenWithRoles_primaryNotInRoles_stillWorks() {
        // 비정상 입력: primary='ADMIN' 인데 roles 에 ADMIN 이 없음
        // 본 테스트는 도메인 레벨 검증을 강제하지 않고 (User.effectiveRoles() 가 보증),
        // JwtTokenProvider 자체는 받은 그대로 직렬화하는지 확인.
        String token = provider.createToken(
            42L, "u@e.com", "ADMIN",
            List.of("LEW"),
            true, true);

        assertThat(provider.getRoles(token)).containsExactly("LEW");
        assertThat(provider.getRole(token)).isEqualTo("ADMIN");
    }
}
