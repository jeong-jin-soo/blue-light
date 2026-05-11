package com.bluelight.backend.domain.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * User 의 WhatsApp 알림 인프라 도메인 메서드 단위 테스트 (PR-0A).
 *
 * <p>verifyPhone / clearPhoneVerification / optInWhatsapp / optOutWhatsapp /
 * isWhatsappReachable / updatePreferredLanguage / anonymize 의 WhatsApp 필드 처리 검증.</p>
 */
@DisplayName("User WhatsApp 알림 환경 - PR-0A")
class UserWhatsappPreferencesTest {

    private User buildUser() {
        return User.builder()
                .email("test@example.com")
                .password("hash")
                .firstName("Test")
                .lastName("User")
                .build();
    }

    @Test
    @DisplayName("기본 빌더 - phoneE164/phoneVerified/whatsappOptIn 모두 비활성, preferredLanguage='en'")
    void defaults() {
        User user = buildUser();
        assertThat(user.getPhoneE164()).isNull();
        assertThat(user.getPhoneVerified()).isFalse();
        assertThat(user.getPhoneVerifiedAt()).isNull();
        assertThat(user.getWhatsappOptIn()).isFalse();
        assertThat(user.getWhatsappOptInAt()).isNull();
        assertThat(user.getWhatsappOptOutAt()).isNull();
        assertThat(user.getPreferredLanguage()).isEqualTo("en");
    }

    // ============================================================
    // verifyPhone
    // ============================================================

