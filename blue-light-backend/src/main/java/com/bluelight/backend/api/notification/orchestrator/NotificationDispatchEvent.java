package com.bluelight.backend.api.notification.orchestrator;

import java.util.Map;

/**
 * 도메인 서비스 → {@link NotificationOrchestrator} 사이의 공용 이벤트.
 *
 * <p>도메인 트랜잭션 안에서 {@code ApplicationEventPublisher.publishEvent(...)} 로 발행되며,
 * orchestrator 는 {@code @TransactionalEventListener(AFTER_COMMIT)} 단계에서 수신한다.
 * 즉 도메인 트랜잭션이 롤백되면 알림 발행 자체가 일어나지 않는다.</p>
 *
 * <h2>필드</h2>
 * <ul>
 *   <li>{@code eventType} — {@code NotificationType} enum 값 (문자열). 향후 enum 외부 확장을 막지
 *       않도록 String 으로 받는다.</li>
 *   <li>{@code recipientUserSeq} — 수신자 user.userSeq. orchestrator 가 환경설정/언어 조회에 사용.</li>
 *   <li>{@code referenceType / referenceId} — 참조 엔티티(예: {@code APPLICATION}, 신청서 seq).
 *       idempotency_key 산정 및 outbox 이력 조회에 사용. nullable.</li>
 *   <li>{@code templateCode} — {@code notification_templates.template_code}. 한 eventType 이 여러
 *       채널·언어에 대해 같은 코드를 공유한다 (예: {@code PAYMENT_REQUEST_APPLICANT}).</li>
 *   <li>{@code payload} — 템플릿 변수 슬롯 (예: {@code {applicantName=..., amount=...}}). 본문
 *       포함 PII 는 PDPA 최소화 원칙에 따라 호출자가 선별한다.</li>
 * </ul>
 *
 * <p>locale 은 본 이벤트에 포함하지 않는다 — orchestrator 가 수신자의 {@code preferredLanguage}
 * 를 조회한다 (Single Source of Truth).</p>
 */
public record NotificationDispatchEvent(
        String eventType,
        Long recipientUserSeq,
        String referenceType,
        Long referenceId,
        String templateCode,
        Map<String, String> payload
) {
    public NotificationDispatchEvent {
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (recipientUserSeq == null) {
            throw new IllegalArgumentException("recipientUserSeq must not be null");
        }
        if (templateCode == null || templateCode.isBlank()) {
            throw new IllegalArgumentException("templateCode must not be blank");
        }
        // payload 는 null 허용하지 않고 빈 Map 으로 정규화 (NPE 방지).
        payload = payload != null ? Map.copyOf(payload) : Map.of();
    }
}
