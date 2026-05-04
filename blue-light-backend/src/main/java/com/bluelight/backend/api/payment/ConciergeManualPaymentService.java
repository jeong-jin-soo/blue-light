package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.admin.dto.ManualPaymentResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.concierge.dto.ConciergeManualPaymentRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.ConciergeOwnershipValidator;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — ConciergeRequest 용 별도 수금 서비스.
 * <p>
 * 스펙: §7.3, §10 AC-A4. CONCIERGE_MANAGER/ADMIN/SYSTEM_ADMIN 이 컨시어지 서비스 수수료를 외부
 * 채널로 수금한 후 시스템에 기록. ADMIN 은 ownership 우회, MANAGER 는 본인 배정 건만.
 * <p>
 * ConciergeRequest 의 상태 전이는 다음 규칙을 따른다:
 * <ul>
 *   <li>{@code AWAITING_LICENCE_PAYMENT} → {@code IN_PROGRESS}: 정상 결제 동선 ({@link ConciergeRequest#markLicencePaid()}).</li>
 *   <li>그 외 상태(SUBMITTED ~ APPLICATION_CREATED 등): 결제만 기록하고 상태는 변경하지 않는다.
 *       선결제·후입금 케이스를 허용하기 위함 (MVP 정책).</li>
 *   <li>{@code IN_PROGRESS} 또는 {@code COMPLETED}: 이미 결제 완료된 row 가 있으면 409 (중복 차단).</li>
 *   <li>{@code CANCELLED}: 거부 (스펙 §7.3 — CANCELLED 는 결제 기록 차단).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConciergeManualPaymentService {

    private final ConciergeRequestRepository conciergeRequestRepository;
    private final PaymentRepository paymentRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * ConciergeRequest 별도 수금 기록.
     */
    @Transactional
    public ManualPaymentResponse recordOfflinePayment(Long conciergeRequestSeq,
                                                       ConciergeManualPaymentRequest request,
                                                       Long actorUserSeq) {
        // ── 1) 입력 검증 ──
        validateRequest(request);

        // ── 2) ConciergeRequest 조회 + 상태 검증 + 권한 검증 ──
        ConciergeRequest cr = conciergeRequestRepository.findById(conciergeRequestSeq)
                .orElseThrow(() -> new BusinessException(
                        "Concierge request not found: " + conciergeRequestSeq,
                        HttpStatus.NOT_FOUND, "NOT_FOUND"));

        User actor = userRepository.findById(actorUserSeq)
                .orElseThrow(() -> new BusinessException(
                        "Actor user not found", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));

        // MANAGER 는 본인 배정 건만, ADMIN/SYSTEM_ADMIN 은 ownership 우회 (validator 내부 분기).
        ConciergeOwnershipValidator.assertManagerCanAccess(cr, actor);

        ConciergeRequestStatus current = cr.getStatus();
        validateConciergeStatus(cr, current);

        User applicant = cr.getApplicantUser();
        if (applicant == null) {
            throw new BusinessException(
                    "ConciergeRequest has no applicant user (data integrity error)",
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");
        }

        // ── 3) Payment.createOfflineRecord ──
        LocalDateTime paidAtDateTime = request.getPaidAt().atStartOfDay();
        Payment payment = Payment.createOfflineRecord(
                PaymentReferenceType.CONCIERGE_REQUEST,
                conciergeRequestSeq,
                request.getAmount(),
                request.getPaymentMethod(),
                actorUserSeq,
                paidAtDateTime,
                applicant.getUserSeq());

        Payment saved = paymentRepository.save(payment);

        // ── 4) ConciergeRequest 상태 전이 (가능한 경우만) ──
        if (current == ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT) {
            try {
                cr.markLicencePaid();
            } catch (IllegalStateException e) {
                // canTransitionTo 가드 위반 — 정합성 보장
                throw new BusinessException(
                        "Concierge transition blocked: " + e.getMessage(),
                        HttpStatus.CONFLICT, "INVALID_TRANSITION");
            }
        }
        // 그 외 상태는 결제만 연결하고 status 는 보존 (선결제 케이스).

        // ── 5) ConciergeRequest.linkPayment ──
        cr.linkPayment(saved.getPaymentSeq());

        // ── 6) Audit ──
        recordAuditTrail(cr, saved, request, actorUserSeq, current);

        // ── 7) AFTER_COMMIT 이벤트 발행 → Invoice 자동 발행 + 영수증 이메일 ──
        eventPublisher.publishEvent(new ManualPaymentRecordedEvent(
                saved.getPaymentSeq(),
                applicant.getUserSeq(),
                PaymentReferenceType.CONCIERGE_REQUEST,
                /* applicationSeq */ null,
                conciergeRequestSeq,
                saved.getAmount(),
                request.getPaymentMethod(),
                request.isReceiptIssue(),
                actorUserSeq));

        log.info("Concierge manual payment recorded: conciergeRequestSeq={}, paymentSeq={}, method={}, amount={}, by actorSeq={}",
                conciergeRequestSeq, saved.getPaymentSeq(), request.getPaymentMethod(),
                request.getAmount(), actorUserSeq);

        return ManualPaymentResponse.builder()
                .paymentSeq(saved.getPaymentSeq())
                .amount(saved.getAmount())
                .paymentMethod(request.getPaymentMethod())
                .paidAt(saved.getPaidAt())
                .recordedAt(saved.getRecordedAt())
                .receiptIssued(request.isReceiptIssue())
                .conciergeRequestSeq(conciergeRequestSeq)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // 검증 헬퍼
    // ────────────────────────────────────────────────────────────

    private void validateRequest(ConciergeManualPaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BusinessException("Amount must be positive",
                    HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        }
        if (request.getPaymentMethod() == null) {
            throw new BusinessException("paymentMethod is required",
                    HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_METHOD");
        }
        if (!request.getPaymentMethod().isOffline()) {
            throw new BusinessException(
                    "PAYNOW_ONLINE is not allowed for manual payment",
                    HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_METHOD");
        }
        if (request.getPaidAt() == null) {
            throw new BusinessException("paidAt is required",
                    HttpStatus.BAD_REQUEST, "INVALID_PAID_AT");
        }
        if (request.getPaidAt().isAfter(LocalDate.now())) {
            throw new BusinessException("paidAt cannot be in the future",
                    HttpStatus.BAD_REQUEST, "INVALID_PAID_AT");
        }
    }

    /**
     * 상태 검증:
     * - CANCELLED: 결제 기록 차단
     * - IN_PROGRESS / COMPLETED 이고 이미 paymentSeq 가 연결되어 있으면: 중복 차단
     */
    private void validateConciergeStatus(ConciergeRequest cr, ConciergeRequestStatus current) {
        if (current == ConciergeRequestStatus.CANCELLED) {
            throw new BusinessException(
                    "Cannot record payment for a cancelled concierge request",
                    HttpStatus.CONFLICT, "CONCIERGE_CANCELLED");
        }
        // 중복 결제 방지 — 이미 결제 row 가 연결된 경우.
        if (cr.getPaymentSeq() != null) {
            throw new BusinessException(
                    "Concierge request already has a recorded payment (paymentSeq=" + cr.getPaymentSeq() + ")",
                    HttpStatus.CONFLICT, "ALREADY_PAID");
        }
    }

    private void recordAuditTrail(ConciergeRequest cr, Payment saved,
                                   ConciergeManualPaymentRequest request,
                                   Long actorUserSeq, ConciergeRequestStatus previousStatus) {
        BigDecimal quoteAmount = cr.getQuotedAmount();
        StringBuilder description = new StringBuilder();
        description.append("Manual offline payment recorded for concierge: ")
                .append("paymentSeq=").append(saved.getPaymentSeq())
                .append(", publicCode=").append(cr.getPublicCode())
                .append(", method=").append(request.getPaymentMethod())
                .append(", amount=").append(request.getAmount())
                .append(", paidAt=").append(request.getPaidAt())
                .append(", previousStatus=").append(previousStatus);
        if (request.getReferenceNote() != null && !request.getReferenceNote().isBlank()) {
            description.append(", referenceNote=").append(request.getReferenceNote());
        }
        if (quoteAmount != null && quoteAmount.compareTo(request.getAmount()) != 0) {
            BigDecimal diff = request.getAmount().subtract(quoteAmount);
            description.append(", quoteDiff: quoted=").append(quoteAmount)
                    .append(", paid=").append(request.getAmount())
                    .append(", diff=").append(diff.signum() > 0 ? "+" + diff : diff.toPlainString());
        }

        auditLogService.log(
                actorUserSeq, null, null,
                AuditAction.MANUAL_PAYMENT_RECORDED,
                AuditCategory.APPLICATION,
                "ConciergeRequest", String.valueOf(cr.getConciergeRequestSeq()),
                description.toString(),
                null, null, null, null,
                "POST", "/api/concierge-manager/requests/" + cr.getConciergeRequestSeq() + "/manual-payment", 200);
    }
}
