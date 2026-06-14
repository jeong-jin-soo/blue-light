package com.bluelight.backend.api.application;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * EMA 반려(T7) 직후 담당 LEW 에게 IN_APP 통지를 발행하는 리스너 (ema-submission-tracking-spec.md §10).
 *
 * <p>{@link LewAssignmentNotificationListener} 의 검증된 패턴을 그대로 복제한다(허점#3 방향 a):
 * {@code NotificationService.createNotification}(REQUIRES_NEW + saveAndFlush)으로 인앱 알림 row 를
 * 오케스트레이터/outbox/템플릿 무관하게 즉시 영속한다.
 *
 * <h3>왜 AFTER_COMMIT 인가</h3>
 * 반려 전이가 커밋된 뒤에만 LEW 가 실제 반려 상태를 조회할 수 있고, NotificationService 일시 오류가
 * 반려 트랜잭션을 롤백시켜선 안 된다.
 *
 * <h3>비노출 정책 (US-C1)</h3>
 * 신청자에게는 EMA 중간/반려 상태를 알리지 않는다 — 본 리스너는 담당 LEW 에게만 발송한다.
 *
 * <h3>이메일</h3>
 * 본 범위(PR-E5)는 IN_APP 우선이다. 이메일 채널은 신경로 와이어링 인프라가 준비되는 PR-E6 후속에서
 * 선례처럼 분리 try/catch 로 추가한다(이번엔 신규 직접발송 금지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaRejectedNotificationListener {

    /** 인앱 알림 referenceType — 프론트가 /lew/applications/{id}/review 로 라우팅하는 키. */
    static final String REFERENCE_TYPE_APPLICATION = "APPLICATION";

    private final NotificationService notificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmaRejected(EmaRejectedEvent event) {
        Long lewSeq = event.getLewUserSeq();
        if (lewSeq == null) {
            // 배정 LEW 가 없으면 통지 대상이 없다 — 조용히 skip (반려 자체는 정상 처리됨).
            log.debug("EMA rejected notification skipped — no assigned LEW: applicationSeq={}",
                    event.getApplicationSeq());
            return;
        }

        // IN_APP 알림 — 신청자에게는 발송하지 않는다(US-C1).
        try {
            String applicationCode = "APP-" + String.format("%06d", event.getApplicationSeq());
            String reason = event.getReason();
            String body = applicationCode
                    + " — EMA submission rejected. Address the reason and resubmit."
                    + (reason != null && !reason.isBlank() ? " Reason: " + reason : "");
            notificationService.createNotification(
                    lewSeq,
                    NotificationType.EMA_REJECTED_LEW,
                    "EMA submission rejected",
                    body,
                    REFERENCE_TYPE_APPLICATION, event.getApplicationSeq());
        } catch (RuntimeException ex) {
            log.warn("EMA rejected in-app notification failed: lewSeq={}, applicationSeq={}, err={}",
                    lewSeq, event.getApplicationSeq(), ex.getMessage());
        }

        log.info("EMA rejection notified: applicationSeq={}, lewSeq={}",
                event.getApplicationSeq(), lewSeq);
    }
}
