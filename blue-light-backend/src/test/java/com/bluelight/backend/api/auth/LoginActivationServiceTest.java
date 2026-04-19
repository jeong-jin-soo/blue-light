package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * LoginActivationService 단위 테스트 (★ Kaki Concierge v1.5 §4.4 옵션 B, Phase 1 PR#2 Stage C).
 * <p>
 * - 5케이스 동일 응답 (void) — 예외 안 던짐
 * - PENDING만 토큰 발급 + 이메일 등록
 * - ACTIVE/SUSPENDED/DELETED/미존재 → 무작업
 * - 모든 케이스에서 BCrypt.matches() 1회 호출 (타이밍 동등성)
 */
@DisplayName("LoginActivationService - PR#2 Stage C")
class LoginActivationServiceTest {

    private UserRepository userRepository;
    private AccountSetupTokenService tokenService;
    private EmailService emailService;
    private AuditLogService auditLogService;
    private PasswordEncoder passwordEncoder;
    private LoginActivationService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        tokenService = mock(AccountSetupTokenService.class);
        emailService = mock(EmailService.class);
        auditLogService = mock(AuditLogService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        service = new LoginActivationService(userRepository, tokenService, emailService,
            auditLogService, passwordEncoder);
        ReflectionTestUtils.setField(service, "setupBaseUrl", "http://localhost:5174");
    }

    private User buildUser(long seq, String email, UserStatus status) {
        User u = User.builder()
            .email(email).password("stored-hash")
            .firstName("F").lastName("L")
            .role(UserRole.APPLICANT).status(status)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private HttpServletRequest httpReq() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.1");
        when(req.getHeader("User-Agent")).thenReturn("UA-Test");
        return req;
    }

    private AccountSetupToken stubToken() {
        return AccountSetupToken.builder()
            .tokenUuid("uuid-abc")
            .user(buildUser(1L, "x@y.com", UserStatus.PENDING_ACTIVATION))
            .source(AccountSetupTokenSource.LOGIN_ACTIVATION)
            .expiresAt(LocalDateTime.now().plusHours(48))
            .build();
    }

    // ============================================================
    // PENDING_ACTIVATION — 실제 작업
    // ============================================================

    @Test
    @DisplayName("PENDING_ACTIVATION → 토큰 발급 + 이메일 발송(afterCommit 없이 즉시) + AUDIT_SENT")
    void request_pending_issuesTokenAndSendsEmail() {
        User u = buildUser(10L, "pending@example.com", UserStatus.PENDING_ACTIVATION);
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);
        when(tokenService.issue(eq(u), eq(AccountSetupTokenSource.LOGIN_ACTIVATION), any()))
            .thenReturn(stubToken());

        service.requestActivation("pending@example.com", httpReq());

        verify(tokenService).issue(eq(u), eq(AccountSetupTokenSource.LOGIN_ACTIVATION), any());
        // 트랜잭션 동기화 비활성 컨텍스트 → 즉시 이메일 발송 실행
        verify(emailService).sendAccountSetupLinkEmail(
            eq("pending@example.com"), eq("F L"),
            eq("http://localhost:5174/setup-account/uuid-abc"), anyString());
        verify(auditLogService).logAsync(
            eq(10L), eq(AuditAction.ACCOUNT_ACTIVATION_REQUEST_SENT), eq(AuditCategory.AUTH),
            eq("User"), eq("10"), anyString(), isNull(), isNull(),
            anyString(), anyString(), anyString(), anyString(), eq(200));
    }

    // ============================================================
    // 무작업 케이스 (응답은 동일 — void)
    // ============================================================

    @Test
    @DisplayName("ACTIVE → 무작업 + NO_MATCH 감사 로그")
    void request_active_noAction() {
        User u = buildUser(20L, "active@example.com", UserStatus.ACTIVE);
        when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(u));

        assertThatCode(() -> service.requestActivation("active@example.com", httpReq()))
            .doesNotThrowAnyException();

        verify(tokenService, never()).issue(any(), any(), any());
        verify(emailService, never()).sendAccountSetupLinkEmail(any(), any(), any(), any());
        verify(auditLogService).logAsync(
            eq(20L), eq(AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH), any(),
            anyString(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("SUSPENDED → 무작업 + NO_MATCH")
    void request_suspended_noAction() {
        User u = buildUser(30L, "s@y.com", UserStatus.SUSPENDED);
        when(userRepository.findByEmail("s@y.com")).thenReturn(Optional.of(u));

        service.requestActivation("s@y.com", httpReq());

        verify(tokenService, never()).issue(any(), any(), any());
        verify(auditLogService).logAsync(
            eq(30L), eq(AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH), any(),
            anyString(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("DELETED → 무작업 + NO_MATCH")
    void request_deleted_noAction() {
        User u = buildUser(40L, "d@y.com", UserStatus.DELETED);
        when(userRepository.findByEmail("d@y.com")).thenReturn(Optional.of(u));

        service.requestActivation("d@y.com", httpReq());

        verify(tokenService, never()).issue(any(), any(), any());
        verify(auditLogService).logAsync(
            eq(40L), eq(AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH), any(),
            anyString(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("미존재 이메일 → 무작업 + NO_MATCH (userSeq=null)")
    void request_unknownEmail_noAction() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());

        service.requestActivation("unknown@example.com", httpReq());

        verify(tokenService, never()).issue(any(), any(), any());
        verify(auditLogService).logAsync(
            isNull(), eq(AuditAction.ACCOUNT_ACTIVATION_REQUEST_NO_MATCH), any(),
            anyString(), isNull(), anyString(), any(), any(),
            anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    // ============================================================
    // 타이밍 동등성 (BCrypt.matches 호출 보장)
    // ============================================================

    @Test
    @DisplayName("모든 케이스에서 passwordEncoder.matches 1회 호출 (타이밍 동등성)")
    void allCases_callBcryptOnce() {
        // PENDING
        User pending = buildUser(1L, "p@y.com", UserStatus.PENDING_ACTIVATION);
        when(userRepository.findByEmail("p@y.com")).thenReturn(Optional.of(pending));
        when(tokenService.issue(any(), any(), any())).thenReturn(stubToken());
        service.requestActivation("p@y.com", httpReq());

        // ACTIVE
        User active = buildUser(2L, "a@y.com", UserStatus.ACTIVE);
        when(userRepository.findByEmail("a@y.com")).thenReturn(Optional.of(active));
        service.requestActivation("a@y.com", httpReq());

        // 미존재
        when(userRepository.findByEmail("x@y.com")).thenReturn(Optional.empty());
        service.requestActivation("x@y.com", httpReq());

        // 총 3회 호출 (각 케이스 1회)
        verify(passwordEncoder, times(3)).matches(eq("dummy"), anyString());
    }

    // ============================================================
    // 이메일 정규화
    // ============================================================

    @Test
    @DisplayName("이메일 정규화: 대소문자/공백 → 소문자 trim")
    void request_normalizesEmail() {
        when(userRepository.findByEmail("norm@example.com")).thenReturn(Optional.empty());

        service.requestActivation("  NORM@Example.com  ", httpReq());

        verify(userRepository).findByEmail("norm@example.com");
    }
}
