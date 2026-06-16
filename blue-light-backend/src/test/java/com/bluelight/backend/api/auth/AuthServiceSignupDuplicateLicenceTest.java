package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.PasswordResetTokenRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserConsentLogRepository;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * P0: LEW 면허번호 중복 가입 차단 검증 (한 실물 LEW = 한 계정).
 */
@DisplayName("AuthService.signup() — LEW 면허번호 중복 차단")
class AuthServiceSignupDuplicateLicenceTest {

    private AuthService newService(UserRepository userRepository) {
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
            .thenReturn("jwt");
        when(jwtTokenProvider.getExpirationInSeconds()).thenReturn(86400L);
        return new AuthService(
            userRepository, passwordEncoder, jwtTokenProvider,
            mock(SystemSettingRepository.class),
            mock(PasswordResetTokenRepository.class),
            mock(EmailService.class),
            mock(AuditLogService.class),
            mock(UserConsentLogRepository.class));
    }

    private SignupRequest lewSignup(String email, String licenceNo) {
        SignupRequest req = new SignupRequest();
        ReflectionTestUtils.setField(req, "email", email);
        ReflectionTestUtils.setField(req, "password", "password1");
        ReflectionTestUtils.setField(req, "firstName", "Foo");
        ReflectionTestUtils.setField(req, "lastName", "Bar");
        ReflectionTestUtils.setField(req, "pdpaConsent", true);
        ReflectionTestUtils.setField(req, "role", "LEW");
        ReflectionTestUtils.setField(req, "lewLicenceNo", licenceNo);
        ReflectionTestUtils.setField(req, "lewGrade", "GRADE_8");
        return req;
    }

    @Test
    @DisplayName("이미 등록된 면허번호로 LEW 가입 시 DUPLICATE_LEW_LICENCE_NO")
    void duplicateLicence_throws() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByLewLicenceNo("8/35550")).thenReturn(true);

        AuthService authService = newService(userRepository);

        assertThatThrownBy(() -> authService.signup(lewSignup("new@example.com", "8/35550"), null))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("code", "DUPLICATE_LEW_LICENCE_NO");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("면허번호 정규화(trim) 후 중복 검사 + 저장")
    void freshLicence_isTrimmedAndSaved() {
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(userRepository.existsByLewLicenceNo("8/35550")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "userSeq", 42L);
            return u;
        });

        AuthService authService = newService(userRepository);

        TokenResponse resp = authService.signup(lewSignup("new@example.com", "  8/35550  "), null);

        assertThat(resp).isNotNull();
        // 중복 검사는 trim된 값으로 수행됐고, 저장된 면허도 trim됨
        verify(userRepository).existsByLewLicenceNo("8/35550");
        verify(userRepository).save(argThat(u -> "8/35550".equals(u.getLewLicenceNo())));
    }
}
