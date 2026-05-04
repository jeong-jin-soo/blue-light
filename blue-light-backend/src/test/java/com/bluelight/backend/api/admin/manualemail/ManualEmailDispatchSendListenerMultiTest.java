package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.manualemail.RecipientType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * PR-2 — {@link ManualEmailDispatchSendListener} MULTI 청크/쓰로틀/부분 실패 테스트.
 *
 * <p>스펙: AC-A4 부분 실패 + D7=B 청크/쓰로틀.</p>
 */
@DisplayName("ManualEmailDispatchSendListener — PR-2 MULTI")
class ManualEmailDispatchSendListenerMultiTest {

    private static final long DISPATCH_SEQ = 77L;
    private static final long ADMIN_SEQ = 99L;

    private ManualEmailDispatchRepository dispatchRepository;
    private UserRepository userRepository;
    private EmailService emailService;
    private ManualEmailDispatchStatusUpdater statusUpdater;
    private ManualEmailDispatchSendListener listener;

    @BeforeEach
    void setUp() {
        dispatchRepository = mock(ManualEmailDispatchRepository.class);
        userRepository = mock(UserRepository.class);
        emailService = mock(EmailService.class);
        statusUpdater = mock(ManualEmailDispatchStatusUpdater.class);
        listener = new ManualEmailDispatchSendListener(
                dispatchRepository, userRepository, emailService, statusUpdater);

        // Mockito nested-when 트랩 회피 — User mock 을 변수로 만든 후 stub 에 주입.
        User admin = mock(User.class);
        when(admin.getEmail()).thenReturn("admin@licensekaki.sg");
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
    }

    private ManualEmailDispatch multiRow(List<String> recipients) {
        return ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.MULTI)
                .recipientEmail(recipients.get(0))
                .recipientEmailsJson(new ArrayList<>(recipients))
                .subject("Batch notice")
                .bodyText("Hello batch.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .recipientHash("dummyhash")
                .build();
    }

    @Test
    @DisplayName("AC-A4 MULTI 5명 정상 발송 — 1청크 (delay 없음), markBatchResult(sent=5, failed=0)")
    void multi_5명_1청크() {
        List<String> recipients = List.of("a@x.com", "b@x.com", "c@x.com", "d@x.com", "e@x.com");
        when(dispatchRepository.findById(DISPATCH_SEQ))
                .thenReturn(Optional.of(multiRow(recipients)));

        long started = System.currentTimeMillis();
        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));
        long elapsed = System.currentTimeMillis() - started;

        // 5건 발송 — emailService 호출 5회.
        verify(emailService, times(5)).sendManualPlainTextEmail(
                anyString(), anyString(), anyString(), anyString());
        // 1청크 → 청크 사이 sleep 없음 → 100ms 미만 (CI 환경 변동을 고려해 50ms 여유).
        // 본 어서션은 너무 빡빡하면 flaky 하므로 단순히 청크 delay 가 발생했는지만 검증.
        assertThat(elapsed).isLessThan(ManualEmailDispatchSendListener.CHUNK_DELAY_MS);

        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(5), eq(0), eq(null));
    }

    @Test
    @DisplayName("AC-A4 / D7=B MULTI 6명 — 2청크 (5+1), 청크 사이 100ms sleep 검증")
    void multi_6명_2청크_delay() {
        List<String> recipients = List.of("a@x.com", "b@x.com", "c@x.com",
                                          "d@x.com", "e@x.com", "f@x.com");
        when(dispatchRepository.findById(DISPATCH_SEQ))
                .thenReturn(Optional.of(multiRow(recipients)));

        long started = System.currentTimeMillis();
        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));
        long elapsed = System.currentTimeMillis() - started;

        verify(emailService, times(6)).sendManualPlainTextEmail(
                anyString(), anyString(), anyString(), anyString());
        // 청크 사이 1회의 sleep(100ms) 가 발생해야 함.
        assertThat(elapsed).isGreaterThanOrEqualTo(ManualEmailDispatchSendListener.CHUNK_DELAY_MS);

        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(6), eq(0), eq(null));
    }

    @Test
    @DisplayName("AC-A4 부분 실패 — 3건 중 1건 SMTP 실패 → PARTIAL (sent=2, failed=1, reason multi-line 형식)")
    void multi_3건_부분실패() {
        List<String> recipients = List.of("ok1@x.com", "fail@x.com", "ok2@x.com");
        when(dispatchRepository.findById(DISPATCH_SEQ))
                .thenReturn(Optional.of(multiRow(recipients)));
        // fail@x.com 만 SMTP 예외.
        doThrow(new RuntimeException("Mailbox full"))
                .when(emailService).sendManualPlainTextEmail(eq("fail@x.com"),
                        anyString(), anyString(), anyString());

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(2), eq(1), reasonCap.capture());
        // failed_reason 은 "email: error" 형식 — 실패한 수신자 명시.
        assertThat(reasonCap.getValue()).contains("fail@x.com");
        assertThat(reasonCap.getValue()).contains("Mailbox full");
    }

    @Test
    @DisplayName("AC-A4 전체 실패 — 3건 모두 실패 → markBatchResult(sent=0, failed=3) + 멀티라인")
    void multi_3건_전체실패() {
        List<String> recipients = List.of("a@x.com", "b@x.com", "c@x.com");
        when(dispatchRepository.findById(DISPATCH_SEQ))
                .thenReturn(Optional.of(multiRow(recipients)));
        doThrow(new RuntimeException("SMTP server down"))
                .when(emailService).sendManualPlainTextEmail(anyString(), anyString(), anyString(), anyString());

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        ArgumentCaptor<String> reasonCap = ArgumentCaptor.forClass(String.class);
        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(0), eq(3), reasonCap.capture());
        // 멀티라인 — 3개 라인 (newline 으로 join).
        String reason = reasonCap.getValue();
        assertThat(reason.split("\n")).hasSize(3);
        assertThat(reason).contains("a@x.com").contains("b@x.com").contains("c@x.com");
    }

    @Test
    @DisplayName("PR-1 호환: 단일 row (recipientEmail 만, _json null) — 1건 loop + markBatchResult(1,0)")
    void single_row_PR1호환() {
        ManualEmailDispatch row = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.APPLICANT)
                .recipientUserSeq(12L)
                .recipientEmail("alice@example.com")
                .subject("Hello")
                .bodyText("Body")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .build();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(row));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(emailService, times(1)).sendManualPlainTextEmail(
                eq("alice@example.com"), eq("Hello"), eq("Body"), eq("admin@licensekaki.sg"));
        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(1), eq(0), eq(null));
    }

    @Test
    @DisplayName("빈 수신자 row — 발송 없이 markBatchResult(0,1) + 비정상 reason")
    void empty_recipients() {
        // entity 가 비정상적으로 emails 도 single email 도 비어있는 케이스 — 가드.
        // 일반 빌더로는 recipient_email 이 NOT NULL 이지만, 통합 테스트 시나리오 차원에서 가드 검증.
        ManualEmailDispatch row = ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.MULTI)
                .recipientEmail(null) // intentionally null — 가드 동작 확인
                .recipientEmailsJson(List.of())
                .subject("S").bodyText("B")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .build();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(row));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(emailService, never()).sendManualPlainTextEmail(any(), any(), any(), any());
        verify(statusUpdater).markBatchResult(eq(DISPATCH_SEQ), eq(0), eq(1), anyString());
    }
}
