package com.bluelight.backend.service.kva;

import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideRequest;
import com.bluelight.backend.api.admin.dto.KvaPostPaymentOverrideResponse;
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
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 결제 후 kVA 사후 변경 서비스 단위 테스트 (PR-1).
 *
 * <p>스펙: {@code doc/Project Analysis/kva-postpayment-adjustment-spec.md} §4.</p>
 *
 * <h2>커버되는 수용 기준</h2>
 * <ul>
 *   <li>AC-A1: ADMIN 직접 변경 정상 흐름 (PAID 상태)</li>
 *   <li>AC-A2: PRE-PAYMENT 상태 거부 (PENDING_PAYMENT 등)</li>
 *   <li>AC-A3: COMPLETED 허용, EXPIRED 거부</li>
 *   <li>AC-A4: master_prices 미존재 → 400 INVALID_KVA_TIER</li>
 *   <li>AC-A5: 동일 newKva → 400 KVA_NO_CHANGE</li>
 *   <li>D3 Invoice invalidate + 재발행 검증</li>
 *   <li>masterPriceSeqUsed 기록 검증</li>
 * </ul>
 */
class KvaPostPaymentServiceTest {

    private ApplicationRepository applicationRepository;
    private MasterPriceRepository masterPriceRepository;
    private UserRepository userRepository;
    private KvaAdjustmentRepository kvaAdjustmentRepository;
    private InvoiceRepository invoiceRepository;
    private PaymentRepository paymentRepository;
    private InvoiceGenerationService invoiceGenerationService;
    private AuditLogService auditLogService;
    private ApplicationEventPublisher eventPublisher;
    private KvaPostPaymentService service;

    private static final Long APP_ID = 1L;
    private static final Long ADMIN_SEQ = 99L;

