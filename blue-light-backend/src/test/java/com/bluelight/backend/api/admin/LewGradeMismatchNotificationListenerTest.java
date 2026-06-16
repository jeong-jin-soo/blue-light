package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link LewGradeMismatchNotificationListener} 단위 테스트 (#5) — ADMIN 인앱 경고 브로드캐스트.
 */
@DisplayName("LewGradeMismatchNotificationListener")
class LewGradeMismatchNotificationListenerTest {

    private UserRepository userRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private LewGradeMismatchNotificationListener listener;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        listener = new LewGradeMismatchNotificationListener(
                userRepository, notificationRepository, notificationService);
    }

    private User admin(Long seq) {
        User u = User.builder().email("a" + seq + "@x.com").password("h")
                .firstName("Ad").lastName("Min").role(UserRole.ADMIN).build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private LewGradeMismatchEvent event() {
        return new LewGradeMismatchEvent(42L, 7L, "GRADE_7", 45, 100);
    }

    @Test
    @DisplayName("활성 ADMIN 전원에게 인앱 경고를 보낸다")
    void notifiesAllAdmins() {
        when(userRepository.findByRoleInAndStatus(
                List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN), UserStatus.ACTIVE))
                .thenReturn(List.of(admin(1L), admin(2L)));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                anyLong(), any(), anyString(), anyLong())).thenReturn(false);

        listener.onLewGradeMismatch(event());

        verify(notificationService).createNotification(
                eq(1L), eq(NotificationType.LEW_GRADE_MISMATCH_ADMIN),
                anyString(), contains("100"), eq("APPLICATION"), eq(42L));
        verify(notificationService).createNotification(
                eq(2L), eq(NotificationType.LEW_GRADE_MISMATCH_ADMIN),
                anyString(), anyString(), eq("APPLICATION"), eq(42L));
    }

    @Test
    @DisplayName("멱등성: 이미 알림이 있으면 스킵")
    void idempotentSkip() {
        when(userRepository.findByRoleInAndStatus(anyList(), eq(UserStatus.ACTIVE)))
                .thenReturn(List.of(admin(1L)));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(1L), any(), anyString(), eq(42L))).thenReturn(true);

        listener.onLewGradeMismatch(event());

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("활성 ADMIN 이 없으면 아무 것도 하지 않는다")
    void noAdmins() {
        when(userRepository.findByRoleInAndStatus(anyList(), eq(UserStatus.ACTIVE)))
                .thenReturn(List.of());

        listener.onLewGradeMismatch(event());

        verifyNoInteractions(notificationService);
    }

    @Test
    @DisplayName("한 ADMIN 알림 실패가 다른 ADMIN 으로 전파되지 않는다 (격리)")
    void failureIsolation() {
        when(userRepository.findByRoleInAndStatus(anyList(), eq(UserStatus.ACTIVE)))
                .thenReturn(List.of(admin(1L), admin(2L)));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                anyLong(), any(), anyString(), anyLong())).thenReturn(false);
        doThrow(new RuntimeException("boom")).when(notificationService).createNotification(
                eq(1L), any(), anyString(), anyString(), anyString(), anyLong());

        listener.onLewGradeMismatch(event());

        verify(notificationService).createNotification(
                eq(2L), any(), anyString(), anyString(), anyString(), anyLong());
    }
}
