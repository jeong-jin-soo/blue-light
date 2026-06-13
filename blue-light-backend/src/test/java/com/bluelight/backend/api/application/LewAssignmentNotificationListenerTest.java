package com.bluelight.backend.api.application;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LewAssignmentNotificationListener} 단위 테스트.
 *
 * <p>자동/수동 배정 두 경로가 공유하는 알림 흐름을 검증: 배정된 LEW 에게 인앱 알림
 * ({@link NotificationType#APPLICATION_LEW_ASSIGNED_LEW}) + 이메일이 발송되며,
 * 한 채널의 실패가 다른 채널로 전파되지 않는다(실패 격리).</p>
 */
@DisplayName("LewAssignmentNotificationListener")
class LewAssignmentNotificationListenerTest {

    private UserRepository userRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewAssignmentNotificationListener listener;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new LewAssignmentNotificationListener(userRepository, notificationService, emailService);
    }

    private User lewWithSeq(Long seq, String email) {
        User lew = User.builder()
                .email(email).password("h").firstName("Lee").lastName("Wong")
                .build();
        ReflectionTestUtils.setField(lew, "userSeq", seq);
        return lew;
    }

    @Test
    @DisplayName("배정된 LEW 에게 인앱 알림 + 이메일을 발송한다")
    void notifiesInAppAndEmail() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));

        listener.onLewAssigned(new LewAssignedEvent(42L, 7L, "John Tan", "1 Marina Blvd", true));

        verify(notificationService).createNotification(
                eq(7L),
                eq(NotificationType.APPLICATION_LEW_ASSIGNED_LEW),
                anyString(),
                contains("42"),
                eq("APPLICATION"),
                eq(42L));
        verify(emailService).sendLewAssignedEmail(
                eq("lew@x.com"), anyString(), eq(42L), eq("1 Marina Blvd"), eq("John Tan"));
    }

    @Test
    @DisplayName("LEW 가 존재하지 않으면 인앱/이메일 모두 발송하지 않는다")
    void skipsWhenLewNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        listener.onLewAssigned(new LewAssignedEvent(1L, 99L, "X", "addr", false));

        verifyNoInteractions(notificationService);
        verify(emailService, never()).sendLewAssignedEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("이메일이 비어 있으면 이메일은 건너뛰되 인앱 알림은 발송한다")
    void skipsEmailWhenBlank() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "  ")));

        listener.onLewAssigned(new LewAssignedEvent(42L, 7L, "John Tan", "addr", false));

        verify(notificationService).createNotification(eq(7L), any(), anyString(), anyString(), anyString(), eq(42L));
        verify(emailService, never()).sendLewAssignedEmail(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("인앱 알림이 실패해도 이메일 발송은 계속된다 (실패 격리)")
    void emailStillSentWhenInAppFails() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));
        doThrow(new RuntimeException("db down")).when(notificationService)
                .createNotification(anyLong(), any(), anyString(), anyString(), anyString(), anyLong());

        listener.onLewAssigned(new LewAssignedEvent(42L, 7L, "John Tan", "addr", true));

        verify(emailService).sendLewAssignedEmail(eq("lew@x.com"), anyString(), eq(42L), eq("addr"), eq("John Tan"));
    }
}