    @BeforeEach
    void setUp() {
        applicationRepository = mock(ApplicationRepository.class);
        masterPriceRepository = mock(MasterPriceRepository.class);
        userRepository = mock(UserRepository.class);
        kvaAdjustmentRepository = mock(KvaAdjustmentRepository.class);
        invoiceRepository = mock(InvoiceRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        invoiceGenerationService = mock(InvoiceGenerationService.class);
        auditLogService = mock(AuditLogService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);

        service = new KvaPostPaymentService(
                applicationRepository, masterPriceRepository, userRepository,
                kvaAdjustmentRepository, invoiceRepository,
                paymentRepository, invoiceGenerationService, auditLogService,
                eventPublisher);

        // 저장 시 동일 객체 반환 + adjustmentSeq 를 ReflectionTestUtils 로 채워 PR-2 이벤트의
        // adjustmentSeq 가 null 이 아니도록 보장 (Application.@GeneratedValue 동작 흉내).
        when(kvaAdjustmentRepository.save(any(KvaAdjustmentRecord.class)))
                .thenAnswer(inv -> {
                    KvaAdjustmentRecord r = inv.getArgument(0);
                    org.springframework.test.util.ReflectionTestUtils.setField(r, "adjustmentSeq", 42L);
                    return r;
                });

        // PR-3: 기본적으로 PENDING LEW 요청 없음 — 개별 테스트가 필요시 override.
        when(kvaAdjustmentRepository.findByApplicationSeqAndStatusForUpdate(
                any(), eq(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)))
                .thenReturn(Collections.emptyList());
    }

    // ── 헬퍼 ────────────────────────────────────────────────

    private Application mockApp(ApplicationStatus status, Integer currentKva,
                                BigDecimal currentQuote) {
        Application app = mock(Application.class);
        when(app.getApplicationSeq()).thenReturn(APP_ID);
        when(app.getStatus()).thenReturn(status);
        when(app.getSelectedKva()).thenReturn(currentKva);
        when(app.getQuoteAmount()).thenReturn(currentQuote);
        when(app.getApplicationType()).thenReturn(ApplicationType.NEW);
        // isPostPaymentStatus 는 실제 메서드 호출되도록 한다 (Mockito 의 default 는 false 반환)
        when(app.isPostPaymentStatus()).thenAnswer(inv ->
                status == ApplicationStatus.PAID
                        || status == ApplicationStatus.IN_PROGRESS
                        || status == ApplicationStatus.COMPLETED);
        return app;
    }

    private MasterPrice mockPrice(Long seq, BigDecimal price) {
        MasterPrice mp = mock(MasterPrice.class);
        when(mp.getMasterPriceSeq()).thenReturn(seq);
        when(mp.getPrice()).thenReturn(price);
        when(mp.getRenewalPrice()).thenReturn(BigDecimal.ZERO);
        when(mp.getSldPrice()).thenReturn(BigDecimal.ZERO);
        return mp;
    }

    private KvaPostPaymentOverrideRequest req(Integer newKva, String reason) {
        KvaPostPaymentOverrideRequest r = new KvaPostPaymentOverrideRequest();
        r.setNewKva(newKva);
        r.setReason(reason);
        r.setAdminMemo("memo");
        r.setPaymentAdjustment(AdminPaymentAdjustment.PAID_DIFFERENCE);
        return r;
    }

    private void stubActiveInvoice() {
        Invoice inv = mock(Invoice.class);
        when(inv.getInvoiceSeq()).thenReturn(7L);
        when(inv.getInvalidatedReason()).thenReturn("KVA_ADJUSTMENT_*");
        when(invoiceRepository.findFirstByApplicationSeqAndReferenceTypeAndStatus(
                APP_ID, "APPLICATION", "ACTIVE"))
                .thenReturn(Optional.of(inv));

        Payment payment = mock(Payment.class);
        when(payment.getPaymentSeq()).thenReturn(33L);
        when(paymentRepository.findByApplicationApplicationSeqAndStatus(
                APP_ID, PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(payment));

        Invoice newInvoice = mock(Invoice.class);
        when(newInvoice.getInvoiceSeq()).thenReturn(8L);
        when(invoiceGenerationService.generateFromPayment(eq(payment), any()))
                .thenReturn(newInvoice);
    }

    // ── ACs ─────────────────────────────────────────────────

    @Test
    void AC_A1_PAID_상태에서_정상_변경_KvaAdjustmentRecord_생성() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650.00"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        stubActiveInvoice();

        KvaPostPaymentOverrideResponse resp =
                service.overrideKva(APP_ID, req(200, "Site survey: actual 200 kVA"), ADMIN_SEQ);

        // Application 도메인 메서드가 정확히 호출됐는가
        verify(app).overrideKvaPostPayment(eq(200), eq(new BigDecimal("650.00")), eq(admin));

        // KvaAdjustmentRecord 저장 검증
        ArgumentCaptor<KvaAdjustmentRecord> recCap =
                ArgumentCaptor.forClass(KvaAdjustmentRecord.class);
        verify(kvaAdjustmentRepository).save(recCap.capture());
        KvaAdjustmentRecord saved = recCap.getValue();
        assertThat(saved.getStatus()).isEqualTo(KvaAdjustmentStatus.APPLIED);
        assertThat(saved.getChangedByRole()).isEqualTo(ChangedByRole.ADMIN);
        assertThat(saved.getChangedByUserSeq()).isEqualTo(ADMIN_SEQ);
        assertThat(saved.getPreviousKva()).isEqualTo(100);
        assertThat(saved.getNewKva()).isEqualTo(200);
        assertThat(saved.getPreviousQuoteAmount()).isEqualByComparingTo("450.00");
        assertThat(saved.getNewQuoteAmount()).isEqualByComparingTo("650.00");
        assertThat(saved.getAmountDifference()).isEqualByComparingTo("200.00");
        // D1: masterPriceSeqUsed 기록
        assertThat(saved.getMasterPriceSeqUsed()).isEqualTo(5L);
        assertThat(saved.getReason()).isEqualTo("Site survey: actual 200 kVA");
        assertThat(saved.getAdminPaymentAdjustment()).isEqualTo(AdminPaymentAdjustment.PAID_DIFFERENCE);

        // 응답 DTO
        assertThat(resp.getNewKva()).isEqualTo(200);
        assertThat(resp.getPreviousKva()).isEqualTo(100);

        // KVA_OVERRIDE_POSTPAYMENT 감사 기록
        ArgumentCaptor<AuditAction> actionCap = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService, times(2)).logAsync(
                any(), actionCap.capture(), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any());
        // 2회 호출: KVA_OVERRIDE_POSTPAYMENT, INVOICE_REGENERATED
        assertThat(actionCap.getAllValues()).contains(AuditAction.KVA_OVERRIDE_POSTPAYMENT);
        assertThat(actionCap.getAllValues()).contains(AuditAction.INVOICE_REGENERATED);
    }

