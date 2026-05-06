package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AccountSetupToken 도메인 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 4).
 * <p>
 * PRD §9 AC-28b(5회 실패 잠금) + AC-28c(단일 활성 토큰) 검증.
 * Repository 레벨의 findActiveTokensByUser 쿼리 테스트는 Service 레이어에서 통합 테스트로 커버.
 */
@DisplayName("AccountSetupToken 도메인 - PR#1 Stage 4")
class AccountSetupTokenTest {

    private User dummyUser() {
        return User.builder()
            .email("x@y.com").password("h").firstName("a").lastName("b")
            .build();
    }

    private AccountSetupToken newToken(LocalDateTime expiresAt) {
        return AccountSetupToken.builder()
            .tokenUuid(UUID.randomUUID().toString())
            .user(dummyUser())
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(expiresAt)
            .build();
    }

    // ============================================================
    // isUsable()
    // ============================================================

    @Test
    @DisplayName("isUsable() - 신규 토큰(미사용/미revoke/미잠금/미만료)은 사용 가능")
    void isUsable_freshToken() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));
        assertThat(t.isUsable()).isTrue();
        assertThat(t.getFailedAttempts()).isZero();
    }

    @Test
    @DisplayName("isUsable() - 만료 시 false")
    void expired_isNotUsable() {
        AccountSetupToken t = newToken(LocalDateTime.now().minusMinutes(1));
        assertThat(t.isUsable()).isFalse();
    }

    // ============================================================
    // markUsed()
    // ============================================================

    @Test
    @DisplayName("markUsed() - 이후 isUsable=false, usedAt 세팅")
    void markUsed_thenNotUsable() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));

        t.markUsed();

        assertThat(t.getUsedAt()).isNotNull();
        assertThat(t.isUsable()).isFalse();
    }

    @Test
    @DisplayName("markUsed() - 이미 사용된 토큰 재사용 시 IllegalStateException")
    void markUsed_alreadyUsed_throws() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));
        t.markUsed();

        assertThatThrownBy(t::markUsed)
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("markUsed() - 만료된 토큰 사용 시 IllegalStateException")
    void markUsed_expired_throws() {
        AccountSetupToken t = newToken(LocalDateTime.now().minusMinutes(1));

        assertThatThrownBy(t::markUsed)
            .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // revoke() (O-17)
    // ============================================================

    @Test
    @DisplayName("revoke() - 이후 isUsable=false, revokedAt 세팅")
    void revoke_thenNotUsable() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));

        t.revoke();

        assertThat(t.getRevokedAt()).isNotNull();
        assertThat(t.isUsable()).isFalse();
    }

    @Test
    @DisplayName("revoke() - 재호출은 멱등 (revokedAt 덮어쓰지 않음)")
    void revoke_isIdempotent() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));
        t.revoke();
        LocalDateTime firstRevoke = t.getRevokedAt();

        t.revoke(); // 멱등 — 예외 없음

        assertThat(t.getRevokedAt()).isEqualTo(firstRevoke);
    }

    @Test
    @DisplayName("revoke() - 이미 사용된 토큰은 revoke 무시 (usedAt 우선)")
    void revoke_alreadyUsed_noOp() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));
        t.markUsed();

        t.revoke();

        assertThat(t.getRevokedAt()).isNull();
        assertThat(t.getUsedAt()).isNotNull();
    }

    // ============================================================
    // recordFailedAttempt() (H-3 / AC-28b)
    // ============================================================

    @Test
    @DisplayName("recordFailedAttempt() - 5회 누적 시 lockedAt 세팅 + isUsable=false (AC-28b)")
    void recordFailedAttempt_fiveTimes_locks() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));

        for (int i = 0; i < 4; i++) {
            t.recordFailedAttempt();
        }
        assertThat(t.getFailedAttempts()).isEqualTo(4);
        assertThat(t.getLockedAt()).isNull();
        assertThat(t.isUsable()).isTrue();

        t.recordFailedAttempt(); // 5th

        assertThat(t.getFailedAttempts()).isEqualTo(5);
        assertThat(t.getLockedAt()).isNotNull();
        assertThat(t.isUsable()).isFalse();
    }

    @Test
    @DisplayName("recordFailedAttempt() - 4회까지는 isUsable=true 유지")
    void recordFailedAttempt_belowThreshold_stillUsable() {
        AccountSetupToken t = newToken(LocalDateTime.now().plusHours(48));

        for (int i = 0; i < 4; i++) {
            t.recordFailedAttempt();
        }

        assertThat(t.isUsable()).isTrue();
    }
}
