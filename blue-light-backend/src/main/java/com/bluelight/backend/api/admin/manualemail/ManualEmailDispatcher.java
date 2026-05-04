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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <h2>PR-2 범위</h2>
 * <ul>
 *   <li>MULTI 다수 수신자 활성화 — 시스템 사용자 lookup + 외부 이메일 합치기 + 중복 제거.</li>
 *   <li>멱등성 가드 — 단일/다수 통합 {@code recipientHash} 컬럼 비교. 정렬된 수신자 + subject + body
 *       의 SHA-256 해시.</li>
 *   <li>청크/쓰로틀(D7=B) 은 listener 의 책임 — dispatcher 는 단일 row + 수신자 리스트만 영속화.</li>
 *   <li>Daily cap(PR-4) 은 본 PR 범위 외 — 추후 별도 가드 메서드로 추가 예정.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualEmailDispatcher {

    /** 멱등성 윈도우 (스펙 AC-A9, D3=B). */
    static final int IDEMPOTENCY_WINDOW_SECONDS = 30;

    /** MULTI 발송 최소 수신자 수 — 1명 이하면 단일 타입을 쓰도록 거부. */
    static final int MULTI_MIN_RECIPIENTS = 2;

    /** MULTI 발송 최대 수신자 수 — UI/SMTP 보호. system_settings 외부화는 PR-4 에서. */
    static final int MULTI_MAX_RECIPIENTS = 100;

    private final ManualEmailDispatchRepository dispatchRepository;
    private final UserRepository userRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    // ── 1) 발송 ─────────────────────────────────────────────────

    @Transactional
    public ManualEmailDispatchResponse dispatch(SendManualEmailRequest request, Long adminUserSeq) {
        // 수신자 lookup → 단일/다수 통합 형태로 정규화.
        ResolvedRecipients resolved = resolveRecipients(request);

        // 신청 컨텍스트 검증 (옵션).
        if (request.getRelatedApplicationSeq() != null
                && !applicationRepository.existsById(request.getRelatedApplicationSeq())) {
            throw new BusinessException(
                    "Application #" + request.getRelatedApplicationSeq() + " not found",
                    HttpStatus.BAD_REQUEST,
                    "APPLICATION_NOT_FOUND");
        }

        // 멱등성 가드 (D3=B) — 단일/다수 통합 recipientHash 비교.
        String recipientHash = ManualEmailRecipientHasher.hashOf(
                resolved.allEmails(),
                request.getSubject(),
                request.getBodyText());
        boolean force = Boolean.TRUE.equals(request.getForceDuplicate());
        if (!force) {
            LocalDateTime since = LocalDateTime.now().minusSeconds(IDEMPOTENCY_WINDOW_SECONDS);
            List<ManualEmailDispatch> recent = dispatchRepository.findRecentDuplicateByHash(
                    adminUserSeq, recipientHash, since, PageRequest.of(0, 1));
            if (!recent.isEmpty()) {
                log.warn("Manual email dispatch duplicate suspected: adminSeq={}, hash={}, recentSeq={}",
                        adminUserSeq, recipientHash, recent.get(0).getDispatchSeq());
                throw new BusinessException(
                        "Duplicate dispatch detected within " + IDEMPOTENCY_WINDOW_SECONDS
                                + " seconds. Set forceDuplicate=true to confirm intent.",
                        HttpStatus.CONFLICT,
                        "MANUAL_EMAIL_DUPLICATE_SUSPECTED");
            }
        }

        // row 저장 (PENDING). bodyFormat 은 PLAIN_TEXT 로 강제.
        // MULTI 도 row 1건 — 수신자 리스트는 JSON 컬럼에 저장.
        ManualEmailDispatch saved = dispatchRepository.save(
                ManualEmailDispatch.builder()
                        .senderUserSeq(adminUserSeq)
                        .recipientType(request.getRecipientType())
                        .recipientUserSeq(resolved.singleUserSeq)
                        .recipientEmail(resolved.primaryEmail())
                        .recipientUserSeqsJson(resolved.userSeqsJsonOrNull())
                        .recipientEmailsJson(resolved.emailsJsonOrNull())
                        .recipientHash(recipientHash)
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
                buildAuditMetadata(saved, request, force, resolved),
                null, null,
                "POST", "/api/admin/manual-emails", 200);

        // AFTER_COMMIT 에서 SMTP 시도 — 실패해도 본 트랜잭션은 이미 커밋된 PENDING row 를 보존.
        eventPublisher.publishEvent(new ManualEmailDispatchRequestedEvent(saved.getDispatchSeq()));

        log.info("Manual email dispatch queued: seq={}, adminSeq={}, type={}, recipientCount={}, force={}",
                saved.getDispatchSeq(), adminUserSeq, saved.getRecipientType(),
                resolved.allEmails().size(), force);

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

    /**
     * 수신자 lookup — APPLICANT/LEW/EXTERNAL/MULTI 4종을 모두 동일한 {@link ResolvedRecipients}
     * 형태로 정규화한다. listener 는 항상 {@code allEmails} 만 보고 loop 를 수행 — 단일/다수
     * 코드 경로 단일화.
     */
    private ResolvedRecipients resolveRecipients(SendManualEmailRequest request) {
        return switch (request.getRecipientType()) {
            case APPLICANT -> resolveSystemUser(request, UserRole.APPLICANT);
            case LEW       -> resolveSystemUser(request, UserRole.LEW);
            case EXTERNAL  -> resolveExternal(request);
            case MULTI     -> resolveMulti(request);
        };
    }

    private ResolvedRecipients resolveSystemUser(SendManualEmailRequest req, UserRole expectedRole) {
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
        String normalized = user.getEmail().trim();
        return new ResolvedRecipients(
                user.getUserSeq(),
                normalized,
                List.of(normalized),
                /*userSeqs*/ null,
                /*emails*/ null);
    }

    private ResolvedRecipients resolveExternal(SendManualEmailRequest req) {
        if (req.getRecipientEmail() == null || req.getRecipientEmail().isBlank()) {
            throw new BusinessException(
                    "Recipient email is required for EXTERNAL dispatch",
                    HttpStatus.BAD_REQUEST,
                    "RECIPIENT_EMAIL_REQUIRED");
        }
        String normalized = req.getRecipientEmail().trim();
        return new ResolvedRecipients(
                null,
                normalized,
                List.of(normalized),
                null,
                null);
    }

    /**
     * MULTI 수신자 해석 (PR-2 §5.1, AC-A4).
     *
     * <p>시스템 사용자 user_seq + 외부 이메일을 합쳐 최종 발송 대상을 구성한다.</p>
     * <ol>
     *   <li>각 user_seq → User lookup → role 검증 (APPLICANT 또는 LEW 만 허용) → email 추출.</li>
     *   <li>외부 이메일은 정규화(trim) 후 그대로 사용. 형식 검증은 DTO Bean Validation 에서 끝난 상태.</li>
     *   <li>이메일 소문자 비교로 중복 제거 (insertion order 유지).</li>
     *   <li>합산 수신자 수가 {@link #MULTI_MIN_RECIPIENTS} 미만이면 400 거부 (단일 발송을 사용하도록 유도).</li>
     *   <li>{@link #MULTI_MAX_RECIPIENTS} 초과 시 400 거부.</li>
     * </ol>
     */
    private ResolvedRecipients resolveMulti(SendManualEmailRequest req) {
        List<Long> userSeqsRaw = req.getRecipientUserSeqs() == null
                ? List.of() : req.getRecipientUserSeqs();
        List<String> externalEmailsRaw = req.getRecipientEmails() == null
                ? List.of() : req.getRecipientEmails();

        // 시스템 사용자 lookup → 이메일 + user_seq 스냅샷 확보.
        List<Long> normalizedUserSeqs = new ArrayList<>();
        List<String> userEmails = new ArrayList<>();
        for (Long userSeq : userSeqsRaw) {
            if (userSeq == null) continue;
            User user = userRepository.findById(userSeq)
                    .orElseThrow(() -> new BusinessException(
                            "Recipient user #" + userSeq + " not found",
                            HttpStatus.BAD_REQUEST,
                            "RECIPIENT_USER_NOT_FOUND"));
            // MULTI 는 APPLICANT/LEW 혼합 가능. 그 외 role 은 거부 (ADMIN/SYSTEM_ADMIN/SLD_MANAGER 등 운영
            // 사용자에게 manual email 을 직접 발송하는 건 본 기능 범위 외).
            if (user.getRole() != UserRole.APPLICANT && user.getRole() != UserRole.LEW) {
                throw new BusinessException(
                        "Recipient user #" + userSeq + " role " + user.getRole()
                                + " is not allowed in MULTI dispatch (only APPLICANT/LEW)",
                        HttpStatus.BAD_REQUEST,
                        "RECIPIENT_ROLE_MISMATCH");
            }
            if (user.getEmail() == null || user.getEmail().isBlank()) {
                throw new BusinessException(
                        "Recipient user #" + userSeq + " has no email on file",
                        HttpStatus.BAD_REQUEST,
                        "RECIPIENT_EMAIL_MISSING");
            }
            normalizedUserSeqs.add(user.getUserSeq());
            userEmails.add(user.getEmail().trim());
        }

        // 외부 이메일 정규화 (trim) — null/blank 항목 스킵.
        List<String> externalEmails = externalEmailsRaw.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        // 중복 제거: 소문자 키로 LinkedHashSet 관리, 출력은 원본 (대소문자) 유지.
        // 전체 발송 대상 리스트 = userEmails 먼저 + externalEmails 뒤 → 입력 순서 보존.
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> mergedEmails = new ArrayList<>();
        for (String e : userEmails) {
            if (seen.add(e.toLowerCase(Locale.ROOT))) mergedEmails.add(e);
        }
        for (String e : externalEmails) {
            if (seen.add(e.toLowerCase(Locale.ROOT))) mergedEmails.add(e);
        }

        if (mergedEmails.size() < MULTI_MIN_RECIPIENTS) {
            throw new BusinessException(
                    "MULTI dispatch requires at least " + MULTI_MIN_RECIPIENTS
                            + " recipients (use APPLICANT/LEW/EXTERNAL for single recipient)",
                    HttpStatus.BAD_REQUEST,
                    "MULTI_REQUIRES_AT_LEAST_TWO_RECIPIENTS");
        }
        if (mergedEmails.size() > MULTI_MAX_RECIPIENTS) {
            throw new BusinessException(
                    "MULTI dispatch exceeds maximum " + MULTI_MAX_RECIPIENTS + " recipients",
                    HttpStatus.BAD_REQUEST,
                    "MULTI_EXCEEDS_MAX_RECIPIENTS");
        }

        // primaryEmail = 첫 번째 (단일 컬럼 호환을 위한 대표값).
        // userSeqsJson 은 시스템 사용자가 1명 이상일 때만 채움 (전부 외부 이메일이면 null).
        // emailsJson 은 항상 채움 (MULTI 의 정본).
        return new ResolvedRecipients(
                /*singleUserSeq*/ null,
                /*primaryEmail*/ mergedEmails.get(0),
                /*allEmails*/ Collections.unmodifiableList(mergedEmails),
                /*userSeqs*/ normalizedUserSeqs.isEmpty() ? null : normalizedUserSeqs,
                /*emails*/ Collections.unmodifiableList(mergedEmails));
    }

    private Map<String, Object> buildAuditMetadata(ManualEmailDispatch saved,
                                                    SendManualEmailRequest req,
                                                    boolean forceDuplicate,
                                                    ResolvedRecipients resolved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("dispatchSeq", saved.getDispatchSeq());
        m.put("recipientType", saved.getRecipientType().name());
        m.put("recipientCount", resolved.allEmails().size());
        m.put("recipientUserSeq", saved.getRecipientUserSeq());
        m.put("recipientEmail", saved.getRecipientEmail());
        m.put("recipientUserSeqs", saved.getRecipientUserSeqsJson());
        // 이메일 리스트는 audit 본문에 그대로 노출 (운영 추적 목적). 100건 cap 으로 페이로드 보호.
        m.put("recipientEmails", saved.getRecipientEmailsJson());
        m.put("recipientHash", saved.getRecipientHash());
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

    /**
     * 수신자 lookup 결과 — 단일/다수 모두를 통합 표현.
     *
     * @param singleUserSeq      단일 시스템 사용자 발송 시 user_seq. EXTERNAL/MULTI 시 null.
     * @param primaryEmail       단일 컬럼({@code recipient_email}) 호환을 위한 대표 이메일 (항상 채워짐).
     * @param allEmails          전체 발송 대상 이메일 리스트 (단일이면 1개). 멱등성 해시 + listener loop 의 정본.
     * @param userSeqsJsonOrNull MULTI 시 시스템 사용자 user_seq 목록. 단일/EXTERNAL 시 null.
     * @param emailsJsonOrNull   MULTI 시 전체 이메일 목록. 단일/EXTERNAL 시 null (PR-1 row 와 시각 동일).
     */
    private record ResolvedRecipients(
            Long singleUserSeq,
            String primaryEmail,
            List<String> allEmails,
            List<Long> userSeqsJsonOrNull,
            List<String> emailsJsonOrNull
    ) {}

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

    /** PR-2 후속 가드(daily cap 등) 추가 시 사용할 수 있도록 Optional 반환 helper 보존. */
    @SuppressWarnings("unused")
    private static <T> Optional<T> opt(T value) { return Optional.ofNullable(value); }
}