    @Test
    void AC_A2_PRE_PAYMENT_상태_PENDING_PAYMENT_거부_409_KVA_NOT_POSTPAYMENT() {
        Application app = mockApp(ApplicationStatus.PENDING_PAYMENT, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.overrideKva(APP_ID, req(200, "x"), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_NOT_POSTPAYMENT"));

        verify(app, never()).overrideKvaPostPayment(any(), any(), any());
        verify(kvaAdjustmentRepository, never()).save(any());
    }

    @Test
    void AC_A3_EXPIRED_거부_409_KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED() {
        Application app = mockApp(ApplicationStatus.EXPIRED, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.overrideKva(APP_ID, req(200, "x"), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED"));

        verify(app, never()).overrideKvaPostPayment(any(), any(), any());
    }

    @Test
    void AC_A3_COMPLETED_허용() {
        Application app = mockApp(ApplicationStatus.COMPLETED, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        stubActiveInvoice();

        KvaPostPaymentOverrideResponse resp =
                service.overrideKva(APP_ID, req(200, "License correction in progress"), ADMIN_SEQ);

        assertThat(resp).isNotNull();
        verify(app).overrideKvaPostPayment(eq(200), any(BigDecimal.class), eq(admin));
    }

    @Test
    void AC_A4_master_prices_미존재_400_INVALID_KVA_TIER() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        when(masterPriceRepository.findByKva(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.overrideKva(APP_ID, req(999, "weird"), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVALID_KVA_TIER"));

        verify(kvaAdjustmentRepository, never()).save(any());
    }

    @Test
    void AC_A5_동일_newKva_400_KVA_NO_CHANGE() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.overrideKva(APP_ID, req(100, "noop"), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_NO_CHANGE"));

        verify(kvaAdjustmentRepository, never()).save(any());
        verify(masterPriceRepository, never()).findByKva(any());
    }

    @Test
    void AC_D3_활성_Invoice_invalidate_후_신규_발행_호출_검증() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));

        // 활성 Invoice + Payment + 신규 Invoice
        Invoice activeInv = mock(Invoice.class);
        when(activeInv.getInvoiceSeq()).thenReturn(7L);
        when(activeInv.getInvalidatedReason()).thenReturn("KVA_ADJUSTMENT_*");
        when(invoiceRepository.findFirstByApplicationSeqAndReferenceTypeAndStatus(
                APP_ID, "APPLICATION", "ACTIVE"))
                .thenReturn(Optional.of(activeInv));

        Payment payment = mock(Payment.class);
        when(payment.getPaymentSeq()).thenReturn(33L);
        when(paymentRepository.findByApplicationApplicationSeqAndStatus(
                APP_ID, PaymentStatus.SUCCESS))
                .thenReturn(Optional.of(payment));

        Invoice newInvoice = mock(Invoice.class);
        when(newInvoice.getInvoiceSeq()).thenReturn(8L);
        when(invoiceGenerationService.generateFromPayment(eq(payment), eq(app)))
                .thenReturn(newInvoice);

        service.overrideKva(APP_ID, req(200, "verify"), ADMIN_SEQ);

        // 기존 Invoice invalidate 호출
        verify(activeInv).invalidate(anyString());
        // 신규 Invoice 자동 발행 호출
        verify(invoiceGenerationService).generateFromPayment(eq(payment), eq(app));
    }

    // ── PR-2: AFTER_COMMIT 알림 이벤트 발행 검증 ─────────────────────

    @Test
    void PR2_정상_변경_시_KvaOverrideAppliedEvent_가_발행된다_payload_검증() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650.00"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        // assignedLew 가 있는 케이스: payload 의 assignedLewUserSeq 가 채워져야 한다.
        User assignedLew = mock(User.class);
        when(assignedLew.getUserSeq()).thenReturn(77L);
        when(app.getAssignedLew()).thenReturn(assignedLew);
        stubActiveInvoice();

        service.overrideKva(APP_ID, req(200, "Site survey: 200 kVA"), ADMIN_SEQ);

        // 이벤트 publish 검증 + payload 검증
        ArgumentCaptor<KvaOverrideAppliedEvent> evCap =
                ArgumentCaptor.forClass(KvaOverrideAppliedEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        KvaOverrideAppliedEvent ev = evCap.getValue();
        assertThat(ev.getApplicationSeq()).isEqualTo(APP_ID);
        assertThat(ev.getAdjustmentSeq()).isEqualTo(42L);
        assertThat(ev.getAssignedLewUserSeq()).isEqualTo(77L);
        assertThat(ev.getPreviousKva()).isEqualTo(100);
        assertThat(ev.getNewKva()).isEqualTo(200);
        assertThat(ev.getPreviousQuoteAmount()).isEqualByComparingTo("450.00");
        assertThat(ev.getNewQuoteAmount()).isEqualByComparingTo("650.00");
        assertThat(ev.getAmountDifference()).isEqualByComparingTo("200.00");
        assertThat(ev.getReason()).isEqualTo("Site survey: 200 kVA");
        assertThat(ev.getTriggeredByUserSeq()).isEqualTo(ADMIN_SEQ);
        assertThat(ev.getTriggeredByRole()).isEqualTo("ADMIN");
    }

    @Test
    void PR2_assignedLew_없을_때_event_의_assignedLewUserSeq_는_null() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650.00"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        when(app.getAssignedLew()).thenReturn(null); // 명시
        stubActiveInvoice();

        service.overrideKva(APP_ID, req(200, "no LEW"), ADMIN_SEQ);

        ArgumentCaptor<KvaOverrideAppliedEvent> evCap =
                ArgumentCaptor.forClass(KvaOverrideAppliedEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        assertThat(evCap.getValue().getAssignedLewUserSeq()).isNull();
    }

    @Test
    void PR2_거부_케이스_KVA_NO_CHANGE_에선_이벤트_미발행() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.overrideKva(APP_ID, req(100, "noop"), ADMIN_SEQ))
                .isInstanceOf(BusinessException.class);

        verify(eventPublisher, never()).publishEvent(any(KvaOverrideAppliedEvent.class));
    }

    // ── PR-3: requestAdjustmentByLew ─────────────────────────────────

    private LewKvaAdjustmentRequest lewReq(Integer proposedKva, String reason) {
        LewKvaAdjustmentRequest r = new LewKvaAdjustmentRequest();
        r.setProposedKva(proposedKva);
        r.setReason(reason);
        return r;
    }

    @Test
    void AC_L1_LEW_요청_정상_PENDING_ADMIN_REVIEW_row_생성() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650.00"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        // resolveLewDisplayName lookup
        User lew = mock(User.class);
        when(lew.getFirstName()).thenReturn("Long");
        when(lew.getLastName()).thenReturn("Eric");
        when(lew.getEmail()).thenReturn("lew@licensekaki.sg");
        when(userRepository.findById(50L)).thenReturn(Optional.of(lew));

        LewKvaAdjustmentResponse resp =
                service.requestAdjustmentByLew(APP_ID, 50L, lewReq(200, "Site survey reason"));

        // 저장된 row 검증
        ArgumentCaptor<KvaAdjustmentRecord> cap =
                ArgumentCaptor.forClass(KvaAdjustmentRecord.class);
        verify(kvaAdjustmentRepository).save(cap.capture());
        KvaAdjustmentRecord saved = cap.getValue();
        assertThat(saved.getStatus()).isEqualTo(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW);
        assertThat(saved.getChangedByRole()).isEqualTo(ChangedByRole.LEW);
        assertThat(saved.getChangedByUserSeq()).isEqualTo(50L);
        assertThat(saved.getProposedKva()).isEqualTo(200);
        assertThat(saved.getNewKva()).isNull();
        assertThat(saved.getPreviousKva()).isEqualTo(100);
        assertThat(saved.getReason()).isEqualTo("Site survey reason");
        assertThat(saved.getLewRequestSeq()).isNull();
        // amountDifference / newQuoteAmount 는 LEW 요청 단계에선 null
        assertThat(saved.getAmountDifference()).isNull();
        assertThat(saved.getNewQuoteAmount()).isNull();

        // Application 상태 변경 없음 (LEW 요청은 단순 제안)
        verify(app, never()).overrideKvaPostPayment(any(), any(), any());

        // 이벤트 publish 검증
        ArgumentCaptor<LewKvaAdjustmentRequestedEvent> evCap =
                ArgumentCaptor.forClass(LewKvaAdjustmentRequestedEvent.class);
        verify(eventPublisher).publishEvent(evCap.capture());
        LewKvaAdjustmentRequestedEvent ev = evCap.getValue();
        assertThat(ev.getApplicationSeq()).isEqualTo(APP_ID);
        assertThat(ev.getAdjustmentSeq()).isEqualTo(42L);
        assertThat(ev.getLewUserSeq()).isEqualTo(50L);
        assertThat(ev.getProposedKva()).isEqualTo(200);
        assertThat(ev.getCurrentKva()).isEqualTo(100);
        assertThat(ev.getReason()).isEqualTo("Site survey reason");
        assertThat(ev.getLewName()).isEqualTo("Long Eric");

        // 응답 DTO
        assertThat(resp.getAdjustmentSeq()).isEqualTo(42L);
        assertThat(resp.getStatus()).isEqualTo(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW);
        assertThat(resp.getProposedKva()).isEqualTo(200);
        assertThat(resp.getCurrentKva()).isEqualTo(100);
    }

    @Test
    void AC_L3_LEW_요청_PRE_PAYMENT_거부_409_KVA_NOT_POSTPAYMENT() {
        Application app = mockApp(ApplicationStatus.PENDING_PAYMENT, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestAdjustmentByLew(APP_ID, 50L, lewReq(200, "x reason")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_NOT_POSTPAYMENT"));

        verify(kvaAdjustmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void AC_L3_LEW_요청_EXPIRED_거부() {
        Application app = mockApp(ApplicationStatus.EXPIRED, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestAdjustmentByLew(APP_ID, 50L, lewReq(200, "late")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_ADJUSTMENT_NOT_ALLOWED_EXPIRED"));

        verify(kvaAdjustmentRepository, never()).save(any());
    }

    @Test
    void AC_L1_LEW_요청_동일_proposedKva_거부_KVA_NO_CHANGE() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        assertThatThrownBy(() -> service.requestAdjustmentByLew(APP_ID, 50L, lewReq(100, "noop")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_NO_CHANGE"));

        verify(kvaAdjustmentRepository, never()).save(any());
        verify(masterPriceRepository, never()).findByKva(any());
    }

    @Test
    void AC_L1_LEW_요청_master_prices_미존재_400_INVALID_KVA_TIER() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        when(masterPriceRepository.findByKva(999)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.requestAdjustmentByLew(APP_ID, 50L, lewReq(999, "weird tier")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("INVALID_KVA_TIER"));

        verify(kvaAdjustmentRepository, never()).save(any());
    }

    @Test
    void AC_L5_중복_PENDING_요청_거부_409_KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));

        // 이미 PENDING 요청 row 가 락 조회 결과에 존재
        KvaAdjustmentRecord existing = mock(KvaAdjustmentRecord.class);
        when(existing.getAdjustmentSeq()).thenReturn(7L);
        when(kvaAdjustmentRepository.findByApplicationSeqAndStatusForUpdate(
                eq(APP_ID), eq(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)))
                .thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.requestAdjustmentByLew(APP_ID, 50L, lewReq(200, "again duplicate")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode())
                        .isEqualTo("KVA_ADJUSTMENT_REQUEST_ALREADY_PENDING"));

        verify(kvaAdjustmentRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(LewKvaAdjustmentRequestedEvent.class));
    }

    // ── PR-3 AC-L4: ADMIN 직접 변경 시 PENDING LEW 요청 자동 RESOLVED 마킹 ──────────

    @Test
    void AC_L4_ADMIN_override_시_PENDING_LEW_요청이_RESOLVED_BY_ADMIN_OVERRIDE_마킹() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650"));
        when(masterPriceRepository.findByKva(150)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        stubActiveInvoice();

        // 동일 application 의 PENDING LEW 요청 row 1건이 존재 — markResolvedByAdminOverride 가 호출되어야.
        KvaAdjustmentRecord pending = makePendingLewRow(7L, 50L, 200);
        when(kvaAdjustmentRepository.findByApplicationSeqAndStatusForUpdate(
                eq(APP_ID), eq(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)))
                .thenReturn(List.of(pending));

        service.overrideKva(APP_ID, req(150, "ADMIN decided 150"), ADMIN_SEQ);

        // PENDING row 의 status 가 RESOLVED 로 전이되었는지 검증.
        Assertions.assertThat(pending.getStatus())
                .isEqualTo(KvaAdjustmentStatus.RESOLVED_BY_ADMIN_OVERRIDE);

        // ADMIN 의 새 row 가 생성되며 lewRequestSeq=7 로 self-FK 연결.
        ArgumentCaptor<KvaAdjustmentRecord> cap =
                ArgumentCaptor.forClass(KvaAdjustmentRecord.class);
        verify(kvaAdjustmentRepository).save(cap.capture());
        KvaAdjustmentRecord adminRow = cap.getValue();
        Assertions.assertThat(adminRow.getLewRequestSeq()).isEqualTo(7L);

        // 해소 알림 이벤트 발행 (요청 LEW 에게)
        ArgumentCaptor<LewKvaRequestResolvedByOverrideEvent> resolvedCap =
                ArgumentCaptor.forClass(LewKvaRequestResolvedByOverrideEvent.class);
        verify(eventPublisher).publishEvent(resolvedCap.capture());
        LewKvaRequestResolvedByOverrideEvent ev = resolvedCap.getValue();
        Assertions.assertThat(ev.getApplicationSeq()).isEqualTo(APP_ID);
        Assertions.assertThat(ev.getRequestingLewUserSeq()).isEqualTo(50L);
        Assertions.assertThat(ev.getLewRequestAdjustmentSeq()).isEqualTo(7L);
        Assertions.assertThat(ev.getProposedKva()).isEqualTo(200);
        Assertions.assertThat(ev.getAppliedKva()).isEqualTo(150);

        // 감사 로그에 KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE 포함
        ArgumentCaptor<AuditAction> actionCap = ArgumentCaptor.forClass(AuditAction.class);
        verify(auditLogService, org.mockito.Mockito.atLeast(1)).logAsync(
                any(), actionCap.capture(), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any(), anyString(), any());
        Assertions.assertThat(actionCap.getAllValues())
                .contains(AuditAction.KVA_LEW_REQUEST_RESOLVED_BY_OVERRIDE);
    }

    @Test
    void AC_L4_PENDING_LEW_요청_없을_때_resolved_event_미발행() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));
        MasterPrice mp = mockPrice(5L, new BigDecimal("650"));
        when(masterPriceRepository.findByKva(200)).thenReturn(Optional.of(mp));
        User admin = mock(User.class);
        when(userRepository.findById(ADMIN_SEQ)).thenReturn(Optional.of(admin));
        stubActiveInvoice();

        service.overrideKva(APP_ID, req(200, "no LEW request to resolve"), ADMIN_SEQ);

        // ADMIN row 의 lewRequestSeq=null
        ArgumentCaptor<KvaAdjustmentRecord> cap =
                ArgumentCaptor.forClass(KvaAdjustmentRecord.class);
        verify(kvaAdjustmentRepository).save(cap.capture());
        Assertions.assertThat(cap.getValue().getLewRequestSeq()).isNull();
        // 해소 이벤트 미발행
        verify(eventPublisher, never()).publishEvent(any(LewKvaRequestResolvedByOverrideEvent.class));
    }

    /**
     * 실제 KvaAdjustmentRecord 인스턴스를 builder 로 생성하고 status 전이 / FK 연결 검증을 위해 사용.
     * 단순 mock 으로는 markResolvedByAdminOverride 의 도메인 로직을 검증할 수 없다.
     */
    private KvaAdjustmentRecord makePendingLewRow(Long adjustmentSeq, Long lewSeq, Integer proposedKva) {
        KvaAdjustmentRecord row = KvaAdjustmentRecord.builder()
                .application(null) // 테스트에서는 사용 안 함
                .lewRequestSeq(null)
                .previousKva(100)
                .newKva(null)
                .proposedKva(proposedKva)
                .reason("LEW reason")
                .status(KvaAdjustmentStatus.PENDING_ADMIN_REVIEW)
                .changedByRole(ChangedByRole.LEW)
                .changedByUserSeq(lewSeq)
                .previousQuoteAmount(new BigDecimal("450.00"))
                .newQuoteAmount(null)
                .amountDifference(null)
                .masterPriceSeqUsed(null)
                .adminMemo(null)
                .adminPaymentAdjustment(null)
                .settledAmount(null)
                .receiptReferenceNumber(null)
                .settlementMemo(null)
                .adminAdjustmentAt(null)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(row, "adjustmentSeq", adjustmentSeq);
        return row;
    }

    // ──────────────────────────────────────────────────────────
    // PR-4: 이력 조회 + Settlement 마킹 테스트
    // 스펙: kva-postpayment-adjustment-spec.md §4.3 / PR-4
    // ──────────────────────────────────────────────────────────

    private KvaAdjustmentRecord adminRecord(Long seq, KvaAdjustmentStatus status,
                                              AdminPaymentAdjustment paymentAdj) {
        KvaAdjustmentRecord r = KvaAdjustmentRecord.builder()
                .application(mock(Application.class))
                .lewRequestSeq(null)
                .previousKva(100).newKva(200)
                .reason("Admin reason")
                .status(status)
                .changedByRole(ChangedByRole.ADMIN)
                .changedByUserSeq(ADMIN_SEQ)
                .previousQuoteAmount(new BigDecimal("450.00"))
                .newQuoteAmount(new BigDecimal("650.00"))
                .amountDifference(new BigDecimal("200.00"))
                .masterPriceSeqUsed(5L)
                .adminMemo(null)
                .adminPaymentAdjustment(paymentAdj)
                .settledAmount(null)
                .receiptReferenceNumber(null)
                .settlementMemo(null)
                .adminAdjustmentAt(java.time.LocalDateTime.now())
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(r, "adjustmentSeq", seq);
        return r;
    }

    @Test
    void PR4_이력_조회_application_없으면_404() {
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAdjustmentHistory(APP_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "APPLICATION_NOT_FOUND");
    }

    @Test
    void PR4_이력_조회_정상_changedByUserName_채워짐() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(applicationRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        KvaAdjustmentRecord r1 = adminRecord(11L, KvaAdjustmentStatus.APPLIED, AdminPaymentAdjustment.PENDING);
        // application 필드를 적절히 mock — entity.getApplication().getApplicationSeq() 호출 안 됨.
        when(kvaAdjustmentRepository.findByApplication_ApplicationSeqOrderByCreatedAtDescAdjustmentSeqDesc(APP_ID))
                .thenReturn(List.of(r1));

        User admin = mock(User.class);
        when(admin.getUserSeq()).thenReturn(ADMIN_SEQ);
        when(admin.getFirstName()).thenReturn("Admin");
        when(admin.getLastName()).thenReturn("User");
        when(userRepository.findAllById(any())).thenReturn(List.of(admin));

        var items = service.getAdjustmentHistory(APP_ID);

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getAdjustmentSeq()).isEqualTo(11L);
        assertThat(items.get(0).getChangedByUserName()).isEqualTo("Admin User");
        assertThat(items.get(0).getStatus()).isEqualTo(KvaAdjustmentStatus.APPLIED);
    }

