package com.bluelight.backend.service.kva;

import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideRequest;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.cof.CertificateOfFitness;
import com.bluelight.backend.domain.cof.CertificateOfFitnessRepository;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
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
    private final CertificateOfFitnessRepository cofRepository;
    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceGenerationService invoiceGenerationService;
    private final AuditLogService auditLogService;

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
                .cofReissueTriggered(false)
                .build();
        record = kvaAdjustmentRepository.save(record);

        // ── Invoice invalidate + 재발행 (D3) ─────────────
        invalidateAndRegenerateInvoice(application, record.getAdjustmentSeq());

        // ── CoF unfinalize (AC-C1) ───────────────────────
        boolean cofReissueTriggered = unfinalizeCofIfNeeded(application, request.getNewKva());
        if (cofReissueTriggered) {
            record.markCofReissueTriggered();
        }

        // ── Audit logs (REQUIRES_NEW) ────────────────────
        Map<String, Object> overrideMeta = buildOverrideMetadata(
                previousKva, previousQuote, request.getNewKva(), newQuote,
                amountDifference, masterPrice.getMasterPriceSeq(),
                paymentAdjustment, request.getReason(), request.getAdminMemo(),
                current, record.getAdjustmentSeq(), cofReissueTriggered);
        auditLogService.logAsync(
                adminUserSeq, AuditAction.KVA_OVERRIDE_POSTPAYMENT, AuditCategory.ADMIN,
                "Application", String.valueOf(applicationSeq),
                "kVA overridden post-payment by ADMIN",
                null, overrideMeta,
                null, null, "POST",
                "/api/admin/applications/" + applicationSeq + "/kva-override-postpayment", 200);

        if (cofReissueTriggered) {
            Map<String, Object> cofMeta = new LinkedHashMap<>(overrideMeta);
            cofMeta.put("cofUnfinalized", true);
            auditLogService.logAsync(
                    adminUserSeq, AuditAction.COF_UNFINALIZED_BY_KVA_ADJUSTMENT,
                    AuditCategory.ADMIN,
                    "Application", String.valueOf(applicationSeq),
                    "CoF unfinalized due to kVA post-payment override",
                    null, cofMeta,
                    null, null, "POST",
                    "/api/admin/applications/" + applicationSeq + "/kva-override-postpayment", 200);
        }

        log.info("kVA post-payment overridden: applicationSeq={}, prev={}kVA/{}, new={}kVA/{}, "
                        + "adjustmentSeq={}, cofReissued={}, adminUserSeq={}",
                applicationSeq, previousKva, previousQuote,
                request.getNewKva(), newQuote, record.getAdjustmentSeq(),
                cofReissueTriggered, adminUserSeq);

        // 알림은 PR-2 에서 추가. PR-1 은 ApplicationEventPublisher 호출 없음.

        return KvaPostPaymentOverrideResponse.from(record);
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

    /**
     * CoF 가 finalized 면 unfinalize. true 반환 시 LEW 재서명 흐름 필요.
     * Application.status 는 변경하지 않는다 (PR3 모델 — CoF 는 결제 후 단계).
     */
    private boolean unfinalizeCofIfNeeded(Application application, Integer newKva) {
        Optional<CertificateOfFitness> cofOpt = cofRepository
                .findByApplication_ApplicationSeq(application.getApplicationSeq());
        if (cofOpt.isEmpty()) {
            return false;
        }
        CertificateOfFitness cof = cofOpt.get();
        if (!cof.isFinalized()) {
            // 이미 unfinalized 면 approvedLoadKva 만 새 값으로 동기화.
            try {
                cof.snapshotApprovedLoadKva(newKva);
            } catch (IllegalStateException ignore) {
                // race condition 으로 finalized 가 됐다면 다시 reopen
                cof.reopenForReissue(newKva);
                return true;
            }
            return false;
        }
        cof.reopenForReissue(newKva);
        return true;
    }

    private Map<String, Object> buildOverrideMetadata(
            Integer previousKva, BigDecimal previousQuote,
            Integer newKva, BigDecimal newQuote, BigDecimal amountDifference,
            Long masterPriceSeq, AdminPaymentAdjustment paymentAdjustment,
            String reason, String adminMemo,
            ApplicationStatus status, Long adjustmentSeq, boolean cofReissueTriggered) {
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
        m.put("cofReissueTriggered", cofReissueTriggered);
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
            case "KVA_NO_CHANGE" -> 400;
            case "INVALID_KVA_TIER" -> 400;
            default -> 400;
        };
    }
}
