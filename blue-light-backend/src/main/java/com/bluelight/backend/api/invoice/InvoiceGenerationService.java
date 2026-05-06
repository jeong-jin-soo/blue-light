package com.bluelight.backend.api.invoice;

import com.bluelight.backend.api.audit.AuditLogService;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicantType;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.audit.AuditAction;
import com.bluelight.backend.domain.audit.AuditCategory;
import com.bluelight.backend.domain.concierge.ConciergeRequest;
import com.bluelight.backend.domain.concierge.ConciergeRequestRepository;
import com.bluelight.backend.domain.invoice.Invoice;
import com.bluelight.backend.domain.invoice.InvoiceRepository;
import com.bluelight.backend.domain.payment.Payment;
import com.bluelight.backend.domain.payment.PaymentReferenceType;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import com.bluelight.backend.domain.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * E-Invoice 발행/재발행 서비스 (invoice-spec §5, §8).
 *
 * <p>자동 발행: {@link #generateFromPayment(Payment, Application)} — 결제 확인 직후 호출.</p>
 * <p>재발행: {@link #regenerate(Long, Long, String)} — PDF만 재생성 (스냅샷 불변).</p>
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class InvoiceGenerationService {

    private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final InvoiceRepository invoiceRepository;
    private final InvoiceNumberGenerator invoiceNumberGenerator;
    private final InvoicePdfRenderer invoicePdfRenderer;
    private final SystemSettingRepository systemSettingRepository;
    private final AuditLogService auditLogService;
    // ★ Concierge 강화 + 별도 수금 PR-1: referenceType 분기 dispatcher 가 사용.
    // PR-1 은 분기만 도입하고, CONCIERGE_REQUEST 경로의 실제 스냅샷 구성은 PR-2 에서 구현.
    private final ApplicationRepository applicationRepository;
    private final ConciergeRequestRepository conciergeRequestRepository;

    /**
     * 결제 확인 직후 자동 발행.
     * Payment별 1건 (unique) — 중복 발행 시 {@code INVOICE_ALREADY_EXISTS}.
     */
    public Invoice generateFromPayment(Payment payment, Application application) {
        if (payment == null || payment.getPaymentSeq() == null) {
            throw new BusinessException("Payment must be persisted before invoice generation",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        if (application == null || application.getApplicationSeq() == null) {
            throw new BusinessException("Application required for invoice generation",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        // 활성(ACTIVE) 영수증만 중복 발행 차단. INVALIDATED 영수증이 있으면 같은 payment 에 대해
        // 신규 영수증을 발행할 수 있다 (kva-postpayment-adjustment-spec.md §10 D3).
        if (invoiceRepository.existsByPaymentSeqAndStatus(payment.getPaymentSeq(), "ACTIVE")) {
            throw new BusinessException(
                    "Active invoice already exists for payment " + payment.getPaymentSeq(),
                    HttpStatus.CONFLICT,
                    "INVOICE_ALREADY_EXISTS");
        }

        // ── 1) 스냅샷 구성 ──
        User recipient = application.getUser();
        if (recipient == null) {
            throw new BusinessException("Application recipient (User) is missing",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }

        String invoiceNumber = invoiceNumberGenerator.next(LocalDate.now());

        // ── 2) Invoice 엔티티 1차 구성 (pdfFileSeq는 이후 세팅) ──
        Invoice draft = buildInvoiceSnapshot(invoiceNumber, payment, application, recipient);

        // ── 3) PDF 렌더 → fileSeq 확정 ──
        Long pdfFileSeq = invoicePdfRenderer.render(draft);

        // ── 4) 최종 Invoice (pdfFileSeq 포함) 빌드 & save ──
        Invoice persistable = rebuildWithPdfFile(draft, pdfFileSeq);
        Invoice saved = invoiceRepository.save(persistable);

        // ── 5) AuditLog ──
        auditLogService.log(
                null, null, null,
                AuditAction.INVOICE_GENERATED,
                AuditCategory.APPLICATION,
                "Invoice",
                String.valueOf(saved.getInvoiceSeq()),
                "Invoice auto-generated for payment " + payment.getPaymentSeq()
                        + " (number=" + saved.getInvoiceNumber() + ")",
                null, null, null, null, null, null, null);

        log.info("Invoice generated: invoiceSeq={}, number={}, paymentSeq={}",
                saved.getInvoiceSeq(), saved.getInvoiceNumber(), payment.getPaymentSeq());

        return saved;
    }

    /**
     * ★ Concierge 강화 + 별도 수금 PR-1 — 다형 referenceType 진입점.
     * <p>
     * 결제 1건만 받아 referenceType 에 따라 적절한 스냅샷 빌더로 라우팅한다.
     * 본 PR-1 은 dispatcher 골격만 도입하고, CONCIERGE_REQUEST 경로의 실제 스냅샷은 PR-2 에서 구현.
     * 기존 호출처({@link com.bluelight.backend.api.admin.AdminPaymentService},
     * {@link com.bluelight.backend.service.kva.KvaPostPaymentService})는
     * {@link #generateFromPayment(Payment, Application)} 두 인자 형태를 그대로 사용 — 회귀 영향 없음.
     *
     * @param payment 영수증 발행 대상 결제 (referenceType/referenceSeq 필수)
     * @return 발행된 Invoice
     * @throws BusinessException APPLICATION 인데 referenceSeq 가 무효한 경우 또는
     *                           CONCIERGE_REQUEST 경로가 PR-2 이전 호출된 경우
     *                           (UnsupportedOperationException 대신 INVALID_ARGUMENT 비즈니스 예외).
     */
    public Invoice generateFromPayment(Payment payment) {
        if (payment == null) {
            throw new BusinessException("Payment is required",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        PaymentReferenceType refType = payment.getReferenceType();
        if (refType == null) {
            // 매우 오래된 데이터(PR#7 마이그레이션 이전) — APPLICATION 으로 가정.
            refType = PaymentReferenceType.APPLICATION;
        }
        return switch (refType) {
            case APPLICATION -> {
                Long appSeq = payment.getReferenceSeq() != null
                        ? payment.getReferenceSeq()
                        : (payment.getApplication() != null ? payment.getApplication().getApplicationSeq() : null);
                if (appSeq == null) {
                    throw new BusinessException(
                            "APPLICATION payment has no reference seq",
                            HttpStatus.BAD_REQUEST,
                            "INVALID_ARGUMENT");
                }
                Application application = payment.getApplication() != null
                        ? payment.getApplication()
                        : applicationRepository.findById(appSeq)
                            .orElseThrow(() -> new BusinessException(
                                    "Application not found: " + appSeq,
                                    HttpStatus.NOT_FOUND,
                                    "APPLICATION_NOT_FOUND"));
                yield generateFromPayment(payment, application);
            }
            case CONCIERGE_REQUEST -> {
                ConciergeRequest cr = conciergeRequestRepository.findById(payment.getReferenceSeq())
                        .orElseThrow(() -> new BusinessException(
                                "ConciergeRequest not found: " + payment.getReferenceSeq(),
                                HttpStatus.NOT_FOUND,
                                "CONCIERGE_REQUEST_NOT_FOUND"));
                // PR-1: placeholder — PR-2 에서 buildSnapshotForConciergeRequest 채움.
                yield buildSnapshotForConciergeRequest(payment, cr);
            }
            case SLD_ORDER -> {
                // SLD_ORDER 결제는 현재 별도 SldOrderPayment 엔티티가 처리 — 본 진입점에 도달할 일이
                // 없지만 안전 가드로 기록만 남기고 동일한 NOT_IMPLEMENTED 패턴 적용.
                throw new BusinessException(
                        "SLD_ORDER invoice generation via Payment is not supported in PR-1 — "
                                + "use SldOrderPayment flow instead",
                        HttpStatus.NOT_IMPLEMENTED,
                        "INVOICE_PATH_NOT_SUPPORTED");
            }
        };
    }

    /**
     * APPLICATION 결제의 스냅샷 빌더 (extracted for clarity).
     * 기존 {@link #generateFromPayment(Payment, Application)} 와 동일 동작이며, 향후 dispatcher 가
     * APPLICATION 분기에서 호출한다 (현재는 dispatcher 가 두 인자 메서드를 위임 호출).
     *
     * <p>※ PR-1 에서는 스펙대로 분리만 해두고, 본 메서드는 현 시점 직접 호출되지 않는다 — 기존
     * 두 인자 메서드 시그니처와 호출처를 보존하기 위해 그대로 둔다.</p>
     */
    Invoice buildSnapshotForApplication(Payment payment, Application application) {
        return generateFromPayment(payment, application);
    }

    /**
     * ★ Concierge 강화 + 별도 수금 + 영수증 자동 발행 PR-2 — CONCIERGE_REQUEST 결제 영수증 스냅샷 빌더.
     * <p>
     * APPLICATION 결제와 동일한 발행자(회사) 스냅샷 + PayNow + footer 를 사용하지만, 빌링·설치 주소·
     * description 은 ConciergeRequest 도메인에서 추출한다.
     * <ul>
     *   <li>billingRecipient: {@code ConciergeRequest.applicantUser.fullName} 우선,
     *       fallback {@code submitterName} (스냅샷)</li>
     *   <li>billingAddress: ConciergeRequest 폼에는 주소 컬럼이 없어 비워둠 (R6 위험 — 후속 PR 에서 보완 예정).</li>
     *   <li>installation: 비워둠 (Concierge 서비스 자체 — 물리 설치 주소 없음)</li>
     *   <li>description: {@code "Concierge service fee — request <publicCode>"}</li>
     * </ul>
     * ACTIVE 영수증 중복 차단(스펙 §10 D3 — kVA invalidation 케이스 대비)은 동일하게 적용.
     */
    Invoice buildSnapshotForConciergeRequest(Payment payment, ConciergeRequest conciergeRequest) {
        if (payment == null || payment.getPaymentSeq() == null) {
            throw new BusinessException("Payment must be persisted",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        if (conciergeRequest == null || conciergeRequest.getConciergeRequestSeq() == null) {
            throw new BusinessException("ConciergeRequest must be persisted",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        // 활성(ACTIVE) 영수증만 중복 발행 차단 — APPLICATION 분기와 동일 정책.
        if (invoiceRepository.existsByPaymentSeqAndStatus(payment.getPaymentSeq(), "ACTIVE")) {
            throw new BusinessException(
                    "Active invoice already exists for payment " + payment.getPaymentSeq(),
                    HttpStatus.CONFLICT,
                    "INVOICE_ALREADY_EXISTS");
        }

        User recipient = conciergeRequest.getApplicantUser();
        if (recipient == null) {
            throw new BusinessException("ConciergeRequest applicant user is missing",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }

        String invoiceNumber = invoiceNumberGenerator.next(LocalDate.now());

        // ── 1) 스냅샷 1차 구성 (pdfFileSeq=null) ──
        Invoice draft = buildConciergeInvoiceSnapshot(invoiceNumber, payment, conciergeRequest, recipient);

        // ── 2) PDF 렌더 → fileSeq 확정 ──
        Long pdfFileSeq = invoicePdfRenderer.render(draft);

        // ── 3) 최종 Invoice 빌드 & save ──
        Invoice persistable = rebuildWithPdfFile(draft, pdfFileSeq);
        Invoice saved = invoiceRepository.save(persistable);

        // ── 4) AuditLog ──
        auditLogService.log(
                null, null, null,
                AuditAction.INVOICE_GENERATED,
                AuditCategory.APPLICATION,
                "Invoice",
                String.valueOf(saved.getInvoiceSeq()),
                "Invoice auto-generated for concierge payment " + payment.getPaymentSeq()
                        + " (number=" + saved.getInvoiceNumber() + ", publicCode=" + conciergeRequest.getPublicCode() + ")",
                null, null, null, null, null, null, null);

        log.info("Concierge invoice generated: invoiceSeq={}, number={}, paymentSeq={}, conciergeRequestSeq={}",
                saved.getInvoiceSeq(), saved.getInvoiceNumber(), payment.getPaymentSeq(),
                conciergeRequest.getConciergeRequestSeq());

        return saved;
    }

    /**
     * Concierge 결제용 Invoice 스냅샷 (pdfFileSeq=null 인 draft).
     * 발행자/통화/PayNow 는 Application 분기와 동일하게 system_settings 에서 로드.
     */
    private Invoice buildConciergeInvoiceSnapshot(String invoiceNumber, Payment payment,
                                                   ConciergeRequest cr, User recipient) {
        BigDecimal totalAmount = payment.getAmount();
        BigDecimal rateAmount = totalAmount;

        // 빌링: applicantUser.fullName 우선, fallback submitterName 스냅샷.
        String billingName = firstNonBlank(recipient.getFullName(), cr.getSubmitterName());
        if (billingName == null || billingName.isBlank()) {
            billingName = "Concierge Customer"; // 절대 null 방지 (NOT NULL 컬럼).
        }

        String description = "Concierge service fee — request " + cr.getPublicCode();

        Long paynowQrFileSeq = resolveSettingLong("invoice_paynow_qr_file_seq");
        String paynowUen = firstNonBlank(
                resolveSetting("invoice_paynow_uen"),
                resolveSetting("invoice_company_uen"));

        String currency = firstNonBlank(resolveSetting("invoice_currency"), "SGD");

        return Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .paymentSeq(payment.getPaymentSeq())
                .referenceType(PaymentReferenceType.CONCIERGE_REQUEST.name())
                .referenceSeq(cr.getConciergeRequestSeq())
                .applicationSeq(null) // Concierge 결제는 application FK 없음
                .recipientUserSeq(recipient.getUserSeq())
                .issuedByUserSeq(null) // 자동 발행
                .issuedAt(LocalDateTime.now())
                .totalAmount(totalAmount)
                .qtySnapshot(1)
                .rateAmountSnapshot(rateAmount)
                .currencySnapshot(currency)
                .companyNameSnapshot(resolveSetting("invoice_company_name"))
                .companyAliasSnapshot(resolveSetting("invoice_company_alias"))
                .companyUenSnapshot(resolveSetting("invoice_company_uen"))
                .companyAddressLine1Snapshot(resolveSetting("invoice_company_address_line1"))
                .companyAddressLine2Snapshot(resolveSetting("invoice_company_address_line2"))
                .companyAddressLine3Snapshot(resolveSetting("invoice_company_address_line3"))
                .companyEmailSnapshot(resolveSetting("invoice_company_email"))
                .companyWebsiteSnapshot(resolveSetting("invoice_company_website"))
                .billingRecipientNameSnapshot(billingName)
                .billingRecipientCompanySnapshot(null) // Concierge 폼은 회사 정보 미수집
                .billingAddressLine1Snapshot(null) // R6: 주소 컬럼 부재 — 후속 PR 에서 추가 검토
                .billingAddressLine2Snapshot(null)
                .billingAddressLine3Snapshot(null)
                .billingAddressLine4Snapshot(null)
                .installationNameSnapshot(null) // 컨시어지 서비스 자체엔 설치 위치 없음
                .installationAddressLine1Snapshot(null)
                .installationAddressLine2Snapshot(null)
                .installationAddressLine3Snapshot(null)
                .installationAddressLine4Snapshot(null)
                .descriptionSnapshot(description)
                .paynowUenSnapshot(paynowUen)
                .paynowQrFileSeqSnapshot(paynowQrFileSeq)
                .footerNoteSnapshot(resolveSetting("invoice_footer_note"))
                .pdfFileSeq(null) // 렌더러에서 확정 후 rebuild
                .build();
    }

    /**
     * Admin 요청으로 PDF만 재생성 (스냅샷은 불변).
     * invoice-spec §8 재발행 정책.
     */
    public Invoice regenerate(Long invoiceSeq, Long adminUserSeq, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("Regeneration reason is required",
                    HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT");
        }
        Invoice invoice = invoiceRepository.findById(invoiceSeq)
                .orElseThrow(() -> new BusinessException(
                        "Invoice not found: " + invoiceSeq,
                        HttpStatus.NOT_FOUND,
                        "INVOICE_NOT_FOUND"));

        Long newPdfFileSeq = invoicePdfRenderer.render(invoice);
        invoice.replacePdfFile(newPdfFileSeq, adminUserSeq);
        // 변경 감지로 pdfFileSeq UPDATE

        auditLogService.log(
                adminUserSeq, null, null,
                AuditAction.INVOICE_REGENERATED,
                AuditCategory.ADMIN,
                "Invoice",
                String.valueOf(invoice.getInvoiceSeq()),
                "Invoice PDF regenerated: " + reason,
                null, null, null, null, null, null, null);

        log.info("Invoice regenerated: invoiceSeq={}, newPdfFileSeq={}, by user {}",
                invoice.getInvoiceSeq(), newPdfFileSeq, adminUserSeq);

        return invoice;
    }

    // ── 내부 헬퍼 ────────────────────────────────────────────────────────────

    private Invoice buildInvoiceSnapshot(String invoiceNumber, Payment payment,
                                         Application application, User recipient) {
        String referenceType = payment.getReferenceType() != null
                ? payment.getReferenceType().name() : "APPLICATION";

        BigDecimal totalAmount = payment.getAmount();
        BigDecimal rateAmount = totalAmount; // qty=1 고정 Phase 1

        // 빌링(To:) 블록
        String billingName = firstNonBlank(
                application.getLoaApplicantNameSnapshot(),
                recipient.getFullName());
        String billingCompany = application.getApplicantType() == ApplicantType.CORPORATE
                ? firstNonBlank(application.getLoaCompanyNameSnapshot(), recipient.getCompanyName())
                : null;

        // Correspondence 우선, 비면 installation 재사용
        String[] billingAddrLines = resolveBillingAddressLines(application);

        // 설치 주소 블록
        String[] installAddrLines = resolveInstallationAddressLines(application);
        String installationName = application.getApplicantType() == ApplicantType.CORPORATE
                ? firstNonBlank(application.getLoaCompanyNameSnapshot(), recipient.getCompanyName())
                : null;

        String description = buildDescription(application);

        Long paynowQrFileSeq = resolveSettingLong("invoice_paynow_qr_file_seq");
        String paynowUen = firstNonBlank(
                resolveSetting("invoice_paynow_uen"),
                resolveSetting("invoice_company_uen"));

        String currency = firstNonBlank(resolveSetting("invoice_currency"), "SGD");

        return Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .paymentSeq(payment.getPaymentSeq())
                .referenceType(referenceType)
                .referenceSeq(payment.getReferenceSeq() != null
                        ? payment.getReferenceSeq() : application.getApplicationSeq())
                .applicationSeq(application.getApplicationSeq())
                .recipientUserSeq(recipient.getUserSeq())
                .issuedByUserSeq(null) // 자동 발행
                .issuedAt(LocalDateTime.now())
                .totalAmount(totalAmount)
                .qtySnapshot(1)
                .rateAmountSnapshot(rateAmount)
                .currencySnapshot(currency)
                .companyNameSnapshot(resolveSetting("invoice_company_name"))
                .companyAliasSnapshot(resolveSetting("invoice_company_alias"))
                .companyUenSnapshot(resolveSetting("invoice_company_uen"))
                .companyAddressLine1Snapshot(resolveSetting("invoice_company_address_line1"))
                .companyAddressLine2Snapshot(resolveSetting("invoice_company_address_line2"))
                .companyAddressLine3Snapshot(resolveSetting("invoice_company_address_line3"))
                .companyEmailSnapshot(resolveSetting("invoice_company_email"))
                .companyWebsiteSnapshot(resolveSetting("invoice_company_website"))
                .billingRecipientNameSnapshot(billingName)
                .billingRecipientCompanySnapshot(billingCompany)
                .billingAddressLine1Snapshot(arrIdx(billingAddrLines, 0))
                .billingAddressLine2Snapshot(arrIdx(billingAddrLines, 1))
                .billingAddressLine3Snapshot(arrIdx(billingAddrLines, 2))
                .billingAddressLine4Snapshot(arrIdx(billingAddrLines, 3))
                .installationNameSnapshot(installationName)
                .installationAddressLine1Snapshot(arrIdx(installAddrLines, 0))
                .installationAddressLine2Snapshot(arrIdx(installAddrLines, 1))
                .installationAddressLine3Snapshot(arrIdx(installAddrLines, 2))
                .installationAddressLine4Snapshot(arrIdx(installAddrLines, 3))
                .descriptionSnapshot(description)
                .paynowUenSnapshot(paynowUen)
                .paynowQrFileSeqSnapshot(paynowQrFileSeq)
                .footerNoteSnapshot(resolveSetting("invoice_footer_note"))
                .pdfFileSeq(null) // 렌더러에서 확정 후 rebuild
                .build();
    }

    /**
     * draft Invoice(pdfFileSeq=null)에 렌더 결과 fileSeq를 주입한 새 Invoice 반환.
     * Invoice는 불변 스냅샷이므로 setter 없이 Builder로 재구성한다.
     */
    private Invoice rebuildWithPdfFile(Invoice draft, Long pdfFileSeq) {
        return Invoice.builder()
                .invoiceNumber(draft.getInvoiceNumber())
                .paymentSeq(draft.getPaymentSeq())
                .referenceType(draft.getReferenceType())
                .referenceSeq(draft.getReferenceSeq())
                .applicationSeq(draft.getApplicationSeq())
                .recipientUserSeq(draft.getRecipientUserSeq())
                .issuedByUserSeq(draft.getIssuedByUserSeq())
                .issuedAt(draft.getIssuedAt())
                .totalAmount(draft.getTotalAmount())
                .qtySnapshot(draft.getQtySnapshot())
                .rateAmountSnapshot(draft.getRateAmountSnapshot())
                .currencySnapshot(draft.getCurrencySnapshot())
                .companyNameSnapshot(draft.getCompanyNameSnapshot())
                .companyAliasSnapshot(draft.getCompanyAliasSnapshot())
                .companyUenSnapshot(draft.getCompanyUenSnapshot())
                .companyAddressLine1Snapshot(draft.getCompanyAddressLine1Snapshot())
                .companyAddressLine2Snapshot(draft.getCompanyAddressLine2Snapshot())
                .companyAddressLine3Snapshot(draft.getCompanyAddressLine3Snapshot())
                .companyEmailSnapshot(draft.getCompanyEmailSnapshot())
                .companyWebsiteSnapshot(draft.getCompanyWebsiteSnapshot())
                .billingRecipientNameSnapshot(draft.getBillingRecipientNameSnapshot())
                .billingRecipientCompanySnapshot(draft.getBillingRecipientCompanySnapshot())
                .billingAddressLine1Snapshot(draft.getBillingAddressLine1Snapshot())
                .billingAddressLine2Snapshot(draft.getBillingAddressLine2Snapshot())
                .billingAddressLine3Snapshot(draft.getBillingAddressLine3Snapshot())
                .billingAddressLine4Snapshot(draft.getBillingAddressLine4Snapshot())
                .installationNameSnapshot(draft.getInstallationNameSnapshot())
                .installationAddressLine1Snapshot(draft.getInstallationAddressLine1Snapshot())
                .installationAddressLine2Snapshot(draft.getInstallationAddressLine2Snapshot())
                .installationAddressLine3Snapshot(draft.getInstallationAddressLine3Snapshot())
                .installationAddressLine4Snapshot(draft.getInstallationAddressLine4Snapshot())
                .descriptionSnapshot(draft.getDescriptionSnapshot())
                .paynowUenSnapshot(draft.getPaynowUenSnapshot())
                .paynowQrFileSeqSnapshot(draft.getPaynowQrFileSeqSnapshot())
                .footerNoteSnapshot(draft.getFooterNoteSnapshot())
                .pdfFileSeq(pdfFileSeq)
                .build();
    }

    /** Description 본문 — ApplicationType 별 표준 문구 (invoice-spec §7). */
    private String buildDescription(Application app) {
        ApplicationType type = app.getApplicationType();
        if (type == ApplicationType.RENEWAL) {
            Integer months = app.getRenewalPeriodMonths();
            LocalDate expiry = app.getExistingExpiryDate();
            StringBuilder sb = new StringBuilder("Renewal of EMA license");
            if (months != null) {
                sb.append(" for ").append(months).append(" months");
            }
            if (expiry != null) {
                LocalDate start = expiry.plusDays(1);
                LocalDate end = (months != null) ? expiry.plusMonths(months) : null;
                sb.append(" for the period of ").append(start.format(PERIOD_FORMAT));
                if (end != null) {
                    sb.append(" to ").append(end.format(PERIOD_FORMAT));
                }
            }
            return sb.toString();
        }
        return "New EMA license application";
    }

    /** Layer B 5-part correspondence 우선, 없으면 installation 주소 4행으로 fallback. */
    private String[] resolveBillingAddressLines(Application app) {
        String block = app.getCorrespondenceAddressBlock();
        String unit = app.getCorrespondenceAddressUnit();
        String street = app.getCorrespondenceAddressStreet();
        String building = app.getCorrespondenceAddressBuilding();
        String postal = app.getCorrespondenceAddressPostalCode();
        if (anyNotBlank(block, unit, street, building, postal)) {
            return composeAddressLines(block, unit, street, building, postal);
        }
        // fallback: installation 5-part
        return resolveInstallationAddressLines(app);
    }

    /** Layer B 5-part installation 우선, 없으면 application.address 단일 문자열. */
    private String[] resolveInstallationAddressLines(Application app) {
        String block = app.getInstallationAddressBlock();
        String unit = app.getInstallationAddressUnit();
        String street = app.getInstallationAddressStreet();
        String building = app.getInstallationAddressBuilding();
        String postal = app.getInstallationAddressPostalCode();
        if (anyNotBlank(block, unit, street, building, postal)) {
            return composeAddressLines(block, unit, street, building, postal);
        }
        // legacy 단일 주소 → 1행
        String legacy = app.getAddress();
        String legacyPostal = app.getPostalCode();
        String[] out = new String[4];
        out[0] = isBlank(legacy) ? null : legacy;
        out[1] = isBlank(legacyPostal) ? null : "SINGAPORE " + legacyPostal;
        return out;
    }

    /**
     * 5-part 주소를 최대 4행으로 배치.
     * 행1: block+unit / 행2: street / 행3: building / 행4: SINGAPORE + postal.
     */
    private String[] composeAddressLines(String block, String unit, String street,
                                         String building, String postal) {
        String[] out = new String[4];
        StringBuilder line1 = new StringBuilder();
        if (!isBlank(block)) line1.append(block);
        if (!isBlank(unit)) {
            if (line1.length() > 0) line1.append(" ");
            line1.append(unit);
        }
        out[0] = line1.length() > 0 ? line1.toString() : null;
        out[1] = isBlank(street) ? null : street;
        out[2] = isBlank(building) ? null : building;
        out[3] = isBlank(postal) ? null : "SINGAPORE " + postal;
        return out;
    }

    private String resolveSetting(String key) {
        return systemSettingRepository.findById(key)
                .map(SystemSetting::getSettingValue)
                .filter(v -> v != null && !v.isBlank())
                .orElse(null);
    }

    private Long resolveSettingLong(String key) {
        String raw = resolveSetting(key);
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("Setting {} is not a valid long value: {}", key, raw);
            return null;
        }
    }

    private String firstNonBlank(String... cand) {
        for (String c : cand) {
            if (c != null && !c.isBlank()) return c;
        }
        return null;
    }

    private boolean anyNotBlank(String... s) {
        for (String v : s) if (v != null && !v.isBlank()) return true;
        return false;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private String arrIdx(String[] arr, int idx) {
        return (arr != null && idx < arr.length) ? arr[idx] : null;
    }
}
