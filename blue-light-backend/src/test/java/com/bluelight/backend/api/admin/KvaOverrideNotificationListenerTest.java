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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-2 — {@link KvaOverrideNotificationListener} 단위 테스트.
 *
 * <p>책임: ① assignedLewUserSeq 정상 케이스에서 인앱 + 이메일 발송, ② LEW 미배정 스킵,
 * ③ 멱등성, ④ 이메일 실패가 비즈니스/리스너 트랜잭션을 깨뜨리지 않음, ⑤ application lookup
 * 실패 시 인앱은 진행하되 이메일은 스킵.</p>
 */
@DisplayName("KvaOverrideNotificationListener — PR-2")
class KvaOverrideNotificationListenerTest {

    private static final Long APPLICATION_SEQ = 100L;
    private static final Long ADJUSTMENT_SEQ = 42L;
    private static final Long LEW_SEQ = 50L;
    private static final Long ADMIN_SEQ = 99L;

    private ApplicationRepository applicationRepository;
    private NotificationRepository notificationRepository;
    private NotificationService notificationService;
    private EmailService emailService;
    private KvaOverrideNotificationListener listener;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        notificationRepository = mock(NotificationRepository.class);
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        listener = new KvaOverrideNotificationListener(
                applicationRepository, notificationRepository, notificationService, emailService);
    }

    private KvaOverrideAppliedEvent event(Long lewSeq) {
        return new KvaOverrideAppliedEvent(
                APPLICATION_SEQ, ADJUSTMENT_SEQ, lewSeq,
                100, 200,
                new BigDecimal("450.00"), new BigDecimal("650.00"),
                new BigDecimal("200.00"),
                "Site survey: 200 kVA",
                ADMIN_SEQ, "ADMIN");
    }

    private void stubLewAndApp() {
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
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(false);
    }

    @Test
    @DisplayName("정상 케이스 — 인앱 알림 1건 + 이메일 1건 발송")
    void onKvaOverrideApplied_정상() {
        stubLewAndApp();

        listener.onKvaOverrideApplied(event(LEW_SEQ));

        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                anyString(),
                anyString(),
                eq("APPLICATION"),
                eq(APPLICATION_SEQ));
        verify(emailService).sendKvaAdjustedToLewEmail(
                eq("lew@licensekaki.sg"),
                anyString(),
                eq(APPLICATION_SEQ),
                eq(100), eq(200),
                eq(new BigDecimal("450.00")), eq(new BigDecimal("650.00")),
                eq(new BigDecimal("200.00")),
                eq("Site survey: 200 kVA"));
    }

    @Test
    @DisplayName("assignedLewUserSeq=null — 알림·이메일·repository 모두 호출 안 됨")
    void onKvaOverrideApplied_LEW_미배정_스킵() {
        listener.onKvaOverrideApplied(event(null));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaAdjustedToLewEmail(
                anyString(), anyString(), anyLong(), any(), any(), any(), any(), any(), anyString());
        verify(notificationRepository, never())
                .existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                        anyLong(), any(), anyString(), anyLong());
        verify(applicationRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("멱등성 — 동일 application + LEW + type 알림이 이미 존재하면 신규 발송 없음")
    void onKvaOverrideApplied_멱등성() {
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(true);

        listener.onKvaOverrideApplied(event(LEW_SEQ));

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendKvaAdjustedToLewEmail(
                anyString(), anyString(), anyLong(), any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("이메일 송신 실패 — 인앱 알림은 정상 생성, 리스너에서 예외가 새어 나오지 않음")
    void onKvaOverrideApplied_이메일_실패() {
        stubLewAndApp();

        // 이메일이 RuntimeException 을 던져도 리스너는 swallow.
        doThrow(new RuntimeException("SMTP down"))
                .when(emailService).sendKvaAdjustedToLewEmail(
                        anyString(), anyString(), anyLong(), any(), any(), any(), any(), any(), anyString());

        // 예외가 호출자(이벤트 디스패처)로 전파되지 않아야 비즈니스 트랜잭션 결과가 영향을 받지 않음.
        listener.onKvaOverrideApplied(event(LEW_SEQ));

        // 인앱 알림은 이메일 실패와 독립적으로 호출되어야 한다 (둘은 독립 채널)
        verify(notificationService).createNotification(
                eq(LEW_SEQ),
                eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                anyString(),
                anyString(),
                eq("APPLICATION"),
                eq(APPLICATION_SEQ));
    }

    @Test
    @DisplayName("인앱 알림 실패 — 이메일은 별도 채널로 발송 시도됨")
    void onKvaOverrideApplied_인앱_실패해도_이메일은_시도() {
        stubLewAndApp();
        doThrow(new RuntimeException("DB down"))
                .when(notificationService).createNotification(
                        anyLong(), any(), anyString(), anyString(), anyString(), anyLong());

        listener.onKvaOverrideApplied(event(LEW_SEQ));

        // 이메일은 시도되어야 한다.
        verify(emailService).sendKvaAdjustedToLewEmail(
                eq("lew@licensekaki.sg"), anyString(), eq(APPLICATION_SEQ),
                any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Application lookup 실패 — 인앱 알림은 진행, 이메일은 스킵")
    void onKvaOverrideApplied_application_없음_이메일_스킵() {
        // notificationRepository 멱등성 체크는 통과해야 인앱 알림 단계로 진입
        when(notificationRepository.existsByRecipientUserSeqAndTypeAndReferenceTypeAndReferenceId(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                eq("APPLICATION"), eq(APPLICATION_SEQ)))
                .thenReturn(false);
        // application 이 사라진 경우 (race: 동시에 삭제됐다고 가정)
        when(applicationRepository.findById(APPLICATION_SEQ)).thenReturn(Optional.empty());

        listener.onKvaOverrideApplied(event(LEW_SEQ));

        // 인앱 알림은 정상 호출됨 (event payload 만으로 본문 구성 가능)
        verify(notificationService).createNotification(
                eq(LEW_SEQ), eq(NotificationType.KVA_ADJUSTED_BY_ADMIN_LEW),
                anyString(), anyString(), eq("APPLICATION"), eq(APPLICATION_SEQ));
        // 이메일은 발송 대상자(email/이름)를 알 수 없으므로 스킵
        verify(emailService, never()).sendKvaAdjustedToLewEmail(
                anyString(), anyString(), anyLong(),
                any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("인앱 본문 — 'Previous: 100kVA → New: 200kVA' 표기 포함")
    void onKvaOverrideApplied_인앱_본문_kVA_표기() {
        stubLewAndApp();

        listener.onKvaOverrideApplied(event(LEW_SEQ));

        verify(notificationService).createNotification(
                eq(LEW_SEQ), any(), anyString(),
                contains("Previous: 100kVA → New: 200kVA"),
                anyString(), anyLong());
    }
}
