package com.bluelight.backend.service.kva;

import com.bluelight.backend.api.admin.KvaSettlementMarkedEvent;
import com.bluelight.backend.api.admin.dto.KvaAdjustmentHistoryItem;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideRequest;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideResponse;
import com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.api.lew.LewKvaAdjustmentRequestedEvent;
import com.bluelight.backend.api.lew.LewKvaRequestResolvedByOverrideEvent;
import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentRequest;
import com.bluelight.backend.api.lew.dto.LewKvaAdjustmentResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import com.bluelight.backend.domain.kva.ChangedByRole;
import com.bluelight.backend.domain.kva.KvaAdjustmentRecord;
import com.bluelight.backend.domain.kva.KvaAdjustmentRepository;
import com.bluelight.backend.domain.kva.KvaAdjustmentStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.api.admin.KvaOverrideAppliedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 결제 후 kVA 사후 변경 전담 서비스 (PR-1).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md}.</p>
 *
 * <h2>책임</h2>
 * <ul>
 *   <li>{@link Application#overrideKvaPostPayment} 호출로 kVA + quoteAmount 갱신.</li>
 *   <li>기존 활성 Invoice 가 있으면 INVALIDATED 마킹 + 신규 Invoice 자동 발행 (D3).</li>
 *   <li>CoF 가 finalized 면 unfinalize 하여 LEW 재서명 흐름 트리거 (AC-C1).</li>
 *   <li>{@link KvaAdjustmentRecord} ledger row 생성 + audit 로그 기록.</li>
 * </ul>
 *
 * <h2>트랜잭션 경계</h2>
 * 단일 {@code @Transactional} — 위 4개 작업이 모두 성공하거나 모두 롤백.
 * AFTER_COMMIT 이벤트(알림)는 PR-2 에서 추가. PR-1 은 알림 미발송.
 *
 * <h2>가드</h2>
 * <ul>
 *   <li>EXPIRED → 409 {@code KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED} (D5).</li>
 *   <li>PRE-PAYMENT 상태 → 409 {@code KVA_NOT_POSTPAYMENT} (기존 endpoint 안내).</li>
 *   <li>동일 newKva → 400 {@code KVA_NO_CHANGE}.</li>
 *   <li>master_prices 미존재 → 400 {@code INVALID_KVA_TIER}.</li>
 *   <li>{@code Application.@Version} 충돌 → {@code GlobalExceptionHandler} 가 409
 *       {@code STALE_STATE} 변환.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KvaPostPaymentService {

    private final ApplicationRepository applicationRepository;
    private final MasterPriceRepository masterPriceRepository;
    private final UserRepository userRepository;
    private final KvaAdjustmentRepository kvaAdjustmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceGenerationService invoiceGenerationService;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * ADMIN 직접 변경 — 결제 후 kVA 변경 적용 + ledger row 생성 + Invoice 재발행 + (필요 시) CoF unfinalize.
     *
     * @param applicationSeq 신청 ID
     * @param request        변경 요청 DTO
     * @param adminUserSeq   요청자 ADMIN userSeq (Authentication 에서 추출)
     */
    @Transactional
    public KvaPostPaymentOverrideResponse overrideKva(Long applicationSeq,
                                                      KvaPostPaymentOverrideRequest request,
                                                      Long adminUserSeq) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // ── 가드 0: 상태 ─────────────────────────────────
        ApplicationStatus current = application.getStatus();
        if (current == ApplicationStatus.EXPIRED) {
            logDenied(adminUserSeq, application, request,
                    "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED",
                    "Application is EXPIRED, cannot adjust kVA");
            throw new BusinessException(
                    "EXPIRED applications cannot be adjusted",
                    HttpStatus.CONFLICT, "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED");
        }
        if (!application.isPostPaymentStatus()) {
            // PENDING_REVIEW / REVISION_REQUESTED / PENDING_PAYMENT
            logDenied(adminUserSeq, application, request,
                    "KVA_NOT_POSTPAYMENT",
                    "Application status " + current + " is pre-payment");
            throw new BusinessException(
                    "Use /api/admin/applications/{id}/kva for pre-payment changes",
                    HttpStatus.CONFLICT, "KVA_NOT_POSTPAYMENT");
        }

        // ── 가드 1: no-op ────────────────────────────────
        Integer previousKva = application.getSelectedKva();
        if (previousKva != null && previousKva.equals(request.getNewKva())) {
            logDenied(adminUserSeq, application, request,
                    "KVA_NO_CHANGE",
                    "newKva is identical to current selectedKva=" + previousKva);
            throw new BusinessException(
                    "New kVA is identical to current value",
                    HttpStatus.BAD_REQUEST, "KVA_NO_CHANGE");
        }

        // ── 가드 2: master_prices 정합성 ─────────────────
        MasterPrice masterPrice = masterPriceRepository.findByKva(request.getNewKva())
                .orElseThrow(() -> {
                    logDenied(adminUserSeq, application, request,
                            "INVALID_KVA_TIER",
                            "Unknown kVA tier: " + request.getNewKva());
                    return new BusinessException(
                            "Invalid kVA tier: " + request.getNewKva(),
                            HttpStatus.BAD_REQUEST, "INVALID_KVA_TIER");
                });

        // ── 가격 재계산 (D1: 변경 시점 현재가) ──────────
        BigDecimal previousQuote = application.getQuoteAmount();
        BigDecimal newQuote = recalculateQuote(application, masterPrice);

        // ── 도메인 메서드 호출 ───────────────────────────
        User overrider = userRepository.findById(adminUserSeq)
                .orElseThrow(() -> new BusinessException(
                        "User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
        application.overrideKvaPostPayment(request.getNewKva(), newQuote, overrider);
        // 출장비 스냅샷도 신규 tier 기준으로 갱신 (newQuote 에 가산된 값과 일치) — New License 만
        application.reflectCalloutFee(
                (application.getApplicationType() != ApplicationType.RENEWAL)
                        ? masterPrice.getCalloutFee() : null);

        // ── KvaAdjustmentRecord ledger 작성 ──────────────
        BigDecimal amountDifference =
                (previousQuote != null) ? newQuote.subtract(previousQuote) : null;
        AdminPaymentAdjustment paymentAdjustment = request.getPaymentAdjustment() != null
                ? request.getPaymentAdjustment()
                : AdminPaymentAdjustment.PENDING;

        KvaAdjustmentRecord record = KvaAdjustmentRecord.builder()
                .application(application)
                .lewRequestSeq(null)
                .previousKva(previousKva)
                .newKva(request.getNewKva())
                .proposedKva(null)
                .reason(request.getReason())
                .status(KvaAdjustmentStatus.APPLIED)
                .changedByRole(ChangedByRole.ADMIN)
                .changedByUserSeq(adminUserSeq)
                .previousQuoteAmount(previousQuote)
                .newQuoteAmount(newQuote)
                .amountDifference(amountDifference)
                .masterPriceSeqUsed(masterPrice.getMasterPriceSeq())
                .adminMemo(request.getAdminMemo())
                .adminPaymentAdjustment(paymentAdjustment)
                .settledAmount(request.getSettledAmount())
                .receiptReferenceNumber(request.getReceiptReferenceNumber())
                .settlementMemo(null)
                .adminAdjustmentAt(LocalDateTime.now())
                .settledAt(null)
                .build();
        record = kvaAdjustmentRepository.save(record);

        // ── PR-3 AC-L4: PENDING LEW 요청 자동 RESOLVED 마킹 + ADMIN row 와 self-FK 연결 ──
        // 같은 application 의 모든 PENDING_ADMIN_REVIEW 요청을 비관적 락으로 가져와 해소.
        // 가장 오래된 1건의 seq 를 ADMIN row 의 lewRequestSeq 에 기록 (대표 1건만 연결).
        List<KvaAdjustmentRecord> pendingLewRequests =
                kvaAdjustmentRepository.findByApplicationSeqAndStatusForUpdate(
                        applicationSeq, KvaAdjustmentStatus.PENDING_ADMIN_REVIEW);
        List<Long> resolvedLewRequestSeqs = new ArrayList<>();
        Long primaryResolvedSeq = null;
        Long primaryRequestingLewUserSeq = null;
        Integer primaryProposedKva = null;
        for (KvaAdjustmentRecord pending : pendingLewRequests) {
            // 방어: changedByRole=LEW 인 PENDING 만 해소 (ADMIN row 가 PENDING 인 경우는 도메인상 없으나 안전).
            if (pending.getChangedByRole() != ChangedByRole.LEW) {
                continue;
            }
            pending.markResolvedByAdminOverride();
            resolvedLewRequestSeqs.add(pending.getAdjustmentSeq());
            if (primaryResolvedSeq == null) {
                primaryResolvedSeq = pending.getAdjustmentSeq();
                primaryRequestingLewUserSeq = pending.getChangedByUserSeq();
                primaryProposedKva = pending.getProposedKva();
            }
        }
        if (primaryResolvedSeq != null) {
            // ADMIN row 의 lewRequestSeq 를 가장 오래된 PENDING 요청과 연결.
            record.linkLewRequest(primaryResolvedSeq);

            // 감사 로그 — 해소된 각 LEW 요청 row 별로 1건 (REQUIRES_NEW)
            for (KvaAdjustmentRecord resolved : pendingLewRequests) {
                if (resolved.getChangedByRole() != ChangedByRole.LEW) continue;
                Map<String, Object> resolveMeta = new LinkedHashMap<>();
                resolveMeta.put("resolvedAdjustmentSeq", resolved.getAdjustmentSeq());
                resolveMeta.put("byAdminAdjustmentSeq", record.getAdjustmentSeq());
                resolveMeta.put("requestingLewUserSeq", resolved.getChangedByUserSeq());
                resolveMeta.put("proposedKva", resolved.getProposedKva());
                resolveMeta.put("appliedKva", request.getNewKva());
                resolveMeta.put("applicationSeq", applicationSeq);
                auditLogService.logAsync(
                        adminUserSeq, AuditAction.KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE,
                        AuditCategory.ADMIN,
                        "KvaAdjustmentRecord", String.valueOf(resolved.getAdjustmentSeq()),
                        "LEW kVA adjustment request resolved by admin override",
                        null, resolveMeta,
                        null, null, "POST",
                        "/api/admin/applications/" + applicationSeq + "/kva-override-postpayment", 200);
            }
        }

        // ── Invoice invalidate + 재발행 (D3) ─────────────
        invalidateAndRegenerateInvoice(application, record.getAdjustmentSeq());

        // ── Audit logs (REQUIRES_NEW) ────────────────────
        Map<String, Object> overrideMeta = buildOverrideMetadata(
                previousKva, previousQuote, request.getNewKva(), newQuote,
                amountDifference, masterPrice.getMasterPriceSeq(),
                paymentAdjustment, request.getReason(), request.getAdminMemo(),
                current, record.getAdjustmentSeq());
        auditLogService.logAsync(
                adminUserSeq, AuditAction.KVA_OVERRIDE_POSTPAYMENT, AuditCategory.ADMIN,
                "Application", String.valueOf(applicationSeq),
                "kVA overridden post-payment by ADMIN",
                null, overrideMeta,
                null, null, "POST",
                "/api/admin/applications/" + applicationSeq + "/kva-override-postpayment", 200);

        log.info("kVA post-payment overridden: applicationSeq={}, prev={}kVA/{}, new={}kVA/{}, "
                        + "adjustmentSeq={}, adminUserSeq={}",
                applicationSeq, previousKva, previousQuote,
                request.getNewKva(), newQuote, record.getAdjustmentSeq(),
                adminUserSeq);

        // ── PR-2: 배정 LEW 알림 이벤트 발행 ──────────────
        // 본 트랜잭션 커밋 후 KvaOverrideNotificationListener (AFTER_COMMIT) 가 인앱+이메일 발송.
        // assignedLew 가 null 이면 listener 가 스킵하므로 여기서는 무조건 publish.
        Long assignedLewUserSeq = (application.getAssignedLew() != null)
                ? application.getAssignedLew().getUserSeq()
                : null;
        eventPublisher.publishEvent(new KvaOverrideAppliedEvent(
                applicationSeq,
                record.getAdjustmentSeq(),
                assignedLewUserSeq,
                previousKva,
                request.getNewKva(),
                previousQuote,
                newQuote,
                amountDifference,
                request.getReason(),
                adminUserSeq,
                "ADMIN"));

        // ── PR-3 AC-L4: 해소된 각 LEW 요청 row 별로 요청자(LEW) 알림 이벤트 발행 ──
        // 요청자가 배정 LEW 와 동일하면 PR-2 알림과 중복될 수 있으나, listener 는 멱등성 가드로 스킵 처리.
        for (KvaAdjustmentRecord resolved : pendingLewRequests) {
            if (resolved.getChangedByRole() != ChangedByRole.LEW) continue;
            Long requestingLewSeq = resolved.getChangedByUserSeq();
            if (requestingLewSeq == null) continue;
            eventPublisher.publishEvent(new LewKvaRequestResolvedByOverrideEvent(
                    applicationSeq,
                    requestingLewSeq,
                    resolved.getAdjustmentSeq(),
                    record.getAdjustmentSeq(),
                    resolved.getProposedKva(),
                    request.getNewKva()));
        }

        return KvaPostPaymentOverrideResponse.from(record);
    }

    /**
     * §4.2 PR-3: LEW 의 결제 후 kVA 변경 요청 — {@code KvaAdjustmentRecord} (status=PENDING_ADMIN_REVIEW) row 작성 + ADMIN 알림 이벤트 발행.
     *
     * <h3>가드</h3>
     * <ul>
     *   <li>EXPIRED → 409 {@code KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED}.</li>
     *   <li>PRE-PAYMENT 상태 → 409 {@code KVA_NOT_POSTPAYMENT}.</li>
     *   <li>동일 {@code proposedKva} → 400 {@code KVA_NO_CHANGE}.</li>
     *   <li>master_prices 미존재 → 400 {@code INVALID_KVA_TIER}.</li>
     *   <li>이미 PENDING 요청 존재 → 409 {@code KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING} (D4 비관적 락).</li>
     * </ul>
     *
     * <h3>트랜잭션 경계</h3>
     * 단일 {@code @Transactional} 내에서 락 + 검증 + row 저장 + audit + 이벤트 publish.
     * 알림 발송은 AFTER_COMMIT 으로 분리({@code LewKvaAdjustmentRequestNotificationListener}).
     */
    @Transactional
    public LewKvaAdjustmentResponse requestAdjustmentByLew(Long applicationSeq,
                                                            Long lewUserSeq,
                                                            LewKvaAdjustmentRequest request) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // ── 가드 0: 상태 ─────────────────────────────────
        ApplicationStatus current = application.getStatus();
        if (current == ApplicationStatus.EXPIRED) {
            logLewDenied(lewUserSeq, application, request,
                    "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED",
                    "Application is EXPIRED, cannot request kVA adjustment");
            throw new BusinessException(
                    "EXPIRED applications cannot be adjusted",
                    HttpStatus.CONFLICT, "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED");
        }
        if (!application.isPostPaymentStatus()) {
            logLewDenied(lewUserSeq, application, request,
                    "KVA_NOT_POSTPAYMENT",
                    "Application status " + current + " is pre-payment");
            throw new BusinessException(
                    "Use Phase 1 kVA confirmation flow for pre-payment changes",
                    HttpStatus.CONFLICT, "KVA_NOT_POSTPAYMENT");
        }

        // ── 가드 1: no-op ────────────────────────────────
        Integer currentKva = application.getSelectedKva();
        if (currentKva != null && currentKva.equals(request.getProposedKva())) {
            logLewDenied(lewUserSeq, application, request,
                    "KVA_NO_CHANGE",
                    "proposedKva is identical to current selectedKva=" + currentKva);
            throw new BusinessException(
                    "Proposed kVA is identical to current value",
                    HttpStatus.BAD_REQUEST, "KVA_NO_CHANGE");
        }

        // ── 가드 2: master_prices 정합성 ─────────────────
        masterPriceRepository.findByKva(request.getProposedKva())
                .orElseThrow(() -> {
                    logLewDenied(lewUserSeq, application, request,
                            "INVALID_KVA_TIER",
                            "Unknown kVA tier: " + request.getProposedKva());
                    return new BusinessException(
                            "Invalid kVA tier: " + request.getProposedKva(),
                            HttpStatus.BAD_REQUEST, "INVALID_KVA_TIER");
                });

        // ── 가드 3 (D4): 동일 application 의 PENDING LEW 요청 비관적 락 + 중복 차단 ──
        // SELECT ... FOR UPDATE 로 직렬화. 존재하는 PENDING 이 본인 또는 타 LEW 의 것이든 모두 차단.
        List<KvaAdjustmentRecord> existingPending =
                kvaAdjustmentRepository.findByApplicationSeqAndStatusForUpdate(
                        applicationSeq, KvaAdjustmentStatus.PENDING_ADMIN_REVIEW);
        if (!existingPending.isEmpty()) {
            logLewDenied(lewUserSeq, application, request,
                    "KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING",
                    "An existing LEW request is pending admin review (adjustmentSeq="
                            + existingPending.get(0).getAdjustmentSeq() + ")");
            throw new BusinessException(
                    "A kVA adjustment request is already pending admin review for this application",
                    HttpStatus.CONFLICT, "KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING");
        }

        // ── KvaAdjustmentRecord LEW 요청 row 작성 ────────
        // 자기 자신이 LEW 요청 row 이므로 lewRequestSeq=null (self-ref 불필요).
        // newKva=null (ADMIN 이 적용하기 전), proposedKva=request 의 값, previousKva=현재 selectedKva.
        KvaAdjustmentRecord record = KvaAdjustmentRecord.builder()
                .application(application)
                .lewRequestSeq(null)
                .previousKva(currentKva)
                .newKva(null)
                .proposedKva(request.getProposedKva())
                .reason(request.getReason())
                .status(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)
                .changedByRole(ChangedByRole.LEW)
                .changedByUserSeq(lewUserSeq)
                .previousQuoteAmount(application.getQuoteAmount())
                .newQuoteAmount(null)
                .amountDifference(null)
                .masterPriceSeqUsed(null)
                .adminMemo(null)
                .adminPaymentAdjustment(null)
                .settledAmount(null)
                .receiptReferenceNumber(null)
                .settlementMemo(null)
                .adminAdjustmentAt(null)
                .settledAt(null)
                .build();
        record = kvaAdjustmentRepository.save(record);

        // ── Audit log (REQUIRES_NEW) ─────────────────────
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("adjustmentSeq", record.getAdjustmentSeq());
        meta.put("currentKva", currentKva);
        meta.put("proposedKva", request.getProposedKva());
        meta.put("reason", request.getReason());
        meta.put("applicationStatus", current.name());
        auditLogService.logAsync(
                lewUserSeq, AuditAction.KVA_ADJUSTMENT_REQUESTED_BY_LEW, AuditCategory.APPLICATION,
                "Application", String.valueOf(applicationSeq),
                "kVA adjustment requested by LEW",
                null, meta,
                null, null, "POST",
                "/api/lew/applications/" + applicationSeq + "/kva-adjustment-request", 200);

        log.info("LEW kVA adjustment requested: applicationSeq={}, lewSeq={}, proposed={}kVA, current={}kVA, adjustmentSeq={}",
                applicationSeq, lewUserSeq, request.getProposedKva(), currentKva, record.getAdjustmentSeq());

        // ── ADMIN 알림 이벤트 발행 (AFTER_COMMIT) ────────
        // LEW 이름은 listener 가 user 조회로 보강 — 본 트랜잭션에서는 lewName 만 best-effort 로 채운다.
        String lewName = resolveLewDisplayName(lewUserSeq);
        eventPublisher.publishEvent(new LewKvaAdjustmentRequestedEvent(
                applicationSeq,
                record.getAdjustmentSeq(),
                lewUserSeq,
                lewName,
                request.getProposedKva(),
                currentKva,
                request.getReason()));

        return LewKvaAdjustmentResponse.from(record);
    }

    // ──────────────────────────────────────────────────────────
    // PR-4: 이력 조회 + Settlement 마킹
    // 스펙: kva-postpayment-adjustment-spec.md §4.3 / PR-4 / §8 PR-4
    // ──────────────────────────────────────────────────────────

    /**
     * §8 PR-4 / 이력 조회 — 특정 신청의 모든 KvaAdjustmentRecord 를 최신순으로 반환.
     *
     * <p>각 row 의 {@code changedByUserSeq} 를 일괄 조회하여 표시 이름({@code firstName +
     * lastName} 또는 {@code email}) 을 채워준다 — 사용자가 soft-delete 되었을 수 있으므로
     * lookup 실패는 {@code null} 로 통과 (응답에 빈 문자열 대신 그대로 노출).</p>
     *
     * <p>readOnly 트랜잭션 — 이력 조회는 부수효과 없음.</p>
     *
     * @param applicationSeq 신청 ID (path variable)
     * @return 시간 내림차순 이력 row 목록 (LEW 요청 row 와 ADMIN 변경 row 모두 포함)
     */
    @Transactional(readOnly = true)
    public List<KvaAdjustmentHistoryItem> getAdjustmentHistory(Long applicationSeq) {
        // 신청 존재 검증 — 없으면 404. 권한은 컨트롤러 @PreAuthorize 에서 처리.
        applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        List<KvaAdjustmentRecord> records = kvaAdjustmentRepository
                .findByApplication_ApplicationSeqOrderByCreatedAtDescAdjustmentSeqDesc(applicationSeq);
        if (records.isEmpty()) {
            return new ArrayList<>();
        }

        // changedByUserSeq → User 일괄 조회 (N+1 방지). null 인 row 는 lookup 대상 제외.
        java.util.Set<Long> userSeqs = new java.util.HashSet<>();
        for (KvaAdjustmentRecord r : records) {
            if (r.getChangedByUserSeq() != null) {
                userSeqs.add(r.getChangedByUserSeq());
            }
        }
        java.util.Map<Long, String> nameByUserSeq = new java.util.HashMap<>();
        if (!userSeqs.isEmpty()) {
            // findAllById 는 가용 row 만 반환 (soft-deleted/없는 user 는 자연스럽게 누락 → null 통과).
            for (User u : userRepository.findAllById(userSeqs)) {
                nameByUserSeq.put(u.getUserSeq(), formatUserDisplayName(u));
            }
        }

        List<KvaAdjustmentHistoryItem> items = new ArrayList<>(records.size());
        for (KvaAdjustmentRecord r : records) {
            String name = (r.getChangedByUserSeq() != null)
                    ? nameByUserSeq.get(r.getChangedByUserSeq())
                    : null;
            items.add(KvaAdjustmentHistoryItem.from(r, name));
        }
        return items;
    }

    /**
     * §4.3 / PR-4 — Settlement 마킹.
     *
     * <h3>가드</h3>
     * <ul>
     *   <li>row 존재 검증 (404 KVA_ADJUSTMENT_NOT_FOUND).</li>
     *   <li>path 의 {@code applicationSeq} 와 row 의 application 이 일치 (404 동일 코드 — 정보 노출 방지).</li>
     *   <li>row.status ∈ (APPLIED, RESOLVED_BY_ADMIN_OVERRIDE) — 그 외는 409 KVA_SETTLEMENT_NOT_APPLICABLE.</li>
     *   <li>row.adminPaymentAdjustment 가 이미 PAID_DIFFERENCE/REFUNDED/WAIVED — D6 거부, 409 KVA_SETTLEMENT_ALREADY_FINALIZED.</li>
     *   <li>요청 paymentAdjustment 가 PENDING/null — 400 (validation).</li>
     * </ul>
     *
     * <h3>트랜잭션 경계</h3>
     * 단일 {@code @Transactional} — 도메인 메서드 호출 + audit 기록 + 이벤트 publish 를 단일 트랜잭션 내에 보관.
     * 알림 발송은 AFTER_COMMIT (KvaSettlementNotificationListener).
     *
     * @param applicationSeq path variable applicationSeq (정보 노출 방지 — row 의 application 과 매칭 검증)
     * @param adjustmentSeq  path variable adjustmentSeq
     * @param request        Settlement 마킹 요청 DTO
     * @param adminUserSeq   요청자 ADMIN userSeq
     */
    @Transactional
    public KvaAdjustmentHistoryItem markSettlement(Long applicationSeq,
                                                    Long adjustmentSeq,
                                                    KvaSettlementUpdateRequest request,
                                                    Long adminUserSeq) {
        KvaAdjustmentRecord record = kvaAdjustmentRepository.findById(adjustmentSeq)
                .orElseThrow(() -> {
                    logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                            "KVA_ADJUSTMENT_NOT_FOUND",
                            "Adjustment row not found");
                    return new BusinessException(
                            "Adjustment record not found",
                            HttpStatus.NOT_FOUND, "KVA_ADJUSTMENT_NOT_FOUND");
                });

        // path 와 row 의 application 일치 검증 — 다른 application 의 row 를 PATCH 하려는 시도 차단.
        // 정보 노출 방지를 위해 동일한 KVA_ADJUSTMENT_NOT_FOUND 로 응답 (실제 row 가 다른 application 에 존재함을 노출하지 않음).
        Long rowApplicationSeq = record.getApplication() != null
                ? record.getApplication().getApplicationSeq() : null;
        if (rowApplicationSeq == null || !rowApplicationSeq.equals(applicationSeq)) {
            logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                    "KVA_ADJUSTMENT_NOT_FOUND",
                    "Adjustment row " + adjustmentSeq + " belongs to a different application");
            throw new BusinessException(
                    "Adjustment record not found",
                    HttpStatus.NOT_FOUND, "KVA_ADJUSTMENT_NOT_FOUND");
        }

        // status 가드 — APPLIED / RESOLVED_BY_ADMIN_OVERRIDE 만 settlement 가능.
        KvaAdjustmentStatus rowStatus = record.getStatus();
        if (rowStatus != KvaAdjustmentStatus.APPLIED
                && rowStatus != KvaAdjustmentStatus.RESOLVED_BY_ADMIN_OVERRIDE) {
            logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                    "KVA_SETTLEMENT_NOT_APPLICABLE",
                    "Settlement not applicable to status " + rowStatus);
            throw new BusinessException(
                    "Settlement is only applicable to APPLIED or RESOLVED_BY_ADMIN_OVERRIDE rows",
                    HttpStatus.CONFLICT, "KVA_SETTLEMENT_NOT_APPLICABLE");
        }

        // request paymentAdjustment 가드 — PENDING/null 거부 (jakarta validation 으로도 잡히지만 방어).
        if (request.getPaymentAdjustment() == null
                || request.getPaymentAdjustment() == AdminPaymentAdjustment.PENDING) {
            logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                    "KVA_SETTLEMENT_INVALID_VALUE",
                    "paymentAdjustment must be PAID_DIFFERENCE / REFUNDED / WAIVED");
            throw new BusinessException(
                    "paymentAdjustment must be a finalize value (PAID_DIFFERENCE / REFUNDED / WAIVED)",
                    HttpStatus.BAD_REQUEST, "KVA_SETTLEMENT_INVALID_VALUE");
        }

        // D6: 이미 finalize 된 row 는 다시 마킹할 수 없다.
        AdminPaymentAdjustment current = record.getAdminPaymentAdjustment();
        if (current == AdminPaymentAdjustment.PAID_DIFFERENCE
                || current == AdminPaymentAdjustment.REFUNDED
                || current == AdminPaymentAdjustment.WAIVED) {
            logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                    "KVA_SETTLEMENT_ALREADY_FINALIZED",
                    "Settlement already finalized as " + current
                            + " — create a new adjustment record to correct (D6)");
            throw new BusinessException(
                    "Settlement is already finalized — create a new adjustment record to correct",
                    HttpStatus.CONFLICT, "KVA_SETTLEMENT_ALREADY_FINALIZED");
        }

        // 도메인 메서드 호출 — 추가 가드 (race condition 방어).
        try {
            record.markSettlement(
                    request.getPaymentAdjustment(),
                    request.getSettledAmount(),
                    request.getReceiptReferenceNumber(),
                    request.getSettlementMemo(),
                    LocalDateTime.now());
        } catch (IllegalStateException ise) {
            // 동시성 또는 의외 케이스 — 상위 status 가드를 통과했으나 도메인 레벨에서 다시 막힌 경우.
            logSettlementDenied(adminUserSeq, applicationSeq, adjustmentSeq, request,
                    "KVA_SETTLEMENT_ALREADY_FINALIZED",
                    "Domain-level settlement guard rejected: " + ise.getMessage());
            throw new BusinessException(
                    ise.getMessage(),
                    HttpStatus.CONFLICT, "KVA_SETTLEMENT_ALREADY_FINALIZED");
        }
        // 명시적으로 adminAdjustmentAt 도 갱신 (AC-S1: settlement 마킹 시각 기록).
        // ※ KvaAdjustmentRecord 의 adminAdjustmentAt 은 여러 시점(직접 변경 시각 또는 settlement 시각) 에
        //    의해 갱신될 수 있다 — settlement 만 갱신 시 별도 setter 가 없으므로 reflection 대신
        //    settledAt 으로 모든 시각 표시를 통일한다 (UI 는 settledAt 우선 사용).

        // ── Audit log (REQUIRES_NEW) ─────────────────────
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("adjustmentSeq", adjustmentSeq);
        meta.put("applicationSeq", applicationSeq);
        meta.put("paymentAdjustment", request.getPaymentAdjustment().name());
        meta.put("settledAmount", request.getSettledAmount());
        meta.put("receiptReferenceNumber", request.getReceiptReferenceNumber());
        meta.put("settlementMemo", request.getSettlementMemo());
        meta.put("rowStatus", rowStatus.name());
        meta.put("notifyLew", Boolean.TRUE.equals(request.getNotifyLew()));
        auditLogService.logAsync(
                adminUserSeq, AuditAction.KVA_SETTLEMENT_MARKED, AuditCategory.ADMIN,
                "KvaAdjustmentRecord", String.valueOf(adjustmentSeq),
                "kVA settlement marked by ADMIN",
                null, meta,
                null, null, "PATCH",
                "/api/admin/applications/" + applicationSeq + "/kva-adjustments/" + adjustmentSeq + "/settlement", 200);

        log.info("kVA settlement marked: applicationSeq={}, adjustmentSeq={}, paymentAdjustment={}, settledAmount={}, adminUserSeq={}",
                applicationSeq, adjustmentSeq, request.getPaymentAdjustment(),
                request.getSettledAmount(), adminUserSeq);

        // ── notifyLew=true (기본) 인 경우에만 AFTER_COMMIT 알림 이벤트 발행 ──
        // 가드: 본 트랜잭션은 ledger 갱신 + audit 가 본질이고, 알림은 부수효과. notifyLew=false 면 listener 가
        // 깨어나지 않도록 publish 자체를 스킵한다 (listener 내부 가드보다 명확).
        if (Boolean.TRUE.equals(request.getNotifyLew())) {
            Application app = record.getApplication();
            Long lewUserSeq = (app != null && app.getAssignedLew() != null)
                    ? app.getAssignedLew().getUserSeq() : null;
            eventPublisher.publishEvent(new KvaSettlementMarkedEvent(
                    applicationSeq,
                    adjustmentSeq,
                    lewUserSeq,
                    request.getPaymentAdjustment(),
                    request.getSettledAmount(),
                    request.getReceiptReferenceNumber(),
                    adminUserSeq));
        }

        // 응답 — entity 의 갱신된 state 를 그대로 DTO 변환. changedByUserName 은 lookup.
        String changedByUserName = (record.getChangedByUserSeq() != null)
                ? userRepository.findById(record.getChangedByUserSeq())
                    .map(this::formatUserDisplayName)
                    .orElse(null)
                : null;
        return KvaAdjustmentHistoryItem.from(record, changedByUserName);
    }

    /** PR-4: User 표시 이름 — firstName + lastName, 없으면 email, 없으면 null. */
    private String formatUserDisplayName(User u) {
        if (u == null) return null;
        String first = u.getFirstName() != null ? u.getFirstName() : "";
        String last = u.getLastName() != null ? u.getLastName() : "";
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) return full;
        return u.getEmail();
    }

    /** PR-4: settlement 마킹 거부 케이스 audit 로그. */
    private void logSettlementDenied(Long adminUserSeq, Long applicationSeq, Long adjustmentSeq,
                                       KvaSettlementUpdateRequest req,
                                       String errorCode, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", errorCode);
        m.put("reason", reason);
        m.put("requestedPaymentAdjustment",
                req != null && req.getPaymentAdjustment() != null
                        ? req.getPaymentAdjustment().name() : null);
        m.put("requestedSettledAmount", req != null ? req.getSettledAmount() : null);
        m.put("applicationSeq", applicationSeq);
        m.put("adjustmentSeq", adjustmentSeq);
        int httpStatus = switch (errorCode) {
            case "KVA_ADJUSTMENT_NOT_FOUND" -> 404;
            case "KVA_SETTLEMENT_NOT_APPLICABLE", "KVA_SETTLEMENT_ALREADY_FINALIZED" -> 409;
            case "KVA_SETTLEMENT_INVALID_VALUE" -> 400;
            default -> 400;
        };
        auditLogService.logAsync(
                adminUserSeq, AuditAction.KVA_SETTLEMENT_DENIED, AuditCategory.ADMIN,
                "KvaAdjustmentRecord", String.valueOf(adjustmentSeq),
                "kVA settlement denied: " + errorCode,
                null, m,
                null, null, "PATCH",
                "/api/admin/applications/" + applicationSeq + "/kva-adjustments/" + adjustmentSeq + "/settlement",
                httpStatus);
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * tierPrice + sldFee + emaFee 로 newQuote 재계산. {@code ApplicationKvaService.confirm} 과 동일 로직.
     */
    private BigDecimal recalculateQuote(Application application, MasterPrice masterPrice) {
        BigDecimal tierPrice = (application.getApplicationType() == ApplicationType.RENEWAL)
                ? masterPrice.getRenewalPrice()
                : masterPrice.getPrice();
        BigDecimal newQuote = tierPrice;
        // 출장비(call-out fee): New License 에만 가산
        if (application.getApplicationType() != ApplicationType.RENEWAL
                && masterPrice.getCalloutFee() != null) {
            newQuote = newQuote.add(masterPrice.getCalloutFee());
        }
        if (application.getSldOption() != null
                && application.getSldOption().name().equals("REQUEST_LEW")) {
            BigDecimal sldFee = masterPrice.getSldPrice();
            if (sldFee != null) {
                newQuote = newQuote.add(sldFee);
            }
        }
        if (application.getEmaFee() != null) {
            newQuote = newQuote.add(application.getEmaFee());
        }
        return newQuote;
    }

    /**
     * 기존 활성 Invoice 를 INVALIDATED 마킹 + 신규 Invoice 자동 발행.
     *
     * <p>활성 Invoice 가 없으면 무시 (Invoice 자동 발행이 없었던 경우 — invoice-spec §5 의 예외 케이스).
     * 신규 발행은 {@link InvoiceGenerationService#generateFromPayment} 가 책임지며, 같은
     * 트랜잭션 내에서 호출되므로 실패 시 전체 롤백.</p>
     *
     * <p>스펙: {@code kva-postpayment-adjustment-spec.md} §10 D3.</p>
     */
    private void invalidateAndRegenerateInvoice(Application application, Long adjustmentSeq) {
        Long applicationSeq = application.getApplicationSeq();
        Optional<Invoice> activeInvoice = invoiceRepository
                .findFirstByApplicationSeqAndReferenceTypeAndStatus(
                        applicationSeq, "APPLICATION", "ACTIVE");
        if (activeInvoice.isEmpty()) {
            log.warn("No active invoice found for applicationSeq={}; skipping regeneration",
                    applicationSeq);
            return;
        }
        Invoice old = activeInvoice.get();
        old.invalidate("KVA_ADJUSTMENT_" + adjustmentSeq);

        // 신규 영수증은 결제 row 1건당 발행되어야 한다. PaymentRepository 에서 SUCCESS 결제 1건 조회.
        Payment payment = paymentRepository
                .findByApplicationApplicationSeqAndStatus(applicationSeq, PaymentStatus.SUCCESS)
                .orElse(null);
        if (payment == null) {
            log.warn("No SUCCESS payment found for applicationSeq={}; skipping invoice regeneration",
                    applicationSeq);
            return;
        }

        Invoice newInvoice = invoiceGenerationService.generateFromPayment(payment, application);
        log.info("Invoice regenerated: oldInvoiceSeq={}, newInvoiceSeq={}, applicationSeq={}",
                old.getInvoiceSeq(), newInvoice.getInvoiceSeq(), applicationSeq);

        // INVOICE_REGENERATED audit (REQUIRES_NEW)
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("oldInvoiceSeq", old.getInvoiceSeq());
        meta.put("newInvoiceSeq", newInvoice.getInvoiceSeq());
        meta.put("invalidatedReason", old.getInvalidatedReason());
        meta.put("kvaAdjustmentSeq", adjustmentSeq);
        auditLogService.logAsync(
                null, AuditAction.INVOICE_REGENERATED, AuditCategory.ADMIN,
                "Invoice", String.valueOf(newInvoice.getInvoiceSeq()),
                "Invoice regenerated due to kVA post-payment override",
                null, meta,
                null, null, "POST",
                "/api/admin/applications/" + applicationSeq + "/kva-override-postpayment", 200);
    }

    private Map<String, Object> buildOverrideMetadata(
            Integer previousKva, BigDecimal previousQuote,
            Integer newKva, BigDecimal newQuote, BigDecimal amountDifference,
            Long masterPriceSeq, AdminPaymentAdjustment paymentAdjustment,
            String reason, String adminMemo,
            ApplicationStatus status, Long adjustmentSeq) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adjustmentSeq", adjustmentSeq);
        m.put("previousKva", previousKva);
        m.put("newKva", newKva);
        m.put("previousQuoteAmount", previousQuote);
        m.put("newQuoteAmount", newQuote);
        m.put("amountDifference", amountDifference);
        m.put("masterPriceSeqUsed", masterPriceSeq);
        m.put("paymentAdjustment", paymentAdjustment != null ? paymentAdjustment.name() : null);
        m.put("applicationStatus", status != null ? status.name() : null);
        m.put("reason", reason);
        m.put("adminMemo", adminMemo);
        return m;
    }

    private void logDenied(Long adminUserSeq, Application application,
                           KvaPostPaymentOverrideRequest req,
                           String errorCode, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", errorCode);
        m.put("reason", reason);
        m.put("requestedKva", req != null ? req.getNewKva() : null);
        m.put("currentKva", application.getSelectedKva());
        m.put("currentStatus",
                application.getStatus() != null ? application.getStatus().name() : null);
        auditLogService.logAsync(
                adminUserSeq, AuditAction.KVA_OVERRIDE_POSTPAYMENT, AuditCategory.ADMIN,
                "Application", String.valueOf(application.getApplicationSeq()),
                "kVA post-payment override denied: " + errorCode,
                null, m,
                null, null, "POST",
                "/api/admin/applications/" + application.getApplicationSeq()
                        + "/kva-override-postpayment",
                statusFromCode(errorCode));
    }

    private int statusFromCode(String code) {
        return switch (code) {
            case "KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED" -> 409;
            case "KVA_NOT_POSTPAYMENT" -> 409;
            case "KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING" -> 409;
            case "KVA_NO_CHANGE" -> 400;
            case "INVALID_KVA_TIER" -> 400;
            default -> 400;
        };
    }

    /** PR-3: LEW 요청 거부 케이스 audit 로그. ADMIN 거부와 코드 매핑은 별 표기되지만 양 흐름의 거부 코드를 공유. */
    private void logLewDenied(Long lewUserSeq, Application application,
                               LewKvaAdjustmentRequest req,
                               String errorCode, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("errorCode", errorCode);
        m.put("reason", reason);
        m.put("proposedKva", req != null ? req.getProposedKva() : null);
        m.put("currentKva", application.getSelectedKva());
        m.put("currentStatus",
                application.getStatus() != null ? application.getStatus().name() : null);
        auditLogService.logAsync(
                lewUserSeq, AuditAction.KVA_ADJUSTMENT_REQUESTED_BY_LEW, AuditCategory.APPLICATION,
                "Application", String.valueOf(application.getApplicationSeq()),
                "kVA adjustment request denied: " + errorCode,
                null, m,
                null, null, "POST",
                "/api/lew/applications/" + application.getApplicationSeq()
                        + "/kva-adjustment-request",
                statusFromCode(errorCode));
    }

    /**
     * LEW userSeq → 표시용 이름 (firstName + lastName, 없으면 email 또는 "LEW").
     * 이벤트 payload 의 best-effort 값으로만 사용 — listener 는 필요 시 user 재조회로 보강한다.
     */
    private String resolveLewDisplayName(Long lewUserSeq) {
        try {
            return userRepository.findById(lewUserSeq)
                    .map(u -> {
                        String first = u.getFirstName() != null ? u.getFirstName() : "";
                        String last = u.getLastName() != null ? u.getLastName() : "";
                        String full = (first + " " + last).trim();
                        if (!full.isEmpty()) return full;
                        return u.getEmail() != null ? u.getEmail() : "LEW";
                    })
                    .orElse("LEW");
        } catch (RuntimeException ex) {
            return "LEW";
        }
    }
}
