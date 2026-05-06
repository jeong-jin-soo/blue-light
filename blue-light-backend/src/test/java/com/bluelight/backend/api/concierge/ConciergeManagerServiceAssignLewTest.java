package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.application.ApplicationService;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.AssignLewRequest;
import com.bluelight.backend.api.concierge.dto.AssignLewResponse;
import com.bluelight.backend.api.concierge.dto.CreateOnBehalfResponse;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.concierge.ConciergeNoteRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.user.AccountSetupTokenRepository;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ★ Concierge 강화 + 별도 수금 PR-3 — LEW 배정 + 신청서 대행 권한 확장 단위 테스트.
 *
 * <p>스펙: §10 AC-L1~L5, AC-D1~D4.</p>
 */
@DisplayName("ConciergeManagerService - PR-3 (LEW assignment + delegated application)")
class ConciergeManagerServiceAssignLewTest {

    private ConciergeRequestRepository conciergeRepository;
    private ConciergeNoteRepository noteRepository;
    private UserRepository userRepository;
    private AccountSetupTokenRepository tokenRepository;
    private AccountSetupTokenService tokenService;
    private EmailService emailService;
    private AuditLogService auditLogService;
    private ConciergeNotifier notifier;
    private ApplicationService applicationService;
    private ApplicationEventPublisher eventPublisher;
    private ConciergeManagerService service;

    @BeforeEach
    void setUp() {
        conciergeRepository = mock(ConciergeRequestRepository.class);
        noteRepository = mock(ConciergeNoteRepository.class);
        userRepository = mock(UserRepository.class);
        tokenRepository = mock(AccountSetupTokenRepository.class);
        tokenService = mock(AccountSetupTokenService.class);
        emailService = mock(EmailService.class);
        auditLogService = mock(AuditLogService.class);
        notifier = mock(ConciergeNotifier.class);
        applicationService = mock(ApplicationService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new ConciergeManagerService(
            applicationService,
            conciergeRepository, noteRepository, userRepository,
            tokenRepository, tokenService, emailService, auditLogService, notifier,
            eventPublisher);
        ReflectionTestUtils.setField(service, "setupBaseUrl", "http://localhost:5174");

        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(anyLong()))
            .thenReturn(List.of());
        when(tokenRepository.findActiveTokensByUser(anyLong())).thenReturn(List.of());
    }

    // ────────────────────────────────────────────────────────────
    // 팩토리
    // ────────────────────────────────────────────────────────────

    private User makeManager(long seq) {
        User u = User.builder()
            .email("mgr" + seq + "@y.com").password("h")
            .firstName("M").lastName("gr" + seq)
            .role(UserRole.CONCIERGE_MANAGER).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private User makeManagerLew(long seq) {
        // 다중 역할: primary=CONCIERGE_MANAGER, secondary=LEW (D6=A 셀프 할당 시나리오)
        User u = User.builder()
            .email("mgrlew" + seq + "@y.com").password("h")
            .firstName("M").lastName("gL" + seq)
            .role(UserRole.CONCIERGE_MANAGER).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        u.addRole(UserRole.LEW);
        return u;
    }

    private User makeLew(long seq, ApprovalStatus approved, UserStatus status) {
        User u = User.builder()
            .email("lew" + seq + "@y.com").password("h")
            .firstName("L").lastName("ew" + seq)
            .role(UserRole.LEW).status(status)
            .approvedStatus(approved)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private User makeApplicant(long seq) {
        User u = User.builder()
            .email("app" + seq + "@y.com").password("h")
            .firstName("A").lastName("p" + seq)
            .role(UserRole.APPLICANT).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private ConciergeRequest makeRequest(long seq, User applicant, User manager,
                                          ConciergeRequestStatus initialStatus) {
        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode("C-2026-0" + seq)
            .submitterName("S").submitterEmail("s@y.com").submitterPhone("+6512345678")
            .applicantUser(applicant)
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
        ReflectionTestUtils.setField(cr, "conciergeRequestSeq", seq);
        ReflectionTestUtils.setField(cr, "createdAt", now);
        if (manager != null) {
            cr.assignManager(manager);
        }
        // 진입 상태로 도달
        if (initialStatus == ConciergeRequestStatus.CONTACTING) {
            cr.markContacted();
        } else if (initialStatus == ConciergeRequestStatus.QUOTE_SENT) {
            cr.markContacted();
            cr.recordQuote(java.math.BigDecimal.valueOf(350), null);
        }
        return cr;
    }

    // ────────────────────────────────────────────────────────────
    // AC-L1: 매니저가 다른 LEW 에 assign-lew → 200 + LEW_ASSIGNED
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-L1 — 매니저가 다른 LEW assign → status=LEW_ASSIGNED + event 발행")
    void assignLew_managerToOtherLew_success() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        AssignLewResponse response = service.assignLew(100L, req, 10L, null);

        assertThat(response.getAssignedLewSeq()).isEqualTo(50L);
        assertThat(response.getAssignedLewName()).isEqualTo("L ew50");
        assertThat(response.getPreviousLewSeq()).isNull();
        assertThat(response.isSelfAssigned()).isFalse();
        assertThat(response.getStatus()).isEqualTo("LEW_ASSIGNED");
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
        assertThat(cr.getAssignedLewSeq()).isEqualTo(50L);
        assertThat(cr.getLewAssignedAt()).isNotNull();

        // event 발행 검증
        verify(eventPublisher).publishEvent(any(ConciergeLewAssignedEvent.class));
        // audit 발생 검증
        verify(auditLogService).log(eq(10L), any(), any(),
            eq(AuditAction.CONCIERGE_LEW_ASSIGNED), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any());
    }

    // ────────────────────────────────────────────────────────────
    // AC-L2: 매니저 본인이 LEW role 보유 시 셀프 할당 → 200 + selfAssigned=true
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-L2 — 매니저+LEW 다중 역할 actor 가 본인 셀프 할당 → selfAssigned=true")
    void assignLew_selfAssign_managerWithLewRole() {
        User actor = makeManagerLew(15L);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, actor,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(15L)).thenReturn(Optional.of(actor));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(15L);

        AssignLewResponse response = service.assignLew(100L, req, 15L, null);

        assertThat(response.getAssignedLewSeq()).isEqualTo(15L);
        assertThat(response.isSelfAssigned()).isTrue();
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);

        verify(eventPublisher).publishEvent(argThat((Object e) ->
            e instanceof ConciergeLewAssignedEvent
                && ((ConciergeLewAssignedEvent) e).isSelfAssigned()));
    }

