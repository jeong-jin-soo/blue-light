package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.application.ApplicationService;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.CancelRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestDetail;
import com.bluelight.backend.api.concierge.dto.CreateOnBehalfResponse;
import com.bluelight.backend.api.concierge.dto.NoteAddRequest;
import com.bluelight.backend.api.concierge.dto.NoteResponse;
import com.bluelight.backend.api.concierge.dto.StatusTransitionRequest;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeNote;
import com.bluelight.backend.domain.concierge.ConciergeNoteRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.concierge.NoteChannel;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenRepository;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ConciergeManagerService 단위 테스트 (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage A).
 */
@DisplayName("ConciergeManagerService - PR#4 Stage A")
class ConciergeManagerServiceTest {

    private ConciergeRequestRepository conciergeRepository;
    private ConciergeNoteRepository noteRepository;
    private UserRepository userRepository;
    private AccountSetupTokenRepository tokenRepository;
    private AccountSetupTokenService tokenService;
    private EmailService emailService;
    private AuditLogService auditLogService;
    private com.bluelight.backend.api.application.ApplicationService applicationService;
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
        applicationService = mock(com.bluelight.backend.api.application.ApplicationService.class);

        service = new ConciergeManagerService(
            applicationService,
            conciergeRepository, noteRepository, userRepository,
            tokenRepository, tokenService, emailService, auditLogService);
        ReflectionTestUtils.setField(service, "setupBaseUrl", "http://localhost:5174");

