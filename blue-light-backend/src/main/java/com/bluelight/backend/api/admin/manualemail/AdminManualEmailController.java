package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchHistoryItem;
import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchResponse;
import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * ADMIN 수동 이메일 발송 컨트롤러 (PR-1).
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §5 엔드포인트.</p>
 *
 * <p>권한: {@code @PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")} — 모든 엔드포인트 동일.</p>
 *
 * <h2>PR-1 엔드포인트</h2>
 * <ul>
 *   <li>{@code POST   /api/admin/manual-emails} — 단일 수신자 발송 (APPLICANT/LEW/EXTERNAL).
 *       MULTI 는 서비스 레이어에서 400 {@code MULTI_NOT_SUPPORTED_IN_PR1} 거부.</li>
 *   <li>{@code GET    /api/admin/manual-emails} — 페이지네이션된 이력 (필터 4종).</li>
 *   <li>{@code GET    /api/admin/manual-emails/{seq}} — 단건 상세 (전체 본문 포함).</li>
 * </ul>
 *
 * <p>PR-2 에서 MULTI/Daily cap, PR-3 에서 Compose UI + Preview 엔드포인트가 추가된다.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/manual-emails")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SYSTEM_ADMIN')")
public class AdminManualEmailController {

    /** 페이지 크기 상한 — 운영 자유도 vs 페이로드 보호 (kVA 이력 조회와 동일 정책). */
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final ManualEmailDispatcher dispatcher;

    /**
     * 단일 수신자 발송 (APPLICANT/LEW/EXTERNAL). MULTI 는 PR-2 에서 활성화.
     *
     * <p>응답: 즉시 반환 — row 는 status=PENDING 으로 저장된 직후 응답한다. SMTP 발송 자체는
     * AFTER_COMMIT 에서 비동기 처리되므로 실제 결과는 {@code GET .../{seq}} 로 확인.</p>
     */
    @PostMapping
    public ResponseEntity<ManualEmailDispatchResponse> dispatch(
            Authentication authentication,
            @Valid @RequestBody SendManualEmailRequest request) {
        Long adminSeq = (Long) authentication.getPrincipal();
        log.info("POST /api/admin/manual-emails: adminSeq={}, type={}, hasUserSeq={}, hasEmail={}, force={}",
                adminSeq,
                request.getRecipientType(),
                request.getRecipientUserSeq() != null,
                request.getRecipientEmail() != null,
                Boolean.TRUE.equals(request.getForceDuplicate()));
        ManualEmailDispatchResponse response = dispatcher.dispatch(request, adminSeq);
        return ResponseEntity.ok(response);
    }

    /**
     * 발송 이력 페이지네이션. 필터는 모두 옵션이며 null 일 때 무시된다.
     *
     * <p>{@code from}/{@code to} 는 ISO-8601 LocalDateTime ({@code 2026-05-01T00:00:00}) 으로 받는다.</p>
     */
    @GetMapping
    public ResponseEntity<Page<ManualEmailDispatchHistoryItem>> getHistory(
            @RequestParam(required = false) Long senderUserSeq,
            @RequestParam(required = false) DispatchStatus dispatchStatus,
            @RequestParam(required = false) Long relatedApplicationSeq,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safePage = Math.max(0, page);
        int safeSize = clampPageSize(size);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        ManualEmailDispatcher.HistoryFilter filter = new ManualEmailDispatcher.HistoryFilter(
                senderUserSeq, dispatchStatus, relatedApplicationSeq, from, to);
        log.debug("GET /api/admin/manual-emails: filter={}, page={}, size={}", filter, safePage, safeSize);
        Page<ManualEmailDispatchHistoryItem> result = dispatcher.getDispatchHistory(filter, pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{dispatchSeq}")
    public ResponseEntity<ManualEmailDispatchHistoryItem> getDetail(
            @PathVariable Long dispatchSeq) {
        log.debug("GET /api/admin/manual-emails/{}", dispatchSeq);
        return ResponseEntity.ok(dispatcher.getDispatchDetail(dispatchSeq));
    }

    private int clampPageSize(int requested) {
        if (requested <= 0) return DEFAULT_PAGE_SIZE;
        return Math.min(requested, MAX_PAGE_SIZE);
    }
}
