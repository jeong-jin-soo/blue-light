package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * InvoiceService 단위 테스트.
 * AC-9 (타인 접근 403 + 감사), AC-10 (PENDING_PAYMENT 400), AC-13 (DOWNLOADED 감사) 커버.
 */
@DisplayName("InvoiceService - AC-9/10/13")
class InvoiceServiceTest {

    private InvoiceRepository invoiceRepository;
    private ApplicationRepository applicationRepository;
    private AuditLogService auditLogService;
    private InvoiceGenerationService invoiceGenerationService;
    private InvoiceService service;

    private static final Long APP_SEQ = 1L;
    private static final Long OWNER_SEQ = 10L;
    private static final Long OTHER_USER_SEQ = 99L;
    private static final Long INVOICE_SEQ = 55L;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        applicationRepository = mock(ApplicationRepository.class);
        auditLogService = mock(AuditLogService.class);
        invoiceGenerationService = mock(InvoiceGenerationService.class);

        service = new InvoiceService(
                invoiceRepository, applicationRepository,
                auditLogService, invoiceGenerationService);
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private Application mockApplication(Long ownerSeq, ApplicationStatus status) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(APP_SEQ);
        when(app.getStatus()).thenReturn(status);

        User owner = mock(User.class);
        when(owner.getUserSeq()).thenReturn(ownerSeq);
        when(app.getUser()).thenReturn(owner);
        return app;
    }

    private Invoice mockInvoice() {
        Invoice invoice = mock(Invoice.class);
        when(invoice.getInvoiceSeq()).thenReturn(INVOICE_SEQ);
        when(invoice.getInvoiceNumber()).thenReturn("IN20260422001");
        when(invoice.getPaymentSeq()).thenReturn(101L);
        when(invoice.getReferenceType()).thenReturn("APPLICATION");
        when(invoice.getReferenceSeq()).thenReturn(APP_SEQ);
        when(invoice.getApplicationSeq()).thenReturn(APP_SEQ);
        when(invoice.getTotalAmount()).thenReturn(new BigDecimal("350.00"));
        when(invoice.getCurrencySnapshot()).thenReturn("SGD");
        when(invoice.getPdfFileSeq()).thenReturn(999L);
        when(invoice.getBillingRecipientNameSnapshot()).thenReturn("Tan Wei Ming");
        when(invoice.getBillingRecipientCompanySnapshot()).thenReturn(null);
        return invoice;
    }

    // ── AC-9: 타인 접근 403 + 감사 ──────────────────────────────────────────

    @Test
    @DisplayName("shouldThrowINVOICE_FORBIDDENAndLogAuditWhenNonOwnerAccesses")
    void shouldThrowINVOICE_FORBIDDENAndLogAuditWhenNonOwnerAccesses() {
        // AC-9: 다른 userSeq로 getByApplicationForApplicant 호출 → INVOICE_FORBIDDEN 403 + 감사
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.PAID);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));

        // When / Then
        assertThatThrownBy(() -> service.getByApplicationForApplicant(APP_SEQ, OTHER_USER_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVOICE_FORBIDDEN"))
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value())
                        .isEqualTo(403));

        // AC-9: 감사 logAsync가 statusCode=403으로 호출됨
        ArgumentCaptor<Integer> statusCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(auditLogService).logAsync(
                eq(OTHER_USER_SEQ),
                any(AuditAction.class),
                any(),
                anyString(), anyString(), anyString(),
                any(), any(),
                any(), any(),
                anyString(), anyString(),
                statusCaptor.capture());
        assertThat(statusCaptor.getValue()).isEqualTo(403);
    }

    @Test
    @DisplayName("shouldNotCallInvoiceRepositoryWhenForbiddenAttemptDetected")
    void shouldNotCallInvoiceRepositoryWhenForbiddenAttemptDetected() {
        // AC-9: 소유권 실패 시 Invoice 조회 자체를 시도하지 않아야 함
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.PAID);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));

        // When
        assertThatThrownBy(() -> service.getByApplicationForApplicant(APP_SEQ, OTHER_USER_SEQ))
                .isInstanceOf(BusinessException.class);

        // Then
        verify(invoiceRepository, never()).findByApplicationSeqAndReferenceType(any(), any());
    }

    // ── AC-10: PENDING_PAYMENT 이전 상태 400 ────────────────────────────────

    @Test
    @DisplayName("shouldThrowPAYMENT_NOT_CONFIRMEDWhenStatusIsPENDING_PAYMENT")
    void shouldThrowPAYMENT_NOT_CONFIRMEDWhenStatusIsPENDING_PAYMENT() {
        // AC-10: Application.status = PENDING_PAYMENT → PAYMENT_NOT_CONFIRMED 400
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.PENDING_PAYMENT);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));

        // When / Then
        assertThatThrownBy(() -> service.getByApplicationForApplicant(APP_SEQ, OWNER_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("PAYMENT_NOT_CONFIRMED"))
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value())
                        .isEqualTo(400));
    }

    @Test
    @DisplayName("shouldThrowPAYMENT_NOT_CONFIRMEDWhenStatusIsPENDING_REVIEW")
    void shouldThrowPAYMENT_NOT_CONFIRMEDWhenStatusIsPENDING_REVIEW() {
        // AC-10: Application.status = PENDING_REVIEW → PAYMENT_NOT_CONFIRMED 400
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.PENDING_REVIEW);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));

        // When / Then
        assertThatThrownBy(() -> service.getByApplicationForApplicant(APP_SEQ, OWNER_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("PAYMENT_NOT_CONFIRMED"));
    }

    @Test
    @DisplayName("shouldAllowAccessWhenStatusIsIN_PROGRESS")
    void shouldAllowAccessWhenStatusIsIN_PROGRESS() {
        // AC-10: IN_PROGRESS 상태는 결제 확정 이후이므로 허용
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.IN_PROGRESS);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));
        Invoice invoice = mockInvoice();
        when(invoiceRepository.findByApplicationSeqAndReferenceType(APP_SEQ, "APPLICATION"))
                .thenReturn(Optional.of(invoice));

        // When — 예외 없이 응답
        InvoiceResponse response = service.getByApplicationForApplicant(APP_SEQ, OWNER_SEQ);

        // Then
        assertThat(response).isNotNull();
        assertThat(response.getInvoiceNumber()).isEqualTo("IN20260422001");
    }

    // ── AC-13: 정상 조회 시 INVOICE_DOWNLOADED 감사 기록 ────────────────────

    @Test
    @DisplayName("shouldLogINVOICE_DOWNLOADEDAuditEventWhenInvoiceSuccessfullyRetrieved")
    void shouldLogINVOICE_DOWNLOADEDAuditEventWhenInvoiceSuccessfullyRetrieved() {
        // AC-13: 정상 조회 시 INVOICE_DOWNLOADED 감사 이벤트 기록
        // Given
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.PAID);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));
        Invoice invoice = mockInvoice();
        when(invoiceRepository.findByApplicationSeqAndReferenceType(APP_SEQ, "APPLICATION"))
                .thenReturn(Optional.of(invoice));

        // When
        service.getByApplicationForApplicant(APP_SEQ, OWNER_SEQ);

        // Then
        ArgumentCaptor<AuditAction> actionCaptor = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService).logAsync(
                eq(OWNER_SEQ),
                actionCaptor.capture(),
                any(),
                anyString(), anyString(), anyString(),
                any(), any(),
                any(), any(),
                anyString(), anyString(),
                eq(200));
        assertThat(actionCaptor.getValue()).isEqualTo(AuditAction.INVOICE_DOWNLOADED);
    }

    @Test
    @DisplayName("shouldLogINVOICE_DOWNLOADEDAuditEventWhenAdminRetrievesInvoice")
    void shouldLogINVOICE_DOWNLOADEDAuditEventWhenAdminRetrievesInvoice() {
        // AC-13: Admin 조회 시에도 INVOICE_DOWNLOADED 감사 이벤트 기록
        // Given
        Long adminSeq = 88L;
        Application app = mockApplication(OWNER_SEQ, ApplicationStatus.COMPLETED);
        when(applicationRepository.findById(APP_SEQ)).thenReturn(Optional.of(app));
        Invoice invoice = mockInvoice();
        when(invoiceRepository.findByApplicationSeqAndReferenceType(APP_SEQ, "APPLICATION"))
                .thenReturn(Optional.of(invoice));

        // When
        service.getByApplicationForAdmin(APP_SEQ, adminSeq);

        // Then
        ArgumentCaptor<AuditAction> actionCaptor = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService).logAsync(
                eq(adminSeq),
                actionCaptor.capture(),
                any(),
                anyString(), anyString(), anyString(),
                any(), any(),
                any(), any(),
                anyString(), anyString(),
                eq(200));
        assertThat(actionCaptor.getValue()).isEqualTo(AuditAction.INVOICE_DOWNLOADED);
    }

    // ── 재발행 사유 검증 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("shouldThrowREASON_REQUIREDWhenReasonIsBlankOnRegenerate")
    void shouldThrowREASON_REQUIREDWhenReasonIsBlankOnRegenerate() {
        // InvoiceService.regenerate() — 빈 reason → REASON_REQUIRED 400
        // Given
        Invoice invoice = mockInvoice();
        when(invoiceRepository.findByApplicationSeqAndReferenceType(APP_SEQ, "APPLICATION"))
                .thenReturn(Optional.of(invoice));

        // When / Then (빈 문자열)
        assertThatThrownBy(() -> service.regenerate(APP_SEQ, 88L, ""))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("REASON_REQUIRED"));

        // When / Then (null)
        assertThatThrownBy(() -> service.regenerate(APP_SEQ, 88L, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("REASON_REQUIRED"));
    }
}
