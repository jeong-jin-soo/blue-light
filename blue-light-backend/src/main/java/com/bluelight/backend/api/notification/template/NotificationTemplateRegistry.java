package com.bluelight.backend.api.notification.template;

import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationTemplate;
import com.bluelight.backend.domain.notification.NotificationTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

/**
 * 알림 템플릿 조회 + 렌더링 진입점.
 *
 * <h2>locale fallback 정책</h2>
 * <ol>
 *   <li>요청한 (code, channel, locale) 조회.</li>
 *   <li>없으면 (code, channel, "en") 으로 폴백 — WARN 로그.</li>
 *   <li>그것도 없으면 {@link TemplateNotFoundException} 던짐 — 발송 자체를 SKIP 처리한다.</li>
 * </ol>
 *
 * <p>{@code enabled=false} 인 템플릿은 조회 결과에서 제외 (admin 이 임시 차단 가능).</p>
 *
 * <p><b>캐시</b> — 초기 PR-0B 는 캐시 미적용. 운영 사용량 측정 후 Caffeine 등으로 추가 (TODO).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateRegistry {

    private final NotificationTemplateRepository templateRepository;
    private final TemplateRenderer renderer;

    /** 요청 locale → en 폴백을 거쳐 활성화된 템플릿을 찾는다. */
    @Transactional(readOnly = true)
    public Optional<NotificationTemplate> findActive(String templateCode, NotificationChannel channel, String locale) {
        Optional<NotificationTemplate> primary = templateRepository
                .findByTemplateCodeAndChannelAndLocale(templateCode, channel, locale)
                .filter(NotificationTemplate::isEnabled);
        if (primary.isPresent()) return primary;

        if (!"en".equalsIgnoreCase(locale)) {
            Optional<NotificationTemplate> fallback = templateRepository
                    .findByTemplateCodeAndChannelAndLocale(templateCode, channel, "en")
                    .filter(NotificationTemplate::isEnabled);
            if (fallback.isPresent()) {
                log.warn("Template locale fallback en applied: code={}, channel={}, requestedLocale={}",
                        templateCode, channel, locale);
                return fallback;
            }
        }
        return Optional.empty();
    }

    /** 템플릿을 조회·렌더링하여 채널 어댑터가 사용할 {@link RenderedMessage} 로 반환. */
    @Transactional(readOnly = true)
    public RenderedMessage render(String templateCode,
                                  NotificationChannel channel,
                                  String locale,
                                  Map<String, String> payload) {
        NotificationTemplate template = findActive(templateCode, channel, locale)
                .orElseThrow(() -> new TemplateNotFoundException(templateCode, channel, locale));
        String body = renderer.render(template.getBodyText(), payload);
        String subject = renderer.render(template.getSubject(), payload);
        return new RenderedMessage(subject, body, template.getProviderTemplateName());
    }

    /** 활성 템플릿 미존재 — orchestrator 가 outbox 적재 자체를 SKIP 한다. */
    public static class TemplateNotFoundException extends RuntimeException {
        public TemplateNotFoundException(String templateCode, NotificationChannel channel, String locale) {
            super("No active template: code=" + templateCode + ", channel=" + channel + ", locale=" + locale);
        }
    }
}
