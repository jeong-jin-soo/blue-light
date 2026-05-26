package com.bluelight.backend.api.admin.notification.template;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * H-S3 보안 알림 리스너 — PR-T2 stub 구현.
 *
 * <p>실제 Slack/email 통지는 PR-T2 범위 외 (다음 PR 에서 구현). 현재는 WARN 로그만 남긴다 —
 * 추후 SlackWebhookClient + EmailService 호출로 대체 예정.</p>
 *
 * <p>{@code @Async} 로 비동기 처리 — disable 트랜잭션을 차단하지 않는다.</p>
 */
@Component
@Slf4j
public class SecurityTemplateDisableAlertListener {

    @Async
    @EventListener
    public void onSecurityTemplateDisabled(SecurityTemplateDisableEvent event) {
        // TODO PR-T-future: SlackWebhookClient.post() + EmailService.sendToSecurityTeam()
        log.warn("[SECURITY ALERT] template {} ({}) disabled by user={} from {}: {}",
                event.templateCode(),
                event.category(),
                event.actorUserSeq(),
                event.actorIp(),
                event.changeReason());
    }
}
