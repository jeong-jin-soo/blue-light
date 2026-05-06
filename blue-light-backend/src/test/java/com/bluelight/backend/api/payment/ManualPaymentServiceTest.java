package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.admin.dto.ManualPaymentRequest;
import com.bluelight.backend.api.admin.dto.ManualPaymentResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * ManualPaymentService — AC-A1 ~ AC-A7 + 권한/상태 가드 단위 테스트.
 * <p>
 * 스펙: {@code doc/Project Analysis/concierge-flow-and-offline-payment-spec.md} §10.
 */
@DisplayName("ManualPaymentService — Concierge 강화 + 별도 수금 PR-2 (AC-A1~A7)")
class ManualPaymentServiceTest {

    private ApplicationRepository applicationRepository;
    private PaymentRepository paymentRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private ManualPaymentService service;

    private static final Long APP_SEQ = 200L;
    private static final Long ADMIN_SEQ = 99L;
    private static final Long PAYMENT_SEQ = 555L;
    private static final Long APPLICANT_SEQ = 77L;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new ManualPaymentService(
                applicationRepository, paymentRepository, auditLogService, eventPublisher);
    }

    private Application stubApplication(ApplicationStatus status, BigDecimal quoteAmount) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(APP_SEQ);
        when(app.getStatus()).thenReturn(status);
        when(app.getQuoteAmount()).thenReturn(quoteAmount);
        User applicant = mock(User.class);
        when(applicant.getUserSeq()).thenReturn(APPLICANT_SEQ);
        when(app.getUser()).thenReturn(applicant);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));
        return app;
    }

    private void stubPaymentSave() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            // paymentSeq 가 null 이라면 ID 부여 시뮬레이션을 위해 reflection 사용 대신
            // 새 mock 으로 wrap — 실제로는 JPA 가 채움.
            Payment spy = spy(p);
            doReturn(PAYMENT_SEQ).when(spy).getPaymentSeq();
            return spy;
        });
    }

    private ManualPaymentRequest validRequest(BigDecimal amount, PaymentMethod method) {
        ManualPaymentRequest req = new ManualPaymentRequest();
        req.setAmount(amount);
        req.setPaymentMethod(method);
        req.setPaidAt(LocalDate.now());
        req.setReceiptIssue(true);
        return req;
    }

    // ── AC-A1: PENDING_PAYMENT 에서 ADMIN 호출 → Payment + Application.PAID + 이벤트 발행 ──
    @Test
    @DisplayName("AC-A1: PENDING_PAYMENT 에서 manual-payment(BANK_TRANSFER) → 200 + PAID + 이벤트")
    void shouldRecordManualPaymentForPendingPayment() {
        Application app = stubApplication(ApplicationStatus.PENDING_PAYMENT, new BigDecimal("350.00"));
        stubPaymentSave();

        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.BANK_TRANSFER);
        ManualPaymentResponse resp = service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ);

        assertThat(resp.getPaymentSeq()).isEqualTo(PAYMENT_SEQ);
        assertThat(resp.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(resp.isReceiptIssued()).isTrue();
        verify(app).markAsPaid();
        verify(eventPublisher).publishEvent(any(ManualPaymentRecordedEvent.class));
        verify(auditLogService).log(eq(ADMIN_SEQ), any(), any(),
                eq(com.bluelight.backend.domain.audit.AuditAction.MANUAL_PAYMENT_RECORDED),
                any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── AC-A2: EXPIRED 에서 호출 → 409 ──
    @Test
    @DisplayName("AC-A2: EXPIRED 에서 호출 → 409 APPLICATION_EXPIRED")
    void shouldRejectManualPaymentForExpiredApplication() {
        stubApplication(ApplicationStatus.EXPIRED, new BigDecimal("350.00"));

        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("APPLICATION_EXPIRED");
                });
        verify(paymentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ── AC-A3: 이미 PAID 에서 호출 → 409 ──
    @Test
    @DisplayName("AC-A3: 이미 PAID 에서 호출 → 409 ALREADY_PAID")
    void shouldRejectDuplicateManualPaymentWhenAlreadyPaid() {
        stubApplication(ApplicationStatus.PAID, new BigDecimal("350.00"));

        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("ALREADY_PAID");
                });
        verify(paymentRepository, never()).save(any());
    }

    // ── AC-A5: receiptIssue=false → 결제는 기록되지만 event 의 receiptIssue 가 false ──
    @Test
    @DisplayName("AC-A5: receiptIssue=false → Payment 저장 + event.receiptIssue=false (listener 가 invoice/email 스킵)")
    void shouldRecordPaymentButSkipReceiptWhenReceiptIssueFalse() {
        Application app = stubApplication(ApplicationStatus.PENDING_PAYMENT, new BigDecimal("350.00"));
        stubPaymentSave();

        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.CASH);
        req.setReceiptIssue(false);

        ManualPaymentResponse resp = service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ);

        assertThat(resp.isReceiptIssued()).isFalse();
        verify(app).markAsPaid();

        org.mockito.ArgumentCaptor<ManualPaymentRecordedEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ManualPaymentRecordedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().isReceiptIssue()).isFalse();
    }

    // ── AC-A6: amount=0 → 400 ──
    @Test
    @DisplayName("AC-A6: amount=0 → 400 INVALID_AMOUNT")
    void shouldRejectZeroAmount() {
        ManualPaymentRequest req = validRequest(BigDecimal.ZERO, PaymentMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(400);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_AMOUNT");
                });
        verify(applicationRepository, never()).findById(anyLong());
    }

    @Test
    @DisplayName("AC-A6 변형: 음수 amount → 400 INVALID_AMOUNT")
    void shouldRejectNegativeAmount() {
        ManualPaymentRequest req = validRequest(new BigDecimal("-1.00"), PaymentMethod.CASH);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_AMOUNT"));
    }

    // ── PAYNOW_ONLINE 거부 ──
    @Test
    @DisplayName("PAYNOW_ONLINE 은 manual-payment 경로에서 거부됨")
    void shouldRejectPayNowOnlineMethod() {
        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.PAYNOW_ONLINE);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo("INVALID_PAYMENT_METHOD"));
    }

    // ── AC-A7: amount ≠ quoteAmount → 200 + audit 에 차이 기록 (D4=B) ──
    @Test
    @DisplayName("AC-A7: amount(=400) ≠ quoteAmount(=350) → 200 + audit description 에 quoteDiff")
    void shouldAcceptAmountDifferentFromQuoteAndRecordDiffInAudit() {
        Application app = stubApplication(ApplicationStatus.PENDING_PAYMENT, new BigDecimal("350.00"));
        stubPaymentSave();

        ManualPaymentRequest req = validRequest(new BigDecimal("400.00"), PaymentMethod.BANK_TRANSFER);
        service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ);

        verify(app).markAsPaid();
        org.mockito.ArgumentCaptor<String> descCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogService).log(any(), any(), any(),
                eq(com.bluelight.backend.domain.audit.AuditAction.MANUAL_PAYMENT_RECORDED),
                any(), any(), any(), descCaptor.capture(),
                any(), any(), any(), any(), any(), any(), any());
        assertThat(descCaptor.getValue()).contains("quoteDiff").contains("quoted=350").contains("paid=400");
    }

    // ── 상태 PENDING_REVIEW 에서 호출 (D3=C) → 200 ──
    @Test
    @DisplayName("D3=C: ADMIN 은 PENDING_REVIEW 에서도 manual-payment 호출 가능 → 200")
    void shouldAllowAdminFromPendingReviewState() {
        Application app = stubApplication(ApplicationStatus.PENDING_REVIEW, new BigDecimal("300.00"));
        stubPaymentSave();

        ManualPaymentRequest req = validRequest(new BigDecimal("300.00"), PaymentMethod.PAYNOW_OFFLINE);
        ManualPaymentResponse resp = service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ);

        assertThat(resp.getPaymentSeq()).isEqualTo(PAYMENT_SEQ);
        verify(app).markAsPaid();
    }

    // ── Application 미존재 → 404 ──
    @Test
    @DisplayName("Application 미존재 → 404 APPLICATION_NOT_FOUND")
    void shouldThrow404WhenApplicationNotFound() {
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.empty());

        ManualPaymentRequest req = validRequest(new BigDecimal("350.00"), PaymentMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> service.recordOfflinePayment(APP_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(404));
    }
}
