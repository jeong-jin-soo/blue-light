package com.bluelight.backend.api.notification.orchestrator;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationPreference;
import com.bluelight.backend.domain.notification.NotificationPreferenceRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NotificationPreferenceResolver 단위 테스트 (PR-0B).
 *
 * <p>preferences row 부재 시 default fallback + WhatsApp 의 reachable 가드 작동을 검증.</p>
 */
@DisplayName("NotificationPreferenceResolver - PR-0B")
class NotificationPreferenceResolverTest {

    private NotificationPreferenceRepository prefRepo;
    private NotificationChannelDefaults defaults;
    private NotificationPreferenceResolver resolver;

    @BeforeEach
    void setUp() {
        prefRepo = mock(NotificationPreferenceRepository.class);
        defaults = new NotificationChannelDefaults();
        resolver = new NotificationPreferenceResolver(prefRepo, defaults);
        when(prefRepo.findByUserSeqAndEventTypeAndChannel(anyLong(), anyString(), any(NotificationChannel.class)))
                .thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("기본값 - WhatsApp 미옵트인 사용자: IN_APP + EMAIL 만 활성")
    void defaults_withoutWhatsappOptIn() {
        User user = userWithSeq(1L);

        Set<NotificationChannel> result = resolver.resolveEnabledChannels(user, "PAYMENT_REQUEST");

        assertThat(result).containsExactlyInAnyOrder(
                NotificationChannel.IN_APP,
                NotificationChannel.EMAIL);
    }

    @Test
    @DisplayName("WhatsApp 옵트인 + 검증된 번호 - WHATSAPP 도 활성")
    void whatsappReachable_includesWhatsapp() {
        User user = userWithSeq(1L);
        user.verifyPhone("+6591234567", LocalDateTime.now());
        user.optInWhatsapp(LocalDateTime.now());

        Set<NotificationChannel> result = resolver.resolveEnabledChannels(user, "PAYMENT_REQUEST");

        assertThat(result).contains(NotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("WhatsApp 옵트인 + 번호 미검증 - WHATSAPP 차단 (reachable=false)")
    void whatsappOptedInButUnverified_excludesWhatsapp() {
        User user = userWithSeq(1L);
        // phoneE164 만 직접 세팅, verified 는 false 유지
        ReflectionTestUtils.setField(user, "phoneE164", "+6591234567");
        user.optInWhatsapp(LocalDateTime.now());

        Set<NotificationChannel> result = resolver.resolveEnabledChannels(user, "PAYMENT_REQUEST");

        assertThat(result).doesNotContain(NotificationChannel.WHATSAPP);
    }

    @Test
    @DisplayName("환경설정 - EMAIL 비활성 row 가 있으면 default 무시")
    void preference_disabledOverridesDefault() {
        User user = userWithSeq(1L);
        NotificationPreference pref = NotificationPreference.builder()
                .userSeq(1L)
                .eventType("PAYMENT_REQUEST")
                .channel(NotificationChannel.EMAIL)
                .enabled(false)
                .build();
        when(prefRepo.findByUserSeqAndEventTypeAndChannel(1L, "PAYMENT_REQUEST", NotificationChannel.EMAIL))
                .thenReturn(Optional.of(pref));

        Set<NotificationChannel> result = resolver.resolveEnabledChannels(user, "PAYMENT_REQUEST");

        assertThat(result).doesNotContain(NotificationChannel.EMAIL);
        assertThat(result).contains(NotificationChannel.IN_APP); // 다른 채널은 영향 없음
    }

    @Test
    @DisplayName("환경설정 - WHATSAPP 명시 활성 row 가 있어도 reachable=false 면 차단")
    void preference_enabledButUnreachable_stillBlocksWhatsapp() {
        User user = userWithSeq(1L); // phone 미검증
        NotificationPreference whatsappOn = NotificationPreference.builder()
                .userSeq(1L)
                .eventType("PAYMENT_REQUEST")
                .channel(NotificationChannel.WHATSAPP)
                .enabled(true)
                .build();
        when(prefRepo.findByUserSeqAndEventTypeAndChannel(1L, "PAYMENT_REQUEST", NotificationChannel.WHATSAPP))
                .thenReturn(Optional.of(whatsappOn));

        boolean enabled = resolver.isEnabledFor(user, "PAYMENT_REQUEST", NotificationChannel.WHATSAPP);

        assertThat(enabled).isFalse();
    }

    private static User userWithSeq(Long seq) {
        User user = User.builder()
                .email("u" + seq + "@test.sg")
                .password("x")
                .firstName("U")
                .lastName(String.valueOf(seq))
                .build();
        ReflectionTestUtils.setField(user, "userSeq", seq);
        return user;
    }
}
