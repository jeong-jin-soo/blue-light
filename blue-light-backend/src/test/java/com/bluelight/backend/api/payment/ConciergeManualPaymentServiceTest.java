package com.bluelight.backend.api.payment;

import com.bluelight.backend.api.admin.dto.ManualPaymentResponse;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.concierge.dto.ConciergeManualPaymentRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.concierge.ConciergeRequestStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentMethod;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import com.bluelight.backend.domain.user.UserRepository;
import com.bluelight.backend.domain.user.UserRole;
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
import static org.mockito.Mockito.*;

/**
 * ConciergeManualPaymentService — AC-A4 + Concierge 권한/상태 가드 단위 테스트.
 */
@DisplayName("ConciergeManualPaymentService — Concierge 강화 + 별도 수금 PR-2 (AC-A4)")
class ConciergeManualPaymentServiceTest {

    private ConciergeRequestRepository conciergeRequestRepository;
    private PaymentRepository paymentRepository;
    private UserRepository userRepository;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private ConciergeManualPaymentService service;

    private static final Long CR_SEQ = 300L;
    private static final Long ADMIN_SEQ = 99L;
    private static final Long PAYMENT_SEQ = 555L;
    private static final Long APPLICANT_SEQ = 77L;

    @BeforeEach
    void setUp() {
        conciergeRequestRepository = mock(ConciergeRequestRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        userRepository = mock(UserRepository.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new ConciergeManualPaymentService(
                conciergeRequestRepository, paymentRepository, userRepository,
                auditLogService, eventPublisher);

        // 기본 actor: ADMIN (ownership 우회)
        User actor = mock(User.class);
        when(actor.getUserSeq()).thenReturn(ADMIN_SEQ);
        when(actor.getRole()).thenReturn(UserRole.ADMIN);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(actor));
    }

    private ConciergeRequest stubConciergeRequest(ConciergeRequestStatus status, BigDecimal quoted) {
        ConciergeRequest cr = mock(ConciergeRequest.class);
        when(cr.getConciergeRequestSeq()).thenReturn(CR_SEQ);
        when(cr.getStatus()).thenReturn(status);
        when(cr.getQuotedAmount()).thenReturn(quoted);
        when(cr.getPublicCode()).thenReturn("C-2026-0300");
        when(cr.getPaymentSeq()).thenReturn(null);
        User applicant = mock(User.class);
        when(applicant.getUserSeq()).thenReturn(APPLICANT_SEQ);
        when(cr.getApplicantUser()).thenReturn(applicant);
        when(conciergeRequestRepository.findById(CR_SEQ)).thenReturn(Optional.of(cr));
        return cr;
    }

    private void stubPaymentSave() {
        when(paymentRepository.save(any(Payment.class))).thenAnswer(invocation -> {
            Payment p = invocation.getArgument(0);
            Payment spy = spy(p);
            doReturn(PAYMENT_SEQ).when(spy).getPaymentSeq();
            return spy;
        });
    }

    private ConciergeManualPaymentRequest validRequest(BigDecimal amount, PaymentMethod method) {
        ConciergeManualPaymentRequest req = new ConciergeManualPaymentRequest();
        req.setAmount(amount);
        req.setPaymentMethod(method);
        req.setPaidAt(LocalDate.now());
        req.setReceiptIssue(true);
        return req;
    }

    // ── AC-A4: QUOTE_SENT 에서 manual-payment → 200 + Payment + linkPayment + 이벤트 ──
    @Test
    @DisplayName("AC-A4: QUOTE_SENT 에서 manual-payment → 200 + Payment + 이벤트 (status 보존)")
    void shouldRecordManualPaymentForQuoteSent() {
        ConciergeRequest cr = stubConciergeRequest(ConciergeRequestStatus.QUOTE_SENT, new BigDecimal("500.00"));
        stubPaymentSave();

        ConciergeManualPaymentRequest req = validRequest(new BigDecimal("500.00"), PaymentMethod.BANK_TRANSFER);
        ManualPaymentResponse resp = service.recordOfflinePayment(CR_SEQ, req, ADMIN_SEQ);

        assertThat(resp.getPaymentSeq()).isEqualTo(PAYMENT_SEQ);
        assertThat(resp.getConciergeRequestSeq()).isEqualTo(CR_SEQ);
        verify(cr).linkPayment(PAYMENT_SEQ);
        // QUOTE_SENT 는 markLicencePaid 호출 X — 상태 보존.
        verify(cr, never()).markLicencePaid();
        verify(eventPublisher).publishEvent(any(ManualPaymentRecordedEvent.class));
    }

    // ── AWAITING_LICENCE_PAYMENT → IN_PROGRESS 정상 동선 ──
    @Test
    @DisplayName("AWAITING_LICENCE_PAYMENT 에서 호출 → markLicencePaid (IN_PROGRESS 전이)")
    void shouldTransitionAwaitingPaymentToInProgress() {
        ConciergeRequest cr = stubConciergeRequest(ConciergeRequestStatus.AWAITING_LICENCE_PAYMENT,
                new BigDecimal("500.00"));
        stubPaymentSave();

        ConciergeManualPaymentRequest req = validRequest(new BigDecimal("500.00"), PaymentMethod.PAYNOW_OFFLINE);
        service.recordOfflinePayment(CR_SEQ, req, ADMIN_SEQ);

        verify(cr).markLicencePaid();
        verify(cr).linkPayment(PAYMENT_SEQ);
    }

    // ── CANCELLED 에서 거부 ──
    @Test
    @DisplayName("CANCELLED 에서 호출 → 409 CONCIERGE_CANCELLED")
    void shouldRejectForCancelledConcierge() {
        stubConciergeRequest(ConciergeRequestStatus.CANCELLED, new BigDecimal("500.00"));

        ConciergeManualPaymentRequest req = validRequest(new BigDecimal("500.00"), PaymentMethod.CASH);
        assertThatThrownBy(() -> service.recordOfflinePayment(CR_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("CONCIERGE_CANCELLED");
                });
        verify(paymentRepository, never()).save(any());
    }

    // ── 이미 paymentSeq 가 연결된 경우 거부 (중복 차단) ──
    @Test
    @DisplayName("이미 paymentSeq 가 연결된 ConciergeRequest → 409 ALREADY_PAID")
    void shouldRejectDuplicatePaymentForConcierge() {
        ConciergeRequest cr = stubConciergeRequest(ConciergeRequestStatus.QUOTE_SENT, new BigDecimal("500.00"));
        when(cr.getPaymentSeq()).thenReturn(999L);

        ConciergeManualPaymentRequest req = validRequest(new BigDecimal("500.00"), PaymentMethod.CASH);
        assertThatThrownBy(() -> service.recordOfflinePayment(CR_SEQ, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(409);
                    assertThat(((BusinessException) ex).getCode()).isEqualTo("ALREADY_PAID");
                });
    }

    // ── MANAGER 가 본인 배정 아닌 ConciergeRequest 호출 → 403 ──
    @Test
    @DisplayName("MANAGER 가 본인 배정이 아닌 ConciergeRequest 호출 → 403 FORBIDDEN/CONCIERGE_NOT_ASSIGNED")
    void shouldRejectManagerWithoutOwnership() {
        ConciergeRequest cr = stubConciergeRequest(ConciergeRequestStatus.QUOTE_SENT, new BigDecimal("500.00"));
        // 다른 MANAGER 가 배정됨.
        User otherManager = mock(User.class);
        when(otherManager.getUserSeq()).thenReturn(2000L);
        when(cr.getAssignedManager()).thenReturn(otherManager);

        // actor 는 다른 MANAGER (seq=99) — ADMIN 이 아님.
        Long managerSeq = 1234L;
        User managerActor = mock(User.class);
        when(managerActor.getUserSeq()).thenReturn(managerSeq);
        when(managerActor.getRole()).thenReturn(UserRole.CONCIERGE_MANAGER);
        when(userRepository.findById(managerSeq)).thenReturn(Optional.of(managerActor));

        ConciergeManualPaymentRequest req = validRequest(new BigDecimal("500.00"), PaymentMethod.CASH);
        assertThatThrownBy(() -> service.recordOfflinePayment(CR_SEQ, req, managerSeq))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(403));
        verify(paymentRepository, never()).save(any());
    }
}
