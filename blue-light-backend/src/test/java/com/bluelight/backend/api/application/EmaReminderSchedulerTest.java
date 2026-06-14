package com.bluelight.backend.api.application;

import com.bluelight.backend.api.admin.EmaSubmissionSettings;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.EmaSubmissionStatus;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EMA 리마인더 스케줄러 검증 (ema-submission-tracking-spec.md §10).
 *
 * <ul>
 *   <li>대상 선정: reminder.days 설정값으로 cutoff 계산 후 repo 쿼리에 전달(설정 우선)</li>
 *   <li>대상에 대해 담당 LEW IN_APP 발행 + markEmaReminderNotified() 호출(멱등 플래그)</li>
 *   <li>배정 LEW 없으면 발송 skip + 플래그 미기록(배정 후 재대상)</li>
 *   <li>대상 0건이면 알림 미발행</li>
 * </ul>
 */
class EmaReminderSchedulerTest {

    private ApplicationRepository applicationRepository;
    private NotificationService notificationService;
    private EmaSubmissionSettings emaSubmissionSettings;
    private EmaReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        notificationService = mock(NotificationService.class);
        emaSubmissionSettings = mock(EmaSubmissionSettings.class);
        scheduler = new EmaReminderScheduler(applicationRepository, notificationService, emaSubmissionSettings);
    }

    private Application mockApp(Long seq, User assignedLew) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(seq);
        when(app.getAssignedLew()).thenReturn(assignedLew);
        when(app.getEmaReferenceNo()).thenReturn("ELISE-001");
        when(app.getEmaSubmittedAt()).thenReturn(LocalDateTime.now().minusDays(5));
        return app;
    }

    @Test
    void reminderDays_설정값으로_cutoff_계산해_쿼리() {
        when(emaSubmissionSettings.loadReminderDays()).thenReturn(3);
        when(applicationRepository.findEmaReminderTargets(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        scheduler.processEmaReminders();

        // cutoff = now - 3d, 쿼리 파라미터에 IN_PROGRESS + SUBMITTED + RESUBMITTED 전달.
        ArgumentCaptor<LocalDateTime> cutoffCap = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(applicationRepository).findEmaReminderTargets(
                eq(ApplicationStatus.IN_PROGRESS),
                eq(EmaSubmissionStatus.SUBMITTED),
                eq(EmaSubmissionStatus.RESUBMITTED),
                cutoffCap.capture(), any());
        // cutoff 은 now-3d 근처 (오차 1분 이내).
        LocalDateTime expected = LocalDateTime.now().minusDays(3);
        assertThat(cutoffCap.getValue()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }

    @Test
    void 대상건에_LEW_IN_APP발행_그리고_멱등플래그_기록() {
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(7L);
        Application app = mockApp(42L, lew);
        when(emaSubmissionSettings.loadReminderDays()).thenReturn(3);
        when(applicationRepository.findEmaReminderTargets(any(), any(), any(), any(), any()))
                .thenReturn(List.of(app));

        scheduler.processEmaReminders();

        verify(notificationService).createNotification(
                eq(7L), eq(NotificationType.EMA_SUBMISSION_REMINDER_LEW),
                any(), any(), eq("APPLICATION"), eq(42L));
        // 멱등: 발송 후 플래그 기록 → 같은 날 재실행 시 쿼리에서 제외됨.
        verify(app).markEmaReminderNotified();
    }

    @Test
    void 배정LEW_없으면_발송_skip_그리고_플래그_미기록() {
        Application app = mockApp(42L, null); // assignedLew = null
        when(emaSubmissionSettings.loadReminderDays()).thenReturn(3);
        when(applicationRepository.findEmaReminderTargets(any(), any(), any(), any(), any()))
                .thenReturn(List.of(app));

        scheduler.processEmaReminders();

        verify(notificationService, never()).createNotification(anyLong(), any(), any(), any(), any(), anyLong());
        verify(app, never()).markEmaReminderNotified(); // 배정 후 즉시 재대상이 되도록 플래그 미기록
    }

    @Test
    void 대상_0건이면_알림_미발행() {
        when(emaSubmissionSettings.loadReminderDays()).thenReturn(3);
        when(applicationRepository.findEmaReminderTargets(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        scheduler.processEmaReminders();

        verify(notificationService, never()).createNotification(anyLong(), any(), any(), any(), any(), anyLong());
    }
}
