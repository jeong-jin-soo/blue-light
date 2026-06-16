package com.bluelight.backend.api.admin;

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
 * {@link LewApprovalNotificationListener} 단위 테스트 — #3 LEW 승인/거절 알림.
 *
 * <p>승인/거절 시 LEW 본인에게 인앱 알림(account-level, referenceType=null) + 이메일이
 * 발송되며, 한 채널 실패가 다른 채널로 전파되지 않는다(실패 격리).</p>
 */
@DisplayName("LewApprovalNotificationListener")
class LewApprovalNotificationListenerTest {

    private UserRepository userRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewApprovalNotificationListener listener;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new LewApprovalNotificationListener(userRepository, notificationService, emailService);
    }

    private User lewWithSeq(Long seq, String email) {
        User lew = User.builder()
                .email(email).password("h").firstName("Lee").lastName("Wong")
                .build();
        ReflectionTestUtils.setField(lew, "userSeq", seq);
        return lew;
    }

    @Test
    @DisplayName("승인 시 LEW_APPROVED 인앱 + 승인 이메일")
    void approved_notifiesInAppAndEmail() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));

        listener.onLewApprovalDecision(new LewApprovalDecisionEvent(7L, true));

        verify(notificationService).createNotification(
                eq(7L), eq(NotificationType.LEW_APPROVED), anyString(), anyString(),
                isNull(), isNull());
        verify(emailService).sendLewApprovalDecisionEmail(eq("lew@x.com"), anyString(), eq(true));
    }

    @Test
    @DisplayName("거절 시 LEW_REJECTED 인앱 + 거절 이메일")
    void rejected_notifiesInAppAndEmail() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));

        listener.onLewApprovalDecision(new LewApprovalDecisionEvent(7L, false));

        verify(notificationService).createNotification(
                eq(7L), eq(NotificationType.LEW_REJECTED), anyString(), anyString(),
                isNull(), isNull());
        verify(emailService).sendLewApprovalDecisionEmail(eq("lew@x.com"), anyString(), eq(false));
    }

    @Test
    @DisplayName("LEW 가 존재하지 않으면 아무 것도 발송하지 않는다")
    void skipsWhenLewNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        listener.onLewApprovalDecision(new LewApprovalDecisionEvent(99L, true));

        verifyNoInteractions(notificationService);
        verify(emailService, never()).sendLewApprovalDecisionEmail(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("인앱 알림이 실패해도 이메일 발송은 계속된다 (실패 격리)")
    void emailStillSentWhenInAppFails() {
        when(userRepository.findById(7L)).thenReturn(Optional.of(lewWithSeq(7L, "lew@x.com")));
        doThrow(new RuntimeException("db down")).when(notificationService)
                .createNotification(anyLong(), any(), anyString(), anyString(), any(), any());

        listener.onLewApprovalDecision(new LewApprovalDecisionEvent(7L, true));

        verify(emailService).sendLewApprovalDecisionEmail(eq("lew@x.com"), anyString(), eq(true));
    }
}