    // ────────────────────────────────────────────────────────────
    // AC-L3: 매니저(LEW role 미보유) 셀프 할당 시도 → 400 USER_NOT_LEW
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-L3 — 매니저가 LEW role 미보유인데 본인을 LEW 로 할당 → 400 USER_NOT_LEW")
    void assignLew_selfAssign_managerWithoutLewRole_rejected() {
        User actor = makeManager(10L); // LEW role 없음
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, actor,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(10L); // 본인

        assertThatThrownBy(() -> service.assignLew(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(be.getCode()).isEqualTo("USER_NOT_LEW");
            });
        verify(eventPublisher, never()).publishEvent(any(ConciergeLewAssignedEvent.class));
    }

    // ────────────────────────────────────────────────────────────
    // AC-L4: 재할당 시 previousLewSeq 응답 + 이전 LEW unassign 알림
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-L4 — 이미 LEW 배정된 상태에서 다른 LEW 로 재할당 → previousLewSeq 응답")
    void assignLew_reassignment_previousLewReturned() {
        User manager = makeManager(10L);
        User lewA = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User lewB = makeLew(60L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);
        // 첫 배정 (LEW_ASSIGNED 진입)
        cr.assignLewWithTransition(50L, LocalDateTime.now());

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(userRepository.findById(60L)).thenReturn(Optional.of(lewB));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(60L);

        AssignLewResponse response = service.assignLew(100L, req, 10L, null);

        assertThat(response.getAssignedLewSeq()).isEqualTo(60L);
        assertThat(response.getPreviousLewSeq()).isEqualTo(50L);
        assertThat(cr.getAssignedLewSeq()).isEqualTo(60L);

        // event 에 previousLewSeq 가 들어가는지 검증
        verify(eventPublisher).publishEvent(argThat((Object e) ->
            e instanceof ConciergeLewAssignedEvent
                && ((ConciergeLewAssignedEvent) e).getPreviousLewUserSeq().equals(50L)
                && ((ConciergeLewAssignedEvent) e).getNewLewUserSeq().equals(60L)));
    }

