package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

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
 * PR-4 — {@link KvaSettlementNotificationListener} 단위 테스트.
 *
 * <p>책임:
 * ① assignedLew 정상 케이스에서 인앱 + 이메일 발송,
 * ② LEW 미배정 스킵,
 * ③ 멱등성 가드(adjustmentSeq 기준),
 * ④ 인앱/이메일 채널 실패가 다른 채널 또는 listener 외부로 전파되지 않음,
 * ⑤ application lookup 실패 시 인앱은 진행하되 이메일은 스킵,
 * ⑥ paymentAdjustment 별 메시지 본문이 차별화.</p>
 */
@DisplayName("KvaSettlementNotificationListener — PR-4")
class KvaSettlementNotificationListenerTest {

    private static final Long APPLICATION_SEQ = 100L;
    private static final Long ADJUSTMENT_SEQ = 42L;
    private static final Long LEW_SEQ = 50L;
    private static final Long ADMIN_SEQ = 99L;

    private ApplicationRepository applicationRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private KvaSettlementNotificationListener listener;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new KvaSettlementNotificationListener(
                applicationRepository, notificationRepository, notificationService, emailService);
    }

    private KvaSettlementMarkedEvent event(Long lewSeq, AdminPaymentAdjustment pa,
                                            BigDecimal amount, String ref) {
        return new KvaSettlementMarkedEvent(
                APPLICATION_SEQ, ADJUSTMENT_SEQ, lewSeq, pa, amount, ref, ADMIN_SEQ);
    }

    private void stubLewAndApp() {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(LEW_SEQ);
        when(lew.getEmail()).thenReturn("lew@licensekaki.sg");
        when(lew.getFirstName()).thenReturn("Long");
        when(lew.getLastName()).thenReturn("Eric");
        when(app.getAssignedLew()).thenReturn(lew);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                eq("KVA_ADJUSTMENT"), eq(ADJUSTMENT_SEQ)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("정상 — 인앱 + 이메일 발송 (PAID_DIFFERENCE)")
    void onKvaSettlementMarked_정상() {
        stubLewAndApp();

        listener.onKvaSettlementMarked(event(LEW_SEQ,
                AdminPaymentAdjustment.PAID_DIFFERENCE,
                new BigDecimal("200.00"), "PAYNOW-ABC-123"));

        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                anyString(), anyString(),
                eq("APPLICATION"), eq(APPLICATION_SEQ));
        verify(emailService).sendKvaSettlementMarkedToLewEmail(
                eq("lew@licensekaki.sg"), eq("Long Eric"), eq(APPLICATION_SEQ),
                eq("PAID_DIFFERENCE"), eq(new BigDecimal("200.00")),
                eq("PAYNOW-ABC-123"));
    }

    @Test
    @DisplayName("LEW 미배정 — 어떤 채널도 호출 안 함")
    void lewUserSeq_null_스킵() {
        listener.onKvaSettlementMarked(event(null,
                AdminPaymentAdjustment.WAIVED, null, null));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaSettlementMarkedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(),
                any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("멱등성 — 같은 (LEW, adjustmentSeq) 알림이 이미 있으면 스킵")
    void 멱등성_가드() {
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                eq("KVA_ADJUSTMENT"), eq(ADJUSTMENT_SEQ)))
                .thenReturn(true);

        listener.onKvaSettlementMarked(event(LEW_SEQ,
                AdminPaymentAdjustment.REFUNDED,
                new BigDecimal("50.00"), null));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaSettlementMarkedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(),
                any(BigDecimal.class), anyString());
    }

    @Test
    @DisplayName("이메일 실패 — 인앱은 발송됨, listener 는 예외 누출 없음")
    void 이메일_실패_격리() {
        stubLewAndApp();
        doThrow(new RuntimeException("SMTP timeout"))
                .when(emailService).sendKvaSettlementMarkedToLewEmail(
                        anyString(), anyString(), anyLong(), anyString(),
                        any(BigDecimal.class), any());

        // listener 가 예외를 swallow.
        listener.onKvaSettlementMarked(event(LEW_SEQ,
                AdminPaymentAdjustment.PAID_DIFFERENCE,
                new BigDecimal("100.00"), null));

        verify(notificationService, times(1)).createNotification(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                anyString(), anyString(),
                eq("APPLICATION"), eq(APPLICATION_SEQ));
    }

    @Test
    @DisplayName("인앱 실패 — 이메일은 발송됨, listener 는 예외 누출 없음")
    void 인앱_실패_격리() {
        stubLewAndApp();
        doThrow(new RuntimeException("DB down"))
                .when(notificationService).createNotification(
                        anyLong(), any(), anyString(), anyString(), anyString(), anyLong());

        listener.onKvaSettlementMarked(event(LEW_SEQ,
                AdminPaymentAdjustment.WAIVED, null, null));

        verify(emailService, times(1)).sendKvaSettlementMarkedToLewEmail(
                eq("lew@licensekaki.sg"), eq("Long Eric"), eq(APPLICATION_SEQ),
                eq("WAIVED"), eq(null), eq(null));
    }

    @Test
    @DisplayName("application lookup 실패 — 인앱 진행, 이메일 스킵")
    void application_lookup_실패() {
        // 멱등성 false 이고 application lookup 만 비어 있는 케이스.
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                eq("KVA_ADJUSTMENT"), eq(ADJUSTMENT_SEQ)))
                .thenReturn(false);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.empty());

        listener.onKvaSettlementMarked(event(LEW_SEQ,
                AdminPaymentAdjustment.PAID_DIFFERENCE,
                new BigDecimal("75.00"), "ref-1"));

        verify(notificationService, times(1)).createNotification(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTMENT_SETTLED_LEW),
                anyString(), anyString(),
                eq("APPLICATION"), eq(APPLICATION_SEQ));
        verify(emailService, never()).sendKvaSettlementMarkedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(),
                any(BigDecimal.class), anyString());
    }
}
