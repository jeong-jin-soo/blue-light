package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * User 상태 전이 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#1 Stage 4).
 * <p>
 * PRD §5.1c User 상태 머신 + §9 AC-30/AC-32/AC-37 검증.
 * 도메인 메서드(activate/suspend/unsuspend/softDelete/recordSignupConsent/optInMarketing)만 사용하며,
 * BaseEntity 필드(createdAt 등) 조작은 이 스위트에서 불필요.
 */
@DisplayName("User 상태 전이 - PR#1 Stage 4")
class UserStatusTransitionTest {

    private User buildUser(UserStatus status) {
        return User.builder()
            .email("test@example.com")
            .password("hash")
            .firstName("Test")
            .lastName("User")
            .status(status)
            .build();
    }

    // ============================================================
    // activate()
    // ============================================================

    @Test
    @DisplayName("activate() - PENDING_ACTIVATION 상태에서 ACTIVE로 전이, activatedAt/firstLoggedInAt 세팅")
    void activate_fromPending_transitionsToActive() {
        User user = buildUser(UserStatus.PENDING_ACTIVATION);
        assertThat(user.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(user.getActivatedAt()).isNull();
        assertThat(user.getFirstLoggedInAt()).isNull();

        user.activate();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getActivatedAt()).isNotNull();
        assertThat(user.getFirstLoggedInAt()).isNotNull();
    }

    @Test
    @DisplayName("activate() - 이미 ACTIVE면 멱등 (예외 없음, activatedAt 재세팅 안 함)")
    void activate_alreadyActive_isIdempotent() {
        User user = buildUser(UserStatus.ACTIVE);
        // ACTIVE로 시작 — activatedAt은 null 상태지만 정상 (기존 사용자 backfill 상황)
        assertThat(user.getActivatedAt()).isNull();

        user.activate(); // 멱등 — 예외 없이 반환

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // 멱등 호출은 이미 ACTIVE이면 아무 것도 건드리지 않음
        assertThat(user.getActivatedAt()).isNull();
        assertThat(user.getFirstLoggedInAt()).isNull();
    }

    @Test
    @DisplayName("activate() - SUSPENDED에서는 IllegalStateException")
    void activate_fromSuspended_throws() {
        User user = buildUser(UserStatus.SUSPENDED);
        assertThatThrownBy(user::activate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("activate() - DELETED에서는 IllegalStateException")
    void activate_fromDeleted_throws() {
        User user = buildUser(UserStatus.DELETED);
        assertThatThrownBy(user::activate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("DELETED");
    }

    // ============================================================
    // suspend() / unsuspend()
    // ============================================================

    @Test
    @DisplayName("suspend() - ACTIVE에서 SUSPENDED 전이")
    void suspend_fromActive_transitionsToSuspended() {
        User user = buildUser(UserStatus.ACTIVE);

        user.suspend("policy violation");

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
    }

    @Test
    @DisplayName("suspend() - DELETED는 불가")
    void suspend_fromDeleted_throws() {
        User user = buildUser(UserStatus.DELETED);
        assertThatThrownBy(() -> user.suspend("reason"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("deleted");
    }

    @Test
    @DisplayName("unsuspend() - SUSPENDED에서 ACTIVE 전이")
    void unsuspend_fromSuspended_transitionsToActive() {
        User user = buildUser(UserStatus.SUSPENDED);

        user.unsuspend();

        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    @DisplayName("unsuspend() - SUSPENDED 이외는 IllegalStateException")
    void unsuspend_fromActive_throws() {
        User user = buildUser(UserStatus.ACTIVE);
        assertThatThrownBy(user::unsuspend)
            .isInstanceOf(IllegalStateException.class);
    }

    // ============================================================
    // softDelete()
    // ============================================================

    @Test
    @DisplayName("softDelete() - status=DELETED + deletedAt 세팅")
    void softDelete_setsStatusToDeleted() {
        User user = buildUser(UserStatus.ACTIVE);

        user.softDelete();

        assertThat(user.getStatus()).isEqualTo(UserStatus.DELETED);
        assertThat(user.getDeletedAt()).isNotNull();
    }

    // ============================================================
    // recordSignupConsent() / marketing / anonymize
    // ============================================================

    @Test
    @DisplayName("recordSignupConsent() - 동의 기록 보존")
    void recordSignupConsent_preservesFields() {
        User user = buildUser(UserStatus.PENDING_ACTIVATION);
        LocalDateTime now = LocalDateTime.now();

        user.recordSignupConsent(now, TermsVersion.CURRENT, SignupSource.CONCIERGE_REQUEST);

        assertThat(user.getSignupConsentAt()).isEqualTo(now);
        assertThat(user.getTermsVersion()).isEqualTo(TermsVersion.CURRENT);
        assertThat(user.getSignupSource()).isEqualTo(SignupSource.CONCIERGE_REQUEST);
    }

    @Test
    @DisplayName("optInMarketing() / optOutMarketing() - 마케팅 토글")
    void marketingOptInOut() {
        User user = buildUser(UserStatus.ACTIVE);
        assertThat(user.getMarketingOptIn()).isFalse();
        assertThat(user.getMarketingOptInAt()).isNull();

        LocalDateTime optInTime = LocalDateTime.now();
        user.optInMarketing(optInTime);

        assertThat(user.getMarketingOptIn()).isTrue();
        assertThat(user.getMarketingOptInAt()).isEqualTo(optInTime);

        user.optOutMarketing();

        assertThat(user.getMarketingOptIn()).isFalse();
        // optOut 시 optInAt은 이력 보존 목적으로 유지 (현 구현 정책)
        assertThat(user.getMarketingOptInAt()).isEqualTo(optInTime);
    }

    @Test
    @DisplayName("anonymize() - 마케팅 필드도 초기화")
    void anonymize_clearsMarketingFields() {
        User user = User.builder()
            .email("x@y.com").password("h").firstName("A").lastName("B")
            .marketingOptIn(true)
            .build();
        user.optInMarketing(LocalDateTime.now());
        assertThat(user.getMarketingOptIn()).isTrue();
        assertThat(user.getMarketingOptInAt()).isNotNull();

        user.anonymize();

        assertThat(user.getMarketingOptIn()).isFalse();
        assertThat(user.getMarketingOptInAt()).isNull();
        assertThat(user.getFirstName()).isEqualTo("Deleted");
        assertThat(user.getLastName()).isEqualTo("User");
        assertThat(user.getPassword()).isEqualTo("DELETED");
    }

    @Test
    @DisplayName("anonymize() - 이메일도 익명화하여 동일 이메일 재가입을 허용")
    void anonymize_alsoAnonymizesEmail() {
        User user = User.builder()
            .email("foo@example.com").password("h").firstName("A").lastName("B")
            .build();
        ReflectionTestUtils.setField(user, "userSeq", 42L);

        user.anonymize();

        assertThat(user.getEmail()).isEqualTo("deleted-42@deleted.licensekaki.sg");
        assertThat(user.getEmail()).doesNotContain("foo@example.com");
    }
}
