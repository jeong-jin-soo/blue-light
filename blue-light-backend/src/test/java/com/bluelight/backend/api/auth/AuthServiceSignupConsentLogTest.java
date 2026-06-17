package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.ConsentAction;
import com.bluelight.backend.domain.user.ConsentSourceContext;
import com.bluelight.backend.domain.user.ConsentType;
import com.bluelight.backend.domain.user.PasswordResetTokenRepository;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.TermsVersion;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserConsentLog;
import com.bluelight.backend.domain.user.UserConsentLogRepository;
import com.bluelight.backend.domain.user.LewPaynowChangeLogRepository;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AuthService.signup() PDPA/TERMS 동의 로그 기록 단위 테스트 (PR-0D).
 *
 * <p>회원가입 시점에 {@link UserConsentLog} 가 PDPA + TERMS 2건 GRANTED 로 기록되고,
 * 감사 로그 {@code USER_CONSENT_RECORDED} 도 동반 발행되는지 검증.
 * 컨시어지 경로와의 증적 비대칭을 해소하는 PR.</p>
 */
@DisplayName("AuthService.signup() - PR-0D 동의 로그 기록")
class AuthServiceSignupConsentLogTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private SystemSettingRepository systemSettingRepository;
    private PasswordResetTokenRepository passwordResetTokenRepository;
    private EmailService emailService;
    private AuditLogService auditLogService;
    private UserConsentLogRepository consentLogRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        systemSettingRepository = mock(SystemSettingRepository.class);
        passwordResetTokenRepository = mock(PasswordResetTokenRepository.class);
        emailService = mock(EmailService.class);
        auditLogService = mock(AuditLogService.class);
        consentLogRepository = mock(UserConsentLogRepository.class);

        authService = new AuthService(
                userRepository, passwordEncoder, jwtTokenProvider,
                systemSettingRepository, passwordResetTokenRepository,
                emailService, auditLogService, consentLogRepository,
                mock(LewPaynowChangeLogRepository.class));

        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
                .thenReturn("jwt");
        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), any(), anyBoolean(), anyBoolean()))
                .thenReturn("jwt");
        when(jwtTokenProvider.getExpirationInSeconds()).thenReturn(86400L);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "userSeq", 555L);
            return u;
        });
    }

    @Test
    @DisplayName("signup - UserConsentLog PDPA + TERMS GRANTED 2건 저장, DIRECT_SIGNUP 맥락")
    void signup_savesPdpaAndTermsConsentLogs() {
        SignupRequest req = signupRequest("foo@example.com", true);

        authService.signup(req, mockHttpRequest("10.1.2.3", "Mozilla/5.0"));

        ArgumentCaptor<UserConsentLog> captor = ArgumentCaptor.forClass(UserConsentLog.class);
        verify(consentLogRepository, times(2)).save(captor.capture());

        List<UserConsentLog> saved = captor.getAllValues();
        assertThat(saved)
                .extracting(UserConsentLog::getConsentType, UserConsentLog::getAction,
                        UserConsentLog::getSourceContext, UserConsentLog::getDocumentVersion,
                        UserConsentLog::getIpAddress, UserConsentLog::getUserAgent)
                .containsExactlyInAnyOrder(
                        tuple(ConsentType.PDPA, ConsentAction.GRANTED,
                                ConsentSourceContext.DIRECT_SIGNUP, TermsVersion.CURRENT,
                                "10.1.2.3", "Mozilla/5.0"),
                        tuple(ConsentType.TERMS, ConsentAction.GRANTED,
                                ConsentSourceContext.DIRECT_SIGNUP, TermsVersion.CURRENT,
                                "10.1.2.3", "Mozilla/5.0"));
    }

    @Test
    @DisplayName("signup - 감사 로그 USER_CONSENT_RECORDED 2회 발행 (type 별 메시지 포함)")
    void signup_emitsAuditLogPerConsent() {
        SignupRequest req = signupRequest("bar@example.com", true);

        authService.signup(req, mockHttpRequest("203.0.113.5", "curl/7.88"));

        verify(auditLogService, times(2)).logAsync(
                eq(555L),
                eq(AuditAction.USER_CONSENT_RECORDED),
                eq(AuditCategory.DATA_PROTECTION),
                eq("user_consent_log"),
                isNull(),
                contains("Consent recorded: type="),
                isNull(), isNull(),
                eq("203.0.113.5"), eq("curl/7.88"),
                eq("POST"), eq("/api/auth/signup"), eq(201));
    }

    @Test
    @DisplayName("signup - User 엔티티에 signupConsentAt + termsVersion + signupSource=DIRECT_SIGNUP 스냅샷")
    void signup_recordsTermsSnapshotOnUser() {
        SignupRequest req = signupRequest("baz@example.com", true);

        authService.signup(req, mockHttpRequest("1.1.1.1", "ua"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User saved = userCaptor.getValue();

        assertThat(saved.getSignupConsentAt()).isNotNull();
        assertThat(saved.getPdpaConsentAt()).isNotNull();
        assertThat(saved.getTermsVersion()).isEqualTo(TermsVersion.CURRENT);
        assertThat(saved.getSignupSource()).isEqualTo(SignupSource.DIRECT_SIGNUP);
    }

    @Test
    @DisplayName("signup - HttpServletRequest 가 null 이어도 NPE 없이 동작 (IP/UA null 로 저장)")
    void signup_nullHttpRequest_isSafe() {
        SignupRequest req = signupRequest("nope@example.com", true);

        authService.signup(req, null);

        ArgumentCaptor<UserConsentLog> captor = ArgumentCaptor.forClass(UserConsentLog.class);
        verify(consentLogRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(log -> {
                    assertThat(log.getIpAddress()).isNull();
                    assertThat(log.getUserAgent()).isNull();
                });
    }

    // ===== helpers =====

    private static SignupRequest signupRequest(String email, boolean pdpaConsent) {
        SignupRequest req = new SignupRequest();
        ReflectionTestUtils.setField(req, "email", email);
        ReflectionTestUtils.setField(req, "password", "password1");
        ReflectionTestUtils.setField(req, "firstName", "First");
        ReflectionTestUtils.setField(req, "lastName", "Last");
        ReflectionTestUtils.setField(req, "pdpaConsent", pdpaConsent);
        return req;
    }

    private static HttpServletRequest mockHttpRequest(String ip, String userAgent) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn(ip);
        when(req.getHeader("User-Agent")).thenReturn(userAgent);
        return req;
    }
}
