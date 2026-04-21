package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.application.ApplicationService;
import com.bluelight.backend.api.application.dto.ApplicationResponse;
import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.auth.AccountSetupTokenService;
import com.bluelight.backend.api.concierge.dto.ApplicantStatusInfo;
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
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import com.bluelight.backend.domain.user.UserStatus;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
        Long filterManagerSeq = ConciergeOwnershipValidator.resolveListFilterManagerSeq(actor);
        ConciergeRequestStatus status = parseStatusOrNull(statusStr);

        int validPage = Math.max(0, page);
        int validSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(validPage, validSize,
            Sort.by(Sort.Direction.DESC, "createdAt"));

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
        ConciergeOwnershipValidator.assertManagerCanAccess(request, actor);

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
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        // CONTACTING 또는 QUOTE_SENT 상태에서 대리 생성 허용
        // (PRD §5.2: CONTACTING → APPLICATION_CREATED, Phase 1.5: QUOTE_SENT → APPLICATION_CREATED)
        if (cr.getStatus() != ConciergeRequestStatus.CONTACTING
                && cr.getStatus() != ConciergeRequestStatus.QUOTE_SENT) {
            throw new BusinessException(
                "Application can only be created after first contact or quote is recorded "
                    + "(requires status=CONTACTING or QUOTE_SENT; current status=" + cr.getStatus() + ")",
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
