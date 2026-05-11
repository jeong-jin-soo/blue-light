package com.bluelight.backend.api.notification.orchestrator;

import com.bluelight.backend.api.notification.outbox.NotificationOutboxDispatcher;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter;
import com.bluelight.backend.api.notification.outbox.NotificationOutboxWriter.EnqueueRequest;
import com.bluelight.backend.api.notification.template.NotificationTemplateRegistry;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.Set;

/**
 * 알림 발행 허브 — 도메인 이벤트({@link NotificationDispatchEvent}) 를 받아 채널×템플릿 결정 후
 * outbox 에 적재한다.
 *
 * <h2>트랜잭션 경계</h2>
 * <ul>
 *   <li><b>AFTER_COMMIT</b>: 도메인 트랜잭션 커밋 완료 후에만 처리. 도메인이 롤백되면 알림 발행
 *       자체가 일어나지 않는다 (예: 결제 실패가 알림 무중지 못함).</li>
 *   <li>본 메서드는 호출자의 트랜잭션과 분리된다 — {@link NotificationOutboxWriter} 가
 *       REQUIRES_NEW 로 자체 트랜잭션을 생성.</li>
 *   <li>적재 직후 {@link NotificationOutboxDispatcher#dispatchAsync} 로 즉시 발송 시도. 실패 시
 *       {@link com.bluelight.backend.api.notification.outbox.NotificationOutboxRetryScheduler} 가
 *       재시도.</li>
 * </ul>
 *
 * <p>채널·템플릿·환경설정 모든 결정은 본 클래스가 책임. 채널 어댑터는 외부 호출만 담당.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationOrchestrator {

    private final UserRepository userRepository;
    private final NotificationPreferenceResolver preferenceResolver;
    private final NotificationTemplateRegistry templateRegistry;
    private final NotificationOutboxWriter outboxWriter;
    private final NotificationOutboxDispatcher outboxDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDispatch(NotificationDispatchEvent event) {
        // 1) 수신자 조회
        Optional<User> recipientOpt = userRepository.findById(event.recipientUserSeq());
        if (recipientOpt.isEmpty()) {
            log.warn("Notification recipient not found: eventType={}, userSeq={}",
                    event.eventType(), event.recipientUserSeq());
            return;
        }
        User recipient = recipientOpt.get();
        String locale = recipient.getPreferredLanguage() != null ? recipient.getPreferredLanguage() : "en";

        // 2) 활성 채널 결정
        Set<NotificationChannel> enabledChannels = preferenceResolver
                .resolveEnabledChannels(recipient, event.eventType());
        if (enabledChannels.isEmpty()) {
            log.debug("No enabled channels for user: eventType={}, userSeq={}",
                    event.eventType(), event.recipientUserSeq());
            return;
        }

        // 3) 채널별로 템플릿 lookup + outbox 적재 + 즉시 발송
        for (NotificationChannel channel : enabledChannels) {
            dispatchToChannel(event, recipient, channel, locale);
        }
    }

    private void dispatchToChannel(NotificationDispatchEvent event,
                                   User recipient,
                                   NotificationChannel channel,
                                   String locale) {
        // 3a) 템플릿 활성 여부 확인 — 없으면 채널 단위로 skip
        if (templateRegistry.findActive(event.templateCode(), channel, locale).isEmpty()) {
            log.warn("No active template for channel — skipping: code={}, channel={}, locale={}, userSeq={}",
                    event.templateCode(), channel, locale, recipient.getUserSeq());
            return;
        }

        // 3b) idempotency key 산정 + outbox 적재 (REQUIRES_NEW 트랜잭션)
        String idempotencyKey = NotificationOutboxWriter.idempotencyKey(
                event.eventType(), event.referenceType(), event.referenceId(),
                recipient.getUserSeq(), channel);

        EnqueueRequest req = new EnqueueRequest(
                idempotencyKey,
                recipient.getUserSeq(),
                channel,
                event.eventType(),
                event.templateCode(),
                locale,
                event.payload(),
                event.referenceType(),
                event.referenceId());

        Optional<NotificationOutbox> rowOpt = outboxWriter.enqueue(req);
        if (rowOpt.isEmpty()) {
            log.warn("Outbox enqueue failed (payload serialization?): key={}", idempotencyKey);
            return;
        }

        NotificationOutbox row = rowOpt.get();
        if (row.getOutboxSeq() == null) {
            // 중복 가드로 인해 새 row 가 생성되지 않은 경우. dispatch 시도 불필요.
            return;
        }

        // 3c) 비동기 즉시 발송 시도 — 실패 시 RetryScheduler 가 재시도.
        outboxDispatcher.dispatchAsync(row.getOutboxSeq());
    }
}