    @Test
    @DisplayName("verifyPhone() - E.164 번호 저장, phoneVerified=true, phoneVerifiedAt 기록")
    void verifyPhone_storesE164AndMarksVerified() {
        User user = buildUser();
        LocalDateTime at = LocalDateTime.now();

        user.verifyPhone("+6591234567", at);

        assertThat(user.getPhoneE164()).isEqualTo("+6591234567");
        assertThat(user.getPhoneVerified()).isTrue();
        assertThat(user.getPhoneVerifiedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("verifyPhone() - null 또는 blank E.164 는 IllegalArgumentException")
    void verifyPhone_rejectsBlank() {
        User user = buildUser();
        assertThatThrownBy(() -> user.verifyPhone(null, LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.verifyPhone("  ", LocalDateTime.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clearPhoneVerification() - phoneVerified=false 로 리셋, 옵트인 상태는 보존")
    void clearPhoneVerification_resetsVerificationFlag() {
        User user = buildUser();
        user.verifyPhone("+6591234567", LocalDateTime.now());
        user.optInWhatsapp(LocalDateTime.now());
        assertThat(user.isWhatsappReachable()).isTrue();

        user.clearPhoneVerification();

        assertThat(user.getPhoneVerified()).isFalse();
        assertThat(user.getPhoneVerifiedAt()).isNull();
        // 옵트인 토글 자체는 보존됨 — 발송 가드는 isWhatsappReachable() 에서 차단.
        assertThat(user.getWhatsappOptIn()).isTrue();
        assertThat(user.isWhatsappReachable()).isFalse();
    }

    // ============================================================
    // optIn / optOut
    // ============================================================

    @Test
    @DisplayName("optInWhatsapp() - whatsappOptIn=true, whatsappOptInAt 기록")
    void optInWhatsapp_marksOptIn() {
        User user = buildUser();
        LocalDateTime at = LocalDateTime.now();

        user.optInWhatsapp(at);

        assertThat(user.getWhatsappOptIn()).isTrue();
        assertThat(user.getWhatsappOptInAt()).isEqualTo(at);
        assertThat(user.getWhatsappOptOutAt()).isNull();
    }

    @Test
    @DisplayName("optOutWhatsapp() - whatsappOptIn=false, whatsappOptOutAt 기록 (optInAt 은 이력 보존)")
    void optOutWhatsapp_marksOptOutAndPreservesOptInAt() {
        User user = buildUser();
        LocalDateTime optInAt = LocalDateTime.now().minusDays(7);
        LocalDateTime optOutAt = LocalDateTime.now();
        user.optInWhatsapp(optInAt);

        user.optOutWhatsapp(optOutAt);

        assertThat(user.getWhatsappOptIn()).isFalse();
        assertThat(user.getWhatsappOptInAt()).isEqualTo(optInAt); // 이력 보존
        assertThat(user.getWhatsappOptOutAt()).isEqualTo(optOutAt);
    }

    // ============================================================
    // isWhatsappReachable - 발송 직전 가드
    // ============================================================

    @Test
    @DisplayName("isWhatsappReachable() - 검증된 E.164 + 옵트인 ON 조합에서만 true")
    void isWhatsappReachable_requiresAllConditions() {
        User user = buildUser();
        assertThat(user.isWhatsappReachable()).isFalse(); // 기본 false

        // 검증만 하고 옵트인 X
        user.verifyPhone("+6591234567", LocalDateTime.now());
        assertThat(user.isWhatsappReachable()).isFalse();

        // 옵트인까지
        user.optInWhatsapp(LocalDateTime.now());
        assertThat(user.isWhatsappReachable()).isTrue();

        // 옵트아웃
        user.optOutWhatsapp(LocalDateTime.now());
        assertThat(user.isWhatsappReachable()).isFalse();
    }

    @Test
    @DisplayName("isWhatsappReachable() - phoneE164 만 있고 phoneVerified=false 이면 false")
    void isWhatsappReachable_unverifiedPhoneIsNotReachable() {
        User user = buildUser();
        ReflectionTestUtils.setField(user, "phoneE164", "+6591234567");
        ReflectionTestUtils.setField(user, "whatsappOptIn", true);

        // phoneVerified 가 false 이므로 차단
        assertThat(user.isWhatsappReachable()).isFalse();
    }

    @Test
    @DisplayName("isWhatsappReachable() - DELETED 상태는 발송 불가")
    void isWhatsappReachable_deletedUserIsNotReachable() {
        User user = buildUser();
        user.verifyPhone("+6591234567", LocalDateTime.now());
        user.optInWhatsapp(LocalDateTime.now());
        assertThat(user.isWhatsappReachable()).isTrue();

        user.softDelete(); // status=DELETED

        assertThat(user.isWhatsappReachable()).isFalse();
    }

    // ============================================================
    // updatePreferredLanguage
    // ============================================================

    @Test
    @DisplayName("updatePreferredLanguage() - 새 locale 저장")
    void updatePreferredLanguage_storesLocale() {
        User user = buildUser();
        user.updatePreferredLanguage("zh-Hans");
        assertThat(user.getPreferredLanguage()).isEqualTo("zh-Hans");
    }

    @Test
    @DisplayName("updatePreferredLanguage() - null/blank 거부")
    void updatePreferredLanguage_rejectsBlank() {
        User user = buildUser();
        assertThatThrownBy(() -> user.updatePreferredLanguage(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> user.updatePreferredLanguage(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============================================================
    // anonymize() — PDPA 삭제 시 WhatsApp 필드 일괄 초기화
    // ============================================================

    @Test
    @DisplayName("anonymize() - WhatsApp 필드 일괄 초기화, opt_out_at 은 감사용으로 기록")
    void anonymize_clearsWhatsappFields() {
        User user = buildUser();
        user.verifyPhone("+6591234567", LocalDateTime.now());
        user.optInWhatsapp(LocalDateTime.now());

        user.anonymize();

        assertThat(user.getPhoneE164()).isNull();
        assertThat(user.getPhoneVerified()).isFalse();
        assertThat(user.getPhoneVerifiedAt()).isNull();
        assertThat(user.getWhatsappOptIn()).isFalse();
        assertThat(user.getWhatsappOptInAt()).isNull();
        assertThat(user.getWhatsappOptOutAt()).isNotNull(); // 감사용 시각 기록
    }
}
