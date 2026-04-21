package com.bluelight.backend.api.concierge;

import com.bluelight.backend.api.application.dto.CreateApplicationRequest;
import com.bluelight.backend.api.concierge.dto.CancelRequest;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestDetail;
import com.bluelight.backend.api.concierge.dto.ConciergeRequestSummary;
import com.bluelight.backend.api.concierge.dto.CreateOnBehalfResponse;
import com.bluelight.backend.api.concierge.dto.NoteAddRequest;
import com.bluelight.backend.api.concierge.dto.NoteResponse;
import com.bluelight.backend.api.concierge.dto.SendQuoteRequest;
import com.bluelight.backend.api.concierge.dto.StatusTransitionRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Concierge Manager 대시보드 API (★ Kaki Concierge v1.5 Phase 1 PR#4 Stage A).
 * <p>
 * 권한: CONCIERGE_MANAGER / ADMIN / SYSTEM_ADMIN (SecurityConfig + @PreAuthorize 이중 방어).
 * - ADMIN 계열: 전체 조회/수정 가능.
 * - CONCIERGE_MANAGER: 본인에게 배정된 요청만 상세/수정 가능 (목록은 본인 배정만 조회).
 * <p>
 * 인증 주체 resolve는 기존 Admin Controller와 동일하게
 * {@code (Long) authentication.getPrincipal()} 패턴.
 */
@Slf4j
@RestController
@RequestMapping("/api/concierge-manager/requests")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('CONCIERGE_MANAGER', 'ADMIN', 'SYSTEM_ADMIN')")
public class ConciergeManagerController {

    private final ConciergeManagerService managerService;

    /**
     * 목록 조회 (페이지네이션 + 상태 필터 + 검색)
     * GET /api/concierge-manager/requests?status=CONTACTING&q=tan&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<Page<ConciergeRequestSummary>> list(
        Authentication authentication,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Concierge manager list: userSeq={}, status={}, q={}, page={}, size={}",
            userSeq, status, q, page, size);
        return ResponseEntity.ok(
            managerService.listForActor(userSeq, status, q, page, size));
    }

    /**
     * 상세 조회 (노트 타임라인 + 신청자 활성화 상태 포함)
     * GET /api/concierge-manager/requests/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<ConciergeRequestDetail> detail(
        Authentication authentication,
        @PathVariable Long id) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(managerService.getDetail(id, userSeq));
    }

    /**
     * 상태 전이
     * PATCH /api/concierge-manager/requests/{id}/status
     * body: { "nextStatus": "ASSIGNED", "assignedManagerSeq": null }
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<ConciergeRequestDetail> transitionStatus(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody StatusTransitionRequest request,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Concierge status transition: id={}, nextStatus={}, actorSeq={}",
            id, request.getNextStatus(), userSeq);
        return ResponseEntity.ok(
            managerService.transitionStatus(id, request, userSeq, httpRequest));
    }

    /**
     * 연락 노트 추가 (최초 노트 + ASSIGNED 상태이면 CONTACTING 자동 전이)
     * POST /api/concierge-manager/requests/{id}/notes
     */
    @PostMapping("/{id}/notes")
    public ResponseEntity<NoteResponse> addNote(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody NoteAddRequest request,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED).body(
            managerService.addNote(id, request, userSeq, httpRequest));
    }

    /**
     * 활성화 링크 재발송 (신청자가 PENDING_ACTIVATION일 때만)
     * POST /api/concierge-manager/requests/{id}/resend-setup-email
     */
    @PostMapping("/{id}/resend-setup-email")
    public ResponseEntity<Void> resendSetupEmail(
        Authentication authentication,
        @PathVariable Long id,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        managerService.resendSetupEmail(id, userSeq, httpRequest);
        return ResponseEntity.accepted().build();
    }

    /**
     * 대리 Application 생성 (★ Kaki Concierge v1.5 Phase 1 PR#5 Stage A).
     * CONTACTING 상태에서만 허용. 성공 시 ConciergeRequest는 APPLICATION_CREATED로 자동 전이.
     * POST /api/concierge-manager/requests/{id}/applications
     * body: CreateApplicationRequest (applicant 경로와 동일 스키마)
     */
    @PostMapping("/{id}/applications")
    public ResponseEntity<CreateOnBehalfResponse> createApplicationOnBehalf(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody CreateApplicationRequest request,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Concierge on-behalf application: conciergeRequestSeq={}, managerSeq={}",
            id, userSeq);
        CreateOnBehalfResponse result = managerService.createApplicationOnBehalf(
            id, request, userSeq, httpRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }

    /**
     * 견적 발송 (Phase 1.5) — 통화 후 견적 금액·일정을 기록하고 신청자에게 이메일 발송.
     * POST /api/concierge-manager/requests/{id}/quote
     * body: { quotedAmount, callScheduledAt?, note? }
     */
    @PostMapping("/{id}/quote")
    public ResponseEntity<ConciergeRequestDetail> sendQuote(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody SendQuoteRequest request,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Concierge quote email: id={}, amount={}, managerSeq={}",
            id, request.getQuotedAmount(), userSeq);
        return ResponseEntity.ok(
            managerService.sendQuote(id, request, userSeq, httpRequest));
    }

    /**
     * 취소 처리
     * PATCH /api/concierge-manager/requests/{id}/cancel
     * body: { "reason": "..." }
     */
    @PatchMapping("/{id}/cancel")
    public ResponseEntity<ConciergeRequestDetail> cancel(
        Authentication authentication,
        @PathVariable Long id,
        @Valid @RequestBody CancelRequest request,
        HttpServletRequest httpRequest) {
        Long userSeq = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(
            managerService.cancel(id, request, userSeq, httpRequest));
    }
}