        when(noteRepository.save(any(ConciergeNote.class))).thenAnswer(inv -> {
            ConciergeNote n = inv.getArgument(0);
            ReflectionTestUtils.setField(n, "conciergeNoteSeq", 999L);
            return n;
        });
        when(tokenRepository.findActiveTokensByUser(anyLong())).thenReturn(List.of());
    }

    // ── 테스트용 팩토리 ──

    private User makeUser(long seq, UserRole role, UserStatus status) {
        User u = User.builder()
            .email(role.name().toLowerCase() + seq + "@y.com").password("h")
            .firstName("F" + seq).lastName("L")
            .role(role).status(status)
            .build();
        ReflectionTestUtils.setField(u, "userSeq", seq);
        return u;
    }

    private ConciergeRequest makeRequest(long seq, User applicant, User assignedManager) {
        LocalDateTime now = LocalDateTime.now();
        ConciergeRequest cr = ConciergeRequest.builder()
            .publicCode("C-2026-0" + seq)
            .submitterName("Submitter").submitterEmail("s@y.com").submitterPhone("+6512345678")
            .applicantUser(applicant)
            .pdpaConsentAt(now).termsConsentAt(now)
            .signupConsentAt(now).delegationConsentAt(now)
            .build();
        ReflectionTestUtils.setField(cr, "conciergeRequestSeq", seq);
        ReflectionTestUtils.setField(cr, "createdAt", now);
        if (assignedManager != null) {
            cr.assignManager(assignedManager);
        }
        return cr;
    }

    private void stubActor(User actor) {
        when(userRepository.findById(actor.getUserSeq())).thenReturn(Optional.of(actor));
    }

    // ============================================================
    // listForActor
    // ============================================================

    @Test
    @DisplayName("listForActor - ADMIN이면 managerSeq=null로 전체 조회")
    void list_admin_allRequests() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        stubActor(admin);
        when(conciergeRepository.searchForDashboard(
            isNull(), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listForActor(1L, null, null, 0, 20);

        verify(conciergeRepository).searchForDashboard(
            isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("listForActor - MANAGER이면 본인 userSeq로 필터")
    void list_manager_filtersBySelfSeq() {
        User manager = makeUser(42L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        stubActor(manager);
        when(conciergeRepository.searchForDashboard(
            eq(42L), any(), any(), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        service.listForActor(42L, null, null, 0, 20);

        verify(conciergeRepository).searchForDashboard(
            eq(42L), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("listForActor - 잘못된 status 문자열 → 400 INVALID_STATUS")
    void list_invalidStatus_throws() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        stubActor(admin);

        assertThatThrownBy(() -> service.listForActor(1L, "FOO_BAR", null, 0, 20))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(be.getCode()).isEqualTo("INVALID_STATUS");
            });
    }

    // ============================================================
    // getDetail
    // ============================================================

    @Test
    @DisplayName("getDetail - ADMIN → OK, 타 매니저 배정이어도 접근 가능")
    void detail_admin_ok() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        User otherManager = makeUser(3L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, otherManager);

        stubActor(admin);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        ConciergeRequestDetail result = service.getDetail(100L, 1L);

        assertThat(result.getConciergeRequestSeq()).isEqualTo(100L);
        assertThat(result.getAssignedManagerSeq()).isEqualTo(3L);
        assertThat(result.getApplicantStatus().getUserStatus()).isEqualTo("PENDING_ACTIVATION");
    }

    @Test
    @DisplayName("getDetail - 담당 MANAGER → OK")
    void detail_assignedManager_ok() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        ConciergeRequestDetail result = service.getDetail(100L, 10L);
        assertThat(result.getConciergeRequestSeq()).isEqualTo(100L);
    }

    @Test
    @DisplayName("getDetail - 타 MANAGER → 403 CONCIERGE_NOT_ASSIGNED")
    void detail_otherManager_forbidden() {
        User actor = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User otherManager = makeUser(20L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, otherManager);

        stubActor(actor);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        assertThatThrownBy(() -> service.getDetail(100L, 10L))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("CONCIERGE_NOT_ASSIGNED"));
    }

    @Test
    @DisplayName("getDetail - 요청 없음 → 404 NOT_FOUND")
    void detail_notFound() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        stubActor(admin);
        when(conciergeRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail(999L, 1L))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(be.getCode()).isEqualTo("NOT_FOUND");
            });
    }

    // ============================================================
    // transitionStatus
    // ============================================================

    @Test
    @DisplayName("transitionStatus - ASSIGNED self-assign (MANAGER actor)")
    void transition_selfAssign() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        ConciergeRequest cr = makeRequest(100L, applicant, null);

        stubActor(manager);
        when(userRepository.findById(10L)).thenReturn(Optional.of(manager));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("ASSIGNED");
        // assignedManagerSeq null → self-assign

        service.transitionStatus(100L, req, 10L, null);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.ASSIGNED);
        assertThat(cr.getAssignedManager().getUserSeq()).isEqualTo(10L);
    }

    @Test
    @DisplayName("transitionStatus - MANAGER가 타인 지정 ASSIGN 시도 → 403 FORBIDDEN")
    void transition_manager_assignOther_forbidden() {
        User actor = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User other = makeUser(20L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, null);

        stubActor(actor);
        when(userRepository.findById(20L)).thenReturn(Optional.of(other));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("ASSIGNED");
        req.setAssignedManagerSeq(20L);

        assertThatThrownBy(() -> service.transitionStatus(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("FORBIDDEN"));
    }

    @Test
    @DisplayName("transitionStatus - ADMIN이 타 매니저에게 ASSIGN")
    void transition_admin_assignOther() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        User target = makeUser(20L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, null);

        stubActor(admin);
        when(userRepository.findById(20L)).thenReturn(Optional.of(target));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("ASSIGNED");
        req.setAssignedManagerSeq(20L);

        service.transitionStatus(100L, req, 1L, null);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.ASSIGNED);
        assertThat(cr.getAssignedManager().getUserSeq()).isEqualTo(20L);
    }

    @Test
    @DisplayName("transitionStatus - target이 CONCIERGE_MANAGER 아니면 400 INVALID_MANAGER")
    void transition_nonManagerTarget_invalid() {
        User admin = makeUser(1L, UserRole.ADMIN, UserStatus.ACTIVE);
        User nonManager = makeUser(5L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, nonManager, null);

        stubActor(admin);
        when(userRepository.findById(5L)).thenReturn(Optional.of(nonManager));
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("ASSIGNED");
        req.setAssignedManagerSeq(5L);

        assertThatThrownBy(() -> service.transitionStatus(100L, req, 1L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("INVALID_MANAGER"));
    }

    @Test
    @DisplayName("transitionStatus - 전체 라이프사이클 CONTACTING→AWAITING_LOA→AWAITING_PAYMENT→IN_PROGRESS→COMPLETED")
    void transition_fullLifecycle() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);
        // APPLICATION_CREATED까지 수동 전이 (PR#5 대리 Application 경로 시뮬레이션)
        cr.markContacted();
        cr.linkApplication(42L);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        String[] transitions = {
            "AWAITING_APPLICANT_LOA_SIGN",
            "AWAITING_LICENCE_PAYMENT",
            "IN_PROGRESS",
            "COMPLETED"
        };
        for (String next : transitions) {
            StatusTransitionRequest req = new StatusTransitionRequest();
            req.setNextStatus(next);
            service.transitionStatus(100L, req, 10L, null);
        }

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.COMPLETED);
        assertThat(cr.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("transitionStatus - APPLICATION_CREATED → 400 USE_APPLICATION_ENDPOINT")
    void transition_applicationCreated_blocked() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);
        cr.markContacted();

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("APPLICATION_CREATED");

        assertThatThrownBy(() -> service.transitionStatus(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("USE_APPLICATION_ENDPOINT"));
    }

    @Test
    @DisplayName("transitionStatus - CANCELLED → 400 USE_CANCEL_ENDPOINT")
    void transition_cancelled_useCancelEndpoint() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("CANCELLED");

        assertThatThrownBy(() -> service.transitionStatus(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> assertThat(((BusinessException) e).getCode())
                .isEqualTo("USE_CANCEL_ENDPOINT"));
    }

    @Test
    @DisplayName("transitionStatus - 도메인 전이 가드 실패 시 409 INVALID_TRANSITION")
    void transition_domainGuardFails() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        // ASSIGNED 상태에서 COMPLETED로 곧장 시도 → 도메인 가드 IllegalState
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        StatusTransitionRequest req = new StatusTransitionRequest();
        req.setNextStatus("COMPLETED");

        assertThatThrownBy(() -> service.transitionStatus(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("INVALID_TRANSITION");
            });
    }

    // ============================================================
    // addNote
    // ============================================================

    @Test
    @DisplayName("addNote - 최초 노트 + ASSIGNED 상태 → CONTACTING 자동 전이")
    void addNote_firstOnAssigned_autoTransitionsToContacting() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);
        // ASSIGNED 상태 (makeRequest에서 assignManager 호출)
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.ASSIGNED);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        NoteAddRequest req = new NoteAddRequest();
        req.setChannel(NoteChannel.PHONE);
        req.setContent("Called applicant at 10:30");

        NoteResponse response = service.addNote(100L, req, 10L, null);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.CONTACTING);
        assertThat(cr.getFirstContactAt()).isNotNull();
        assertThat(response.getChannel()).isEqualTo("PHONE");
        assertThat(response.getAuthorUserSeq()).isEqualTo(10L);
    }

    @Test
    @DisplayName("addNote - 이미 CONTACTING → 상태 유지 + 노트만 추가")
    void addNote_onContacting_noTransition() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);
        cr.markContacted();  // 이미 CONTACTING
        LocalDateTime firstContact = cr.getFirstContactAt();

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        // 기존 노트 존재 (firstNote=false로 만들기 위해 non-empty list)
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of(mock(ConciergeNote.class)));

        NoteAddRequest req = new NoteAddRequest();
        req.setChannel(NoteChannel.EMAIL);
        req.setContent("Follow-up email");

        service.addNote(100L, req, 10L, null);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.CONTACTING);
        assertThat(cr.getFirstContactAt()).isEqualTo(firstContact);  // 보존
    }

    // ============================================================
    // resendSetupEmail
    // ============================================================

    @Test
    @DisplayName("resendSetupEmail - PENDING_ACTIVATION 유저 → 토큰 발급 + 이메일")
    void resend_pendingUser() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        AccountSetupToken token = AccountSetupToken.builder()
            .tokenUuid("uuid-xyz").user(applicant)
            .source(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP)
            .expiresAt(LocalDateTime.now().plusHours(48))
            .build();
        when(tokenService.issue(eq(applicant), eq(AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP), any()))
            .thenReturn(token);

        service.resendSetupEmail(100L, 10L, null);

        // 트랜잭션 컨텍스트 외부 호출 → afterCommit 즉시 실행 → 이메일 발송 확인
        verify(emailService).sendAccountSetupLinkEmail(
            eq(applicant.getEmail()),
            eq(applicant.getFullName()),
            eq("http://localhost:5174/setup-account/uuid-xyz"),
            anyString());
    }

    @Test
    @DisplayName("resendSetupEmail - ACTIVE 유저 → 409 NOT_PENDING")
    void resend_activeUser_rejected() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        assertThatThrownBy(() -> service.resendSetupEmail(100L, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("NOT_PENDING");
            });
        verify(tokenService, never()).issue(any(), any(), any());
    }

    // ============================================================
    // cancel
    // ============================================================

    @Test
    @DisplayName("cancel - 진행 중 상태에서 CANCELLED 전이")
    void cancel_inProgress() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);
        // CONTACTING 상태
        cr.markContacted();

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));
        when(noteRepository.findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(100L))
            .thenReturn(List.of());

        CancelRequest req = new CancelRequest();
        req.setReason("Applicant request");

        service.cancel(100L, req, 10L, null);

        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.CANCELLED);
        assertThat(cr.getCancellationReason()).isEqualTo("Applicant request");
    }

    @Test
    @DisplayName("cancel - COMPLETED 상태에서 → 409 INVALID_TRANSITION")
    void cancel_completed_rejected() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        ConciergeRequest cr = makeRequest(100L, makeUser(2L, UserRole.APPLICANT, UserStatus.ACTIVE), manager);
        cr.markContacted();
        cr.linkApplication(42L);
        cr.requestLoaSign();
        cr.markLoaSigned();
        cr.markLicencePaid();
        cr.markCompleted();

        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        CancelRequest req = new CancelRequest();
        req.setReason("too late");

        assertThatThrownBy(() -> service.cancel(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("INVALID_TRANSITION");
            });
    }

    // ============================================================
    // createApplicationOnBehalf (PR#5 Stage A)
    // ============================================================

    @Test
    @DisplayName("createApplicationOnBehalf - CONTACTING 상태 + 배정된 Manager → 성공 + APPLICATION_CREATED 전이")
    void createOnBehalf_contactingState_success() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(200L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);
        cr.markContacted();
        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        ApplicationResponse appResp = ApplicationResponse.builder()
            .applicationSeq(777L)
            .build();
        when(applicationService.createOnBehalfOf(eq(200L), eq(100L), any(CreateApplicationRequest.class)))
            .thenReturn(appResp);

        CreateApplicationRequest req = new CreateApplicationRequest();

        CreateOnBehalfResponse resp = service.createApplicationOnBehalf(100L, req, 10L, null);

        assertThat(resp.getApplicationSeq()).isEqualTo(777L);
        assertThat(resp.getConciergeRequestSeq()).isEqualTo(100L);
        assertThat(resp.getConciergeStatus()).isEqualTo("APPLICATION_CREATED");
        assertThat(cr.getStatus()).isEqualTo(ConciergeRequestStatus.APPLICATION_CREATED);
        assertThat(cr.getApplicationSeq()).isEqualTo(777L);
        verify(applicationService).createOnBehalfOf(eq(200L), eq(100L), any(CreateApplicationRequest.class));
    }

    @Test
    @DisplayName("createApplicationOnBehalf - ASSIGNED 상태에서 호출 → 409 INVALID_STATE_FOR_APPLICATION")
    void createOnBehalf_assignedState_conflicts() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(200L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        ConciergeRequest cr = makeRequest(100L, applicant, manager);
        // ASSIGNED 상태 유지 (markContacted 호출 안 함)
        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        CreateApplicationRequest req = new CreateApplicationRequest();

        assertThatThrownBy(() -> service.createApplicationOnBehalf(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(be.getCode()).isEqualTo("INVALID_STATE_FOR_APPLICATION");
            });
        verify(applicationService, never()).createOnBehalfOf(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("createApplicationOnBehalf - 타 Manager → 403 CONCIERGE_NOT_ASSIGNED")
    void createOnBehalf_otherManager_forbidden() {
        User manager = makeUser(10L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User otherManager = makeUser(11L, UserRole.CONCIERGE_MANAGER, UserStatus.ACTIVE);
        User applicant = makeUser(200L, UserRole.APPLICANT, UserStatus.PENDING_ACTIVATION);
        ConciergeRequest cr = makeRequest(100L, applicant, otherManager);
        cr.markContacted();
        stubActor(manager);
        when(conciergeRepository.findById(100L)).thenReturn(Optional.of(cr));

        CreateApplicationRequest req = new CreateApplicationRequest();

        assertThatThrownBy(() -> service.createApplicationOnBehalf(100L, req, 10L, null))
            .isInstanceOf(BusinessException.class)
            .satisfies(e -> {
                BusinessException be = (BusinessException) e;
                assertThat(be.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                assertThat(be.getCode()).isEqualTo("CONCIERGE_NOT_ASSIGNED");
            });
    }
}
