package com.bluelight.backend.api.auth;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.dto.AccountSetupCompleteRequest;
import com.bluelight.backend.api.auth.dto.AccountSetupStatusResponse;
import com.bluelight.backend.api.auth.dto.TokenResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserStatus;
import com.bluelight.backend.security.JwtTokenProvider;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AccountSetupService 단위 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage A).
 */
@DisplayName("AccountSetupService - PR#2 Stage A")
class AccountSetupServiceTest {

    private AccountSetupTokenService tokenService;
    private PasswordEncoder passwordEncoder;
    private JwtTokenProvider jwtTokenProvider;
    private AuditLogService auditLogService;
    private com.bluelight.backend.domain.user.UserRepository userRepository;
    private com.bluelight.backend.domain.user.UserConsentLogRepository consentLogRepository;
    private com.bluelight.backend.domain.user.LewPaynowChangeLogRepository paynowChangeLogRepository;
    private AccountSetupService service;

    @BeforeEach
    void setUp() {
        tokenService = mock(AccountSetupTokenService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtTokenProvider = mock(JwtTokenProvider.class);
        auditLogService = mock(AuditLogService.class);
        userRepository = mock(com.bluelight.backend.domain.user.UserRepository.class);
        consentLogRepository = mock(com.bluelight.backend.domain.user.UserConsentLogRepository.class);
        paynowChangeLogRepository = mock(com.bluelight.backend.domain.user.LewPaynowChangeLogRepository.class);
        service = new AccountSetupService(tokenService, passwordEncoder, jwtTokenProvider, auditLogService,
            userRepository, consentLogRepository, paynowChangeLogRepository);

        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        when(jwtTokenProvider.createToken(anyLong(), anyString(), anyString(), anyBoolean(), anyBoolean()))
            .thenReturn("jwt-token-xyz");
        when(jwtTokenProvider.getExpirationInSeconds()).thenReturn(86400L);
    }

    private User pendingUser(long userSeq, String email) {
        User u = User.builder()
            .email(email).password("old-hash").firstName("F").lastName("L")
            .status(UserStatus.PENDING_ACTIVATION)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", userSeq);
        return u;
    }

    private AccountSetupToken freshTokenFor(User u) {
        return AccountSetupToken.builder()
            .tokenUuid("uuid-1234")
            .user(u)
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(48))
            .build();
    }

    // ============================================================
    // getStatus()
    // ============================================================

    @Test
    @DisplayName("getStatus() - 마스킹된 이메일과 expiresAt 반환")
    void getStatus_returnsMaskedEmail() {
        User u = pendingUser(1L, "alice@example.com");
        AccountSetupToken token = freshTokenFor(u);
        when(tokenService.validate("uuid-1234")).thenReturn(token);

        AccountSetupStatusResponse res = service.getStatus("uuid-1234");

        assertThat(res.getMaskedEmail()).isEqualTo("a***@example.com");
        assertThat(res.getExpiresAt()).isEqualTo(token.getExpiresAt());
    }

    // ============================================================
    // complete() — 성공 경로
    // ============================================================

    @Test
    @DisplayName("complete() - PENDING_ACTIVATION 유저 → ACTIVE 전이 + JWT 발급 + 감사 로그")
    void complete_pendingUser_activatesAndReturnsJwt() {
        User u = pendingUser(10L, "bob@example.com");
        AccountSetupToken token = freshTokenFor(u);
        when(tokenService.validate("uuid-1234")).thenReturn(token);

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("NewPass123");
        req.setPasswordConfirm("NewPass123");

        TokenResponse result = service.complete("uuid-1234", req, null);

        // 비밀번호 변경 + 상태 전이
        assertThat(u.getPassword()).isEqualTo("ENC:NewPass123");
        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.getActivatedAt()).isNotNull();
        assertThat(u.getFirstLoggedInAt()).isNotNull();

        // 토큰 사용 처리
        verify(tokenService).markUsed(token);

        // 감사 로그 동기 호출
        verify(auditLogService).log(
            eq(10L), eq("bob@example.com"), eq("APPLICANT"),
            eq(AuditAction.ACCOUNT_ACTIVATED), eq(AuditCategory.AUTH),
            eq("user"), eq("10"),
            anyString(), isNull(), isNull(),
            isNull(), isNull(),
            eq("POST"), eq("/api/public/account-setup/{token}"), eq(200));

