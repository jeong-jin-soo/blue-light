package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.LoginRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.PasswordResetTokenRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import com.bluelight.backend.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthService.login() H-1 재설계 단위 테스트 (★ Kaki Concierge v1.5 §4.4, Phase 1 PR#2 Stage C).
 * <p>
 * - 비번 선행 검증 → status 분기
 * - 미존재/DELETED는 INVALID_CREDENTIALS로 동일 응답 (존재 감춤)
 * - PENDING_ACTIVATION → ACCOUNT_PENDING_ACTIVATION
 * - SUSPENDED → ACCOUNT_SUSPENDED (403)
 * - 감사 로그 세분화 (UNKNOWN_EMAIL / BAD_PASSWORD / DELETED / SUCCESS)
 * - 타이밍 동등성: 미존재 케이스도 passwordEncoder.matches() 1회 호출
 */
@DisplayName("AuthService.login() H-1 재설계 - PR#2 Stage C")
class AuthServiceLoginTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private AuditLogService auditLogService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        auditLogService = mock(AuditLogService.class);

        authService = new AuthService(
            userRepository, passwordEncoder, jwtTokenProvider,
            mock(SystemSettingRepository.class),
            mock(PasswordResetTokenRepository.class),
            mock(EmailService.class),
            auditLogService);

        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
            .thenReturn("jwt-xyz");
        when(jwtTokenProvider.getExpirationInSeconds()).thenReturn(86400L);
    }

    private User buildUser(long seq, String email, UserStatus status) {
        User u = User.builder()
            .email(email).password("stored-hash")
            .firstName("F").lastName("L")
            .role(UserRole.APPLICANT)
            .status(status)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private LoginRequest req(String email, String password) {
        LoginRequest r = new LoginRequest();
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "password", password);
        return r;
    }

    private HttpServletRequest httpReq() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        when(req.getRemoteAddr()).thenReturn("203.0.113.1");
        when(req.getHeader("User-Agent")).thenReturn("UA-Test");
        return req;
    }

    // ============================================================
    // 1단계: 비번 검증 선행
    // ============================================================

    @Test
    @DisplayName("미존재 이메일 → BCrypt.matches(dummy) 1회 + INVALID_CREDENTIALS + LOGIN_FAILED_UNKNOWN_EMAIL")
    void login_unknownEmail_dummyBcrypt_thenThrows() {
        when(userRepository.findByEmail("unknown@example.com")).thenReturn(Optional.empty());
        // DUMMY_BCRYPT_HASH와 매칭 시도 → false 반환
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req("unknown@example.com", "pw"), httpReq()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(be.getCode()).isEqualTo("INVALID_CREDENTIALS");
            });

        // dummy hash와 비교 1회 호출됐는지 검증 (타이밍 동등성)
        verify(passwordEncoder, times(1)).matches(eq("pw"), anyString());

        // 감사 로그: UNKNOWN_EMAIL
        verify(auditLogService).logAsync(
            isNull(), eq(AuditAction.LOGIN_FAILED_UNKNOWN_EMAIL), eq(AuditCategory.AUTH),
            eq("User"), isNull(), anyString(), isNull(), isNull(),
            eq("203.0.113.1"), eq("UA-Test"), eq("POST"), eq("/api/auth/login"), eq(401));
    }

    @Test
    @DisplayName("존재 이메일 + 잘못된 비번 → INVALID_CREDENTIALS + LOGIN_FAILED_BAD_PASSWORD")
    void login_badPassword_throws() {
        User u = buildUser(10L, "alice@example.com", UserStatus.ACTIVE);
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("wrong", "stored-hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req("alice@example.com", "wrong"), httpReq()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_CREDENTIALS"));

        verify(auditLogService).logAsync(
            eq(10L), eq(AuditAction.LOGIN_FAILED_BAD_PASSWORD), eq(AuditCategory.AUTH),
            eq("User"), eq("10"), anyString(), isNull(), isNull(),
            anyString(), anyString(), anyString(), anyString(), eq(401));
    }

    // ============================================================
    // 3단계: status 분기 (비번 성공 후)
    // ============================================================

    @Test
    @DisplayName("ACTIVE + 올바른 비번 → JWT 반환 + LOGIN_SUCCESS")
    void login_active_returnsJwt() {
        User u = buildUser(20L, "active@example.com", UserStatus.ACTIVE);
        when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches("Pass1234", "stored-hash")).thenReturn(true);

        TokenResponse resp = authService.login(req("active@example.com", "Pass1234"), httpReq());

        assertThat(resp.getAccessToken()).isEqualTo("jwt-xyz");
        assertThat(resp.getUserSeq()).isEqualTo(20L);
        verify(auditLogService).logAsync(
            eq(20L), eq(AuditAction.LOGIN_SUCCESS), eq(AuditCategory.AUTH),
            eq("User"), eq("20"), anyString(), isNull(), isNull(),
            anyString(), anyString(), anyString(), anyString(), eq(200));
    }

    @Test
    @DisplayName("PENDING_ACTIVATION + 올바른 비번 → 401 ACCOUNT_PENDING_ACTIVATION")
    void login_pending_throwsActivationRequired() {
        User u = buildUser(30L, "pending@example.com", UserStatus.PENDING_ACTIVATION);
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(req("pending@example.com", "x"), httpReq()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                assertThat(be.getCode()).isEqualTo("ACCOUNT_PENDING_ACTIVATION");
            });
        // JWT 발급 없음, LOGIN_SUCCESS 감사 로그 없음
        verify(jwtTokenProvider, never()).createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean());
        verify(auditLogService, never()).logAsync(anyLong(), eq(AuditAction.LOGIN_SUCCESS),
            any(), anyString(), anyString(), anyString(), any(), any(),
            anyString(), anyString(), anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("SUSPENDED + 올바른 비번 → 403 ACCOUNT_SUSPENDED")
    void login_suspended_throws403() {
        User u = buildUser(40L, "susp@example.com", UserStatus.SUSPENDED);
        when(userRepository.findByEmail("susp@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(req("susp@example.com", "x"), httpReq()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("ACCOUNT_SUSPENDED");
            });
    }

    @Test
    @DisplayName("DELETED + 올바른 비번 → INVALID_CREDENTIALS (존재 감춤) + LOGIN_FAILED_DELETED")
    void login_deleted_hidesAsInvalidCreds() {
        User u = buildUser(50L, "del@example.com", UserStatus.DELETED);
        when(userRepository.findByEmail("del@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(req("del@example.com", "x"), httpReq()))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                // 존재 감춤 — INVALID_CREDENTIALS로 응답
                assertThat(be.getCode()).isEqualTo("INVALID_CREDENTIALS");
            });

        // 감사 로그만 LOGIN_FAILED_DELETED (내부 기록)
        verify(auditLogService).logAsync(
            eq(50L), eq(AuditAction.LOGIN_FAILED_DELETED), eq(AuditCategory.AUTH),
            eq("User"), eq("50"), anyString(), isNull(), isNull(),
            anyString(), anyString(), anyString(), anyString(), eq(401));
    }

    @Test
    @DisplayName("이메일 정규화: 대문자+공백 → 소문자 trim")
    void login_normalizesEmail() {
        User u = buildUser(60L, "normalized@example.com", UserStatus.ACTIVE);
        when(userRepository.findByEmail("normalized@example.com")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        authService.login(req("  Normalized@EXAMPLE.com  ", "pw"), httpReq());

        verify(userRepository).findByEmail("normalized@example.com");
    }
}
