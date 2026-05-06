package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.ApplicantType;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * InvoiceGenerationService 단위 테스트.
 * AC-4 (스냅샷 불변), AC-5 (INDIVIDUAL 빌링), AC-6 (CORPORATE 빌링),
 * AC-7 (Layer B 설치 주소), AC-8 (결제 당 1건) 커버.
 */
@DisplayName("InvoiceGenerationService - AC-4/5/6/7/8")
class InvoiceGenerationServiceTest {

    private InvoiceRepository invoiceRepository;
    private InvoiceNumberGenerator invoiceNumberGenerator;
    private InvoicePdfRenderer invoicePdfRenderer;
    private SystemSettingRepository systemSettingRepository;
    private AuditLogService auditLogService;
    private ApplicationRepository applicationRepository;
    private ConciergeRequestRepository conciergeRequestRepository;
    private InvoiceGenerationService service;

    private static final Long PAYMENT_SEQ = 101L;
    private static final Long APP_SEQ = 200L;
    private static final Long PDF_FILE_SEQ = 999L;
    private static final Long NEW_PDF_FILE_SEQ = 1234L;
    private static final Long INVOICE_SEQ = 55L;

    @BeforeEach
    void setUp() {
        invoiceRepository = mock(InvoiceRepository.class);
        invoiceNumberGenerator = mock(InvoiceNumberGenerator.class);
        invoicePdfRenderer = mock(InvoicePdfRenderer.class);
        systemSettingRepository = mock(SystemSettingRepository.class);
        auditLogService = mock(AuditLogService.class);
        applicationRepository = mock(ApplicationRepository.class);
        conciergeRequestRepository = mock(ConciergeRequestRepository.class);

        service = new InvoiceGenerationService(
                invoiceRepository, invoiceNumberGenerator, invoicePdfRenderer,
                systemSettingRepository, auditLogService,
                applicationRepository, conciergeRequestRepository);

        // 기본 stub: 중복 없음, 번호 생성, PDF 렌더 성공
        // ★ kva-postpayment-adjustment-spec.md §10 D3 — 활성(ACTIVE) 영수증만 중복 차단.
        when(invoiceRepository.existsByPaymentSeqAndStatus(PAYMENT_SEQ, "ACTIVE")).thenReturn(false);
        when(invoiceNumberGenerator.next(any())).thenReturn("IN20260422001");
        when(invoicePdfRenderer.render(any(Invoice.class))).thenReturn(PDF_FILE_SEQ);
        when(invoiceRepository.save(any(Invoice.class))).thenAnswer(inv -> {
            Invoice i = inv.getArgument(0);
            // id 세팅이 없으므로 그대로 반환
            return i;
        });

        // 기본 system_settings stub
        stubSetting("invoice_company_name", "HanVision holdings Private Ltd.");
        stubSetting("invoice_company_alias", "Licensekaki");
        stubSetting("invoice_company_uen", "202627777H");
        stubSetting("invoice_company_address_line1", "12 WOODLANDS SQUARE");
        stubSetting("invoice_company_address_line2", "#13-79 WOODS SQUARE TOWER ONE,");
        stubSetting("invoice_company_address_line3", "SINGAPORE 737715");
        stubSetting("invoice_company_email", "Admin@licensekaki.com");
        stubSetting("invoice_company_website", "Licensekaki.com");
        stubSetting("invoice_paynow_uen", "202627777H");
        stubSetting("invoice_footer_note", "No electronic signature is necessary.");
        stubSetting("invoice_currency", "SGD");
        // QR 없음
        when(systemSettingRepository.findById("invoice_paynow_qr_file_seq"))
                .thenReturn(Optional.empty());
    }