        // JWT 응답
        assertThat(result.getAccessToken()).isEqualTo("jwt-token-xyz");
        assertThat(result.getUserSeq()).isEqualTo(10L);
        assertThat(result.getEmail()).isEqualTo("bob@example.com");
        assertThat(result.getTokenType()).isEqualTo("Bearer");
    }

    @Test
    @DisplayName("complete() - 이미 ACTIVE 유저도 멱등 (status 유지, 비번만 변경)")
    void complete_activeUser_isIdempotent() {
        User u = User.builder()
            .email("carol@example.com").password("old").firstName("C").lastName("L")
            .status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", 20L);
        AccountSetupToken token = freshTokenFor(u);
        when(tokenService.validate("uuid-1234")).thenReturn(token);

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("NewPass123");
        req.setPasswordConfirm("NewPass123");

        TokenResponse result = service.complete("uuid-1234", req, null);

        assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(u.getActivatedAt()).isNull(); // activate() 호출 안 됨
        assertThat(u.getPassword()).isEqualTo("ENC:NewPass123");
        assertThat(result.getAccessToken()).isEqualTo("jwt-token-xyz");
        verify(tokenService).markUsed(token);
    }

    // ============================================================
    // complete() — 실패 경로
    // ============================================================

    @Test
    @DisplayName("complete() - 비밀번호 확인 불일치 시 400 PASSWORD_MISMATCH")
    void complete_mismatchedConfirm_throws() {
        User u = pendingUser(1L, "x@y.com");
        AccountSetupToken token = freshTokenFor(u);
        when(tokenService.validate(anyString())).thenReturn(token);

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("NewPass123");
        req.setPasswordConfirm("DifferentPass123");

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(be.getCode()).isEqualTo("PASSWORD_MISMATCH");
            });
        // 비밀번호는 변경되지 않음
        assertThat(u.getPassword()).isEqualTo("old-hash");
        verify(tokenService, never()).markUsed(any());
    }

    @Test
    @DisplayName("complete() - 8자 미만 비밀번호는 400 PASSWORD_POLICY_VIOLATION")
    void complete_tooShort_throws() {
        User u = pendingUser(1L, "x@y.com");
        when(tokenService.validate(anyString())).thenReturn(freshTokenFor(u));

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("Abc123"); // 6자
        req.setPasswordConfirm("Abc123");

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));
    }

    @Test
    @DisplayName("complete() - 숫자 없는 비밀번호는 400 PASSWORD_POLICY_VIOLATION")
    void complete_noDigit_throws() {
        User u = pendingUser(1L, "x@y.com");
        when(tokenService.validate(anyString())).thenReturn(freshTokenFor(u));

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("OnlyLetters");
        req.setPasswordConfirm("OnlyLetters");

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));
    }

    @Test
    @DisplayName("complete() - 공백 포함 비밀번호는 400 PASSWORD_POLICY_VIOLATION")
    void complete_withWhitespace_throws() {
        User u = pendingUser(1L, "x@y.com");
        when(tokenService.validate(anyString())).thenReturn(freshTokenFor(u));

        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("Valid Pass 123");
        req.setPasswordConfirm("Valid Pass 123");

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("PASSWORD_POLICY_VIOLATION"));
    }

    // ============================================================
    // complete() - HttpServletRequest 기반 IP/UA 로깅
    // ============================================================

    @Test
    @DisplayName("complete() - HttpServletRequest 주어지면 감사 로그에 IP/UA 기록")
    void complete_withRequest_recordsIpAndUa() {
        User u = pendingUser(5L, "ip@test.com");
        when(tokenService.validate(anyString())).thenReturn(freshTokenFor(u));

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.1");
        when(req.getHeader("User-Agent")).thenReturn("UA-Test");

        AccountSetupCompleteRequest body = new AccountSetupCompleteRequest();
        body.setPassword("NewPass123");
        body.setPasswordConfirm("NewPass123");

        service.complete("uuid-1234", body, req);

        verify(auditLogService).log(
            eq(5L), anyString(), anyString(),
            eq(AuditAction.ACCOUNT_ACTIVATED), eq(AuditCategory.AUTH),
            anyString(), anyString(), anyString(),
            isNull(), isNull(),
            eq("203.0.113.1"), eq("UA-Test"),
            anyString(), anyString(), eq(200));
    }

    // ============================================================
    // complete() — LEW_INVITATION 분기 (PR-3)
    // ============================================================

    private User pendingInvitedLew(long seq, String email) {
        User u = User.builder()
            .email(email).password("old-hash").firstName("Jane").lastName("Tan")
            .role(com.bluelight.backend.domain.user.UserRole.LEW)
            .approvedStatus(com.bluelight.backend.domain.user.ApprovalStatus.PENDING)
            .status(UserStatus.PENDING_ACTIVATION)
            .signupSource(com.bluelight.backend.domain.user.SignupSource.ADMIN_INVITE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private AccountSetupToken lewToken(User u) {
        return AccountSetupToken.builder()
            .tokenUuid("uuid-1234").user(u)
            .source(AccountSetupTokenSource.LEW_INVITATION)
            .expiresAt(LocalDateTime.now().plusHours(48))
            .build();
    }

    private AccountSetupCompleteRequest lewReq() {
        AccountSetupCompleteRequest r = new AccountSetupCompleteRequest();
        r.setPassword("NewPass123");
        r.setPasswordConfirm("NewPass123");
        r.setLewLicenceNo("8/35550");
        r.setLewGrade("GRADE_8");
        r.setPdpaConsent(true);
        r.setPaynowType("MOBILE");
        r.setPaynowValue("97771983");
        return r;
    }

    @Test
    @DisplayName("getStatus() - LEW_INVITATION 토큰 → requiresLewDetails=true (컨시어지=false)")
    void getStatus_lewInvitation_requiresLewDetails() {
        User lew = pendingInvitedLew(30L, "lew@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(lewToken(lew));
        assertThat(service.getStatus("uuid-1234").isRequiresLewDetails()).isTrue();

        User concierge = pendingUser(31L, "c@x.sg");
        when(tokenService.validate("uuid-5678")).thenReturn(freshTokenFor(concierge));
        assertThat(service.getStatus("uuid-5678").isRequiresLewDetails()).isFalse();
    }

    @Test
    @DisplayName("complete() - LEW 초대 정상: APPROVED 자동승인 + PayNow 저장 + 동의/이력 기록 + JWT approved")
    void complete_lew_happyPath() {
        User lew = pendingInvitedLew(40L, "lew@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(lewToken(lew));
        when(userRepository.existsByLewLicenceNo("8/35550")).thenReturn(false);

        TokenResponse result = service.complete("uuid-1234", lewReq(), null);

        assertThat(lew.getApprovedStatus()).isEqualTo(com.bluelight.backend.domain.user.ApprovalStatus.APPROVED);
        assertThat(lew.getLewLicenceNo()).isEqualTo("8/35550");
        assertThat(lew.getLewGrade()).isEqualTo(com.bluelight.backend.domain.user.LewGrade.GRADE_8);
        assertThat(lew.getPaynowType()).isEqualTo(com.bluelight.backend.domain.user.PaynowType.MOBILE);
        assertThat(lew.getPaynowValue()).isEqualTo("97771983");
        assertThat(lew.getStatus()).isEqualTo(UserStatus.ACTIVE);
        // 리뷰 #1: 초대 LEW도 PDPA 동의 시 pdpaConsentAt 설정 → hasPdpaConsent()=true (동의 철회/표시 일관성)
        assertThat(lew.getPdpaConsentAt()).isNotNull();
        assertThat(lew.hasPdpaConsent()).isTrue();

        verify(consentLogRepository, times(2)).save(any());      // PDPA + TERMS
        verify(paynowChangeLogRepository).save(any());           // 최초 PayNow 이력
        verify(tokenService).markUsed(any());
        // JWT approved=true 로 발급(자동승인)
        verify(jwtTokenProvider).createToken(eq(40L), eq("lew@x.sg"), eq("LEW"), eq(true), anyBoolean());
        verify(tokenService, never()).recordInputValidationFailure(anyString());
    }

    @Test
    @DisplayName("complete() - LEW 초대 PDPA 미동의 → 400 + 입력검증 실패 카운트 + 토큰 미사용")
    void complete_lew_noPdpa() {
        User lew = pendingInvitedLew(41L, "lew@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(lewToken(lew));
        AccountSetupCompleteRequest req = lewReq();
        req.setPdpaConsent(false);

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("PDPA_CONSENT_REQUIRED"));
        verify(tokenService).recordInputValidationFailure("uuid-1234");
        verify(tokenService, never()).markUsed(any());
        assertThat(lew.getApprovedStatus()).isEqualTo(com.bluelight.backend.domain.user.ApprovalStatus.PENDING);
    }

    @Test
    @DisplayName("complete() - LEW 초대 PayNow 형식 위반 → 400 INVALID_PAYNOW_VALUE + 입력검증 실패 카운트")
    void complete_lew_badPaynow() {
        User lew = pendingInvitedLew(42L, "lew@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(lewToken(lew));
        AccountSetupCompleteRequest req = lewReq();
        req.setPaynowValue("12345"); // MOBILE 형식 위반

        assertThatThrownBy(() -> service.complete("uuid-1234", req, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_PAYNOW_VALUE"));
        verify(tokenService).recordInputValidationFailure("uuid-1234");
        verify(tokenService, never()).markUsed(any());
    }

    @Test
    @DisplayName("complete() - LEW 초대 면허 중복 → 409, 입력검증 카운트 안 함(토큰 살림)")
    void complete_lew_duplicateLicence() {
        User lew = pendingInvitedLew(43L, "lew@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(lewToken(lew));
        when(userRepository.existsByLewLicenceNo("8/35550")).thenReturn(true);

        assertThatThrownBy(() -> service.complete("uuid-1234", lewReq(), null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("DUPLICATE_LEW_LICENCE_NO"));
        // 리뷰 #3: 면허 중복도 잠금 카운터에 포함(탐침 오라클 차단), 단 토큰은 미사용(정정 재시도 가능)
        verify(tokenService).recordInputValidationFailure("uuid-1234");
        verify(tokenService, never()).markUsed(any());
    }

    @Test
    @DisplayName("complete() - 컨시어지 토큰은 LEW 검증/면허조회를 전혀 타지 않음 (회귀 안전, AC-11)")
    void complete_concierge_skipsLewBranch() {
        User u = pendingUser(44L, "c@x.sg");
        when(tokenService.validate("uuid-1234")).thenReturn(freshTokenFor(u));
        AccountSetupCompleteRequest req = new AccountSetupCompleteRequest();
        req.setPassword("NewPass123");
        req.setPasswordConfirm("NewPass123");

        service.complete("uuid-1234", req, null);

        verify(userRepository, never()).existsByLewLicenceNo(anyString());
        verify(paynowChangeLogRepository, never()).save(any());
        verify(consentLogRepository, never()).save(any());
        verify(tokenService, never()).recordInputValidationFailure(anyString());
        verify(tokenService).markUsed(any());
    }

    // ============================================================
    // maskEmail() 유닛
    // ============================================================

    @Test
    @DisplayName("maskEmail() - 로컬파트 2자 이상: 첫글자 + ***")
    void maskEmail_normal() {
        assertThat(AccountSetupService.maskEmail("alice@example.com")).isEqualTo("a***@example.com");
        assertThat(AccountSetupService.maskEmail("ab@b.com")).isEqualTo("a***@b.com");
    }

    @Test
    @DisplayName("maskEmail() - 로컬파트 1자: *** + @도메인")
    void maskEmail_singleChar() {
        assertThat(AccountSetupService.maskEmail("a@b.com")).isEqualTo("***@b.com");
    }

    @Test
    @DisplayName("maskEmail() - 잘못된 형식/null은 ***")
    void maskEmail_invalid() {
        assertThat(AccountSetupService.maskEmail("noatsign")).isEqualTo("***");
        assertThat(AccountSetupService.maskEmail(null)).isEqualTo("***");
    }
}
