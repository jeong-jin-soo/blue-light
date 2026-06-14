package com.bluelight.backend.api.application;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * EMA 반려 통지 리스너 검증 (ema-submission-tracking-spec.md §10).
 *
 * <ul>
 *   <li>반려 시 담당 LEW 에게 IN_APP 알림(EMA_REJECTED_LEW) 발행</li>
 *   <li>신청자에게는 절대 발송하지 않음(US-C1) — recipient 는 lewSeq 단일</li>
 *   <li>배정 LEW 가 없으면(lewSeq=null) 발송 skip</li>
 * </ul>
 */
class EmaRejectedNotificationListenerTest {

    private NotificationService notificationService;
    private EmaRejectedNotificationListener listener;

    private static final Long LEW_SEQ = 7L;
    private static final Long APP_SEQ = 42L;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        listener = new EmaRejectedNotificationListener(notificationService);
    }

    @Test
    void 반려시_담당LEW에게만_IN_APP_알림() {
        listener.onEmaRejected(new EmaRejectedEvent(APP_SEQ, LEW_SEQ, "Capacity exceeds limit"));

        // 정확히 LEW_SEQ 수신자에게 EMA_REJECTED_LEW 1건 — 신청자(다른 seq) 발송 없음.
        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.EMA_REJECTED_LEW),
                any(), contains("Capacity exceeds limit"),
                eq("APPLICATION"), eq(APP_SEQ));
    }

    @Test
    void 사유_null이어도_알림_발행() {
        listener.onEmaRejected(new EmaRejectedEvent(APP_SEQ, LEW_SEQ, null));

        verify(notificationService).createNotification(
                eq(LEW_SEQ), eq(NotificationType.EMA_REJECTED_LEW),
                any(), any(), eq("APPLICATION"), eq(APP_SEQ));
    }

    @Test
    void 배정LEW_없으면_발송_skip() {
        listener.onEmaRejected(new EmaRejectedEvent(APP_SEQ, null, "reason"));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), any(), any(), any(), anyLong());
    }
}
