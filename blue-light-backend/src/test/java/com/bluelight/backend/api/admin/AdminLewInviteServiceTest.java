package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminUserResponse;
import com.bluelight.backend.api.admin.dto.InviteLewRequest;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AdminLewInviteService} 단위 테스트 (PR-1, AC-1/AC-3/AC-4/AC-10).
 */
@DisplayName("AdminLewInviteService - PR-1")
@ExtendWith(MockitoExtension.class)
class AdminLewInviteServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AccountSetupTokenService tokenService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailService emailService;

    @InjectMocks private AdminLewInviteService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "setupBaseUrl", "https://app.example.com");
    }

    private InviteLewRequest req(String email) {
        InviteLewRequest r = new InviteLewRequest();
        ReflectionTestUtils.setField(r, "email", email);
        ReflectionTestUtils.setField(r, "firstName", "Jane");
        ReflectionTestUtils.setField(r, "lastName", "Tan");
        return r;
    }

    private AccountSetupToken stubToken() {
        return AccountSetupToken.builder()
                .tokenUuid("tok-uuid-123")
                .source(AccountSetupTokenSource.LEW_INVITATION)
                .expiresAt(LocalDateTime.now().plusHours(48))
                .build();
    }

    @Test
    @DisplayName("정상 초대: LEW/PENDING_ACTIVATION/PENDING/ADMIN_INVITE 생성 + LEW_INVITATION 토큰 + 이메일")
    void invite_ok() {
        when(userRepository.findByEmail("new@lew.sg")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenService.issue(any(User.class), eq(AccountSetupTokenSource.LEW_INVITATION), any()))
                .thenReturn(stubToken());

        AdminUserResponse resp = service.invite(req("  New@LEW.sg "), null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getEmail()).isEqualTo("new@lew.sg"); // trim + lowercase
        assertThat(saved.getRole()).isEqualTo(UserRole.LEW);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(saved.getApprovedStatus()).isEqualTo(ApprovalStatus.PENDING);
        assertThat(saved.getSignupSource()).isEqualTo(SignupSource.ADMIN_INVITE);
        assertThat(saved.getLewLicenceNo()).isNull();
        assertThat(saved.getLewGrade()).isNull();

        verify(tokenService).issue(any(User.class), eq(AccountSetupTokenSource.LEW_INVITATION), any());
        // afterCommit 동기화 비활성(테스트) → 즉시 발송. setupUrl 에 토큰 UUID 포함.
        verify(emailService).sendLewInvitationEmail(eq("new@lew.sg"), any(),
                eq("https://app.example.com/setup-account/tok-uuid-123"), any());
        assertThat(resp.getStatus()).isEqualTo("PENDING_ACTIVATION");
    }

    @Test
    @DisplayName("D-4: 기존 LEW 이메일 → 409 EMAIL_ALREADY_LEW")
    void invite_dup_lew() {
        User lew = User.builder().email("a@lew.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.LEW).status(UserStatus.ACTIVE).build();
        when(userRepository.findByEmail("a@lew.sg")).thenReturn(Optional.of(lew));

        assertThatThrownBy(() -> service.invite(req("a@lew.sg"), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_ALREADY_LEW");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("D-4: 기존 PENDING_ACTIVATION(비-LEW) → 409 EMAIL_PENDING_ACTIVATION")
    void invite_dup_pending() {
        User pending = User.builder().email("p@x.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.APPLICANT).status(UserStatus.PENDING_ACTIVATION).build();
        when(userRepository.findByEmail("p@x.sg")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.invite(req("p@x.sg"), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_PENDING_ACTIVATION");
    }

    @Test
    @DisplayName("D-4: 기존 활성 비-LEW → 409 EMAIL_EXISTS_USE_CHANGE_ROLE")
    void invite_dup_other() {
        User applicant = User.builder().email("u@x.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.APPLICANT).status(UserStatus.ACTIVE).build();
        when(userRepository.findByEmail("u@x.sg")).thenReturn(Optional.of(applicant));

        assertThatThrownBy(() -> service.invite(req("u@x.sg"), null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "EMAIL_EXISTS_USE_CHANGE_ROLE");
    }

    @Test
    @DisplayName("재발송: PENDING_ACTIVATION 초대 LEW → 토큰 재발급 + 이메일")
    void resend_ok() {
        User invited = User.builder().email("i@lew.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.LEW).status(UserStatus.PENDING_ACTIVATION)
                .signupSource(SignupSource.ADMIN_INVITE).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(invited));
        when(tokenService.issue(any(User.class), eq(AccountSetupTokenSource.LEW_INVITATION), any()))
                .thenReturn(stubToken());

        service.resendInvite(7L, null);

        verify(tokenService).issue(eq(invited), eq(AccountSetupTokenSource.LEW_INVITATION), any());
        verify(emailService).sendLewInvitationEmail(eq("i@lew.sg"), any(), any(), any());
    }

    @Test
    @DisplayName("재발송: 이미 활성(ACTIVE) → 409 NOT_PENDING")
    void resend_not_pending() {
        User active = User.builder().email("i@lew.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.LEW).status(UserStatus.ACTIVE)
                .signupSource(SignupSource.ADMIN_INVITE).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> service.resendInvite(7L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_PENDING");
        verify(tokenService, never()).issue(any(), any(), any());
    }

    @Test
    @DisplayName("재발송: 초대 LEW 아님(자가가입 LEW) → 400 NOT_INVITED_LEW")
    void resend_not_invited() {
        User selfLew = User.builder().email("s@lew.sg").password("h").firstName("A").lastName("B")
                .role(UserRole.LEW).status(UserStatus.PENDING_ACTIVATION)
                .signupSource(SignupSource.DIRECT_SIGNUP).build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(selfLew));

        assertThatThrownBy(() -> service.resendInvite(7L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_INVITED_LEW");
    }

    @Test
    @DisplayName("재발송: 없는 사용자 → 404 USER_NOT_FOUND")
    void resend_not_found() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resendInvite(99L, null))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "USER_NOT_FOUND");
    }
}
