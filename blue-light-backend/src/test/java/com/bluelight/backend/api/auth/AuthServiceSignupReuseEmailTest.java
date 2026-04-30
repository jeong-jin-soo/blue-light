package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.SignupRequest;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.PasswordResetTokenRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 삭제된 계정의 이메일이 anonymize()로 해방되어 동일 이메일 재가입이 가능함을 검증.
 * <p>
 * 회귀 방지: dev.licensekaki.com에서 회원 가입 → 프로필 삭제 → 동일 이메일 재가입 시
 * UNIQUE 제약 충돌(uk_users_email)로 500 발생하던 버그.
 */
@DisplayName("AuthService.signup() — 삭제 후 동일 이메일 재가입")
class AuthServiceSignupReuseEmailTest {

    @Test
    @DisplayName("anonymize() 후 existsByEmail은 원본 이메일을 더 이상 점유하지 않음")
    void afterAnonymize_existsByEmail_isFalseForOriginal() {
        UserRepository userRepository = mock(UserRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);

        AuthService authService = new AuthService(
            userRepository, passwordEncoder, jwtTokenProvider,
            mock(SystemSettingRepository.class),
            mock(PasswordResetTokenRepository.class),
            mock(EmailService.class),
            mock(AuditLogService.class));

        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
            .thenReturn("jwt");
        when(jwtTokenProvider.getExpirationInSeconds()).thenReturn(86400L);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");

        // 삭제된 계정은 @SQLRestriction으로 인해 existsByEmail()에서 보이지 않음
        when(userRepository.existsByEmail("foo@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "userSeq", 99L);
            return u;
        });

        SignupRequest req = new SignupRequest();
        ReflectionTestUtils.setField(req, "email", "foo@example.com");
        ReflectionTestUtils.setField(req, "password", "password1");
        ReflectionTestUtils.setField(req, "firstName", "Foo");
        ReflectionTestUtils.setField(req, "lastName", "Bar");
        ReflectionTestUtils.setField(req, "pdpaConsent", true);

        TokenResponse resp = authService.signup(req);

        assertThat(resp).isNotNull();
        assertThat(resp.getEmail()).isEqualTo("foo@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("anonymize()는 email을 'deleted-{seq}@deleted.licensekaki.sg' 형식으로 익명화한다")
    void anonymize_replacesEmailWithDeterministicForm() {
        User user = User.builder()
            .email("foo@example.com").password("h").firstName("Foo").lastName("Bar")
            .build();
        ReflectionTestUtils.setField(user, "userSeq", 99L);

        user.anonymize();

        assertThat(user.getEmail()).isEqualTo("deleted-99@deleted.licensekaki.sg");
        // 원본 이메일은 행에서 제거되어 UNIQUE 제약을 점유하지 않음
        assertThat(user.getEmail()).isNotEqualTo("foo@example.com");
    }
}