    private void stubSetting(String key, String value) {
        when(systemSettingRepository.findById(key))
                .thenReturn(Optional.of(new SystemSetting(key, value, "")));
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────

    private Payment mockPayment() {
        Payment payment = mock(Payment.class);
        when(payment.getPaymentSeq()).thenReturn(PAYMENT_SEQ);
        when(payment.getAmount()).thenReturn(new BigDecimal("350.00"));
        when(payment.getReferenceType()).thenReturn(PaymentReferenceType.APPLICATION);
        when(payment.getReferenceSeq()).thenReturn(APP_SEQ);
        return payment;
    }

    private User mockUser(String fullName, String companyName) {
        User u = mock(User.class);
        when(u.getUserSeq()).thenReturn(77L);
        when(u.getFullName()).thenReturn(fullName);
        when(u.getCompanyName()).thenReturn(companyName);
        return u;
    }

    private Application mockApplication(ApplicantType type, User user,
                                        String loaName, String loaCompany,
                                        String instBlock, String instUnit,
                                        String instStreet, String instBuilding,
                                        String instPostal) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(APP_SEQ);
        when(app.getUser()).thenReturn(user);
        when(app.getApplicantType()).thenReturn(type);
        when(app.getApplicationType()).thenReturn(ApplicationType.NEW);
        when(app.getLoaApplicantNameSnapshot()).thenReturn(loaName);
        when(app.getLoaCompanyNameSnapshot()).thenReturn(loaCompany);
        // Installation address (Layer B)
        when(app.getInstallationAddressBlock()).thenReturn(instBlock);
        when(app.getInstallationAddressUnit()).thenReturn(instUnit);
        when(app.getInstallationAddressStreet()).thenReturn(instStreet);
        when(app.getInstallationAddressBuilding()).thenReturn(instBuilding);
        when(app.getInstallationAddressPostalCode()).thenReturn(instPostal);
        // Correspondence address — 없음 (설치 주소 fallback 테스트 위해)
        when(app.getCorrespondenceAddressBlock()).thenReturn(null);
        when(app.getCorrespondenceAddressUnit()).thenReturn(null);
        when(app.getCorrespondenceAddressStreet()).thenReturn(null);
        when(app.getCorrespondenceAddressBuilding()).thenReturn(null);
        when(app.getCorrespondenceAddressPostalCode()).thenReturn(null);
        // legacy 주소 fallback
        when(app.getAddress()).thenReturn("1 Test Street");
        when(app.getPostalCode()).thenReturn("560001");
        return app;
    }

    // ── AC-8: 결제 당 Invoice 1건 ────────────────────────────────────────────

