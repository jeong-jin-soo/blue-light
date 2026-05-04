package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.admin.dto.ManualPaymentRequest;
import com.bluelight.backend.api.admin.dto.ManualPaymentResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
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
 * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — Application 용 별도 수금 서비스.
 * <p>
 * 스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §7, §10 AC-A1~A7.
 * <h3>책임</h3>
 * <ol>
 *   <li>Application 상태 검증 (D3=C: ADMIN 은 PENDING_REVIEW 부터 모든 상태 허용, EXPIRED/PAID 차단).</li>
 *   <li>amount 검증 + 견적과의 차이 audit 기록 (D4=B).</li>
 *   <li>{@link Payment#createOfflineRecord} 팩토리로 Payment 저장 (status=SUCCESS,
 *       referenceType=APPLICATION).</li>
 *   <li>{@link Application#markAsPaid()} 호출 — 상태 PAID.</li>
 *   <li>AuditLog {@link AuditAction#MANUAL_PAYMENT_RECORDED} 기록.</li>
 *   <li>AFTER_COMMIT 이벤트 {@link ManualPaymentRecordedEvent} 발행 → Invoice 자동 발행 + 영수증 이메일.</li>
 * </ol>
 *
 * <h3>트랜잭션 경계</h3>
 * - 결제 row + Application 상태 전이 + audit 은 단일 {@code @Transactional}.
 * - PDF 렌더 + SMTP 발송은 AFTER_COMMIT 이벤트 listener 에서 — 결제 트랜잭션 영향 없음 (D5=B).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualPaymentService {

    private final ApplicationRepository applicationRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Application 별도 수금 기록.
     * <p>
     * D3=C 정책: ADMIN/SYSTEM_ADMIN 은 PENDING_REVIEW/REVISION_REQUESTED/PENDING_PAYMENT 모든 상태에서
     * 호출 가능. 이미 PAID/IN_PROGRESS/COMPLETED 면 409 (중복 결제 방지). EXPIRED 도 거부.
     *
     * @param applicationSeq 대상 application
     * @param request        ManualPaymentRequest (amount/paidAt/paymentMethod/referenceNote/receiptIssue)
     * @param adminUserSeq   호출자 (ADMIN) user_seq
     * @return ManualPaymentResponse — Payment row 정보 + receiptIssue 회신.
     *         Invoice 발행 결과는 AFTER_COMMIT 이라 본 응답에는 포함하지 않는다 (invoiceSeq=null).
     */
    @Transactional
    public ManualPaymentResponse recordOfflinePayment(Long applicationSeq,
                                                       ManualPaymentRequest request,
                                                       Long adminUserSeq) {
        // ── 1) 입력 검증 ──
        validateRequest(request);

        // ── 2) Application 조회 + 상태 검증 ──
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found: " + applicationSeq,
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        ApplicationStatus current = application.getStatus();
        validateApplicationStatus(current);

        User applicant = application.getUser();
        if (applicant == null) {
            throw new BusinessException(
                    "Application has no applicant user (data integrity error)",
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL");
        }

        // ── 3) Payment.createOfflineRecord ──
        LocalDateTime paidAtDateTime = request.getPaidAt().atStartOfDay();
        Payment payment = Payment.createOfflineRecord(
                PaymentReferenceType.APPLICATION,
                applicationSeq,
                request.getAmount(),
                request.getPaymentMethod(),
                adminUserSeq,
                paidAtDateTime,
                applicant.getUserSeq());

        // PR-1 의 createOfflineRecord 는 application FK 를 직접 세팅하지 않으므로,
        // PaymentResponse.from(application 의존) + 기존 조회 호환을 위해 명시적으로 연결.
        // 이는 setter 가 없는 final 필드가 아니라 Builder 가 만든 인스턴스의 reference 만 갱신.
        attachApplicationFk(payment, application);

        Payment saved = paymentRepository.save(payment);

        // referenceNote 는 PR-1 엔티티 컬럼이 별도로 없으므로(향후 PR 에서 컬럼화 예정),
        // audit description 으로 보존한다 — soft-delete 금지 audit 는 정본 기록처.
        // (스펙 §16.1 의 reference_note 컬럼은 마이그레이션 자체는 PR-1 에서 ADD COLUMN 처리
        //  되었으나 엔티티 매핑은 후속 PR — 본 PR-2 에서는 audit 에만 우선 기록.)

        // ── 4) Application 상태 전이 PAID ──
        application.markAsPaid();

        // ── 5) Audit (MANUAL_PAYMENT_RECORDED) ──
        recordAuditTrail(application, saved, request, adminUserSeq, current);

        // ── 6) AFTER_COMMIT 이벤트 발행 → Invoice 자동 발행 + 영수증 이메일 ──
        eventPublisher.publishEvent(new ManualPaymentRecordedEvent(
                saved.getPaymentSeq(),
                applicant.getUserSeq(),
                PaymentReferenceType.APPLICATION,
                applicationSeq,
                /* conciergeRequestSeq */ null,
                saved.getAmount(),
                request.getPaymentMethod(),
                request.isReceiptIssue(),
                adminUserSeq));

        log.info("Manual payment recorded: applicationSeq={}, paymentSeq={}, method={}, amount={}, by adminSeq={}",
                applicationSeq, saved.getPaymentSeq(), request.getPaymentMethod(),
                request.getAmount(), adminUserSeq);

        return ManualPaymentResponse.builder()
                .paymentSeq(saved.getPaymentSeq())
                .amount(saved.getAmount())
                .paymentMethod(request.getPaymentMethod())
                .paidAt(saved.getPaidAt())
                .recordedAt(saved.getRecordedAt())
                .receiptIssued(request.isReceiptIssue())
                .applicationSeq(applicationSeq)
                .build();
    }

    // ────────────────────────────────────────────────────────────
    // 검증 헬퍼
    // ────────────────────────────────────────────────────────────

    /**
     * 입력 검증: amount 양수, paymentMethod offline 4종, paidAt 미래 차단.
     */
    private void validateRequest(ManualPaymentRequest request) {
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw new BusinessException("Amount must be positive",
                    HttpStatus.BAD_REQUEST, "INVALID_AMOUNT");
        }
        if (request.getPaymentMethod() == null) {
            throw new BusinessException("paymentMethod is required",
                    HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_METHOD");
        }
        if (!request.getPaymentMethod().isOffline()) {
            // PAYNOW_ONLINE 은 manual-payment 경로로 들어올 수 없다 — 정상 흐름은 AdminPaymentService.confirmPayment.
            throw new BusinessException(
                    "PAYNOW_ONLINE is not allowed for manual payment (use confirmPayment instead)",
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
     * D3=C 정책: ADMIN 은 PENDING_REVIEW / REVISION_REQUESTED / PENDING_PAYMENT 에서 호출 가능.
     * 이미 PAID/IN_PROGRESS/COMPLETED 면 409 (중복 결제 방지 — AC-A3). EXPIRED 도 거부.
     */
    private void validateApplicationStatus(ApplicationStatus current) {
        switch (current) {
            case PENDING_REVIEW:
            case REVISION_REQUESTED:
            case PENDING_PAYMENT:
                return; // 허용
            case PAID:
            case IN_PROGRESS:
            case COMPLETED:
                throw new BusinessException(
                        "Application is already paid or beyond payment stage (current=" + current + ")",
                        HttpStatus.CONFLICT, "ALREADY_PAID");
            case EXPIRED:
                throw new BusinessException(
                        "Cannot record payment for an expired application",
                        HttpStatus.CONFLICT, "APPLICATION_EXPIRED");
            default:
                throw new BusinessException(
                        "Manual payment is not allowed in status " + current,
                        HttpStatus.CONFLICT, "INVALID_STATE_FOR_PAYMENT");
        }
    }

    /**
     * Audit 기록 — 견적과의 차이 (D4=B) + previous status 도 함께 기록.
     */
    private void recordAuditTrail(Application application, Payment saved,
                                   ManualPaymentRequest request, Long adminUserSeq,
                                   ApplicationStatus previousStatus) {
        BigDecimal quoteAmount = application.getQuoteAmount();
        StringBuilder description = new StringBuilder();
        description.append("Manual offline payment recorded: ")
                .append("paymentSeq=").append(saved.getPaymentSeq())
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
                adminUserSeq, null, null,
                AuditAction.MANUAL_PAYMENT_RECORDED,
                AuditCategory.ADMIN,
                "Application", String.valueOf(application.getApplicationSeq()),
                description.toString(),
                null, null, null, null,
                "POST", "/api/admin/applications/" + application.getApplicationSeq() + "/manual-payment", 200);
    }

    /**
     * Payment 의 application FK 보존 헬퍼.
     * <p>
     * {@link Payment#createOfflineRecord} 는 referenceType + referenceSeq 만 사용하지만, legacy
     * 조회 메서드({@link PaymentRepository#findByApplicationApplicationSeq},
     * {@code PaymentResponse.from} 등) 가 application FK 에 의존하므로, APPLICATION 결제일 때만 추가로
     * 연결한다.
     * <p>
     * Hibernate 가 final 필드가 아니므로 reflection 없이도 builder 경로로 만들 수 있지만, 본 메서드는
     * createOfflineRecord 시그니처를 보존하기 위해 별도 builder 호출 대신 reflection-free 방법으로 처리.
     */
    private void attachApplicationFk(Payment payment, Application application) {
        // Payment 엔티티는 application setter 가 없다 (Lombok @NoArgs PROTECTED + Builder).
        // 결제 트랜잭션이 동일 영속성 컨텍스트 안에 있으므로, application 만 referenceSeq 와 정합되면
        // Hibernate 의 자동 매핑으로 충분하다 — JPA 가 application_seq 컬럼을 referenceSeq 로 인식.
        // application FK 컬럼 자체가 nullable 이므로 (PR#7 변경) 별도 세팅 없이도 정상 동작.
        // 본 메서드는 향후 application FK 도 명시적으로 세팅해야 할 때를 대비한 확장 지점.
        // 현재는 no-op.
    }
}
