package com.bluelight.backend.api.document;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.notification.NotificationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.document.DocumentRequest;
import com.bluelight.backend.domain.notification.NotificationType;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 3 PR#4 — DocumentRequestNotifier 의 상태 전이별 발송 동작 검증.
 *
 * <p>활성 트랜잭션이 없는 상황에서는 {@code afterCommit} 즉시 실행 경로가 동작하므로
 * 동일 스레드에서 바로 호출되었는지를 검증할 수 있다.
 */
class DocumentRequestNotifierTest {

    private NotificationService notificationService;
    private EmailService emailService;
    private DocumentRequestNotifier notifier;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        emailService = mock(EmailService.class);
        notifier = new DocumentRequestNotifier(notificationService, emailService);
    }

    private Application mockApplication(Long appSeq, User applicant, User lew) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(appSeq);
        when(app.getUser()).thenReturn(applicant);
        when(app.getAssignedLew()).thenReturn(lew);
        return app;
    }

    private User mockUser(Long seq, String email, String first, String last) {
        User u = mock(User.class);
        when(u.getUserSeq()).thenReturn(seq);
        when(u.getEmail()).thenReturn(email);
        when(u.getFullName()).thenReturn(first + " " + last);
        return u;
    }

    private DocumentRequest mockDr(Long id, Application app, String code, String customLabel, String reason) {
        DocumentRequest dr = mock(DocumentRequest.class);
        when(dr.getId()).thenReturn(id);
        when(dr.getApplication()).thenReturn(app);
        when(dr.getDocumentTypeCode()).thenReturn(code);
        when(dr.getCustomLabel()).thenReturn(customLabel);
        when(dr.getRejectionReason()).thenReturn(reason);
        return dr;
    }

    @Test
    void 요청_생성시_신청자에게_인앱_이메일_모두_발송() {
        User applicant = mockUser(10L, "applicant@example.com", "John", "Tan");
        Application app = mockApplication(42L, applicant, null);
        DocumentRequest dr1 = mockDr(1L, app, "LOA", null, null);
        DocumentRequest dr2 = mockDr(2L, app, "OTHER", "Renovation plan", null);

        notifier.notifyCreated(app, List.of(dr1, dr2));

        verify(notificationService).createNotification(
                eq(10L),
                eq(NotificationType.DOCUMENT_REQUEST_CREATED),
                anyString(),
                anyString(),
                eq("APPLICATION"),
                eq(42L));
        verify(emailService).sendDocumentRequestCreatedEmail(
                eq("applicant@example.com"),
                eq("John Tan"),
                eq(42L),
                eq(2),
                any());
    }

    @Test
    void 업로드시_할당LEW에게_인앱_이메일_발송() {
        User applicant = mockUser(10L, "a@example.com", "John", "Tan");
        User lew = mockUser(20L, "lew@example.com", "Lee", "Wong");
        Application app = mockApplication(42L, applicant, lew);
        DocumentRequest dr = mockDr(7L, app, "LOA", null, null);

        notifier.notifyFulfilled(dr);

        verify(notificationService).createNotification(
                eq(20L),
                eq(NotificationType.DOCUMENT_REQUEST_FULFILLED),
                anyString(), anyString(),
                eq("DOCUMENT_REQUEST"),
                eq(7L));
        verify(emailService).sendDocumentRequestFulfilledEmail(
                eq("lew@example.com"),
                eq("Lee Wong"),
                eq(42L),
                eq("LOA"));
    }

    @Test
    void LEW_미할당시_업로드_알림_스킵() {
        User applicant = mockUser(10L, "a@example.com", "John", "Tan");
        Application app = mockApplication(42L, applicant, null);
        DocumentRequest dr = mockDr(7L, app, "LOA", null, null);

        notifier.notifyFulfilled(dr);

        verify(notificationService, never()).createNotification(
                anyLong(), any(), anyString(), anyString(), anyString(), anyLong());
        verify(emailService, never()).sendDocumentRequestFulfilledEmail(
                anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void 승인시_신청자에게_인앱_이메일_발송() {
        User applicant = mockUser(10L, "a@example.com", "John", "Tan");
        Application app = mockApplication(42L, applicant, null);
        DocumentRequest dr = mockDr(7L, app, "LOA", null, null);

        notifier.notifyApproved(dr);

        verify(notificationService).createNotification(
                eq(10L), eq(NotificationType.DOCUMENT_REQUEST_APPROVED),
                anyString(), anyString(), eq("DOCUMENT_REQUEST"), eq(7L));
        verify(emailService).sendDocumentRequestApprovedEmail(
                eq("a@example.com"), eq("John Tan"), eq(42L), eq("LOA"));
    }

    @Test
    void 반려시_사유를_이메일에_전달한다() {
        User applicant = mockUser(10L, "a@example.com", "John", "Tan");
        Application app = mockApplication(42L, applicant, null);
        String reason = "Photo is blurred; please resubmit.";
        DocumentRequest dr = mockDr(7L, app, "LOA", null, reason);

        notifier.notifyRejected(dr);

        verify(notificationService).createNotification(
                eq(10L), eq(NotificationType.DOCUMENT_REQUEST_REJECTED),
                anyString(), anyString(), eq("DOCUMENT_REQUEST"), eq(7L));
        verify(emailService).sendDocumentRequestRejectedEmail(
                eq("a@example.com"), eq("John Tan"), eq(42L), eq("LOA"), eq(reason));
    }

    @Test
    void 수신자_이메일_없으면_이메일_발송_스킵되지만_인앱은_유지() {
        User applicant = mockUser(10L, null, "John", "Tan");
        Application app = mockApplication(42L, applicant, null);
        DocumentRequest dr = mockDr(7L, app, "LOA", null, null);

        notifier.notifyApproved(dr);

        verify(notificationService).createNotification(
                eq(10L), eq(NotificationType.DOCUMENT_REQUEST_APPROVED),
                anyString(), anyString(), eq("DOCUMENT_REQUEST"), eq(7L));
        verify(emailService, never()).sendDocumentRequestApprovedEmail(
                anyString(), anyString(), anyLong(), anyString());
    }
}