    @Test
    @DisplayName("shouldReturnExistingInvoiceWhenDuplicatePaymentDetected")
    void shouldReturnExistingInvoiceWhenDuplicatePaymentDetected() {
        // AC-8: existsByPaymentSeqAndStatus(.., ACTIVE) = true → INVOICE_ALREADY_EXISTS 409
        // Given
        Payment payment = mockPayment();
        when(invoiceRepository.existsByPaymentSeqAndStatus(PAYMENT_SEQ, "ACTIVE")).thenReturn(true);

        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(APP_SEQ);

        // When / Then
        assertThatThrownBy(() -> service.generateFromPayment(payment, app))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVOICE_ALREADY_EXISTS"))
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value())
                        .isEqualTo(409));

        // 저장 호출 없어야 함
        verify(invoiceRepository, never()).save(any());
    }

    // ── AC-5: INDIVIDUAL 빌링 ────────────────────────────────────────────────

    @Test
    @DisplayName("shouldSetBillingCompanyToNullWhenApplicantTypeIsINDIVIDUAL")
    void shouldSetBillingCompanyToNullWhenApplicantTypeIsINDIVIDUAL() {
        // AC-5: INDIVIDUAL → billingRecipientCompanySnapshot null, billingRecipientNameSnapshot = loaName
        // Given
        User user = mockUser("Tan Wei Ming", null);
        Application app = mockApplication(
                ApplicantType.INDIVIDUAL, user,
                "Tan Wei Ming", null,
                "12", "#01-01", "Test Street", "Test Building", "560001");
        Payment payment = mockPayment();

        // When
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.generateFromPayment(payment, app);
        verify(invoiceRepository).save(captor.capture());
        Invoice saved = captor.getValue();

        // Then
        assertThat(saved.getBillingRecipientCompanySnapshot()).isNull();
        assertThat(saved.getBillingRecipientNameSnapshot()).isEqualTo("Tan Wei Ming");
    }

    // ── AC-6: CORPORATE 빌링 ────────────────────────────────────────────────

    @Test
    @DisplayName("shouldSetBillingCompanyFromLoaCompanyNameWhenApplicantTypeIsCORPORATE")
    void shouldSetBillingCompanyFromLoaCompanyNameWhenApplicantTypeIsCORPORATE() {
        // AC-6: CORPORATE → billingRecipientCompanySnapshot = loaCompanyNameSnapshot
        // Given
        User user = mockUser("Tan Wei Ming", "TestCorp Pte Ltd");
        Application app = mockApplication(
                ApplicantType.CORPORATE, user,
                "Tan Wei Ming", "TestCorp Pte Ltd",
                "12", "#01-01", "Test Street", "Test Building", "560001");
        Payment payment = mockPayment();

        // When
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.generateFromPayment(payment, app);
        verify(invoiceRepository).save(captor.capture());
        Invoice saved = captor.getValue();

        // Then
        assertThat(saved.getBillingRecipientCompanySnapshot()).isEqualTo("TestCorp Pte Ltd");
        assertThat(saved.getBillingRecipientNameSnapshot()).isEqualTo("Tan Wei Ming");
    }

    // ── AC-7: Layer B 설치 주소 ──────────────────────────────────────────────

    @Test
    @DisplayName("shouldUseInstallationAddressBlockWhenLayerBFieldsPresent")
    void shouldUseInstallationAddressBlockWhenLayerBFieldsPresent() {
        // AC-7: Application에 installationAddress* 5-part가 있으면 해당 값을 스냅샷에 사용
        // Given
        User user = mockUser("Alice Tan", null);
        Application app = mockApplication(
                ApplicantType.INDIVIDUAL, user,
                "Alice Tan", null,
                "10", "#05-25", "Boon Lay Place", "Boon Lay Shopping Centre", "609965");
        Payment payment = mockPayment();

        // When
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.generateFromPayment(payment, app);
        verify(invoiceRepository).save(captor.capture());
        Invoice saved = captor.getValue();

        // Then: Block+Unit → Line1, Street → Line2, Building → Line3, SINGAPORE+Postal → Line4
        assertThat(saved.getInstallationAddressLine1Snapshot()).isEqualTo("10 #05-25");
        assertThat(saved.getInstallationAddressLine2Snapshot()).isEqualTo("Boon Lay Place");
        assertThat(saved.getInstallationAddressLine3Snapshot()).isEqualTo("Boon Lay Shopping Centre");
        assertThat(saved.getInstallationAddressLine4Snapshot()).isEqualTo("SINGAPORE 609965");
    }

    @Test
    @DisplayName("shouldFallbackToApplicationAddressWhenNoLayerBInstallationFields")
    void shouldFallbackToApplicationAddressWhenNoLayerBInstallationFields() {
        // AC-7: 5-part 없으면 application.address + postalCode fallback
        // Given
        User user = mockUser("Bob Lim", null);
        Application app = mockApplication(
                ApplicantType.INDIVIDUAL, user,
                "Bob Lim", null,
                null, null, null, null, null); // Layer B 없음
        when(app.getAddress()).thenReturn("1 Legacy Street");
        when(app.getPostalCode()).thenReturn("123456");
        Payment payment = mockPayment();

        // When
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        service.generateFromPayment(payment, app);
        verify(invoiceRepository).save(captor.capture());
        Invoice saved = captor.getValue();

        // Then: 단일 문자열이 line1에, postal은 line2에
        assertThat(saved.getInstallationAddressLine1Snapshot()).isEqualTo("1 Legacy Street");
        assertThat(saved.getInstallationAddressLine2Snapshot()).isEqualTo("SINGAPORE 123456");
    }

    // ── AC-4: 스냅샷 불변 (regenerate시 pdfFileSeq만 교체) ─────────────────

    @Test
    @DisplayName("shouldOnlyReplacePdfFileSeqWhenRegenerateCalled")
    void shouldOnlyReplacePdfFileSeqWhenRegenerateCalled() {
        // AC-4: regenerate() 호출 시 스냅샷 필드는 건드리지 않고 pdfFileSeq만 교체
        // Given: 기존 Invoice를 실 Invoice 객체로 생성 (replacePdfFile 메서드 동작 확인용)
        Invoice existingInvoice = Invoice.builder()
                .invoiceNumber("IN20260422001")
                .paymentSeq(PAYMENT_SEQ)
                .referenceType("APPLICATION")
                .referenceSeq(APP_SEQ)
                .applicationSeq(APP_SEQ)
                .recipientUserSeq(77L)
                .issuedByUserSeq(null)
                .totalAmount(new BigDecimal("350.00"))
                .qtySnapshot(1)
                .rateAmountSnapshot(new BigDecimal("350.00"))
                .currencySnapshot("SGD")
                .companyNameSnapshot("HanVision holdings Private Ltd.")
                .companyUenSnapshot("202627777H")
                .billingRecipientNameSnapshot("Tan Wei Ming")
                .descriptionSnapshot("New EMA license application")
                .pdfFileSeq(PDF_FILE_SEQ)
                .build();

        when(invoiceRepository.findById(INVOICE_SEQ)).thenReturn(Optional.of(existingInvoice));
        when(invoicePdfRenderer.render(existingInvoice)).thenReturn(NEW_PDF_FILE_SEQ);

        // Capture the snapshot values before regenerate
        String snapshotCompanyNameBefore = existingInvoice.getCompanyNameSnapshot();
        String snapshotBillingNameBefore = existingInvoice.getBillingRecipientNameSnapshot();
        String snapshotDescBefore = existingInvoice.getDescriptionSnapshot();

        // When
        Invoice result = service.regenerate(INVOICE_SEQ, 99L, "Test regeneration reason");

        // Then: pdfFileSeq만 교체됨
        assertThat(result.getPdfFileSeq()).isEqualTo(NEW_PDF_FILE_SEQ);

        // 스냅샷 필드는 불변
        assertThat(result.getCompanyNameSnapshot()).isEqualTo(snapshotCompanyNameBefore);
        assertThat(result.getBillingRecipientNameSnapshot()).isEqualTo(snapshotBillingNameBefore);
        assertThat(result.getDescriptionSnapshot()).isEqualTo(snapshotDescBefore);
        assertThat(result.getInvoiceNumber()).isEqualTo("IN20260422001");
    }

    // ── Concierge 강화 + 별도 수금 PR-2 — CONCIERGE_REQUEST 영수증 발행 ──

    @Test
    @DisplayName("PR-2: CONCIERGE_REQUEST 결제 → Concierge 스냅샷 빌더로 Invoice 자동 발행")
    void shouldGenerateInvoiceForConciergePayment() {
        // Given: CONCIERGE_REQUEST referenceType 결제 + ConciergeRequest entity
        Payment payment = mock(Payment.class);
        Long crSeq = 300L;
        when(payment.getPaymentSeq()).thenReturn(PAYMENT_SEQ);
        when(payment.getReferenceType()).thenReturn(PaymentReferenceType.CONCIERGE_REQUEST);
        when(payment.getReferenceSeq()).thenReturn(crSeq);
        when(payment.getAmount()).thenReturn(new BigDecimal("500.00"));

        com.bluelight.backend.domain.concierge.ConciergeRequest cr =
                mock(com.bluelight.backend.domain.concierge.ConciergeRequest.class);
        when(cr.getConciergeRequestSeq()).thenReturn(crSeq);
        when(cr.getPublicCode()).thenReturn("C-2026-0300");
        when(cr.getSubmitterName()).thenReturn("Tan Wei Ming");
        User applicant = mock(User.class);
        when(applicant.getUserSeq()).thenReturn(77L);
        when(applicant.getFullName()).thenReturn("Tan Wei Ming");
        when(cr.getApplicantUser()).thenReturn(applicant);
        when(conciergeRequestRepository.findById(crSeq)).thenReturn(Optional.of(cr));

        // When: 단일 인자 dispatcher 호출
        ArgumentCaptor<Invoice> captor = ArgumentCaptor.forClass(Invoice.class);
        Invoice saved = service.generateFromPayment(payment);

        // Then: Concierge 스냅샷이 저장됨
        verify(invoiceRepository).save(captor.capture());
        Invoice persisted = captor.getValue();
        assertThat(persisted.getReferenceType()).isEqualTo("CONCIERGE_REQUEST");
        assertThat(persisted.getReferenceSeq()).isEqualTo(crSeq);
        assertThat(persisted.getApplicationSeq()).isNull(); // Concierge 결제는 application FK 없음
        assertThat(persisted.getDescriptionSnapshot()).contains("Concierge service fee").contains("C-2026-0300");
        assertThat(persisted.getBillingRecipientNameSnapshot()).isEqualTo("Tan Wei Ming");
    }

    @Test
    @DisplayName("PR-2: CONCIERGE_REQUEST + 동일 payment 의 ACTIVE invoice 이미 존재 → 409")
    void shouldRejectDuplicateConciergeInvoiceWhenActiveExists() {
        Payment payment = mock(Payment.class);
        when(payment.getPaymentSeq()).thenReturn(PAYMENT_SEQ);
        when(payment.getReferenceType()).thenReturn(PaymentReferenceType.CONCIERGE_REQUEST);
        when(payment.getReferenceSeq()).thenReturn(300L);
        when(payment.getAmount()).thenReturn(new BigDecimal("500.00"));

        com.bluelight.backend.domain.concierge.ConciergeRequest cr =
                mock(com.bluelight.backend.domain.concierge.ConciergeRequest.class);
        when(cr.getConciergeRequestSeq()).thenReturn(300L);
        when(cr.getPublicCode()).thenReturn("C-2026-0300");
        User applicant = mock(User.class);
        when(applicant.getUserSeq()).thenReturn(77L);
        when(applicant.getFullName()).thenReturn("Tan");
        when(cr.getApplicantUser()).thenReturn(applicant);
        when(conciergeRequestRepository.findById(300L)).thenReturn(Optional.of(cr));

        when(invoiceRepository.existsByPaymentSeqAndStatus(PAYMENT_SEQ, "ACTIVE")).thenReturn(true);

        assertThatThrownBy(() -> service.generateFromPayment(payment))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus().value()).isEqualTo(409));
        verify(invoiceRepository, never()).save(any());
    }
}