    @Test
    void PR4_settlement_정상_PAID_DIFFERENCE_마킹() {
        Application app = mockApp(ApplicationStatus.PAID, 200, new BigDecimal("650.00"));
        when(app.getApplicationSeq()).thenReturn(APP_ID);
        KvaAdjustmentRecord row = adminRecord(42L, KvaAdjustmentStatus.APPLIED, AdminPaymentAdjustment.PENDING);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "application", app);
        when(kvaAdjustmentRepository.findById(42L)).thenReturn(Optional.of(row));

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.PAID_DIFFERENCE);
        req.setSettledAmount(new BigDecimal("200.00"));
        req.setReceiptReferenceNumber("PAYNOW-ABC-123");
        req.setSettlementMemo("Manual transfer");
        req.setNotifyLew(true);

        var result = service.markSettlement(APP_ID, 42L, req, ADMIN_SEQ);

        assertThat(result.getPaymentAdjustment()).isEqualTo(AdminPaymentAdjustment.PAID_DIFFERENCE);
        assertThat(result.getSettledAmount()).isEqualByComparingTo(new BigDecimal("200.00"));
        verify(eventPublisher, times(1)).publishEvent(
                any(com.bluelight.backend.api.admin.KvaSettlementMarkedEvent.class));
        verify(auditLogService, times(1)).logAsync(
                eq(ADMIN_SEQ), eq(AuditAction.KVA_SETTLEMENT_MARKED), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString(), anyString(), eq(200));
    }

    @Test
    void PR4_settlement_D6_거부_이미_finalize() {
        Application app = mockApp(ApplicationStatus.PAID, 200, new BigDecimal("650.00"));
        when(app.getApplicationSeq()).thenReturn(APP_ID);
        // 이미 PAID_DIFFERENCE 로 finalize 된 row.
        KvaAdjustmentRecord row = adminRecord(42L, KvaAdjustmentStatus.APPLIED,
                AdminPaymentAdjustment.PAID_DIFFERENCE);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "application", app);
        when(kvaAdjustmentRepository.findById(42L)).thenReturn(Optional.of(row));

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.REFUNDED);
        req.setNotifyLew(true);

        assertThatThrownBy(() -> service.markSettlement(APP_ID, 42L, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "KVA_SETTLEMENT_ALREADY_FINALIZED");

        // 이벤트 발행 안 됨.
        verify(eventPublisher, never()).publishEvent(
                any(com.bluelight.backend.api.admin.KvaSettlementMarkedEvent.class));
        // 거부 audit 기록.
        verify(auditLogService, times(1)).logAsync(
                eq(ADMIN_SEQ), eq(AuditAction.KVA_SETTLEMENT_DENIED), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString(), anyString(), eq(409));
    }

    @Test
    void PR4_settlement_status_거부_LEW_PENDING_row() {
        Application app = mockApp(ApplicationStatus.PAID, 100, new BigDecimal("450.00"));
        when(app.getApplicationSeq()).thenReturn(APP_ID);
        // LEW PENDING_ADMIN_REVIEW row — settlement 호출 자체가 부적절.
        KvaAdjustmentRecord row = makePendingLewRow(33L, 60L, 200);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "application", app);
        when(kvaAdjustmentRepository.findById(33L)).thenReturn(Optional.of(row));

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.WAIVED);
        req.setNotifyLew(true);

        assertThatThrownBy(() -> service.markSettlement(APP_ID, 33L, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "KVA_SETTLEMENT_NOT_APPLICABLE");

        verify(eventPublisher, never()).publishEvent(
                any(com.bluelight.backend.api.admin.KvaSettlementMarkedEvent.class));
    }

    @Test
    void PR4_settlement_row_없음_404() {
        when(kvaAdjustmentRepository.findById(99L)).thenReturn(Optional.empty());

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.PAID_DIFFERENCE);
        req.setNotifyLew(true);

        assertThatThrownBy(() -> service.markSettlement(APP_ID, 99L, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "KVA_ADJUSTMENT_NOT_FOUND");
    }

    @Test
    void PR4_settlement_다른_application_row_404() {
        Application appOfRow = mock(Application.class);
        when(appOfRow.getApplicationSeq()).thenReturn(999L);
        KvaAdjustmentRecord row = adminRecord(42L, KvaAdjustmentStatus.APPLIED, AdminPaymentAdjustment.PENDING);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "application", appOfRow);
        when(kvaAdjustmentRepository.findById(42L)).thenReturn(Optional.of(row));

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.PAID_DIFFERENCE);
        req.setNotifyLew(true);

        // path APP_ID(=1) 와 row.application.applicationSeq(=999) 가 다름 — 404 정보 노출 방지.
        assertThatThrownBy(() -> service.markSettlement(APP_ID, 42L, req, ADMIN_SEQ))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "KVA_ADJUSTMENT_NOT_FOUND");
    }

    @Test
    void PR4_settlement_notifyLew_false_이벤트_발행_안함() {
        Application app = mockApp(ApplicationStatus.PAID, 200, new BigDecimal("650.00"));
        when(app.getApplicationSeq()).thenReturn(APP_ID);
        KvaAdjustmentRecord row = adminRecord(42L, KvaAdjustmentStatus.APPLIED, AdminPaymentAdjustment.PENDING);
        org.springframework.test.util.ReflectionTestUtils.setField(row, "application", app);
        when(kvaAdjustmentRepository.findById(42L)).thenReturn(Optional.of(row));

        com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest req =
                new com.bluelight.backend.api.admin.dto.KvaSettlementUpdateRequest();
        req.setPaymentAdjustment(AdminPaymentAdjustment.WAIVED);
        req.setNotifyLew(false); // 명시적 false

        service.markSettlement(APP_ID, 42L, req, ADMIN_SEQ);

        // 마킹은 정상 — 이벤트 발행만 스킵.
        verify(eventPublisher, never()).publishEvent(
                any(com.bluelight.backend.api.admin.KvaSettlementMarkedEvent.class));
        verify(auditLogService, times(1)).logAsync(
                eq(ADMIN_SEQ), eq(AuditAction.KVA_SETTLEMENT_MARKED), any(),
                anyString(), anyString(), anyString(),
                any(), any(), any(), any(), anyString(), anyString(), eq(200));
    }
}