    // ────────────────────────────────────────────────────────────
    // AC-L5: 미승인 LEW → 400 LEW_NOT_APPROVED
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-L5 — primary LEW 가 승인되지 않은 사용자 → 400 LEW_NOT_APPROVED")
    void assignLew_unapprovedLew_rejected() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.PENDING, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        assertThatThrownBy(() -> service.assignLew(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(be.getCode()).isEqualTo("LEW_NOT_APPROVED");
            });
    }

    @Test
    @DisplayName("USER_INACTIVE — LEW 의 status 가 SUSPENDED → 400")
    void assignLew_inactiveLew_rejected() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.SUSPENDED);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        assertThatThrownBy(() -> service.assignLew(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("USER_INACTIVE"));
    }

    @Test
    @DisplayName("타 매니저의 ConciergeRequest 에 assign-lew → 403 CONCIERGE_NOT_ASSIGNED")
    void assignLew_otherManager_rejected() {
        User actor = makeManager(10L);
        User otherManager = makeManager(20L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, otherManager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        assertThatThrownBy(() -> service.assignLew(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_NOT_ASSIGNED"));
    }

    @Test
    @DisplayName("ADMIN 우회 — 본인 배정 아닌 ConciergeRequest 에도 assign-lew 가능")
    void assignLew_adminBypass_allowed() {
        User admin = User.builder()
            .email("admin@y.com").password("h")
            .firstName("A").lastName("dmin")
            .role(UserRole.ADMIN).status(UserStatus.ACTIVE)
            .build();
        ReflectionTestUtils.setField(admin, "userSeq", 1L);

        User otherManager = makeManager(20L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, otherManager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        AssignLewResponse response = service.assignLew(100L, req, 1L, null);
        assertThat(response.getAssignedLewSeq()).isEqualTo(50L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.LEW_ASSIGNED);
    }

    @Test
    @DisplayName("CANCELLED 상태에서 assign-lew → 409 INVALID_TRANSITION")
    void assignLew_fromCancelled_rejected() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);
        cr.cancel("test");

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AssignLewRequest req = new AssignLewRequest();
        req.setLewUserSeq(50L);

        assertThatThrownBy(() -> service.assignLew(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("INVALID_TRANSITION");
            });
    }

    // ────────────────────────────────────────────────────────────
    // AC-D1: assigned LEW 가 createApplicationOnBehalf → 200
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-D1 — assigned LEW 가 신청서 대행 작성 호출 → 200")
    void createApplicationOnBehalf_assignedLew_success() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);
        cr.assignLewWithTransition(50L, LocalDateTime.now()); // LEW_ASSIGNED 상태로

        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationResponse appResp = ApplicationResponse.builder()
            .applicationSeq(777L).build();
        when(applicationService.createOnBehalfOf(eq(2L), eq(100L),
            any(CreateApplicationRequest.class)))
            .thenReturn(appResp);

        CreateApplicationRequest req = new CreateApplicationRequest();
        CreateOnBehalfResponse response = service.createApplicationOnBehalf(
            100L, req, 50L, null);

        assertThat(response.getApplicationSeq()).isEqualTo(777L);
        assertThat(response.getConciergeRequestSeq()).isEqualTo(100L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);
    }

    // ────────────────────────────────────────────────────────────
    // AC-D2: 다른 LEW 가 createApplicationOnBehalf → 403
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-D2 — assigned 가 아닌 다른 LEW 가 신청서 대행 호출 → 403 CONCIERGE_LEW_NOT_ASSIGNED")
    void createApplicationOnBehalf_otherLew_rejected() {
        User manager = makeManager(10L);
        User lewA = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User lewB = makeLew(60L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);
        cr.assignLewWithTransition(50L, LocalDateTime.now()); // LEW A 배정

        // actor = LEW B
        when(userRepository.findById(60L)).thenReturn(Optional.of(lewB));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        CreateApplicationRequest req = new CreateApplicationRequest();
        assertThatThrownBy(() -> service.createApplicationOnBehalf(100L, req, 60L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_LEW_NOT_ASSIGNED"));
        verify(applicationService, never()).createOnBehalfOf(anyLong(), anyLong(), any());
    }

    // ────────────────────────────────────────────────────────────
    // AC-D3: 매니저 호출 → 200 (기존 동작 보존)
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-D3 — 매니저 호출 신청서 대행 → 200 (기존 동작 보존)")
    void createApplicationOnBehalf_manager_success() {
        User manager = makeManager(10L);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);

        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationResponse appResp = ApplicationResponse.builder()
            .applicationSeq(777L).build();
        when(applicationService.createOnBehalfOf(eq(2L), eq(100L),
            any(CreateApplicationRequest.class)))
            .thenReturn(appResp);

        CreateApplicationRequest req = new CreateApplicationRequest();
        CreateOnBehalfResponse response = service.createApplicationOnBehalf(
            100L, req, 10L, null);

        assertThat(response.getApplicationSeq()).isEqualTo(777L);
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);
    }

    // ────────────────────────────────────────────────────────────
    // AC-D4: LEW 미배정 + LEW actor → 403
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-D4 — LEW 미배정 ConciergeRequest 에 LEW actor 호출 → 403")
    void createApplicationOnBehalf_unassignedLew_rejected() {
        User manager = makeManager(10L);
        User lew = makeLew(50L, ApprovalStatus.APPROVED, UserStatus.ACTIVE);
        User applicant = makeApplicant(2L);
        ConciergeRequest cr = makeRequest(100L, applicant, manager,
            ConciergeRequestStatus.CONTACTING);
        // assignedLewSeq null

        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        CreateApplicationRequest req = new CreateApplicationRequest();
        assertThatThrownBy(() -> service.createApplicationOnBehalf(100L, req, 50L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_LEW_NOT_ASSIGNED"));
    }
}
