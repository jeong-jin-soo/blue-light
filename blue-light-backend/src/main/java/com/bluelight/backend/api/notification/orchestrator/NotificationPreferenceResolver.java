package com.bluelight.backend.api.notification.orchestrator;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationPreference;
import com.bluelight.backend.domain.notification.NotificationPreferenceRepository;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * 사용자×이벤트×채널 단위로 발송 가능한 채널을 결정한다.
 *
 * <h2>판정 순서</h2>
 * <ol>
 *   <li>{@code notification_preferences} 에 사용자의 (event, channel) 행이 있으면 → 그 값을 사용.</li>
 *   <li>행이 없으면 → {@link NotificationChannelDefaults} 기본값 적용.</li>
 *   <li>WhatsApp 채널은 추가로 {@link User#isWhatsappReachable()} 가드 — 미검증 번호·옵트아웃
 *       사용자에게는 환경설정이 ON 이어도 발송하지 않는다.</li>
 * </ol>
 *
 * <p>결과는 활성 채널의 EnumSet 으로 반환된다.</p>
 */
@Service
@RequiredArgsConstructor
public class NotificationPreferenceResolver {

    private final NotificationPreferenceRepository preferenceRepository;
    private final NotificationChannelDefaults channelDefaults;

    /**
     * 사용자에게 발송 가능한 채널 집합을 반환.
     *
     * @param recipient   수신자 (WhatsApp 가드용 옵트인/검증 상태 포함)
     * @param eventType   {@code NotificationType} enum 값
     */
    @Transactional(readOnly = true)
    public Set<NotificationChannel> resolveEnabledChannels(User recipient, String eventType) {
        EnumSet<NotificationChannel> enabled = EnumSet.noneOf(NotificationChannel.class);
        for (NotificationChannel channel : NotificationChannel.values()) {
            if (isEnabledFor(recipient, eventType, channel)) {
                enabled.add(channel);
            }
        }
        return enabled;
    }

    /** 단일 채널에 대한 발송 가능 여부 — 호출 측 디버깅/단위 검증용. */
    @Transactional(readOnly = true)
    public boolean isEnabledFor(User recipient, String eventType, NotificationChannel channel) {
        // 1) 사용자 환경설정 우선 — 행이 있으면 그 값을, 없으면 사용자 컨텍스트 기반 기본값.
        Optional<NotificationPreference> pref = preferenceRepository
                .findByUserSeqAndEventTypeAndChannel(recipient.getUserSeq(), eventType, channel);
        boolean preferenceEnabled = pref
                .map(NotificationPreference::isEnabled)
                .orElseGet(() -> channelDefaults.isEnabledByDefault(channel, recipient));
        if (!preferenceEnabled) return false;

        // 2) WhatsApp 채널은 사용자 reachable 가드 추가 적용
        if (channel == NotificationChannel.WHATSAPP) {
            return recipient.isWhatsappReachable();
        }
        return true;
    }
}
