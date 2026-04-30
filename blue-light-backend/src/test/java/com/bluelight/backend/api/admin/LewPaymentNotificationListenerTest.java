package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.notification.NotificationRepository;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR4 — LewPaymentNotificationListener 단위 테스트.
 *
 * <p>리스너의 책임은 4가지: ① assignedLew 정상 케이스에서 인앱+이메일 1쌍 발송, ② LEW 미배정 스킵,
 * ③ 멱등성(이미 알림 존재 시 스킵), ④ 이메일 실패가 트랜잭션을 깨뜨리지 않음. 이 4가지를 단위 테스트로
 * 직접 검증한다.</p>
 */
@DisplayName("LewPaymentNotificationListener — PR4")
class LewPaymentNotificationListenerTest {

    private static final Long APPLICATION_SEQ = 100L;
    private static final Long PAYMENT_SEQ = 200L;
    private static final Long LEW_SEQ = 50L;

    private ApplicationRepository applicationRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private LewPaymentNotificationListener listener;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new LewPaymentNotificationListener(
                applicationRepository, notificationRepository, notificationService, emailService);
    }

    @Test
    @DisplayName("assignedLew 정상 케이스 — 인앱 알림 1건 + 이메일 1건 발송")
    void onPaymentConfirmed_정상() {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(LEW_SEQ);
        when(lew.getEmail()).thenReturn("lew@licensekaki.sg");
        when(lew.getFirstName()).thenReturn("Long");
        when(lew.getLastName()).thenReturn("Eric");
        when(app.getAssignedLew()).thenReturn(lew);
        when(app.getAddress()).thenReturn("123 Orchard Road");
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.PAYMENT_CONFIRMED_LEW),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(false);

        listener.onPaymentConfirmed(new PaymentConfirmedEvent(
                APPLICATION_SEQ, PAYMENT_SEQ, new BigDecimal("1500.00"), LocalDateTime.now()));

        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.PAYMENT_CONFIRMED_LEW),
                anyString(),
                anyString(),
                eq("APPLICATION"),
                eq(APPLICATION_SEQ));
        verify(emailService).sendPaymentConfirmedToLewEmail(
                eq("lew@licensekaki.sg"),
                anyString(),
                eq(APPLICATION_SEQ),
                eq("123 Orchard Road"),
                eq(new BigDecimal("1500.00")));
    }

    @Test
    @DisplayName("assignedLew=null — 알림·이메일 모두 발송 안 됨 (audit skip 로그만)")
    void onPaymentConfirmed_LEW_미배정_스킵() {
        Application app = mock(Application.class);
        when(app.getAssignedLew()).thenReturn(null);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));

        listener.onPaymentConfirmed(new PaymentConfirmedEvent(
                APPLICATION_SEQ, PAYMENT_SEQ, BigDecimal.ONE, LocalDateTime.now()));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendPaymentConfirmedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(), any());
        // 멱등성 체크에 도달하지 않아야 함 (assignedLew null 이 먼저 걸러짐)
        verify(notificationRepository, never())
                .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                        anyLong(), any(), anyString(), anyLong());
    }

    @Test
    @DisplayName("멱등성 — 동일 application + LEW + type 알림이 이미 존재하면 신규 발송 없음")
    void onPaymentConfirmed_멱등성() {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(LEW_SEQ);
        when(app.getAssignedLew()).thenReturn(lew);
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.PAYMENT_CONFIRMED_LEW),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(true);

        listener.onPaymentConfirmed(new PaymentConfirmedEvent(
                APPLICATION_SEQ, PAYMENT_SEQ, BigDecimal.ONE, LocalDateTime.now()));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendPaymentConfirmedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("이메일 송신 실패 — 인앱 알림은 정상 생성, 리스너에서 예외가 새어 나오지 않음")
    void onPaymentConfirmed_이메일_실패() {
        Application app = mock(Application.class);
        User lew = mock(User.class);
        when(lew.getUserSeq()).thenReturn(LEW_SEQ);
        when(lew.getEmail()).thenReturn("lew@licensekaki.sg");
        when(lew.getFirstName()).thenReturn("Long");
        when(lew.getLastName()).thenReturn("Eric");
        when(app.getAssignedLew()).thenReturn(lew);
        when(app.getAddress()).thenReturn("Anywhere");
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.of(app));
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                anyLong(), any(), anyString(), anyLong()))
                .thenReturn(false);

        // 이메일이 RuntimeException 을 던져도 리스너는 swallow 해야 한다.
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).sendPaymentConfirmedToLewEmail(
                        anyString(), anyString(), anyLong(), anyString(), any());

        // 예외가 호출자(이벤트 디스패처)로 전파되지 않아야 결제 트랜잭션이 영향을 안 받는다.
        listener.onPaymentConfirmed(new PaymentConfirmedEvent(
                APPLICATION_SEQ, PAYMENT_SEQ, BigDecimal.ONE, LocalDateTime.now()));

        // 인앱 알림은 이메일 실패와 독립적으로 호출되어야 한다 (둘은 독립 채널)
        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.PAYMENT_CONFIRMED_LEW),
                anyString(),
                anyString(),
                eq("APPLICATION"),
                eq(APPLICATION_SEQ));
    }

    @Test
    @DisplayName("Application 자체가 사라진 경우 — 발송 없음, 예외 없음")
    void onPaymentConfirmed_application_없음() {
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.empty());

        listener.onPaymentConfirmed(new PaymentConfirmedEvent(
                APPLICATION_SEQ, PAYMENT_SEQ, BigDecimal.ONE, LocalDateTime.now()));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendPaymentConfirmedToLewEmail(
                anyString(), anyString(), anyLong(), anyString(), any());
    }
}
