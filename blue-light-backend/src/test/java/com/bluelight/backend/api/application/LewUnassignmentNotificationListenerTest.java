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
 * {@link LewUnassignmentNotificationListener} 단위 테스트 — #4 떠나는 LEW 알림.
 *
 * <p>배정 해제/재배정 시 떠나는 LEW 에게 인앱(account-level, referenceType=null) + 이메일 통지,
 * 채널 실패 격리를 검증한다.</p>
 */
@DisplayName("LewUnassignmentNotificationListener")
class LewUnassignmentNotificationListenerTest {

    private UserRepository userRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewUnassignmentNotificationListener listener;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new LewUnassignmentNotificationListener(userRepository, notificationService, emailService);
    }

    private User lewWithSeq(Long seq, String email) {
        User lew = User.builder()
                .email(email).password("h").firstName("Lee").lastName("Wong")
                .build();
        ReflectionTestUtils.setField(lew, "userSeq", seq);
        return lew;
    }

    @Test
    @DisplayName("해제 시 떠나는 LEW 에게 인앱(딥링크 없음) + 이메일")
    void unassigned_notifiesDepartingLew() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));

        listener.onLewUnassigned(new LewUnassignedEvent(42L, 7L, false));

        verify(notificationService).createNotification(
                eq(7L), eq(NotificationType.APPLICATION_LEW_UNASSIGNED_LEW),
                anyString(), contains("42"),
                isNull(), isNull());
        verify(emailService).sendLewUnassignedEmail(eq("lew@x.com"), anyString(), eq(42L), eq(false));
    }

    @Test
    @DisplayName("재배정이면 이메일에 reassigned=true 전달")
    void reassigned_passesFlag() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));

        listener.onLewUnassigned(new LewUnassignedEvent(42L, 7L, true));

        verify(emailService).sendLewUnassignedEmail(eq("lew@x.com"), anyString(), eq(42L), eq(true));
    }

    @Test
    @DisplayName("LEW 가 존재하지 않으면 아무 것도 발송하지 않는다")
    void skipsWhenLewNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        listener.onLewUnassigned(new LewUnassignedEvent(1L, 99L, false));

        verifyNoInteractions(notificationService);
        verify(emailService, never()).sendLewUnassignedEmail(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("인앱 알림이 실패해도 이메일 발송은 계속된다 (실패 격리)")
    void emailStillSentWhenInAppFails() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));
        doThrow(new RuntimeException("db down")).when(notificationService)
                .createNotification(anyLong(), any(), anyString(), anyString(), any(), any());

        listener.onLewUnassigned(new LewUnassignedEvent(42L, 7L, false));

        verify(emailService).sendLewUnassignedEmail(eq("lew@x.com"), anyString(), eq(42L), eq(false));
    }
}
