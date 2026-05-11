package com.bluelight.backend.api.notification.template;

import com.bluelight.backend.domain.notification.NotificationTemplate;

/**
 * 템플릿 렌더링 결과 — 채널 어댑터가 외부 발송에 사용할 최종 메시지.
 *
 * @param subject               EMAIL 채널의 제목. 다른 채널은 null.
 * @param body                  렌더링된 본문 (변수 치환 완료). 인앱·이메일·WhatsApp 모든 채널이 사용.
 * @param providerTemplateName  WhatsApp 채널이 Meta/BSP 측 사전 승인된 템플릿 식별자로 사용.
 *                              인앱/이메일에서는 null.
 */
public record RenderedMessage(String subject, String body, String providerTemplateName) {

    /** 인앱/이메일 발송용 짧은 팩토리. providerTemplateName 미사용. */
    public static RenderedMessage of(String subject, String body) {
        return new RenderedMessage(subject, body, null);
    }

    public static RenderedMessage fromTemplate(NotificationTemplate template, String body) {
        return new RenderedMessage(template.getSubject(), body, template.getProviderTemplateName());
    }
}
