package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.file.FileEntity;
import com.bluelight.backend.domain.file.FileRepository;
import com.bluelight.backend.domain.file.FileType;
import com.bluelight.backend.domain.invoice.Invoice;
import com.lowagie.text.Document;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * E-Invoice PDF 렌더링 (invoice-spec §7).
 *
 * <p>{@link Invoice} 스냅샷을 입력받아 OpenPDF(iText 포크)로 A4 단일 페이지 PDF를 생성하고,
 * {@link FileStorageService}에 저장한 후 {@link FileEntity}에 등록, {@code fileSeq}를 반환한다.</p>
 *
 * <p>{@code LoaGenerationService}와 동일하게 {@link BaseFont#HELVETICA} 기반으로 좌표 렌더링하며,
 * 스펙 §7 표의 좌표·폰트를 그대로 사용한다. 시각 유사도 80% 이상(AC-2)이 목표.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvoicePdfRenderer {

    /** 로고 — 있으면 사용, 없으면 무시 (스펙 §12). */
    private static final String LOGO_CLASSPATH = "templates/licensekaki-logo.png";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("dd MMM yy");

    private final FileStorageService fileStorageService;
    private final FileRepository fileRepository;

    /**
     * 영수증 PDF 렌더 → FileStorage 저장 → FileEntity 등록 후 fileSeq 반환.
     */
    public Long render(Invoice invoice) {
        String filename = "INVOICE_" + invoice.getInvoiceNumber() + "_"
                + UUID.randomUUID().toString().substring(0, 8) + ".pdf";
        String subDirectory = "invoices/" + invoice.getApplicationSeq();

        try {
            byte[] pdfBytes = buildPdf(invoice);
            String storedUrl = fileStorageService.storeBytes(pdfBytes, filename, subDirectory);

            FileEntity fileEntity = FileEntity.builder()
                    .fileType(FileType.PAYMENT_RECEIPT)
                    .fileUrl(storedUrl)
                    .originalFilename(filename)
                    .fileSize((long) pdfBytes.length)
                    .build();
            FileEntity saved = fileRepository.save(fileEntity);

            log.info("Invoice PDF rendered: invoice_number={}, file_seq={}, path={}",
                    invoice.getInvoiceNumber(), saved.getFileSeq(), storedUrl);
            return saved.getFileSeq();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to render invoice PDF (invoice_number={}): {}",
                    invoice.getInvoiceNumber(), e.getMessage(), e);
            throw new BusinessException(
                    "Failed to render invoice PDF",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "INVOICE_RENDER_ERROR");
        }
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private byte[] buildPdf(Invoice inv) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4);
        PdfWriter writer = PdfWriter.getInstance(document, baos);
        document.open();

        PdfContentByte cb = writer.getDirectContent();

        BaseFont helv = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        BaseFont helvBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        BaseFont helvOblique = BaseFont.createFont(BaseFont.HELVETICA_OBLIQUE, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);

        // ── 좌상단 회사 블록 ──
        String companyLine1 = nullSafe(inv.getCompanyNameSnapshot())
                + (isBlank(inv.getCompanyAliasSnapshot()) ? "" : " (" + inv.getCompanyAliasSnapshot() + ")");
        drawText(cb, helvBold, 10, 50, 800, companyLine1);
        drawText(cb, helv, 9, 50, 784, nullSafe(inv.getCompanyWebsiteSnapshot()));
        drawText(cb, helv, 9, 50, 770, nullSafe(inv.getCompanyEmailSnapshot()));
        drawText(cb, helv, 9, 50, 754, nullSafe(inv.getCompanyAddressLine1Snapshot()));
        drawText(cb, helv, 9, 50, 740, nullSafe(inv.getCompanyAddressLine2Snapshot()));
        drawText(cb, helv, 9, 50, 726, nullSafe(inv.getCompanyAddressLine3Snapshot()));

        // ── 우상단 로고 + 회사명 + UEN 뱃지 ──
        drawLogoIfPresent(cb, 430, 800);
        drawText(cb, helv, 9, 430, 745, nullSafe(inv.getCompanyNameSnapshot()));
        drawText(cb, helvBold, 9, 430, 730, "UEN: " + nullSafe(inv.getCompanyUenSnapshot()));

        // ── INVOICE 타이틀 + 번호 + 날짜 ──
        drawText(cb, helvBold, 24, 50, 680, "INVOICE");
        drawText(cb, helvBold, 12, 50, 650, nullSafe(inv.getInvoiceNumber()));
        LocalDateTime issuedAt = inv.getIssuedAt() != null ? inv.getIssuedAt() : LocalDateTime.now();
        drawText(cb, helv, 10, 50, 632, "Date: " + issuedAt.toLocalDate().format(DATE_FORMAT));

        // ── To: 블록 (빌링) ──
        drawText(cb, helvBold, 10, 50, 600, "To:");
        float toY = 586f;
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingRecipientNameSnapshot());
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingRecipientCompanySnapshot());
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingAddressLine1Snapshot());
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingAddressLine2Snapshot());
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingAddressLine3Snapshot());
        toY = drawLineIfPresent(cb, helv, 10, 50, toY, inv.getBillingAddressLine4Snapshot());

        // ── 4열 테이블 헤더 (좌표 기반으로 직접 그림) ──
        float tableTop = 500f;
        drawHorizontalLine(cb, 50, 545, tableTop + 12);
        drawText(cb, helvBold, 10, 50, tableTop, "Description");
        drawText(cb, helvBold, 10, 360, tableTop, "Qty");
        drawText(cb, helvBold, 10, 430, tableTop, "Rate");
        drawText(cb, helvBold, 10, 500, tableTop, "Amount");
        drawHorizontalLine(cb, 50, 545, tableTop - 4);

        // ── Description 본문 (multi-line) ──
        float descStartY = 480f;
        float descLineH = 12f;
        float descY = descStartY;
        String[] descLines = splitDescription(inv);
        for (String line : descLines) {
            if (descY < 360) break;
            drawText(cb, helv, 9, 50, descY, line);
            descY -= descLineH;
        }

        // Qty / Rate / Amount (첫 줄에 정렬)
        Integer qty = inv.getQtySnapshot() != null ? inv.getQtySnapshot() : 1;
        drawText(cb, helv, 10, 360, descStartY, String.valueOf(qty));
        drawText(cb, helv, 10, 430, descStartY, formatCurrency(inv.getRateAmountSnapshot(), inv.getCurrencySnapshot()));
        drawText(cb, helv, 10, 500, descStartY, formatCurrency(inv.getTotalAmount(), inv.getCurrencySnapshot()));

        // ── Total 라인 ──
        drawHorizontalLine(cb, 360, 545, 352);
        String totalLabel = "Total: " + formatCurrency(inv.getTotalAmount(), inv.getCurrencySnapshot());
        float totalWidth = helvBold.getWidthPoint(totalLabel, 11);
        drawText(cb, helvBold, 11, 545 - totalWidth, 340, totalLabel);

        // ── Footer note ──
        String footerNote = nullSafe(inv.getFooterNoteSnapshot());
        drawText(cb, helvOblique, 9, 50, 300, footerNote);

        // ── 하단 우측: Payment Method + QR + UEN ──
        drawText(cb, helvBold, 10, 400, 260, "Payment Method via SG QR");
        drawQrOrPlaceholder(cb, inv, helv, 400f, 150f);
        drawText(cb, helv, 9, 400, 130, "UEN Paynow: " + nullSafe(inv.getPaynowUenSnapshot()));

        document.close();
        return baos.toByteArray();
    }

    private void drawText(PdfContentByte cb, BaseFont font, float size, float x, float y, String text) {
        if (text == null || text.isEmpty()) return;
        cb.beginText();
        cb.setFontAndSize(font, size);
        cb.setTextMatrix(x, y);
        cb.showText(text);
        cb.endText();
    }

    /** 값이 있으면 해당 y에 그리고 y를 14pt 내려서 반환. 값이 비었으면 y 그대로 반환. */
    private float drawLineIfPresent(PdfContentByte cb, BaseFont font, float size,
                                    float x, float y, String text) {
        if (text == null || text.isBlank()) return y;
        drawText(cb, font, size, x, y, text);
        return y - 14f;
    }

    private void drawHorizontalLine(PdfContentByte cb, float x1, float x2, float y) {
        cb.setLineWidth(0.5f);
        cb.moveTo(x1, y);
        cb.lineTo(x2, y);
        cb.stroke();
    }

    private void drawLogoIfPresent(PdfContentByte cb, float x, float y) {
        try {
            ClassPathResource logoRes = new ClassPathResource(LOGO_CLASSPATH);
            if (!logoRes.exists()) return;
            Image img = Image.getInstance(logoRes.getInputStream().readAllBytes());
            img.scaleToFit(100, 40);
            img.setAbsolutePosition(x, y);
            cb.addImage(img);
        } catch (Exception e) {
            log.debug("Invoice logo asset not available, skipping: {}", e.getMessage());
        }
    }

    private void drawQrOrPlaceholder(PdfContentByte cb, Invoice inv, BaseFont font,
                                     float x, float y) {
        Long qrSeq = inv.getPaynowQrFileSeqSnapshot();
        if (qrSeq != null) {
            try {
                FileEntity qrFile = fileRepository.findById(qrSeq).orElse(null);
                if (qrFile != null) {
                    Resource qrResource = fileStorageService.loadAsResource(qrFile.getFileUrl());
                    byte[] qrBytes = qrResource.getInputStream().readAllBytes();
                    Image qrImg = Image.getInstance(qrBytes);
                    qrImg.scaleToFit(110, 110);
                    qrImg.setAbsolutePosition(x, y);
                    cb.addImage(qrImg);
                    return;
                }
            } catch (Exception e) {
                log.warn("Failed to load PayNow QR (file_seq={}): {} — falling back to placeholder",
                        qrSeq, e.getMessage());
            }
        }
        // Placeholder 박스
        cb.setLineWidth(0.5f);
        cb.rectangle(x, y, 110f, 110f);
        cb.stroke();
        drawText(cb, font, 9, x + 28, y + 55, "[QR pending]");
    }

    /**
     * Description 본문 + 설치 장소 블록을 라인으로 쪼갠다.
     * {@code descriptionSnapshot}는 개행 포함 가능.
     * 설치 장소는 별도 스냅샷 필드에 저장되어 있어 본문 뒤에 이어 붙인다.
     */
    private String[] splitDescription(Invoice inv) {
        StringBuilder sb = new StringBuilder();
        String desc = inv.getDescriptionSnapshot();
        if (desc != null && !desc.isBlank()) {
            sb.append(desc);
        }
        // 설치 장소 블록
        String instName = inv.getInstallationNameSnapshot();
        String instL1 = inv.getInstallationAddressLine1Snapshot();
        String instL2 = inv.getInstallationAddressLine2Snapshot();
        String instL3 = inv.getInstallationAddressLine3Snapshot();
        String instL4 = inv.getInstallationAddressLine4Snapshot();
        if (hasAnyLine(instName, instL1, instL2, instL3, instL4)) {
            if (sb.length() > 0) sb.append("\n\n");
            sb.append("Installation Address:");
            if (!isBlank(instName)) sb.append("\n").append(instName);
            if (!isBlank(instL1)) sb.append("\n").append(instL1);
            if (!isBlank(instL2)) sb.append("\n").append(instL2);
            if (!isBlank(instL3)) sb.append("\n").append(instL3);
            if (!isBlank(instL4)) sb.append("\n").append(instL4);
        }
        return sb.toString().split("\\r?\\n");
    }

    private String formatCurrency(BigDecimal amount, String currency) {
        if (amount == null) return "";
        String cur = (currency == null || currency.isBlank()) ? "SGD" : currency;
        return "$" + amount.toPlainString() + (cur.equalsIgnoreCase("SGD") ? "" : " " + cur);
    }

    private boolean hasAnyLine(String... lines) {
        for (String l : lines) if (!isBlank(l)) return true;
        return false;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }
}
