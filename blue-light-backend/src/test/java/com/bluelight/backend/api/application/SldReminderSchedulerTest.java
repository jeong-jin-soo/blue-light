package com.bluelight.backend.api.application;

import com.bluelight.backend.api.notification.orchestrator.NotificationDispatchEvent;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

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
 * SLD 미제출 리마인더 스케줄러 — DRAWING_SLD 파일 유무 필터 + 담당 LEW 발송 검증.
 */
class SldReminderSchedulerTest {

    private ApplicationRepository applicationRepository;
    private FileRepository fileRepository;
    private ApplicationEventPublisher eventPublisher;
    private SldReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        fileRepository = mock(FileRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        scheduler = new SldReminderScheduler(applicationRepository, fileRepository, eventPublisher);
    }

    private Application candidate(long seq, Long lewSeq) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(seq);
        when(app.getLicenseIssuedAt()).thenReturn(LocalDateTime.now().minusMonths(2).minusDays(10));
        User user = mock(User.class);
        when(user.getFullName()).thenReturn("Tan Ah Kow");
        when(app.getUser()).thenReturn(user);
        if (lewSeq != null) {
            User lew = mock(User.class);
            when(lew.getUserSeq()).thenReturn(lewSeq);
            when(app.getAssignedLew()).thenReturn(lew);
        }
        return app;
    }

    @Test
    void SLD_없는_건은_담당LEW에게_A60_발행_및_멱등마킹() {
        Application app = candidate(101L, 55L);
        when(applicationRepository.findSldReminderCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of(app));
        // DRAWING_SLD 파일 없음
        when(fileRepository.findByApplicationApplicationSeqAndFileType(101L, FileType.DRAWING_SLD))
                .thenReturn(List.of());

        scheduler.processSldReminders();

        ArgumentCaptor<NotificationDispatchEvent> cap = ArgumentCaptor.forClass(NotificationDispatchEvent.class);
        verify(eventPublisher).publishEvent(cap.capture());
        NotificationDispatchEvent ev = cap.getValue();
        assertThat(ev.eventType()).isEqualTo(NotificationType.SLD_SUBMISSION_REMINDER_LEW.name());
        assertThat(ev.recipientUserSeq()).isEqualTo(55L);
        assertThat(ev.templateCode()).isEqualTo("A-60");
        assertThat(ev.referenceId()).isEqualTo(101L);
        assertThat(ev.payload().get("ctaUrl")).isEqualTo("/lew/applications/101/review#sld");
        verify(app).markSldReminderNotified();
    }

    @Test
    void SLD_이미_업로드된_건은_발행_안함_및_마킹_안함() {
        Application app = candidate(102L, 55L);
        when(applicationRepository.findSldReminderCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of(app));
        // DRAWING_SLD 파일 존재
        when(fileRepository.findByApplicationApplicationSeqAndFileType(102L, FileType.DRAWING_SLD))
                .thenReturn(List.of(mock(FileEntity.class)));

        scheduler.processSldReminders();

        verify(eventPublisher, never()).publishEvent(any(NotificationDispatchEvent.class));
        verify(app, never()).markSldReminderNotified();
    }

    @Test
    void 후보_없으면_아무것도_안함() {
        when(applicationRepository.findSldReminderCandidates(any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        scheduler.processSldReminders();

        verify(eventPublisher, never()).publishEvent(any());
        verify(fileRepository, never()).findByApplicationApplicationSeqAndFileType(anyLong(), eq(FileType.DRAWING_SLD));
    }
}
