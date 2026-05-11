package com.bluelight.backend.api.notification.outbox;

import com.bluelight.backend.domain.notification.NotificationOutbox;
import com.bluelight.backend.domain.notification.NotificationOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 알림 Outbox 재시도 스케줄러 — due row 를 폴링하여 디스패처에 전달.
 *
 * <p>fixedDelay 패턴(이전 실행 종료 후 N 초)으로 동시 실행을 피한다. 단일 노드 가정. 다중 노드
 * 운영 시 로우 수준 락(SELECT ... FOR UPDATE SKIP LOCKED) 또는 분산 락이 필요하지만 본 PR
 * 범위 밖.</p>
 *
 * <h2>설정 키</h2>
 * <ul>
 *   <li>{@code notification.outbox.poll-fixed-delay-ms} (기본 30000) — 폴링 주기</li>
 *   <li>{@code notification.outbox.poll-batch-size} (기본 50) — 한 폴링당 최대 row 수</li>
 *   <li>{@code notification.outbox.scheduler-enabled} (기본 true) — 테스트 환경에서 false 로 끄기</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationOutboxRetryScheduler {

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationOutboxDispatcher dispatcher;

    @Value("${notification.outbox.poll-batch-size:50}")
    private int batchSize;

    @Value("${notification.outbox.scheduler-enabled:true}")
    private boolean enabled;

    /** fixedDelay 는 millisecond. 기본 30s = 30000. */
    @Scheduled(fixedDelayString = "${notification.outbox.poll-fixed-delay-ms:30000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            List<NotificationOutbox> due = outboxRepository.findDue(LocalDateTime.now(), PageRequest.of(0, batchSize));
            if (due.isEmpty()) {
                return;
            }
            log.debug("Outbox poll: {} due rows", due.size());
            for (NotificationOutbox row : due) {
                try {
                    dispatcher.dispatch(row.getOutboxSeq());
                } catch (RuntimeException ex) {
                    // dispatcher 내부에서 처리되지만 마지막 안전망. 다음 폴링 사이클에서 재시도.
                    log.error("Outbox dispatch failure (will retry on next poll): outboxSeq={}",
                            row.getOutboxSeq(), ex);
                }
            }
        } catch (RuntimeException ex) {
            // 스케줄러 자체가 죽지 않도록 전체 예외 잡기.
            log.error("Outbox poll iteration failed", ex);
        }
    }
}
