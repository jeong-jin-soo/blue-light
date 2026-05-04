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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR-1 — {@link ManualEmailDispatchSendListener} 단위 테스트.
 *
 * <p>스펙: AC-A8 SMTP 실패 격리.</p>
 *
 * <p><b>주의</b>: {@code @Transactional(REQUIRES_NEW)} 메서드(markSent/markFailed/findRowReadOnly)는
 * Spring AOP 가 적용되지 않은 단위 테스트 환경에서 일반 메서드처럼 동작한다 — 실제 트랜잭션 분리는
 * 컨테이너 통합 테스트에서 보장. 본 테스트는 status 갱신 호출 자체와 SMTP 실패 swallow 만 검증.</p>
 */
@DisplayName("ManualEmailDispatchSendListener — PR-1")
class ManualEmailDispatchSendListenerTest {

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
    }

    private ManualEmailDispatch row() {
        return ManualEmailDispatch.builder()
                .senderUserSeq(ADMIN_SEQ)
                .recipientType(RecipientType.APPLICANT)
                .recipientUserSeq(12L)
                .recipientEmail("alice@example.com")
                .subject("Maintenance notice")
                .bodyText("We will undergo maintenance.")
                .bodyFormat(BodyFormat.PLAIN_TEXT)
                .build();
    }

    private User adminUser(String email) {
        User u = mock(User.class);
        when(u.getEmail()).thenReturn(email);
        return u;
    }

    @Test
    @DisplayName("정상 발송 — emailService 호출 + statusUpdater.markSent")
    void onDispatchRequested_정상() {
        ManualEmailDispatch entity = row();
        User admin = adminUser("admin@licensekaki.sg");
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(emailService).sendManualPlainTextEmail(
                eq("alice@example.com"),
                eq("Maintenance notice"),
                eq("We will undergo maintenance."),
                eq("admin@licensekaki.sg"));
        // statusUpdater 빈에 위임 — markSent 가 dispatchSeq 와 함께 호출되어야 한다.
        verify(statusUpdater).markSent(eq(DISPATCH_SEQ));
        verify(statusUpdater, never()).markFailed(anyLong(), anyString());
    }

    @Test
    @DisplayName("AC-A8 SMTP 실패 — statusUpdater.markFailed + 예외 swallow (리스너에서 빠져나가지 않음)")
    void onDispatchRequested_SMTP실패() {
        ManualEmailDispatch entity = row();
        User admin = adminUser("admin@licensekaki.sg");
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        doThrow(new RuntimeException("SMTP server unavailable"))
                .when(emailService).sendManualPlainTextEmail(eq("alice@example.com"),
                        eq("Maintenance notice"), eq("We will undergo maintenance."), eq("admin@licensekaki.sg"));

        // 예외가 빠져나오면 안 됨.
        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(statusUpdater).markFailed(eq(DISPATCH_SEQ), contains("SMTP server unavailable"));
        verify(statusUpdater, never()).markSent(anyLong());
    }

    @Test
    @DisplayName("row 미존재 — emailService 호출 없이 ERROR 로깅 후 반환")
    void onDispatchRequested_row미존재() {
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.empty());

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        verify(emailService, never()).sendManualPlainTextEmail(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("ADMIN 이메일 lookup 실패 — 빈 문자열 fallback 으로 발송 진행")
    void onDispatchRequested_admin이메일_lookup실패() {
        ManualEmailDispatch entity = row();
        when(dispatchRepository.findById(DISPATCH_SEQ)).thenReturn(Optional.of(entity));
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.empty());

        listener.onDispatchRequested(new ManualEmailDispatchRequestedEvent(DISPATCH_SEQ));

        ArgumentCaptor<String> adminEmailCap = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendManualPlainTextEmail(
                eq("alice@example.com"),
                eq("Maintenance notice"),
                eq("We will undergo maintenance."),
                adminEmailCap.capture());
        // 빈 문자열로 fallback — 자동 푸터에 "Sent by:" 빈값이 표시되지만 발송 자체는 진행.
        assertThat(adminEmailCap.getValue()).isEmpty();
        verify(statusUpdater).markSent(eq(DISPATCH_SEQ));
    }
}
