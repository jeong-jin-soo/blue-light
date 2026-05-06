package com.bluelight.backend.api.auth;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenRepository;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AccountSetupTokenService 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 * <p>
 * O-17(단일 활성 토큰), H-3(5회 실패 잠금), 검증 실패 분기(GONE + 세부 코드) 커버.
 */
@DisplayName("AccountSetupTokenService - PR#2 Stage A")
class AccountSetupTokenServiceTest {

    private AccountSetupTokenRepository repository;
    private AccountSetupTokenService service;

    @BeforeEach
    void setUp() {
        repository = mock(AccountSetupTokenRepository.class);
        service = new AccountSetupTokenService(repository);
        // save()는 입력을 그대로 반환(id 세팅 없이 테스트에 충분)
        when(repository.save(any(AccountSetupToken.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private User dummyUser(long userSeq) {
        User u = User.builder()
            .email("x@y.com").password("h").firstName("a").lastName("b")
            .build();
        // userSeq는 DB 생성값이므로 리플렉션으로 주입
        org.springframework.test.util.ReflectionTestUtils.setField(u, "userSeq", userSeq);
        return u;
    }

    // ============================================================
    // issue() — O-17 단일 활성 토큰 유지
    // ============================================================

    @Test
    @DisplayName("issue() - 기존 활성 토큰이 없으면 바로 신규 발급")
    void issue_noExistingToken_savesNew() {
        User user = dummyUser(10L);
        when(repository.findActiveTokensByUser(10L)).thenReturn(List.of());

        AccountSetupToken token = service.issue(user, AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP, null);

        assertThat(token).isNotNull();
        assertThat(token.getUser()).isSameAs(user);
        assertThat(token.getSource()).isEqualTo(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP);
        assertThat(token.getTokenUuid()).isNotBlank();
        assertThat(token.getExpiresAt()).isAfter(LocalDateTime.now().plusHours(47));
        assertThat(token.getExpiresAt()).isBefore(LocalDateTime.now().plusHours(49));
        verify(repository).save(any(AccountSetupToken.class));
    }

    @Test
    @DisplayName("issue() - 기존 활성 토큰 있으면 revoke 후 신규 발급 (O-17)")
    void issue_withExistingActive_revokesOld() {
        User user = dummyUser(20L);
        AccountSetupToken existing = AccountSetupToken.builder()
            .tokenUuid(UUID.randomUUID().toString())
            .user(user)
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(10))
            .build();
        when(repository.findActiveTokensByUser(20L)).thenReturn(List.of(existing));

        AccountSetupToken newToken = service.issue(user, AccountSetupTokenSource.LOGIN_ACTIVATION, null);

        assertThat(existing.getRevokedAt()).isNotNull();
        assertThat(existing.isUsable()).isFalse();
        assertThat(newToken.getTokenUuid()).isNotEqualTo(existing.getTokenUuid());
        assertThat(newToken.getSource()).isEqualTo(AccountSetupTokenSource.LOGIN_ACTIVATION);
    }

    @Test
    @DisplayName("issue() - HttpServletRequest에서 IP/UA 추출")
    void issue_recordsIpAndUserAgent() {
        User user = dummyUser(30L);
        when(repository.findActiveTokensByUser(30L)).thenReturn(List.of());

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.42, 10.0.0.1");
        when(req.getHeader("User-Agent")).thenReturn("Mozilla/5.0 Test");

        AccountSetupToken token = service.issue(user, AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP, req);

        // X-Forwarded-For의 첫 번째 IP를 취함
        assertThat(token.getRequestingIp()).isEqualTo("203.0.113.42");
        assertThat(token.getRequestingUserAgent()).isEqualTo("Mozilla/5.0 Test");
    }

    // ============================================================
    // validate()
    // ============================================================

    @Test
    @DisplayName("validate() - 없는 토큰은 410 GONE + TOKEN_INVALID")
    void validate_notFound_throwsGone() {
        when(repository.findByTokenUuid("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validate("missing"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.GONE);
                assertThat(be.getCode()).isEqualTo("TOKEN_INVALID");
            });
    }

    @Test
    @DisplayName("validate() - 이미 사용된 토큰은 410 GONE + TOKEN_ALREADY_USED")
    void validate_alreadyUsed_throws() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("used-uuid").user(dummyUser(1L))
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();
        t.markUsed();
        when(repository.findByTokenUuid("used-uuid")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.validate("used-uuid"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("TOKEN_ALREADY_USED"));
    }

    @Test
    @DisplayName("validate() - 잠긴 토큰은 410 GONE + TOKEN_LOCKED")
    void validate_locked_throws() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("locked-uuid").user(dummyUser(1L))
            .source(AccountSetupTokenSource.LOGIN_ACTIVATION)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();
        for (int i = 0; i < 5; i++) t.recordFailedAttempt();
        when(repository.findByTokenUuid("locked-uuid")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.validate("locked-uuid"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("TOKEN_LOCKED"));
    }

    @Test
    @DisplayName("validate() - revoke된 토큰은 410 GONE + TOKEN_REVOKED")
    void validate_revoked_throws() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("revoked-uuid").user(dummyUser(1L))
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();
        t.revoke();
        when(repository.findByTokenUuid("revoked-uuid")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.validate("revoked-uuid"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("TOKEN_REVOKED"));
    }

    @Test
    @DisplayName("validate() - 만료된 토큰은 410 GONE + TOKEN_EXPIRED")
    void validate_expired_throws() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("expired-uuid").user(dummyUser(1L))
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .build();
        when(repository.findByTokenUuid("expired-uuid")).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.validate("expired-uuid"))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("TOKEN_EXPIRED"));
    }

    @Test
    @DisplayName("validate() - 유효 토큰은 그대로 반환")
    void validate_usable_returns() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("ok-uuid").user(dummyUser(1L))
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(24))
            .build();
        when(repository.findByTokenUuid("ok-uuid")).thenReturn(Optional.of(t));

        AccountSetupToken returned = service.validate("ok-uuid");

        assertThat(returned).isSameAs(t);
        assertThat(returned.isUsable()).isTrue();
    }

    // ============================================================
    // markUsed() / recordFailure()
    // ============================================================

    @Test
    @DisplayName("markUsed() - usedAt 세팅")
    void markUsed_setsUsedAt() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("u").user(dummyUser(1L))
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();

        service.markUsed(t);

        assertThat(t.getUsedAt()).isNotNull();
        assertThat(t.isUsable()).isFalse();
    }

    @Test
    @DisplayName("recordFailure() - 5회 누적 시 엔티티 잠금 (H-3)")
    void recordFailure_fiveTimes_locks() {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid("u").user(dummyUser(1L))
            .source(AccountSetupTokenSource.LOGIN_ACTIVATION)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .build();

        for (int i = 0; i < 5; i++) service.recordFailure(t);

        assertThat(t.getFailedAttempts()).isEqualTo(5);
        assertThat(t.getLockedAt()).isNotNull();
        assertThat(t.isUsable()).isFalse();
    }
}
