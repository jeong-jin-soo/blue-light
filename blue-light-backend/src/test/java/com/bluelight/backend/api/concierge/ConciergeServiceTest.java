package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestCreateResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ConsentType;
import com.bluelight.backend.domain.user.SignupSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserConsentLog;
import com.bluelight.backend.domain.user.UserConsentLogRepository;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConciergeService 시나리오 테스트 (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage B).
 * <p>
 * C1~C5 케이스별 처리 + 동의 기록 + 토큰 발급 + afterCommit 호출 검증.
 */
@DisplayName("ConciergeService - PR#2 Stage B")
class ConciergeServiceTest {

    private ConciergeRequestRepository conciergeRepository;
    private UserRepository userRepository;
    private UserConsentLogRepository consentLogRepository;
    private AccountSetupTokenService tokenService;
    private PublicCodeGenerator publicCodeGenerator;
    private ConciergeNotifier notifier;
    private AuditLogService auditLogService;
    private PasswordEncoder passwordEncoder;
    private ConciergeService service;

    @BeforeEach
    void setUp() {
        conciergeRepository = mock(ConciergeRequestRepository.class);
        userRepository = mock(UserRepository.class);
        consentLogRepository = mock(UserConsentLogRepository.class);
        tokenService = mock(AccountSetupTokenService.class);
        publicCodeGenerator = mock(PublicCodeGenerator.class);
        notifier = mock(ConciergeNotifier.class);
        auditLogService = mock(AuditLogService.class);
        passwordEncoder = mock(PasswordEncoder.class);

        service = new ConciergeService(
            conciergeRepository, userRepository, consentLogRepository,
            tokenService, publicCodeGenerator, notifier, auditLogService, passwordEncoder);

        // 공통 stubbing
        when(publicCodeGenerator.generate()).thenReturn("C-2026-0001");
        when(passwordEncoder.encode(anyString())).thenAnswer(inv -> "ENC:" + inv.getArgument(0));
        when(conciergeRepository.save(any(ConciergeRequest.class))).thenAnswer(inv -> {
            ConciergeRequest cr = inv.getArgument(0);
            ReflectionTestUtils.setField(cr, "conciergeRequestSeq", 100L);
            return cr;
        });
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "userSeq", 42L);
            return u;
        });
    }

    private ConciergeRequestCreateRequest buildRequest(String email, boolean marketing) {
        ConciergeRequestCreateRequest req = new ConciergeRequestCreateRequest();
        req.setFullName("Tan Wei Ming");
        req.setEmail(email);
        req.setMobileNumber("+6591234567");
        req.setMemo("Shop house at Bukit Timah");
        req.setPdpaConsent(true);
        req.setTermsAgreed(true);
        req.setSignupConsent(true);
        req.setDelegationConsent(true);
        req.setMarketingOptIn(marketing);
        return req;
    }

    private AccountSetupToken stubToken(String uuid) {
        AccountSetupToken t = AccountSetupToken.builder()
            .tokenUuid(uuid)
            .user(User.builder().email("x@y.com").password("h").firstName("a").lastName("b").build())
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(48))
            .build();
        return t;
    }

    // ============================================================
    // C1: 신규 가입
    // ============================================================

    @Test
    @DisplayName("C1 - 이메일 미존재 → 신규 User(PENDING_ACTIVATION) 생성, signupSource=CONCIERGE_REQUEST, 토큰 발급, Notifier 호출")
    void submit_C1_newSignup() {
        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());
        when(tokenService.issue(any(User.class), eq(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP), any()))
            .thenReturn(stubToken("token-c1"));

        ConciergeRequestCreateResponse res = service.submitRequest(
            buildRequest("new@example.com", false), null);

        // User 생성 검증
        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCap.capture());
        User saved = userCap.getValue();
        assertThat(saved.getStatus()).isEqualTo(UserStatus.PENDING_ACTIVATION);
        assertThat(saved.getSignupSource()).isEqualTo(SignupSource.CONCIERGE_REQUEST);
        assertThat(saved.getRole()).isEqualTo(UserRole.APPLICANT);
        assertThat(saved.getEmail()).isEqualTo("new@example.com");
        assertThat(saved.getFirstName()).isEqualTo("Tan");
        assertThat(saved.getLastName()).isEqualTo("Wei Ming");
        assertThat(saved.getPassword()).startsWith("ENC:!PLACEHOLDER!");

        // 토큰 발급 검증
        verify(tokenService).issue(any(User.class),
            eq(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP), isNull());

        // 동의 로그 4건 (marketing=false이므로 MARKETING 없음)
        verify(consentLogRepository, times(4)).save(any(UserConsentLog.class));

        // 감사 로그: AUTO_CREATED + TOKEN_ISSUED + REQUEST_SUBMITTED + 4 CONSENT
        verify(auditLogService, times(1)).log(anyLong(), anyString(), anyString(),
            eq(AuditAction.CONCIERGE_ACCOUNT_AUTO_CREATED), any(), anyString(), anyString(),
            anyString(), any(), any(), any(), any(), anyString(), anyString(), anyInt());
        verify(auditLogService, times(1)).log(anyLong(), anyString(), anyString(),
            eq(AuditAction.ACCOUNT_SETUP_TOKEN_ISSUED), any(), anyString(), anyString(),
            anyString(), any(), any(), any(), any(), anyString(), anyString(), anyInt());
        verify(auditLogService, times(1)).log(anyLong(), anyString(), anyString(),
            eq(AuditAction.CONCIERGE_REQUEST_SUBMITTED), any(), anyString(), anyString(),
            anyString(), any(), any(), any(), any(), anyString(), anyString(), anyInt());

        // Notifier 호출 검증
        verify(notifier).notifySubmitted(eq(100L), eq("new@example.com"), anyString(),
            eq("C-2026-0001"), eq("token-c1"), any(LocalDateTime.class),
            eq(ConciergeCaseResolver.Case.C1_NEW_SIGNUP));

        // 응답 검증
        assertThat(res.getPublicCode()).isEqualTo("C-2026-0001");
        assertThat(res.getStatus()).isEqualTo(ConciergeRequestStatus.SUBMITTED.name());
        assertThat(res.isExistingUser()).isFalse();
        assertThat(res.isAccountSetupRequired()).isTrue();
    }

    @Test
    @DisplayName("C1 - marketingOptIn=true → MARKETING 동의 로그 추가 + optInMarketing() 호출")
    void submit_C1_marketingOptIn() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(tokenService.issue(any(), any(), any())).thenReturn(stubToken("t"));

        service.submitRequest(buildRequest("mkt@example.com", true), null);

        // 5건 (PDPA/TERMS/SIGNUP/DELEGATION + MARKETING)
        ArgumentCaptor<UserConsentLog> consentCap = ArgumentCaptor.forClass(UserConsentLog.class);
        verify(consentLogRepository, times(5)).save(consentCap.capture());
        assertThat(consentCap.getAllValues()).extracting(UserConsentLog::getConsentType)
            .containsExactly(ConsentType.PDPA, ConsentType.TERMS, ConsentType.SIGNUP,
                ConsentType.DELEGATION, ConsentType.MARKETING);

        // User.optInMarketing 확인
        ArgumentCaptor<User> userCap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCap.capture());
        assertThat(userCap.getValue().getMarketingOptIn()).isTrue();
        assertThat(userCap.getValue().getMarketingOptInAt()).isNotNull();
    }

    @Test
    @DisplayName("이메일 정규화 - 대문자/공백 → 소문자 trim")
    void submit_normalizesEmail() {
        when(userRepository.findByEmail(anyString())).thenReturn(Optional.empty());
        when(tokenService.issue(any(), any(), any())).thenReturn(stubToken("t"));

        service.submitRequest(buildRequest("  Alice@Example.COM  ", false), null);

        // 정규화된 이메일로 조회 호출
        verify(userRepository).findByEmail("alice@example.com");
        // 저장된 User의 이메일도 정규화
        ArgumentCaptor<User> cap = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(cap.capture());
        assertThat(cap.getValue().getEmail()).isEqualTo("alice@example.com");
    }

    // ============================================================
    // C2: 기존 ACTIVE
    // ============================================================

    @Test
    @DisplayName("C2 - 기존 APPLICANT+ACTIVE → 기존 User 연결, 토큰 발급 안 함")
    void submit_C2_existingActive() {
        User existing = User.builder()
            .email("active@example.com").password("h").firstName("A").lastName("B")
            .role(UserRole.APPLICANT).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(existing, "userSeq", 7L);
        when(userRepository.findByEmail("active@example.com")).thenReturn(Optional.of(existing));

        ConciergeRequestCreateResponse res = service.submitRequest(
            buildRequest("active@example.com", false), null);

        // 신규 User 생성 없음
        verify(userRepository, never()).save(any(User.class));
        // 토큰 발급 없음
        verify(tokenService, never()).issue(any(), any(), any());
        // 감사 로그: EXISTING_LINKED
        verify(auditLogService).log(eq(7L), anyString(), anyString(),
            eq(AuditAction.CONCIERGE_EXISTING_USER_LINKED), any(), anyString(), anyString(),
            anyString(), any(), any(), any(), any(), anyString(), anyString(), anyInt());
        // Notifier: setupToken null
        verify(notifier).notifySubmitted(eq(100L), eq("active@example.com"), anyString(),
            eq("C-2026-0001"), isNull(), isNull(),
            eq(ConciergeCaseResolver.Case.C2_EXISTING_ACTIVE));
        // 응답 플래그
        assertThat(res.isExistingUser()).isTrue();
        assertThat(res.isAccountSetupRequired()).isFalse();
    }

    // ============================================================
    // C3: 기존 PENDING
    // ============================================================

    @Test
    @DisplayName("C3 - 기존 APPLICANT+PENDING → 기존 User 재사용, 토큰 재발급")
    void submit_C3_existingPending() {
        User existing = User.builder()
            .email("pending@example.com").password("h").firstName("A").lastName("B")
            .role(UserRole.APPLICANT).status(UserStatus.PENDING_ACTIVATION)
            .build();
        ReflectionTestUtils.setField(existing, "userSeq", 9L);
        when(userRepository.findByEmail("pending@example.com")).thenReturn(Optional.of(existing));
        when(tokenService.issue(eq(existing), eq(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP), any()))
            .thenReturn(stubToken("token-c3"));

        ConciergeRequestCreateResponse res = service.submitRequest(
            buildRequest("pending@example.com", false), null);

        // 신규 User 생성 안 함
        verify(userRepository, never()).save(any(User.class));
        // 토큰 발급
        verify(tokenService).issue(eq(existing), eq(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP), any());
        // Notifier에 토큰 전달
        verify(notifier).notifySubmitted(eq(100L), eq("pending@example.com"), anyString(),
            eq("C-2026-0001"), eq("token-c3"), any(LocalDateTime.class),
            eq(ConciergeCaseResolver.Case.C3_EXISTING_PENDING));
        assertThat(res.isExistingUser()).isTrue();
        assertThat(res.isAccountSetupRequired()).isTrue();
    }

    // ============================================================
    // C4: SUSPENDED/DELETED 거부
    // ============================================================

    @Test
    @DisplayName("C4 - SUSPENDED → 409 ACCOUNT_NOT_ELIGIBLE")
    void submit_C4_suspended_throws409() {
        User existing = User.builder()
            .email("s@y.com").password("h").firstName("A").lastName("B")
            .role(UserRole.APPLICANT).status(UserStatus.SUSPENDED)
            .build();
        when(userRepository.findByEmail("s@y.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.submitRequest(buildRequest("s@y.com", false), null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("ACCOUNT_NOT_ELIGIBLE");
            });
        // 어떤 저장/발송도 일어나지 않아야 함
        verify(userRepository, never()).save(any(User.class));
        verify(conciergeRepository, never()).save(any(ConciergeRequest.class));
        verify(notifier, never()).notifySubmitted(any(), any(), any(), any(), any(), any(), any());
    }

    // ============================================================
    // C5: 스태프 거부
    // ============================================================

    @Test
    @DisplayName("C5 - LEW 이메일 → 422 STAFF_EMAIL_NOT_ALLOWED")
    void submit_C5_lew_throws422() {
        User lew = User.builder()
            .email("lew@bluelight.sg").password("h").firstName("L").lastName("W")
            .role(UserRole.LEW).status(UserStatus.ACTIVE)
            .build();
        when(userRepository.findByEmail("lew@bluelight.sg")).thenReturn(Optional.of(lew));

        assertThatThrownBy(() -> service.submitRequest(buildRequest("lew@bluelight.sg", false), null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(be.getCode()).isEqualTo("STAFF_EMAIL_NOT_ALLOWED");
            });
        verify(conciergeRepository, never()).save(any(ConciergeRequest.class));
    }

    // ============================================================
    // 동시성 race 재시도
    // ============================================================

    @Test
    @DisplayName("DataIntegrityViolationException 발생 시 재조회 → 재분기 (한 번만 재시도)")
    void submit_concurrentSignup_retriesOnce() {
        // 1st findByEmail: empty → C1 분기로 신규 User 생성 시도
        // 그러나 userRepository.save(any) 에서 DataIntegrityViolationException 발생
        // 2nd findByEmail: ACTIVE 유저 존재 → C2로 재분기하여 성공
        User raced = User.builder()
            .email("race@example.com").password("h").firstName("A").lastName("B")
            .role(UserRole.APPLICANT).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(raced, "userSeq", 77L);
        when(userRepository.findByEmail("race@example.com"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(raced));
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("unique key violated"));

        ConciergeRequestCreateResponse res = service.submitRequest(
            buildRequest("race@example.com", false), null);

        // 2번 조회 (초기 + 재시도)
        verify(userRepository, times(2)).findByEmail("race@example.com");
        // 토큰 발급 없음 (최종 분기 = C2)
        verify(tokenService, never()).issue(any(), any(), any());
        assertThat(res.isExistingUser()).isTrue();
        assertThat(res.isAccountSetupRequired()).isFalse();
    }

    @Test
    @DisplayName("race 재시도 후에도 차단 케이스면 409")
    void submit_concurrentSignup_retryToBlockingCase() {
        User suspended = User.builder()
            .email("r@y.com").password("h").firstName("A").lastName("B")
            .role(UserRole.APPLICANT).status(UserStatus.SUSPENDED)
            .build();
        when(userRepository.findByEmail("r@y.com"))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(suspended));
        when(userRepository.save(any(User.class)))
            .thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.submitRequest(buildRequest("r@y.com", false), null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("ACCOUNT_NOT_ELIGIBLE"));
    }
}
