package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchHistoryItem;
import com.bluelight.backend.api.admin.manualemail.dto.ManualEmailDispatchResponse;
import com.bluelight.backend.api.admin.manualemail.dto.SendManualEmailRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.manualemail.BodyFormat;
import com.bluelight.backend.domain.manualemail.DispatchStatus;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatch;
import com.bluelight.backend.domain.manualemail.ManualEmailDispatchRepository;
import com.bluelight.backend.domain.manualemail.RecipientType;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADMIN 수동 이메일 발송 서비스.
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §8 백엔드 구현 메모.</p>
 *
 * <h2>책임 분리</h2>
 * <ol>
 *   <li><b>{@link #dispatch}</b> — 단일 트랜잭션에서 ① 입력 검증 ② 수신자 lookup ③ 멱등성 가드 ④
 *       row 저장(status=PENDING) ⑤ audit 로그 기록 ⑥ AFTER_COMMIT 이벤트 발행. SMTP 발송 자체는
 *       {@link ManualEmailDispatchSendListener} 가 별도로 처리한다 (실패 격리, 스펙 §8.7).</li>
 *   <li><b>{@link #getDispatchHistory}</b> — 이력 페이지네이션 (필터 4종).</li>
 *   <li><b>{@link #getDispatchDetail}</b> — 단건 상세.</li>
 * </ol>
 *
 * <h2>PR-1 범위</h2>
 * <ul>
 *   <li>단일 수신자 (APPLICANT/LEW/EXTERNAL) 만 처리. {@code MULTI} 는 {@link #rejectIfMulti} 에서 400 거부.</li>
 *   <li>본문 형식 PLAIN_TEXT 강제 — HTML 입력 자체를 거부하진 않으며 (DTO 에 형식 필드 없음)
 *       내부적으로 PLAIN_TEXT 로만 저장/발송한다.</li>
 *   <li>Daily cap, 다중 수신자 chunk 는 PR-2/PR-4 에서 도입.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualEmailDispatcher {

    /** 멱등성 윈도우 (스펙 AC-A9, D3=B). */
    static final int IDEMPOTENCY_WINDOW_SECONDS = 30;

    private final ManualEmailDispatchRepository dispatchRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ── 1) 발송 ─────────────────────────────────────────────────

    @Transactional
    public ManualEmailDispatchResponse dispatch(SendManualEmailRequest request, Long adminUserSeq) {
        // PR-1 은 MULTI 불허 — enum 정의는 두지만 컨트롤러/서비스 양쪽에서 거부 (안전망 이중화).
        rejectIfMulti(request.getRecipientType());

        // 수신자 lookup → 이메일 스냅샷 확보.
        ResolvedRecipient resolved = resolveRecipient(request);

        // 신청 컨텍스트 검증 (옵션).
        if (request.getRelatedApplicationSeq() != null
                && !applicationRepository.existsById(request.getRelatedApplicationSeq())) {
            throw new BusinessException(
                    "Application #" + request.getRelatedApplicationSeq() + " not found",
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_NOT_FOUND");
        }

        // 멱등성 가드 (D3=B).
        boolean force = Boolean.TRUE.equals(request.getForceDuplicate());
        if (!force) {
            LocalDateTime since = LocalDateTime.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
            List<ManualEmailDispatch> recent = dispatchRepository.findRecentDuplicate(
                    adminUserSeq,
                    resolved.email,
                    request.getSubject(),
                    request.getBodyText(),
                    since,
                    PageRequest.of(0, 1));
            if (!recent.isEmpty()) {
                log.warn("Manual email dispatch duplicate suspected: adminSeq={}, recipient={}, recentSeq={}",
                        adminUserSeq, resolved.email, recent.get(0).getDispatchSeq());
                throw new BusinessException(
                        "Duplicate dispatch detected within " + IDEMPOTENCY_WINDOW_SECONDS
                                + " seconds. Set forceDuplicate=true to confirm intent.",
                        HttpStatus.CONFLICT,
                        "MANUAL_EMAIL_DUPLICATE_SUSPECTED");
            }
        }

        // row 저장 (PENDING). bodyFormat 은 PR-1 에서 PLAIN_TEXT 로 강제.
        ManualEmailDispatch saved = dispatchRepository.save(
                ManualEmailDispatch.builder()
                        .senderUserSeq(adminUserSeq)
                        .recipientType(request.getRecipientType())
                        .recipientUserSeq(resolved.userSeq)
                        .recipientEmail(resolved.email)
                        .relatedApplicationSeq(request.getRelatedApplicationSeq())
                        .subject(request.getSubject())
                        .bodyText(request.getBodyText())
                        .bodyFormat(BodyFormat.PLAIN_TEXT)
                        .categoryTag(blankToNull(request.getCategoryTag()))
                        .build());

        // audit 로그 — PENDING 시점에서 즉시 기록 (스펙 AC-A1 / §13.2). SMTP 결과와 무관하게
        // ADMIN 의 발송 시도가 항상 추적되도록 한다.
        auditLogService.logAsync(
                adminUserSeq,
                AuditAction.MANUAL_EMAIL_DISPATCHED,
                AuditCategory.ADMIN,
                "ManualEmailDispatch",
                String.valueOf(saved.getDispatchSeq()),
                "Manual email dispatch queued",
                null,
                buildAuditMetadata(saved, request, force),
                null, null,
                "POST", "/api/admin/manual-emails", 200);

        // AFTER_COMMIT 에서 SMTP 시도 — 실패해도 본 트랜잭션은 이미 커밋된 PENDING row 를 보존.
        eventPublisher.publishEvent(new ManualEmailDispatchRequestedEvent(saved.getDispatchSeq()));

        log.info("Manual email dispatch queued: seq={}, adminSeq={}, type={}, recipient={}, force={}",
                saved.getDispatchSeq(), adminUserSeq, saved.getRecipientType(),
                saved.getRecipientEmail(), force);

        return ManualEmailDispatchResponse.from(saved);
    }

    // ── 2) 이력 ─────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<ManualEmailDispatchHistoryItem> getDispatchHistory(HistoryFilter filter, Pageable pageable) {
        Page<ManualEmailDispatch> page = dispatchRepository.searchHistory(
                filter.senderUserSeq(),
                filter.dispatchStatus(),
                filter.relatedApplicationSeq(),
                filter.from(),
                filter.to(),
                pageable);
        return page.map(ManualEmailDispatchHistoryItem::from);
    }

    @Transactional(readOnly = true)
    public ManualEmailDispatchHistoryItem getDispatchDetail(Long dispatchSeq) {
        return dispatchRepository.findById(dispatchSeq)
                .map(ManualEmailDispatchHistoryItem::from)
                .orElseThrow(() -> new BusinessException(
                        "Manual email dispatch #" + dispatchSeq + " not found",
                        HttpStatus.NOT_FOUND,
                        "MANUAL_EMAIL_DISPATCH_NOT_FOUND"));
    }

    // ── 내부 헬퍼 ───────────────────────────────────────────────

    private void rejectIfMulti(RecipientType type) {
        if (type == RecipientType.MULTI) {
            throw new BusinessException(
                    "Multi-recipient dispatch is not supported in PR-1. Use single APPLICANT/LEW/EXTERNAL.",
                    HttpStatus.BAD_REQUEST,
                    "MULTI_NOT_SUPPORTED_IN_PR1");
        }
    }

    private ResolvedRecipient resolveRecipient(SendManualEmailRequest request) {
        return switch (request.getRecipientType()) {
            case APPLICANT -> resolveSystemUser(request, UserRole.APPLICANT);
            case LEW       -> resolveSystemUser(request, UserRole.LEW);
            case EXTERNAL  -> resolveExternal(request);
            // MULTI 는 rejectIfMulti() 에서 이미 차단됨 — switch exhaustiveness 만족용.
            case MULTI     -> throw new IllegalStateException("MULTI must be rejected before resolveRecipient");
        };
    }

    private ResolvedRecipient resolveSystemUser(SendManualEmailRequest req, UserRole expectedRole) {
        if (req.getRecipientUserSeq() == null) {
            throw new BusinessException(
                    "Recipient user seq is required for " + expectedRole + " dispatch",
                    HttpStatus.BAD_REQUEST,
                    "RECIPIENT_USER_SEQ_REQUIRED");
        }
        User user = userRepository.findById(req.getRecipientUserSeq())
                .orElseThrow(() -> new BusinessException(
                        "Recipient user #" + req.getRecipientUserSeq() + " not found",
                        HttpStatus.BAD_REQUEST,
                        "RECIPIENT_USER_NOT_FOUND"));
        if (user.getRole() != expectedRole) {
            throw new BusinessException(
                    "Recipient user #" + req.getRecipientUserSeq() + " is not " + expectedRole
                            + " (actual=" + user.getRole() + ")",
                    HttpStatus.BAD_REQUEST,
                    "RECIPIENT_ROLE_MISMATCH");
        }
        if (user.getEmail() == null || user.getEmail().isBlank()) {
            throw new BusinessException(
                    "Recipient user #" + req.getRecipientUserSeq() + " has no email on file",
                    HttpStatus.BAD_REQUEST,
                    "RECIPIENT_EMAIL_MISSING");
        }
        return new ResolvedRecipient(user.getUserSeq(), user.getEmail().trim());
    }

    private ResolvedRecipient resolveExternal(SendManualEmailRequest req) {
        if (req.getRecipientEmail() == null || req.getRecipientEmail().isBlank()) {
            throw new BusinessException(
                    "Recipient email is required for EXTERNAL dispatch",
                    HttpStatus.BAD_REQUEST,
                    "RECIPIENT_EMAIL_REQUIRED");
        }
        // @Email + @Size 는 컨트롤러 단계에서 이미 통과 — 여기서는 trim 만.
        return new ResolvedRecipient(null, req.getRecipientEmail().trim());
    }

    private Map<String, Object> buildAuditMetadata(ManualEmailDispatch saved,
                                                    SendManualEmailRequest req,
                                                    boolean forceDuplicate) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispatchSeq", saved.getDispatchSeq());
        m.put("recipientType", saved.getRecipientType().name());
        m.put("recipientUserSeq", saved.getRecipientUserSeq());
        m.put("recipientEmail", saved.getRecipientEmail());
        m.put("relatedApplicationSeq", saved.getRelatedApplicationSeq());
        m.put("subject", saved.getSubject());
        m.put("bodyLength", saved.getBodyText() == null ? 0 : saved.getBodyText().length());
        m.put("categoryTag", saved.getCategoryTag());
        m.put("forceDuplicate", forceDuplicate);
        m.put("status", DispatchStatus.PENDING.name());
        return m;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 수신자 lookup 결과 — userSeq 는 EXTERNAL 일 때 null. */
    private record ResolvedRecipient(Long userSeq, String email) {}

    /**
     * 발송 이력 조회 필터. 컨트롤러가 query param 을 변환해 전달.
     */
    public record HistoryFilter(
            Long senderUserSeq,
            DispatchStatus dispatchStatus,
            Long relatedApplicationSeq,
            LocalDateTime from,
            LocalDateTime to
    ) {}
}
