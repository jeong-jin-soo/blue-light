package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.application.ApplicationService;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.ApplicantStatusInfo;
import com.bluelight.backend.api.concierge.dto.AssignLewRequest;
import com.bluelight.backend.api.concierge.dto.AssignLewResponse;
import com.bluelight.backend.api.concierge.dto.CancelRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestDetail;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestSummary;
import com.bluelight.backend.api.concierge.dto.CreateOnBehalfResponse;
import com.bluelight.backend.api.concierge.dto.NoteAddRequest;
import com.bluelight.backend.api.concierge.dto.NoteResponse;
import com.bluelight.backend.api.concierge.dto.SendQuoteRequest;
import com.bluelight.backend.api.concierge.dto.StatusTransitionRequest;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.ConciergeOwnershipValidator;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.concierge.ConciergeNote;
import com.bluelight.backend.domain.concierge.ConciergeNoteRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.user.AccountSetupToken;
import com.bluelight.backend.domain.user.AccountSetupTokenRepository;
import com.bluelight.backend.domain.user.AccountSetupTokenSource;
import com.bluelight.backend.domain.user.ApprovalStatus;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Concierge Manager 대시보드 비즈니스 로직 (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage A).
 * <p>
 * - ADMIN/SYSTEM_ADMIN은 전체, CONCIERGE_MANAGER는 자기 배정 건만 (ConciergeOwnershipValidator).
 * - 상태 전이는 도메인 메서드({@code cr.assignManager()}, {@code markContacted()} 등) 위임.
 * - APPLICATION_CREATED 전이는 PR#5(on-behalf Application) 전용 엔드포인트 사용 — 여기서는 차단.
 * - 감사 로그: CONCIERGE_STATUS_TRANSITION / CONCIERGE_NOTE_ADDED / CONCIERGE_CANCELLED 등.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConciergeManagerService {

    private final ApplicationService applicationService;
    private final ConciergeRequestRepository conciergeRepository;
    private final ConciergeNoteRepository noteRepository;
    private final UserRepository userRepository;
    private final AccountSetupTokenRepository tokenRepository;
    private final AccountSetupTokenService tokenService;
    private final EmailService emailService;
    private final AuditLogService auditLogService;
    private final ConciergeNotifier notifier;
    /** ★ PR-3: ConciergeLewAssignedEvent 발행 — AFTER_COMMIT 알림 listener 가 구독. */
    private final ApplicationEventPublisher eventPublisher;

    @Value("${concierge.account-setup.base-url}")
    private String setupBaseUrl;

    private static final DateTimeFormatter EXPIRES_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'SGT'");
    private static final ZoneId SG_ZONE = ZoneId.of("Asia/Singapore");

    // ────────────────────────────────────────────────────────────
    // 목록
    // ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ConciergeRequestSummary> listForActor(Long actorSeq, String statusStr,
                                                       String q, int page, int size) {
        User actor = loadActor(actorSeq);
        ConciergeRequestStatus status = parseStatusOrNull(statusStr);

        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(validPage, validSize,
            Sort.by(Sort.Direction.DESC, "createdAt"));

        // ★ PR-3 (D7=B): LEW 만 가진 사용자(매니저/ADMIN 권한 없음)는 본인 배정 LEW row 만 조회.
        boolean isManagerOrAdmin = actor.hasRole(UserRole.ADMIN)
            || actor.hasRole(UserRole.SYSTEM_ADMIN)
            || actor.hasRole(UserRole.CONCIERGE_MANAGER);
        if (!isManagerOrAdmin && actor.hasRole(UserRole.LEW)) {
            // LEW 단독 사용자 — assignedLewSeq 필터.
            // 검색 q 와 status 필터는 본 PR 범위 외(LEW UI 가 단순). 추후 PR-4 에서 확장 가능.
            Page<ConciergeRequest> results = conciergeRepository
                .findByAssignedLewSeqOrderByCreatedAtDesc(actor.getUserSeq(), pageable);
            return results.map(this::toSummary);
        }

        Long filterManagerSeq = ConciergeOwnershipValidator.resolveListFilterManagerSeq(actor);
        String normalizedQ = (q == null || q.isBlank()) ? null : q.trim();
        Page<ConciergeRequest> results = conciergeRepository.searchForDashboard(
            filterManagerSeq, status, normalizedQ, pageable);
        return results.map(this::toSummary);
    }

    // ────────────────────────────────────────────────────────────
    // 상세
    // ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public ConciergeRequestDetail getDetail(Long id, Long actorSeq) {
        User actor = loadActor(actorSeq);
        ConciergeRequest request = loadRequest(id);
        // ★ PR-3 (D7=B): LEW 가 본인 assigned ConciergeRequest 상세를 조회할 수 있도록
        // assertAccessible 로 통합 (ADMIN/매니저 + 배정 LEW 모두 통과).
        ConciergeOwnershipValidator.assertAccessible(request, actor);

        List<ConciergeNote> notes = noteRepository
            .findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(id);
        return toDetail(request, notes);
    }

    // ────────────────────────────────────────────────────────────
    // 상태 전이
    // ────────────────────────────────────────────────────────────

    @Transactional
    public ConciergeRequestDetail transitionStatus(Long id, StatusTransitionRequest request,
                                                    Long actorSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(id);

        ConciergeRequestStatus next = parseStatusOrThrow(request.getNextStatus());

        if (next == ConciergeRequestStatus.ASSIGNED) {
            assignManagerTransition(cr, request, actor);
        } else {
            ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);
            switch (next) {
                case CONTACTING:
                    invokeDomain(() -> cr.markContacted());
                    break;
                case APPLICATION_CREATED:
                    throw new BusinessException(
                        "Use the on-behalf Application creation endpoint instead.",
                        HttpStatus.BAD_REQUEST, "USE_APPLICATION_ENDPOINT");
                case AWAITING_APPLICANT_LOA_SIGN:
                    invokeDomain(() -> cr.requestLoaSign());
                    break;
                case AWAITING_LICENCE_PAYMENT:
                    invokeDomain(() -> cr.markLoaSigned());
                    break;
                case IN_PROGRESS:
                    invokeDomain(() -> cr.markLicencePaid());
                    break;
                case COMPLETED:
                    invokeDomain(() -> cr.markCompleted());
                    break;
                case CANCELLED:
                    throw new BusinessException(
                        "Use the /cancel endpoint instead.",
                        HttpStatus.BAD_REQUEST, "USE_CANCEL_ENDPOINT");
                case SUBMITTED:
                case ASSIGNED:
                    // ASSIGNED는 위에서 처리했고, SUBMITTED는 역행 불가
                    throw new BusinessException("Unsupported transition target: " + next,
                        HttpStatus.BAD_REQUEST, "UNSUPPORTED_TRANSITION");
            }
        }

        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.CONCIERGE_STATUS_TRANSITION, AuditCategory.APPLICATION,
            "concierge_request", cr.getConciergeRequestSeq().toString(),
            "Transition to " + cr.getStatus(), null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "PATCH", "/api/concierge-manager/requests/{id}/status", 200);

        List<ConciergeNote> notes = noteRepository
            .findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(
                cr.getConciergeRequestSeq());
        return toDetail(cr, notes);
    }

    /**
     * ASSIGNED 전이 전용 처리. ADMIN은 임의 매니저 지정 가능, MANAGER는 self-assign만 허용.
     */
    private void assignManagerTransition(ConciergeRequest cr, StatusTransitionRequest request,
                                          User actor) {
        Long targetManagerSeq = request.getAssignedManagerSeq() != null
            ? request.getAssignedManagerSeq()
            : actor.getUserSeq();

        User target = userRepository.findById(targetManagerSeq)
            .orElseThrow(() -> new BusinessException(
                "Target manager not found",
                HttpStatus.BAD_REQUEST, "INVALID_MANAGER"));

        if (target.getRole() != UserRole.CONCIERGE_MANAGER) {
            throw new BusinessException(
                "Target user is not a Concierge Manager",
                HttpStatus.BAD_REQUEST, "INVALID_MANAGER");
        }

        if (actor.getRole() == UserRole.CONCIERGE_MANAGER
            && !target.getUserSeq().equals(actor.getUserSeq())) {
            throw new BusinessException(
                "Managers can only self-assign",
                HttpStatus.FORBIDDEN, "FORBIDDEN");
        }

        invokeDomain(() -> cr.assignManager(target));
    }

    // ────────────────────────────────────────────────────────────
    // 노트 추가
    // ────────────────────────────────────────────────────────────

    @Transactional
    public NoteResponse addNote(Long id, NoteAddRequest request,
                                 Long actorSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(id);
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        // 최초 노트 + ASSIGNED 상태이면 CONTACTING 자동 전이 (SLA firstContactAt 기록)
        boolean isFirstNote = noteRepository
            .findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(id).isEmpty();
        if (isFirstNote && cr.getStatus() == ConciergeRequestStatus.ASSIGNED) {
            cr.markContacted();
        }

        ConciergeNote note = ConciergeNote.builder()
            .conciergeRequest(cr)
            .author(actor)
            .channel(request.getChannel())
            .content(request.getContent())
            .build();
        note = noteRepository.save(note);

        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.CONCIERGE_NOTE_ADDED, AuditCategory.APPLICATION,
            "concierge_request", cr.getConciergeRequestSeq().toString(),
            "Note added via " + request.getChannel(), null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "POST", "/api/concierge-manager/requests/{id}/notes", 201);

        return NoteResponse.builder()
            .conciergeNoteSeq(note.getConciergeNoteSeq())
            .authorUserSeq(actor.getUserSeq())
            .authorName(actor.getFullName())
            .channel(note.getChannel().name())
            .content(note.getContent())
            .createdAt(note.getCreatedAt())
            .build();
    }

    // ────────────────────────────────────────────────────────────
    // 활성화 링크 재발송
    // ────────────────────────────────────────────────────────────

    @Transactional
    public void resendSetupEmail(Long id, Long actorSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(id);
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        User applicant = cr.getApplicantUser();
        if (applicant == null) {
            throw new BusinessException("Applicant not linked",
                HttpStatus.CONFLICT, "APPLICANT_MISSING");
        }
        if (applicant.getStatus() != UserStatus.PENDING_ACTIVATION) {
            throw new BusinessException(
                "Applicant is not in PENDING_ACTIVATION state",
                HttpStatus.CONFLICT, "NOT_PENDING");
        }

        AccountSetupToken token = tokenService.issue(applicant,
            AccountSetupTokenSource.CONCIERGE_ACCOUNT_SETUP, httpRequest);

        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.ACCOUNT_SETUP_TOKEN_ISSUED, AuditCategory.AUTH,
            "user", applicant.getUserSeq().toString(),
            "Manager resent setup token", null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "POST", "/api/concierge-manager/requests/{id}/resend-setup-email", 202);

        final String email = applicant.getEmail();
        final String name = applicant.getFullName();
        final String setupUrl = setupBaseUrl + "/setup-account/" + token.getTokenUuid();
        final String expStr = token.getExpiresAt().atZone(SG_ZONE).format(EXPIRES_FMT);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        safeSend(email, name, setupUrl, expStr);
                    }
                });
        } else {
            safeSend(email, name, setupUrl, expStr);
        }
    }

    private void safeSend(String email, String name, String url, String exp) {
        try {
            emailService.sendAccountSetupLinkEmail(email, name, url, exp);
        } catch (Exception e) {
            log.warn("resend setup email failed (suppressed): email={}, err={}",
                email, e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────
    // 취소
    // ────────────────────────────────────────────────────────────

    // ────────────────────────────────────────────────────────────
    // 견적 발송 (★ Phase 1.5 — 통화 후 이메일로 견적 + 일정 + PayNow QR 송부)
    // ────────────────────────────────────────────────────────────

    /**
     * 매니저가 통화 후 수집한 견적과 일정을 저장하고, 신청자에게 견적 이메일을 발송한다.
     * <p>
     * 전이: CONTACTING → QUOTE_SENT (QUOTE_SENT 재호출은 금액/일정 덮어쓰기).
     * 이메일 발송은 afterCommit 훅에서 실행되며, 감사 로그도 함께 기록한다.
     */
    @Transactional
    public ConciergeRequestDetail sendQuote(Long id, SendQuoteRequest request,
                                             Long actorSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(id);
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        ConciergeRequestStatus current = cr.getStatus();
        if (current != ConciergeRequestStatus.CONTACTING
                && current != ConciergeRequestStatus.QUOTE_SENT) {
            throw new BusinessException(
                "Quote can only be sent from CONTACTING or QUOTE_SENT state (current=" + current + ")",
                HttpStatus.CONFLICT, "INVALID_STATE_FOR_QUOTE");
        }

        invokeDomain(() -> cr.recordQuote(request.getQuotedAmount(), request.getCallScheduledAt()));
        // 발송 시점 마킹은 afterCommit 실제 발송 성공 여부와 무관하게 "시도됨"을 의미
        cr.markQuoteEmailSent();

        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.CONCIERGE_QUOTE_EMAIL_SENT, AuditCategory.APPLICATION,
            "concierge_request", cr.getConciergeRequestSeq().toString(),
            "Quote issued by manager: amount=" + request.getQuotedAmount()
                + ", scheduled=" + request.getCallScheduledAt(),
            null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "POST", "/api/concierge-manager/requests/{id}/quote", 200);

        // 발송은 커밋 이후 — 실패해도 트랜잭션 롤백되지 않도록 notifier 내부에서 격리
        notifier.notifyQuoteSent(
            cr.getConciergeRequestSeq(),
            cr.getSubmitterEmail(),
            cr.getSubmitterName(),
            cr.getPublicCode(),
            request.getQuotedAmount(),
            request.getCallScheduledAt(),
            request.getNote(),
            cr.getVerificationPhrase());

        List<ConciergeNote> notes = noteRepository
            .findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(id);
        return toDetail(cr, notes);
    }

    @Transactional
    public ConciergeRequestDetail cancel(Long id, CancelRequest request,
                                          Long actorSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(id);
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        invokeDomain(() -> cr.cancel(request.getReason()));

        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.CONCIERGE_CANCELLED, AuditCategory.APPLICATION,
            "concierge_request", cr.getConciergeRequestSeq().toString(),
            "Cancelled by manager: " + request.getReason(), null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "PATCH", "/api/concierge-manager/requests/{id}/cancel", 200);

        List<ConciergeNote> notes = noteRepository
            .findAllByConciergeRequest_ConciergeRequestSeqOrderByCreatedAtDesc(id);
        return toDetail(cr, notes);
    }

    // ────────────────────────────────────────────────────────────
    // 대리 Application 생성 (★ Phase 1 PR#5 Stage A)
    // ────────────────────────────────────────────────────────────

    /**
     * Concierge Manager가 대리 Application을 생성한다.
     * <p>
     * 전이 요건: ConciergeRequest.status = CONTACTING (첫 노트로 자동 전이 이후만 허용).
     * 성공 시 ConciergeRequest.status = APPLICATION_CREATED로 자동 전이 + applicationSeq 연결.
     * Application.viaConciergeRequestSeq = conciergeRequestSeq 기록.
     *
     * @param conciergeRequestId 대상 ConciergeRequest seq
     * @param appRequest         신청서 본문 (기존 CreateApplicationRequest 재사용)
     * @param managerSeq         Manager userSeq (감사 로그 actor)
     */
    @Transactional
    public CreateOnBehalfResponse createApplicationOnBehalf(
            Long conciergeRequestId, CreateApplicationRequest appRequest,
            Long managerSeq, HttpServletRequest httpRequest) {
        User actor = loadActor(managerSeq);
        ConciergeRequest cr = loadRequest(conciergeRequestId);
        // ★ PR-3 (D7=B): assigned LEW 도 호출 가능 — assertAccessible 로 통합.
        // ADMIN/매니저는 기존 동작 보존 (AC-D3), LEW 는 본인 assignedLewSeq 일 때만 통과 (AC-D1/D2/D4).
        ConciergeOwnershipValidator.assertAccessible(cr, actor);

        // ★ PR-3: LEW_ASSIGNED 상태에서도 대리 생성 허용 (LEW 가 신청서를 만드는 정상 동선).
        // CONTACTING/QUOTE_SENT 는 매니저가 LEW 할당 전에도 만들 수 있도록 기존 동작 유지.
        ConciergeRequestStatus current = cr.getStatus();
        if (current != ConciergeRequestStatus.CONTACTING
                && current != ConciergeRequestStatus.QUOTE_SENT
                && current != ConciergeRequestStatus.LEW_ASSIGNED) {
            throw new BusinessException(
                "Application can only be created after first contact or quote is recorded "
                    + "(requires status=CONTACTING, QUOTE_SENT, or LEW_ASSIGNED; current status=" + current + ")",
                HttpStatus.CONFLICT, "INVALID_STATE_FOR_APPLICATION");
        }

        User applicant = cr.getApplicantUser();
        if (applicant == null) {
            // 이론상 도달 불가 (ConciergeRequest.applicantUser는 nullable=false)
            throw new BusinessException("Applicant user missing for concierge request",
                HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");
        }

        // 1. Application 대리 생성 — owner=applicant, viaConciergeRequestSeq=cr.seq
        ApplicationResponse created = applicationService.createOnBehalfOf(
            applicant.getUserSeq(), cr.getConciergeRequestSeq(), appRequest);

        // 2. ConciergeRequest 자동 전이 CONTACTING → APPLICATION_CREATED + applicationSeq 세팅
        invokeDomain(() -> cr.linkApplication(created.getApplicationSeq()));

        // 3. 감사 로그 — Actor는 Manager, Subject는 Applicant, Entity는 Application
        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.APPLICATION_CREATED_ON_BEHALF, AuditCategory.APPLICATION,
            "application", created.getApplicationSeq().toString(),
            "Application created on behalf of applicant " + applicant.getUserSeq()
                + " via concierge " + cr.getPublicCode(),
            null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "POST", "/api/concierge-manager/requests/{id}/applications", 201);

        return CreateOnBehalfResponse.builder()
            .applicationSeq(created.getApplicationSeq())
            .conciergeRequestSeq(cr.getConciergeRequestSeq())
            .conciergeStatus(cr.getStatus().name())
            .build();
    }

    // ────────────────────────────────────────────────────────────
    // ★ PR-3 — LEW 배정 (셀프 할당 포함, D6=A)
    // ────────────────────────────────────────────────────────────

    /**
     * LEW 배정/재배정 엔드포인트의 비즈니스 로직.
     *
     * <p>스펙: §5 / §10 AC-L1~L4, §14 PR-3.</p>
     *
     * <h3>흐름</h3>
     * <ol>
     *   <li>actor 로드 + ConciergeRequest 로드 + ownership 검증 (매니저: 본인 배정 / ADMIN: 우회)</li>
     *   <li>LEW 후보 검증: hasRole(LEW), status ACTIVE, approvedStatus APPROVED</li>
     *   <li>도메인 메서드 {@link ConciergeRequest#assignLewWithTransition} 호출 — 상태 전이 + 시각 갱신 + 이전 LEW 반환</li>
     *   <li>AuditLog 기록 (selfAssign 플래그 metadata 포함)</li>
     *   <li>ConciergeLewAssignedEvent 발행 — listener 가 AFTER_COMMIT 으로 알림 처리</li>
     * </ol>
     *
     * <h3>D6=A 셀프 할당</h3>
     * lewUserSeq == actor.userSeq 이고 actor 가 LEW role 보유 시 정상 처리. 음소거는 listener 책임.
     */
    @Transactional
    public AssignLewResponse assignLew(Long conciergeRequestId,
                                         AssignLewRequest request,
                                         Long actorSeq,
                                         HttpServletRequest httpRequest) {
        // ── 1) 입력 ──
        if (request == null || request.getLewUserSeq() == null) {
            throw new BusinessException("lewUserSeq is required",
                HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        Long lewUserSeq = request.getLewUserSeq();

        User actor = loadActor(actorSeq);
        ConciergeRequest cr = loadRequest(conciergeRequestId);

        // ── 2) ownership: 매니저 본인 배정 또는 ADMIN ──
        // 본 엔드포인트는 LEW 호출자를 받지 않는다 — 매니저/ADMIN 만 assign-lew 가능.
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        // ── 3) LEW 후보 검증 ──
        User targetLew = userRepository.findById(lewUserSeq)
            .orElseThrow(() -> new BusinessException(
                "Target LEW user not found", HttpStatus.BAD_REQUEST, "USER_NOT_FOUND"));

        // role: hasRole(LEW) — primary 또는 secondary 어디든 보유하면 통과 (D1=B 다중 역할).
        if (!targetLew.hasRole(UserRole.LEW)) {
            throw new BusinessException(
                "Target user does not have the LEW role",
                HttpStatus.BAD_REQUEST, "USER_NOT_LEW");
        }

        // status 활성 + 승인 검증
        if (targetLew.getStatus() != UserStatus.ACTIVE) {
            throw new BusinessException(
                "Target LEW is not active (status=" + targetLew.getStatus() + ")",
                HttpStatus.BAD_REQUEST, "USER_INACTIVE");
        }
        // primary 가 LEW 인 경우에만 approvedStatus 가 의미 있음 (다중 역할 매니저+LEW 의 경우 primary=CONCIERGE_MANAGER 이면 approvedStatus null).
        // 안전망: primary 또는 secondary 로 LEW 를 보유하지만 LEW 활동을 위한 승인 정보가 있으면 검증, 없으면 통과.
        if (targetLew.getRole() == UserRole.LEW
                && targetLew.getApprovedStatus() != ApprovalStatus.APPROVED) {
            throw new BusinessException(
                "Target LEW is not approved",
                HttpStatus.BAD_REQUEST, "LEW_NOT_APPROVED");
        }

        // ── 4) 셀프 할당 판정 (D6=A) ──
        boolean selfAssigned = lewUserSeq.equals(actor.getUserSeq()) && actor.hasRole(UserRole.LEW);

        // ── 5) 도메인 메서드 호출 ──
        Long previousLewSeq;
        try {
            previousLewSeq = cr.assignLewWithTransition(lewUserSeq, LocalDateTime.now());
        } catch (IllegalStateException e) {
            // 예: COMPLETED/CANCELLED 등 진입 불가 상태
            throw new BusinessException(
                "Cannot assign LEW from current status: " + cr.getStatus(),
                HttpStatus.CONFLICT, "INVALID_TRANSITION");
        }

        // ── 6) Audit ──
        StringBuilder description = new StringBuilder();
        description.append("Concierge LEW assigned: lewUserSeq=").append(lewUserSeq)
                   .append(", lewName=").append(targetLew.getFullName())
                   .append(", publicCode=").append(cr.getPublicCode())
                   .append(", selfAssign=").append(selfAssigned);
        if (previousLewSeq != null) {
            description.append(", previousLewSeq=").append(previousLewSeq);
        }
        auditLogService.log(
            actor.getUserSeq(), actor.getEmail(), actor.getRole().name(),
            AuditAction.CONCIERGE_LEW_ASSIGNED, AuditCategory.APPLICATION,
            "concierge_request", String.valueOf(cr.getConciergeRequestSeq()),
            description.toString(), null, null,
            extractIp(httpRequest), userAgent(httpRequest),
            "POST", "/api/concierge-manager/requests/" + cr.getConciergeRequestSeq() + "/assign-lew", 200);

        // ── 7) AFTER_COMMIT 이벤트 발행 ──
        eventPublisher.publishEvent(new ConciergeLewAssignedEvent(
            cr.getConciergeRequestSeq(),
            cr.getPublicCode(),
            lewUserSeq,
            previousLewSeq,
            actor.getUserSeq(),
            selfAssigned,
            cr.getSubmitterName(),
            cr.getSubmitterEmail(),
            cr.getSubmitterPhone(),
            cr.getMemo()));

        log.info("Concierge LEW assigned: conciergeRequestSeq={}, lewSeq={}, previousLewSeq={}, selfAssign={}, by actorSeq={}",
            cr.getConciergeRequestSeq(), lewUserSeq, previousLewSeq, selfAssigned, actor.getUserSeq());

        return AssignLewResponse.builder()
            .conciergeRequestSeq(cr.getConciergeRequestSeq())
            .assignedLewSeq(lewUserSeq)
            .assignedLewName(targetLew.getFullName())
            .lewAssignedAt(cr.getLewAssignedAt())
            .previousLewSeq(previousLewSeq)
            .selfAssigned(selfAssigned)
            .status(cr.getStatus().name())
            .build();
    }

    // ────────────────────────────────────────────────────────────
    // 공통 유틸
    // ────────────────────────────────────────────────────────────

    private User loadActor(Long actorSeq) {
        if (actorSeq == null) {
            throw new BusinessException("Unauthenticated",
                HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        return userRepository.findById(actorSeq)
            .orElseThrow(() -> new BusinessException(
                "Actor user not found", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    }

    private ConciergeRequest loadRequest(Long id) {
        return conciergeRepository.findById(id)
            .orElseThrow(() -> new BusinessException(
                "Concierge request not found",
                HttpStatus.NOT_FOUND, "NOT_FOUND"));
    }

    /**
     * nextStatus 문자열 파싱. 잘못된 값이면 400 BAD_REQUEST.
     * enum valueOf IllegalArgumentException을 BusinessException으로 변환.
     */
    private ConciergeRequestStatus parseStatusOrThrow(String statusStr) {
        try {
            return ConciergeRequestStatus.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("Invalid status value: " + statusStr,
                HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }

    /**
     * 쿼리 파라미터 status 파싱. 빈값/null은 null 반환(필터 제외).
     */
    private ConciergeRequestStatus parseStatusOrNull(String statusStr) {
        if (statusStr == null || statusStr.isBlank()) {
            return null;
        }
        return parseStatusOrThrow(statusStr);
    }

    /**
     * 도메인 전이 메서드의 IllegalStateException을 409 CONFLICT로 변환.
     */
    private void invokeDomain(Runnable op) {
        try {
            op.run();
        } catch (IllegalStateException e) {
            throw new BusinessException(e.getMessage(),
                HttpStatus.CONFLICT, "INVALID_TRANSITION");
        }
    }

    private ConciergeRequestSummary toSummary(ConciergeRequest cr) {
        User assigned = cr.getAssignedManager();
        User applicant = cr.getApplicantUser();
        return ConciergeRequestSummary.builder()
            .conciergeRequestSeq(cr.getConciergeRequestSeq())
            .publicCode(cr.getPublicCode())
            .submitterName(cr.getSubmitterName())
            .submitterEmail(cr.getSubmitterEmail())
            .submitterPhone(cr.getSubmitterPhone())
            .status(cr.getStatus().name())
            .slaBreached(cr.isSlaBreached())
            .assignedManagerSeq(assigned != null ? assigned.getUserSeq() : null)
            .assignedManagerName(assigned != null ? assigned.getFullName() : null)
            .applicationSeq(cr.getApplicationSeq())
            .applicantUserStatus(applicant != null ? applicant.getStatus().name() : null)
            .createdAt(cr.getCreatedAt())
            .firstContactAt(cr.getFirstContactAt())
            // ★ PR-3
            .assignedLewSeq(cr.getAssignedLewSeq())
            .lewAssignedAt(cr.getLewAssignedAt())
            .build();
    }

    private ConciergeRequestDetail toDetail(ConciergeRequest cr, List<ConciergeNote> notes) {
        User assigned = cr.getAssignedManager();
        User applicant = cr.getApplicantUser();

        List<NoteResponse> noteResponses = notes.stream()
            .map(n -> NoteResponse.builder()
                .conciergeNoteSeq(n.getConciergeNoteSeq())
                .authorUserSeq(n.getAuthor().getUserSeq())
                .authorName(n.getAuthor().getFullName())
                .channel(n.getChannel().name())
                .content(n.getContent())
                .createdAt(n.getCreatedAt())
                .build())
            .toList();

        // 신청자 활성화 상태 정보
        ApplicantStatusInfo applicantInfo = null;
        if (applicant != null) {
            List<AccountSetupToken> activeTokens =
                tokenRepository.findActiveTokensByUser(applicant.getUserSeq());
            AccountSetupToken activeToken = activeTokens.isEmpty() ? null : activeTokens.get(0);
            applicantInfo = ApplicantStatusInfo.builder()
                .userStatus(applicant.getStatus().name())
                .emailVerified(applicant.isEmailVerified())
                .activatedAt(applicant.getActivatedAt())
                .firstLoggedInAt(applicant.getFirstLoggedInAt())
                .hasActiveSetupToken(activeToken != null)
                .setupTokenExpiresAt(activeToken != null ? activeToken.getExpiresAt() : null)
                .build();
        }

        boolean marketing = Boolean.TRUE.equals(cr.getMarketingOptIn());

        // ★ PR-3: 배정 LEW 정보 채우기.
        Long assignedLewSeq = cr.getAssignedLewSeq();
        String lewName = null;
        String lewEmail = null;
        if (assignedLewSeq != null) {
            User lew = userRepository.findById(assignedLewSeq).orElse(null);
            if (lew != null) {
                lewName = lew.getFullName();
                lewEmail = lew.getEmail();
            }
        }

        return ConciergeRequestDetail.builder()
            .conciergeRequestSeq(cr.getConciergeRequestSeq())
            .publicCode(cr.getPublicCode())
            .submitterName(cr.getSubmitterName())
            .submitterEmail(cr.getSubmitterEmail())
            .submitterPhone(cr.getSubmitterPhone())
            .status(cr.getStatus().name())
            .slaBreached(cr.isSlaBreached())
            .assignedManagerSeq(assigned != null ? assigned.getUserSeq() : null)
            .assignedManagerName(assigned != null ? assigned.getFullName() : null)
            .applicationSeq(cr.getApplicationSeq())
            .createdAt(cr.getCreatedAt())
            .firstContactAt(cr.getFirstContactAt())
            .memo(cr.getMemo())
            .marketingOptIn(marketing)
            .assignedAt(cr.getAssignedAt())
            .applicationCreatedAt(cr.getApplicationCreatedAt())
            .loaRequestedAt(cr.getLoaRequestedAt())
            .loaSignedAt(cr.getLoaSignedAt())
            .licencePaidAt(cr.getLicencePaidAt())
            .completedAt(cr.getCompletedAt())
            .cancelledAt(cr.getCancelledAt())
            .cancellationReason(cr.getCancellationReason())
            .callScheduledAt(cr.getCallScheduledAt())
            .quotedAmount(cr.getQuotedAmount())
            .quoteSentAt(cr.getQuoteSentAt())
            .verificationPhrase(cr.getVerificationPhrase())
            // ★ PR-3 필드
            .assignedLewSeq(assignedLewSeq)
            .assignedLewName(lewName)
            .assignedLewEmail(lewEmail)
            .lewAssignedAt(cr.getLewAssignedAt())
            .notes(noteResponses)
            .applicantStatus(applicantInfo)
            .build();
    }

    private static String extractIp(HttpServletRequest request) {
        if (request == null) return null;
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    private static String userAgent(HttpServletRequest request) {
        return request != null ? request.getHeader("User-Agent") : null;
    }
}
