package com.bluelight.backend.api.invoice;

import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.common.util.OwnershipValidator;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationStatus;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.api.audit.AuditLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * E-Invoice 조회·재발행 서비스 (신청자/관리자 대상).
 *
 * <p>PDF 생성은 {@link InvoiceGenerationService} 가 담당. 본 서비스는 **조회 + 권한 검증
 * + 다운로드 감사 로깅 + 재발행 위임**에 한정.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final ApplicationRepository applicationRepository;
    private final AuditLogService auditLogService;
    private final InvoiceGenerationService invoiceGenerationService;

    /**
     * 신청자 본인 — Application의 Invoice 조회.
     * PAID 이후에만 발행되며, PAID 이전에는 {@code PAYMENT_NOT_CONFIRMED} 400.
     * 본인 소유가 아니면 {@code INVOICE_FORBIDDEN} 403.
     */
    public InvoiceResponse getByApplicationForApplicant(Long applicationSeq, Long userSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        OwnershipValidator.validateOwner(application.getUser().getUserSeq(), userSeq);

        assertPaymentConfirmed(application);

        Invoice invoice = invoiceRepository
                .findByApplicationSeqAndReferenceType(applicationSeq, "APPLICATION")
                .orElseThrow(() -> new BusinessException(
                        "Invoice not yet available",
                        HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));

        recordDownload(invoice, userSeq);
        return InvoiceResponse.from(invoice);
    }

    /**
     * Admin/SYSTEM_ADMIN — Application의 Invoice 조회. 본인 검증 생략, 감사 로그 기록.
     */
    public InvoiceResponse getByApplicationForAdmin(Long applicationSeq, Long adminUserSeq) {
        Application application = findApplicationOrThrow(applicationSeq);
        assertPaymentConfirmed(application);

        Invoice invoice = invoiceRepository
                .findByApplicationSeqAndReferenceType(applicationSeq, "APPLICATION")
                .orElseThrow(() -> new BusinessException(
                        "Invoice not yet available",
                        HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));

        recordDownload(invoice, adminUserSeq);
        return InvoiceResponse.from(invoice);
    }

    /**
     * Admin/SYSTEM_ADMIN — Invoice PDF 재생성. 스냅샷 데이터는 불변, pdfFileSeq만 교체.
     */
    @Transactional
    public InvoiceResponse regenerate(Long applicationSeq, Long adminUserSeq, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(
                    "Regeneration reason is required",
                    HttpStatus.BAD_REQUEST, "REASON_REQUIRED");
        }
        Invoice invoice = invoiceRepository
                .findByApplicationSeqAndReferenceType(applicationSeq, "APPLICATION")
                .orElseThrow(() -> new BusinessException(
                        "Invoice not found",
                        HttpStatus.NOT_FOUND, "INVOICE_NOT_FOUND"));

        Invoice updated = invoiceGenerationService.regenerate(invoice.getInvoiceSeq(), adminUserSeq, reason);
        return InvoiceResponse.from(updated);
    }

    // ── helpers ──────────────────────────────────────────

    private Application findApplicationOrThrow(Long applicationSeq) {
        return applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found",
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));
    }

    /**
     * Application 상태가 결제 확정 이후(PAID/IN_PROGRESS/COMPLETED)가 아니면 400.
     * PENDING_REVIEW / REVISION_REQUESTED / PENDING_PAYMENT 단계에서는 영수증이 없음.
     */
    private void assertPaymentConfirmed(Application application) {
        ApplicationStatus s = application.getStatus();
        boolean confirmed = s == ApplicationStatus.PAID
                || s == ApplicationStatus.IN_PROGRESS
                || s == ApplicationStatus.COMPLETED;
        if (!confirmed) {
            throw new BusinessException(
                    "Payment not confirmed yet",
                    HttpStatus.BAD_REQUEST, "PAYMENT_NOT_CONFIRMED");
        }
    }

    private void recordDownload(Invoice invoice, Long userSeq) {
        try {
            auditLogService.logAsync(
                    userSeq,
                    AuditAction.INVOICE_DOWNLOADED,
                    AuditCategory.APPLICATION,
                    "Invoice", String.valueOf(invoice.getInvoiceSeq()),
                    "Invoice metadata retrieved for download",
                    null, null,
                    null, null, "GET", "/api/applications/**/invoice", 200);
        } catch (Exception e) {
            log.warn("Audit log write failed for invoice download: invoiceSeq={}, err={}",
                    invoice.getInvoiceSeq(), e.getMessage());
        }
    }
}
