package com.bluelight.backend.api.lew;

import com.bluelight.backend.api.email.EmailService;
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

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-3 — {@link LewKvaAdjustmentRequestNotificationListener} 단위 테스트.
 *
 * <p>책임: ① ADMIN/SYSTEM_ADMIN 활성 사용자 전체에게 인앱 + 이메일 발송, ② 멱등성, ③ 단일 ADMIN 실패 격리,
 * ④ 인앱/이메일 채널 실패가 다른 채널 또는 다른 ADMIN 으로 전파되지 않음.</p>
 */
@DisplayName("LewKvaAdjustmentRequestNotificationListener — PR-3")
class LewKvaAdjustmentRequestNotificationListenerTest {

    private static final Long APPLICATION_SEQ = 100L;
    private static final Long ADJUSTMENT_SEQ = 42L;
    private static final Long LEW_SEQ = 50L;

    private UserRepository userRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewKvaAdjustmentRequestNotificationListener listener;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new LewKvaAdjustmentRequestNotificationListener(
                userRepository, notificationRepository, notificationService, emailService);
    }

    private LewKvaAdjustmentRequestedEvent event() {
        return new LewKvaAdjustmentRequestedEvent(
                APPLICATION_SEQ,
                ADJUSTMENT_SEQ,
                LEW_SEQ,
                "Long Eric",
                200,
                100,
                "Site survey: actual load 180 kVA");
    }

    private User mockAdmin(Long seq, String email, String first, String last) {
        User u = mock(User.class);
        when(u.getUserSeq()).thenReturn(seq);
        when(u.getEmail()).thenReturn(email);
        when(u.getFirstName()).thenReturn(first);
        when(u.getLastName()).thenReturn(last);
        return u;
    }

    private void stubAdminQuery(List<User> admins) {
        when(userRepository.findByRoleInAndStatus(
                eq(List.of(UserRole.ADMIN, UserRole.SYSTEM_ADMIN)), eq(UserStatus.ACTIVE)))
                .thenReturn(admins);
    }

    private void stubIdempotencyFalse(Long adminSeq) {
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(adminSeq), eq(NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("정상 — ADMIN/SYSTEM_ADMIN 두 명에게 각각 인앱 + 이메일 발송")
    void onLewKvaAdjustmentRequested_정상() {
        User admin = mockAdmin(11L, "admin@licensekaki.sg", "Linda", "Tan");
        User sysAdmin = mockAdmin(12L, "sys@licensekaki.sg", "Sys", "Admin");
        stubAdminQuery(List.of(admin, sysAdmin));
        stubIdempotencyFalse(11L);
        stubIdempotencyFalse(12L);

        listener.onLewKvaAdjustmentRequested(event());

        // 두 ADMIN 모두에게 인앱 알림 호출됨
        verify(notificationService, times(2)).createNotification(
                anyLong(), eq(NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN),
                anyString(), anyString(), eq("APPLICATION"), eq(APPLICATION_SEQ));
        // 이메일도 두 번
        verify(emailService).sendKvaAdjustmentRequestedToAdminEmail(
                eq("admin@licensekaki.sg"), anyString(), eq("Long Eric"),
                eq(APPLICATION_SEQ), eq(200), eq(100), anyString());
        verify(emailService).sendKvaAdjustmentRequestedToAdminEmail(
                eq("sys@licensekaki.sg"), anyString(), eq("Long Eric"),
                eq(APPLICATION_SEQ), eq(200), eq(100), anyString());
    }

    @Test
    @DisplayName("ADMIN 0명 — 호출 안 됨")
    void onLewKvaAdjustmentRequested_수신자_없음() {
        stubAdminQuery(List.of());

        listener.onLewKvaAdjustmentRequested(event());

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaAdjustmentRequestedToAdminEmail(
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("멱등성 — 이미 알림 받은 ADMIN 은 스킵")
    void onLewKvaAdjustmentRequested_멱등성() {
        User admin = mockAdmin(11L, "admin@licensekaki.sg", "Linda", "Tan");
        stubAdminQuery(List.of(admin));
        // 이미 알림 존재
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(11L), eq(NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(true);

        listener.onLewKvaAdjustmentRequested(event());

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaAdjustmentRequestedToAdminEmail(
                anyString(), anyString(), anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    @DisplayName("실패 격리 — 한 ADMIN 인앱 실패가 다른 ADMIN 의 발송을 막지 않음")
    void onLewKvaAdjustmentRequested_단일_ADMIN_실패_격리() {
        User admin1 = mockAdmin(11L, "admin1@licensekaki.sg", "A", "One");
        User admin2 = mockAdmin(12L, "admin2@licensekaki.sg", "B", "Two");
        stubAdminQuery(List.of(admin1, admin2));
        stubIdempotencyFalse(11L);
        stubIdempotencyFalse(12L);
        // admin1 인앱 호출만 실패
        doThrow(new RuntimeException("DB down for admin1"))
                .when(notificationService).createNotification(
                        eq(11L), any(), anyString(), anyString(), anyString(), anyLong());

        // 예외가 호출자(이벤트 디스패처)로 전파되지 않아야 한다.
        listener.onLewKvaAdjustmentRequested(event());

        // admin2 의 인앱은 정상 호출되어야 한다 (실패 격리)
        verify(notificationService).createNotification(
                eq(12L), eq(NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN),
                anyString(), anyString(), eq("APPLICATION"), eq(APPLICATION_SEQ));
        // admin1 의 이메일은 인앱 실패와 독립적으로 시도됨
        verify(emailService).sendKvaAdjustmentRequestedToAdminEmail(
                eq("admin1@licensekaki.sg"), anyString(), anyString(),
                eq(APPLICATION_SEQ), any(), any(), anyString());
        // admin2 이메일도 호출
        verify(emailService).sendKvaAdjustmentRequestedToAdminEmail(
                eq("admin2@licensekaki.sg"), anyString(), anyString(),
                eq(APPLICATION_SEQ), any(), any(), anyString());
    }

    @Test
    @DisplayName("이메일 송신 실패 — 인앱 알림은 정상, 리스너 외부로 예외 누출 없음")
    void onLewKvaAdjustmentRequested_이메일_실패() {
        User admin = mockAdmin(11L, "admin@licensekaki.sg", "Linda", "Tan");
        stubAdminQuery(List.of(admin));
        stubIdempotencyFalse(11L);
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).sendKvaAdjustmentRequestedToAdminEmail(
                        anyString(), anyString(), anyString(),
                        anyLong(), any(), any(), anyString());

        listener.onLewKvaAdjustmentRequested(event());

        verify(notificationService).createNotification(
                eq(11L), eq(NotificationType.KVA_ADJUSTMENT_REQUESTED_ADMIN),
                anyString(), anyString(), eq("APPLICATION"), eq(APPLICATION_SEQ));
    }
}
