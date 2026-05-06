package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.manualemail.RecipientType;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-4 — {@link ManualEmailDispatchSendListener} 인앱 알림 동반 단위 테스트.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8.5 / AC-A11 / D4=B.</p>
 *
 * <h3>검증</h3>
 * <ol>
 *   <li>단일 시스템 사용자(APPLICANT) + alsoCreateInAppNotification=true → Notification row 1건 생성.</li>
 *   <li>EXTERNAL 단일 발송 → Notification 미생성 (시스템 사용자 없음).</li>
 *   <li>MULTI 혼합 (시스템 2 + 외부 1) → Notification 2건만 (시스템 사용자에 대해서만).</li>
 *   <li>alsoCreateInAppNotification=false → Notification 미생성 (옵션 OFF).</li>
 *   <li>relatedApplicationSeq 있음 → referenceType=APPLICATION + referenceId=appSeq.</li>
 *   <li>relatedApplicationSeq 없음 → referenceType=MANUAL_EMAIL + referenceId=dispatchSeq.</li>
 *   <li>NotificationService 예외 → 다른 사용자 알림은 계속 진행 (실패 격리).</li>
 * </ol>
 */
@DisplayName("ManualEmailDispatchSendListener — PR-4 인앱 동반")
class ManualEmailInAppNotificationTest {

    private static final long DISPATCH_SEQ = 88L;
    private static final long ADMIN_SEQ = 99L;
    private static final long APPLICANT_SEQ = 12L;
    private static final long LEW_SEQ = 45L;
    private static final long APPLICATION_SEQ = 1234L;

    private ManualEmailDispatchRepository dispatchRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private ManualEmailDispatchStatusUpdater statusUpdater;
    private NotificationService notificationService;
    private ManualEmailDispatchSendListener listener;

