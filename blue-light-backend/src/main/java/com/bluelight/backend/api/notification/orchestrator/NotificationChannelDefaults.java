package com.bluelight.backend.api.notification.orchestrator;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.user.User;
import org.springframework.stereotype.Component;

/**
 * 알림 채널 기본값 — {@code notification_preferences} 에 행이 없는 사용자에 대해 어떤 채널을
 * ON 으로 간주할지 결정.
 *
 * <p><b>현재 정책 (PR-0B)</b>:</p>
 * <ul>
 *   <li>IN_APP, EMAIL — 항상 ON (admin 이 사용자별로 명시적 disable row 를 만들기 전엔).</li>
 *   <li>WHATSAPP — 사용자 {@code whatsapp_opt_in=true} 일 때만 ON. 옵트인 자체가 채널 활성화의
 *       정본 신호이므로 별도 preferences row 가 없어도 옵트인하면 자동 활성된다.</li>
 * </ul>
 *
 * <p>WhatsApp 의 발송 직전 가드(검증된 번호, 옵트아웃 없음, 익명화 안됨)는
 * {@link NotificationPreferenceResolver} 가 {@link User#isWhatsappReachable()} 로 별도 확인.</p>
 *
 * <h2>TODO (Phase 2/UI)</h2>
 * IN_APP/EMAIL 의 기본값은 향후 {@code system_settings.notification.channel.default.{CHANNEL}.enabled}
 * 로 이관하여 CLAUDE.md §설계 원칙(Single Source of Truth)을 충족한다. WhatsApp 은 옵트인 신호로
 * 자체 충족.
 */
@Component
public class NotificationChannelDefaults {

    /**
     * 사용자 컨텍스트를 고려한 채널 기본 활성 여부.
     *
     * @param channel 대상 채널
     * @param user    수신자 (WhatsApp 옵트인 신호 사용)
     */
    public boolean isEnabledByDefault(NotificationChannel channel, User user) {
        return switch (channel) {
            case IN_APP, EMAIL -> true;
            case WHATSAPP -> Boolean.TRUE.equals(user.getWhatsappOptIn());
            // PR-T2: SMS 어댑터는 미구현이므로 발송 차단 — Phase 1+ SMS 게이트웨이 도입 시 정책 추가.
            case SMS -> false;
        };
    }
}
