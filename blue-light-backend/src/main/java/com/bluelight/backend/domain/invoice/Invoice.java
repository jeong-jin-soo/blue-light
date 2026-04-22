package com.bluelight.backend.domain.invoice;

import com.bluelight.backend.domain.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * E-Invoice (영수증) 엔티티.
 *
 * <p>결제 확인 시점에 자동 발행되는 불변 증빙 문서. 스펙:
 * {@code doc/Project Analysis/invoice-spec.md}.</p>
 *
 * <h2>Immutability 정책 (invoice-spec §8)</h2>
 * <ul>
 *   <li>모든 {@code *Snapshot} 필드는 {@code @Column(updatable = false)} — 발행 시점 값 불변.</li>
 *   <li>{@code pdfFileSeq}는 재발행 허용 (updatable=true). 스냅샷 데이터는 그대로 두고 PDF만 교체.</li>
 *   <li>Soft delete만 허용 — 법적 증빙이므로 물리 삭제 금지.</li>
 * </ul>
 *
 * <h2>Payment 1:1 관계</h2>
 * 하나의 {@code payment_seq}당 Invoice 1건. {@code uk_invoices_payment} unique constraint로 보장.
 */
@Entity
@Table(name = "invoices")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SQLDelete(sql = "UPDATE invoices SET deleted_at = NOW() WHERE invoice_seq = ?")
@SQLRestriction("deleted_at IS NULL")
public class Invoice extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "invoice_seq")
    private Long invoiceSeq;

    /** 영수증 번호 (예: IN20260422001). {@code uk_invoices_number}로 유일성 보장. */
    @Column(name = "invoice_number", nullable = false, length = 30, updatable = false)
    private String invoiceNumber;

    // ── 참조 (payments의 poly 참조 구조와 동일) ──

    @Column(name = "payment_seq", nullable = false, updatable = false)
    private Long paymentSeq;

    @Column(name = "reference_type", nullable = false, length = 30, updatable = false)
    private String referenceType;

    @Column(name = "reference_seq", nullable = false, updatable = false)
    private Long referenceSeq;

    /** reference_type=APPLICATION 일 때 채움 (조회 편의). */
    @Column(name = "application_seq", updatable = false)
    private Long applicationSeq;

    @Column(name = "recipient_user_seq", nullable = false, updatable = false)
    private Long recipientUserSeq;

    /** 자동 발행 시 null, 재발행 시 admin userSeq. */
    @Column(name = "issued_by_user_seq")
    private Long issuedByUserSeq;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private LocalDateTime issuedAt;

    // ── 금액 ──

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "qty_snapshot", nullable = false, updatable = false)
    private Integer qtySnapshot = 1;

    @Column(name = "rate_amount_snapshot", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal rateAmountSnapshot;

    @Column(name = "currency_snapshot", nullable = false, length = 5, updatable = false)
    private String currencySnapshot = "SGD";

    // ── 발행자(당사 — HanVision/LicenseKaki) 스냅샷 ──

    @Column(name = "company_name_snapshot", nullable = false, length = 150, updatable = false)
    private String companyNameSnapshot;

    @Column(name = "company_alias_snapshot", length = 80, updatable = false)
    private String companyAliasSnapshot;

    @Column(name = "company_uen_snapshot", nullable = false, length = 30, updatable = false)
    private String companyUenSnapshot;

    @Column(name = "company_address_line1_snapshot", length = 200, updatable = false)
    private String companyAddressLine1Snapshot;

    @Column(name = "company_address_line2_snapshot", length = 200, updatable = false)
    private String companyAddressLine2Snapshot;

    @Column(name = "company_address_line3_snapshot", length = 200, updatable = false)
    private String companyAddressLine3Snapshot;

    @Column(name = "company_email_snapshot", length = 120, updatable = false)
    private String companyEmailSnapshot;

    @Column(name = "company_website_snapshot", length = 120, updatable = false)
    private String companyWebsiteSnapshot;

    // ── 빌링 대상(To:) 스냅샷 — Application Layer B 우선 ──

    @Column(name = "billing_recipient_name_snapshot", nullable = false, length = 150, updatable = false)
    private String billingRecipientNameSnapshot;

    @Column(name = "billing_recipient_company_snapshot", length = 200, updatable = false)
    private String billingRecipientCompanySnapshot;

    @Column(name = "billing_address_line1_snapshot", length = 300, updatable = false)
    private String billingAddressLine1Snapshot;

    @Column(name = "billing_address_line2_snapshot", length = 300, updatable = false)
    private String billingAddressLine2Snapshot;

    @Column(name = "billing_address_line3_snapshot", length = 300, updatable = false)
    private String billingAddressLine3Snapshot;

    @Column(name = "billing_address_line4_snapshot", length = 300, updatable = false)
    private String billingAddressLine4Snapshot;

    // ── 설치 장소(Description 내부) 스냅샷 ──

    @Column(name = "installation_name_snapshot", length = 200, updatable = false)
    private String installationNameSnapshot;

    @Column(name = "installation_address_line1_snapshot", length = 300, updatable = false)
    private String installationAddressLine1Snapshot;

    @Column(name = "installation_address_line2_snapshot", length = 300, updatable = false)
    private String installationAddressLine2Snapshot;

    @Column(name = "installation_address_line3_snapshot", length = 300, updatable = false)
    private String installationAddressLine3Snapshot;

    @Column(name = "installation_address_line4_snapshot", length = 300, updatable = false)
    private String installationAddressLine4Snapshot;

    /** Description 본문 전체 (period/licence 표현 포함). */
    @Column(name = "description_snapshot", nullable = false, columnDefinition = "TEXT", updatable = false)
    private String descriptionSnapshot;

    // ── PayNow QR 블록 스냅샷 ──

    @Column(name = "paynow_uen_snapshot", length = 30, updatable = false)
    private String paynowUenSnapshot;

    @Column(name = "paynow_qr_file_seq_snapshot", updatable = false)
    private Long paynowQrFileSeqSnapshot;

    @Column(name = "footer_note_snapshot", length = 500, updatable = false)
    private String footerNoteSnapshot;

    // ── 생성된 PDF 파일 — 재발행 가능 (스냅샷 외 유일한 가변 필드) ──

    @Column(name = "pdf_file_seq", nullable = false)
    private Long pdfFileSeq;

    @Builder
    public Invoice(String invoiceNumber,
                   Long paymentSeq,
                   String referenceType,
                   Long referenceSeq,
                   Long applicationSeq,
                   Long recipientUserSeq,
                   Long issuedByUserSeq,
                   LocalDateTime issuedAt,
                   BigDecimal totalAmount,
                   Integer qtySnapshot,
                   BigDecimal rateAmountSnapshot,
                   String currencySnapshot,
                   String companyNameSnapshot,
                   String companyAliasSnapshot,
                   String companyUenSnapshot,
                   String companyAddressLine1Snapshot,
                   String companyAddressLine2Snapshot,
                   String companyAddressLine3Snapshot,
                   String companyEmailSnapshot,
                   String companyWebsiteSnapshot,
                   String billingRecipientNameSnapshot,
                   String billingRecipientCompanySnapshot,
                   String billingAddressLine1Snapshot,
                   String billingAddressLine2Snapshot,
                   String billingAddressLine3Snapshot,
                   String billingAddressLine4Snapshot,
                   String installationNameSnapshot,
                   String installationAddressLine1Snapshot,
                   String installationAddressLine2Snapshot,
                   String installationAddressLine3Snapshot,
                   String installationAddressLine4Snapshot,
                   String descriptionSnapshot,
                   String paynowUenSnapshot,
                   Long paynowQrFileSeqSnapshot,
                   String footerNoteSnapshot,
                   Long pdfFileSeq) {
        this.invoiceNumber = invoiceNumber;
        this.paymentSeq = paymentSeq;
        this.referenceType = referenceType;
        this.referenceSeq = referenceSeq;
        this.applicationSeq = applicationSeq;
        this.recipientUserSeq = recipientUserSeq;
        this.issuedByUserSeq = issuedByUserSeq;
        this.issuedAt = issuedAt != null ? issuedAt : LocalDateTime.now();
        this.totalAmount = totalAmount;
        this.qtySnapshot = qtySnapshot != null ? qtySnapshot : 1;
        this.rateAmountSnapshot = rateAmountSnapshot;
        this.currencySnapshot = currencySnapshot != null ? currencySnapshot : "SGD";
        this.companyNameSnapshot = companyNameSnapshot;
        this.companyAliasSnapshot = companyAliasSnapshot;
        this.companyUenSnapshot = companyUenSnapshot;
        this.companyAddressLine1Snapshot = companyAddressLine1Snapshot;
        this.companyAddressLine2Snapshot = companyAddressLine2Snapshot;
        this.companyAddressLine3Snapshot = companyAddressLine3Snapshot;
        this.companyEmailSnapshot = companyEmailSnapshot;
        this.companyWebsiteSnapshot = companyWebsiteSnapshot;
        this.billingRecipientNameSnapshot = billingRecipientNameSnapshot;
        this.billingRecipientCompanySnapshot = billingRecipientCompanySnapshot;
        this.billingAddressLine1Snapshot = billingAddressLine1Snapshot;
        this.billingAddressLine2Snapshot = billingAddressLine2Snapshot;
        this.billingAddressLine3Snapshot = billingAddressLine3Snapshot;
        this.billingAddressLine4Snapshot = billingAddressLine4Snapshot;
        this.installationNameSnapshot = installationNameSnapshot;
        this.installationAddressLine1Snapshot = installationAddressLine1Snapshot;
        this.installationAddressLine2Snapshot = installationAddressLine2Snapshot;
        this.installationAddressLine3Snapshot = installationAddressLine3Snapshot;
        this.installationAddressLine4Snapshot = installationAddressLine4Snapshot;
        this.descriptionSnapshot = descriptionSnapshot;
        this.paynowUenSnapshot = paynowUenSnapshot;
        this.paynowQrFileSeqSnapshot = paynowQrFileSeqSnapshot;
        this.footerNoteSnapshot = footerNoteSnapshot;
        this.pdfFileSeq = pdfFileSeq;
    }

    /**
     * PDF 파일만 재생성 — 스냅샷 데이터는 불변, pdfFileSeq만 교체.
     * 재발행 로직은 {@code InvoiceGenerationService.regenerate()}에서 이 메서드로 위임.
     */
    public void replacePdfFile(Long newPdfFileSeq, Long regeneratedByUserSeq) {
        if (newPdfFileSeq == null) {
            throw new IllegalArgumentException("newPdfFileSeq must not be null");
        }
        this.pdfFileSeq = newPdfFileSeq;
        this.issuedByUserSeq = regeneratedByUserSeq;
    }
}