    @BeforeEach
    void setUp() {
        dispatchRepository = mock(ManualEmailDispatchRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        statusUpdater = mock(ManualEmailDispatchStatusUpdater.class);
        notificationService = mock(NotificationService.class);
        listener = new ManualEmailDispatchSendListener(
                dispatchRepository, userRepository, emailService, statusUpdater, notificationService);

        // ADMIN 이메일 lookup
        User admin = mock(User.class);
        when(admin.getEmail()).thenReturn("admin@licensekaki.sg");
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
    }

    private ManualEmailDispatch singleApplicantRow(boolean alsoInApp, Long relatedAppSeq) {
        ManualEmailDispatch row = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.APPLICANT)
                .recipientUserSeq(APPLICANT_SEQ)
                .recipientEmail("alice@example.com")
                .relatedApplicationSeq(relatedAppSeq)
                .subject("Maintenance notice")
                .bodyText("We will undergo maintenance tomorrow.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .alsoCreateInAppNotification(alsoInApp)
                .build();
        injectDispatchSeq(row, DISPATCH_SEQ);
        return row;
    }

    private ManualEmailDispatch externalRow() {
        return ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.EXTERNAL)
                .recipientEmail("partner@spgroup.com.sg")
                .subject("External notice")
                .bodyText("Hello partner.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .alsoCreateInAppNotification(true)  // EXTERNAL 도 옵션은 true 지만 listener 가 자동 스킵.
                .build();
    }

    private ManualEmailDispatch multiMixRow() {
        ManualEmailDispatch row = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.MULTI)
                .recipientEmail("alice@example.com")
                .recipientUserSeqsJson(List.of(APPLICANT_SEQ, LEW_SEQ))
                .recipientEmailsJson(List.of("alice@example.com", "bob@example.com", "ext@example.com"))
                // MULTI 도 relatedApplicationSeq 가 있을 수 있음 — 본 테스트는 referenceId 매처 호환을
                // 위해 1234L 채워둔다. (refType=APPLICATION 라우팅 검증).
                .relatedApplicationSeq(APPLICATION_SEQ)
                .subject("Batch")
                .bodyText("Hi all.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .alsoCreateInAppNotification(true)
                .build();
        // dispatchSeq 도 reflection 으로 주입 — 일부 분기에서 referenceId fallback 으로 사용됨.
        injectDispatchSeq(row, DISPATCH_SEQ);
        return row;
    }

    @Test
    @DisplayName("AC-A11: 단일 APPLICANT — Notification row 1건 + referenceType=APPLICATION (relatedAppSeq 있음)")
    void inApp_단일APPLICANT_relatedApp() {
        ManualEmailDispatch entity = singleApplicantRow(true, APPLICATION_SEQ);
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        // Notification 1건 — APPLICANT 에게.
        ArgumentCaptor<Long> recipient = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<NotificationType> type = ArgumentCaptor.forClass(NotificationType.class);
        ArgumentCaptor<String> refType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> refId = ArgumentCaptor.forClass(Long.class);
        verify(notificationService).createNotification(
                recipient.capture(), type.capture(), anyString(), anyString(),
                refType.capture(), refId.capture());
        assertThat(recipient.getValue()).isEqualTo(APPLICANT_SEQ);
        assertThat(type.getValue()).isEqualTo(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE);
        assertThat(refType.getValue()).isEqualTo("APPLICATION");
        assertThat(refId.getValue()).isEqualTo(APPLICATION_SEQ);
    }

    @Test
    @DisplayName("relatedAppSeq 없음 — referenceType=MANUAL_EMAIL + referenceId=dispatchSeq")
    void inApp_relatedApp없음_dispatchSeq라우팅() {
        // singleApplicantRow 가 이미 reflection 으로 dispatchSeq 를 주입한다.
        ManualEmailDispatch entity = singleApplicantRow(true, /*relatedAppSeq*/ null);
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        ArgumentCaptor<String> refType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> refId = ArgumentCaptor.forClass(Long.class);
        verify(notificationService).createNotification(
                eq(APPLICANT_SEQ), eq(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE),
                anyString(), anyString(), refType.capture(), refId.capture());
        assertThat(refType.getValue()).isEqualTo("MANUAL_EMAIL");
        assertThat(refId.getValue()).isEqualTo(DISPATCH_SEQ);
    }

    @Test
    @DisplayName("EXTERNAL 단일 — Notification 미생성 (시스템 사용자 없음)")
    void inApp_EXTERNAL_미생성() {
        ManualEmailDispatch entity = externalRow();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(notificationService, never()).createNotification(
                anyLong(), any(NotificationType.class), anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("MULTI 혼합 — 시스템 사용자에 대해서만 Notification 2건 (외부 이메일은 무시)")
    void inApp_MULTI_시스템사용자만() {
        ManualEmailDispatch entity = multiMixRow();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        // user_seq 두 개에 대해 각각 1번씩 호출 — 외부 이메일은 미포함.
        verify(notificationService, times(2)).createNotification(
                anyLong(), eq(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE),
                anyString(), anyString(), anyString(), anyLong());
        verify(notificationService).createNotification(
                eq(APPLICANT_SEQ), eq(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE),
                anyString(), anyString(), anyString(), anyLong());
        verify(notificationService).createNotification(
                eq(LEW_SEQ), eq(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE),
                anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    @DisplayName("alsoCreateInAppNotification=false — Notification 미생성")
    void inApp_옵션OFF_미생성() {
        ManualEmailDispatch entity = singleApplicantRow(/*alsoInApp*/ false, APPLICATION_SEQ);
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(notificationService, never()).createNotification(
                anyLong(), any(NotificationType.class), anyString(), anyString(), anyString(), anyLong());
        // SMTP 발송은 정상적으로 일어났는지 확인 — 옵션이 인앱에만 영향, 이메일 발송은 항상.
        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(1), eq(0), eq(null));
    }

    @Test
    @DisplayName("NotificationService 예외 — 다른 사용자 알림은 계속 진행 (실패 격리)")
    void inApp_부분실패_격리() {
        ManualEmailDispatch entity = multiMixRow();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));
        // 첫 번째(APPLICANT_SEQ) 호출만 실패하도록 stub.
        org.mockito.Mockito.doThrow(new RuntimeException("DB temp glitch"))
                .when(notificationService).createNotification(
                        eq(APPLICANT_SEQ), any(NotificationType.class), anyString(), anyString(),
                        anyString(), anyLong());

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        // 두 번째(LEW_SEQ) 호출은 발생해야 한다.
        verify(notificationService).createNotification(
                eq(LEW_SEQ), eq(NotificationType.ADMIN_MANUAL_EMAIL_NOTICE),
                anyString(), anyString(), anyString(), anyLong());
    }

    /**
     * ManualEmailDispatch.dispatchSeq 는 @GeneratedValue 라 builder 에서 직접 주입 불가 — 본 단위
     * 테스트는 listener 동작 검증용이므로 reflection 으로 주입한다.
     */
    private static void injectDispatchSeq(ManualEmailDispatch row, long seq) {
        try {
            java.lang.reflect.Field f = ManualEmailDispatch.class.getDeclaredField("dispatchSeq");
            f.setAccessible(true);
            f.set(row, seq);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }
}
