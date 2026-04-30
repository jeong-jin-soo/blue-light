package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.PaymentConfirmRequest;
import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.api.email.EmailService;
import com.bluelight.backend.api.invoice.InvoiceGenerationService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR4 — {@link AdminPaymentService#confirmPayment} 가 결제 확정 후 {@link PaymentConfirmedEvent}
 * 를 발행하는지 검증.
 *
 * <p>실제 LEW 알림 발송은 {@link LewPaymentNotificationListener} 의 책임이며 별도 테스트로 검증된다.
 * 이 테스트는 "이벤트가 발행되었는가" 만 본다 — 단위 테스트 경계 밖의 트랜잭션 페이즈 동작
 * (AFTER_COMMIT)은 통합 테스트 영역.</p>
 */
@DisplayName("AdminPaymentService — PaymentConfirmedEvent 발행")
class AdminPaymentServiceEventTest {

    private ApplicationRepository applicationRepository;
    private PaymentRepository paymentRepository;
    private EmailService emailService;
    private ApplicationEventPublisher eventPublisher;
    private InvoiceGenerationService invoiceGenerationService;
    private AuditLogService auditLogService;
    private AdminPaymentService service;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        emailService = mock(EmailService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        invoiceGenerationService = mock(InvoiceGenerationService.class);
        auditLogService = mock(AuditLogService.class);

        service = new AdminPaymentService(
                applicationRepository, paymentRepository, emailService,
                eventPublisher, invoiceGenerationService, auditLogService);
    }

    @Test
    @DisplayName("PENDING_PAYMENT → PAID 전이 후 PaymentConfirmedEvent 가 publish 된다")
    void confirmPayment_이벤트_발행() {
        Application app = mock(Application.class);
        User applicant = mock(User.class);
        when(applicant.getEmail()).thenReturn("applicant@example.com");
        when(applicant.getFirstName()).thenReturn("A");
        when(applicant.getLastName()).thenReturn("B");
        when(app.getStatus()).thenReturn(ApplicationStatus.PENDING_PAYMENT)
                // markAsPaid 호출 후 다시 호출되면 PAID — 이벤트 발행 시점에 두 번째 호출이 발생할 수 있음
                .thenReturn(ApplicationStatus.PAID);
        when(app.getQuoteAmount()).thenReturn(new BigDecimal("1500.00"));
        when(app.getUser()).thenReturn(applicant);
        when(app.getAddress()).thenReturn("123 Orchard");
        when(applicationRepository.findById(1L)).thenReturn(Optional.of(app));

        // PaymentRepository.save 가 paymentSeq 가 채워진 객체를 반환하도록 설정
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(p, "paymentSeq", 99L);
            return p;
        });

        PaymentConfirmRequest request = new PaymentConfirmRequest();
        org.springframework.test.util.ReflectionTestUtils.setField(request, "transactionId", "TXN-1");
        org.springframework.test.util.ReflectionTestUtils.setField(request, "paymentMethod", "PayNow");

        service.confirmPayment(1L, request);

        // 이벤트 publish 확인 — ApplicationStatusChangedEvent + PaymentConfirmedEvent 둘 다 발행됨
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(eventCaptor.capture());

        PaymentConfirmedEvent paymentEvent = eventCaptor.getAllValues().stream()
                .filter(PaymentConfirmedEvent.class::isInstance)
                .map(PaymentConfirmedEvent.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("PaymentConfirmedEvent was not published"));

        assertThat(paymentEvent.getApplicationSeq()).isEqualTo(1L);
        assertThat(paymentEvent.getPaymentSeq()).isEqualTo(99L);
        assertThat(paymentEvent.getAmount()).isEqualByComparingTo(new BigDecimal("1500.00"));
        assertThat(paymentEvent.getConfirmedAt()).isNotNull();
    }
}
