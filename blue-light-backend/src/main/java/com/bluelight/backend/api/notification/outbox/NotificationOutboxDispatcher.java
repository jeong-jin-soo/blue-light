package com.bluelight.backend.api.notification.outbox;

import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter;
import com.bluelight.backend.api.notification.channel.NotificationChannelAdapter.SendResult;
import com.bluelight.backend.domain.notification.NotificationChannel;
import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import com.bluelight.backend.domain.notification.OutboxStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Outbox row 디스패처 — 채널 어댑터에 외부 호출을 위임하고 결과를 outbox 상태로 반영.
 *
 * <h2>호출 경로</h2>
 * <ul>
 *   <li>즉시 발송: {@link NotificationOrchestrator} 이 enqueue 직후 {@link #dispatchAsync} 호출 (@Async).</li>
 *   <li>재시도: {@link NotificationOutboxRetryScheduler} 이 due 한 row 들을 polling 후 호출.</li>
 * </ul>
 *
 * <p>본 클래스는 채널 어댑터 자체를 모른다 — Spring 이 등록된 모든 {@link NotificationChannelAdapter}
 * 빈을 채널별 Map 으로 자동 주입하므로 PR-0C 에서 EmailChannelAdapter/InAppChannelAdapter 가
 * 등록되면 자동으로 dispatch 대상이 된다 (open/closed 원칙).</p>
 */
@Service
@Slf4j
public class NotificationOutboxDispatcher {

    private final NotificationOutboxRepository outboxRepository;
    private final ExponentialBackoffPolicy backoffPolicy;
    private final Map<NotificationChannel, NotificationChannelAdapter> adaptersByChannel;

    public NotificationOutboxDispatcher(NotificationOutboxRepository outboxRepository,
                                        ExponentialBackoffPolicy backoffPolicy,
                                        List<NotificationChannelAdapter> adapters) {
        this.outboxRepository = outboxRepository;
        this.backoffPolicy = backoffPolicy;
        this.adaptersByChannel = new EnumMap<>(NotificationChannel.class);
        for (NotificationChannelAdapter adapter : adapters) {
            NotificationChannelAdapter previous = this.adaptersByChannel.put(adapter.channel(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate NotificationChannelAdapter for channel " + adapter.channel()
                                + ": " + previous.getClass() + " and " + adapter.getClass());
            }
        }
    }

    /**
     * 비동기 즉시 발송 — Orchestrator 가 outbox 적재 직후 호출. 트랜잭션 외부에서 실행되므로
     * 호출자가 트랜잭션을 보유하고 있어도 영향 없음.
     */
    @Async
    public void dispatchAsync(Long outboxSeq) {
        dispatch(outboxSeq);
    }

    /**
     * 동기 발송 — 스케줄러가 폴링 후 호출. row 단위 별도 트랜잭션 (REQUIRES_NEW).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void dispatch(Long outboxSeq) {
        Optional<NotificationOutbox> opt = outboxRepository.findById(outboxSeq);
        if (opt.isEmpty()) {
            log.warn("Outbox row not found: outboxSeq={}", outboxSeq);
            return;
        }
        NotificationOutbox row = opt.get();

        // 1) 이미 처리된 row 스킵
        if (row.getStatus() == OutboxStatus.SENT || row.getStatus() == OutboxStatus.DEAD
                || row.getStatus() == OutboxStatus.SKIPPED) {
            log.debug("Outbox row already settled, skipping: outboxSeq={}, status={}", outboxSeq, row.getStatus());
            return;
        }

        // 2) 어댑터 lookup
        NotificationChannelAdapter adapter = adaptersByChannel.get(row.getChannel());
        if (adapter == null) {
            log.warn("No adapter registered for channel: channel={}, outboxSeq={}", row.getChannel(), outboxSeq);
            row.markSkipped("No adapter for channel " + row.getChannel());
            return; // markSkipped 는 dirty checking 으로 flush
        }

        // 3) markSending → 외부 호출 → 결과 반영
        row.markSending();
        SendResult result;
        try {
            result = adapter.send(row);
        } catch (RuntimeException ex) {
            log.error("Adapter threw unexpected exception (treated as retryable): outboxSeq={}, channel={}",
                    outboxSeq, row.getChannel(), ex);
            result = SendResult.retryableFailure("UNEXPECTED_EXCEPTION", ex.getMessage());
        }
        applyResult(row, result);
    }

    private void applyResult(NotificationOutbox row, SendResult result) {
        if (result.success()) {
            row.markSent();
            log.debug("Outbox sent: outboxSeq={}, channel={}, providerMessageId={}",
                    row.getOutboxSeq(), row.getChannel(), result.providerMessageId());
            return;
        }

        String errorTag = (result.errorCode() != null ? result.errorCode() : "ERR") + ": "
                + (result.errorMessage() != null ? result.errorMessage() : "(no message)");

        if (!result.retryable() || backoffPolicy.isExhausted(row.getAttemptCount())) {
            row.markDead(errorTag);
            log.warn("Outbox marked DEAD: outboxSeq={}, channel={}, attemptCount={}, error={}",
                    row.getOutboxSeq(), row.getChannel(), row.getAttemptCount(), errorTag);
            return;
        }

        LocalDateTime next = backoffPolicy.nextAttemptAt(row.getAttemptCount(), LocalDateTime.now());
        row.markFailed(errorTag, next);
        log.info("Outbox marked FAILED for retry: outboxSeq={}, channel={}, attemptCount={}, nextAttemptAt={}",
                row.getOutboxSeq(), row.getChannel(), row.getAttemptCount(), next);
    }

    /** 디스패처가 인식하는 채널들 — 테스트 가시성용. */
    public java.util.Set<NotificationChannel> registeredChannels() {
        return java.util.Collections.unmodifiableSet(adaptersByChannel.keySet());
    }
}
