package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentRepository;
import com.bluelight.backend.domain.payment.PaymentStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 견적 변경 후 활성 Invoice 무효화 + 신규 발행 공용 서비스.
 *
 * <p>kVA 사후조정({@code KvaPostPaymentService})과 SLD 전환({@code SldConversionService})이
 * 동일 로직을 공유하도록 추출(SSOT). 같은 트랜잭션에서 호출되어 실패 시 전체 롤백.
 * SUCCESS 결제가 없으면(결제 전) no-op — 결제 전 견적 변경은 Invoice 가 아직 없으므로 안전.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceRegenerationService {

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceGenerationService invoiceGenerationService;
    private final AuditLogService auditLogService;

    /**
     * @param application      대상 신청 (managed)
     * @param invalidateReason 무효화 사유 태그 (예: "KVA_ADJUSTMENT_12", "SLD_ADDED_34")
     * @param auditPath        감사 로그 request path
     */
    @Transactional
    public void invalidateAndRegenerate(Application application, String invalidateReason, String auditPath) {
        Long applicationSeq = application.getApplicationSeq();
        Optional<Invoice> activeInvoice = invoiceRepository
                .findFirstByApplicationSeqAndReferenceTypeAndStatus(applicationSeq, "APPLICATION", "ACTIVE");
        if (activeInvoice.isEmpty()) {
            log.info("No active invoice for applicationSeq={}; skipping regeneration (pre-payment)", applicationSeq);
            return;
        }
        Invoice old = activeInvoice.get();
        old.invalidate(invalidateReason);

        Payment payment = paymentRepository
                .findByApplicationApplicationSeqAndStatus(applicationSeq, PaymentStatus.SUCCESS)
                .orElse(null);
        if (payment == null) {
            log.warn("No SUCCESS payment for applicationSeq={}; skipping invoice regeneration", applicationSeq);
            return;
        }

        Invoice newInvoice = invoiceGenerationService.generateFromPayment(payment, application);
        log.info("Invoice regenerated: oldInvoiceSeq={}, newInvoiceSeq={}, applicationSeq={}, reason={}",
                old.getInvoiceSeq(), newInvoice.getInvoiceSeq(), applicationSeq, invalidateReason);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("oldInvoiceSeq", old.getInvoiceSeq());
        meta.put("newInvoiceSeq", newInvoice.getInvoiceSeq());
        meta.put("invalidatedReason", old.getInvalidatedReason());
        auditLogService.logAsync(
                null, AuditAction.INVOICE_REGENERATED, AuditCategory.ADMIN,
                "Invoice", String.valueOf(newInvoice.getInvoiceSeq()),
                "Invoice regenerated due to quote adjustment (" + invalidateReason + ")",
                null, meta, null, null, "POST", auditPath, 200);
    }
}
