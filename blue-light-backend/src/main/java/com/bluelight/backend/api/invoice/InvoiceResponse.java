package com.bluelight.backend.api.invoice;

import com.bluelight.backend.domain.invoice.Invoice;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * E-Invoice 조회 응답 DTO.
 *
 * <p>PDF 바이너리 자체는 기존 {@code GET /api/files/{fileId}/download} 로 내려받고,
 * 본 DTO는 프런트에서 영수증 존재 여부·번호·다운로드 파일 seq 를 확인하는 메타 응답.</p>
 */
@Getter
@Builder
public class InvoiceResponse {

    private Long invoiceSeq;
    private String invoiceNumber;
    private Long paymentSeq;
    private String referenceType;
    private Long referenceSeq;
    private Long applicationSeq;
    private LocalDateTime issuedAt;
    private BigDecimal totalAmount;
    private String currency;
    /** PDF 다운로드 경로는 프런트가 {@code /api/files/{pdfFileSeq}/download} 로 조합. */
    private Long pdfFileSeq;
    /** To: 블록 표시용(관리자 UI 등). Applicant 본인은 이미 자기 정보이므로 중복이나 불변성 확인용. */
    private String billingRecipientName;
    private String billingRecipientCompany;

    public static InvoiceResponse from(Invoice invoice) {
        return InvoiceResponse.builder()
                .invoiceSeq(invoice.getInvoiceSeq())
                .invoiceNumber(invoice.getInvoiceNumber())
                .paymentSeq(invoice.getPaymentSeq())
                .referenceType(invoice.getReferenceType())
                .referenceSeq(invoice.getReferenceSeq())
                .applicationSeq(invoice.getApplicationSeq())
                .issuedAt(invoice.getIssuedAt())
                .totalAmount(invoice.getTotalAmount())
                .currency(invoice.getCurrencySnapshot())
                .pdfFileSeq(invoice.getPdfFileSeq())
                .billingRecipientName(invoice.getBillingRecipientNameSnapshot())
                .billingRecipientCompany(invoice.getBillingRecipientCompanySnapshot())
                .build();
    }
}
