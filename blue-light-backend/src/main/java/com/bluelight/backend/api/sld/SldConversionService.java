package com.bluelight.backend.api.sld;

import com.bluelight.backend.api.invoice.InvoiceRegenerationService;
import com.bluelight.backend.api.sld.dto.SldConversionResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationRepository;
import com.bluelight.backend.domain.application.SldOption;
import com.bluelight.backend.domain.application.SldRequest;
import com.bluelight.backend.domain.application.SldRequestRepository;
import com.bluelight.backend.domain.kva.AdjustmentType;
import com.bluelight.backend.domain.kva.AdminPaymentAdjustment;
import com.bluelight.backend.domain.kva.ChangedByRole;
import com.bluelight.backend.domain.kva.KvaAdjustmentRecord;
import com.bluelight.backend.domain.kva.KvaAdjustmentRepository;
import com.bluelight.backend.domain.kva.KvaAdjustmentStatus;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SLD self-upload → LEW 작성(REQUEST_LEW) 전환 공용 서비스.
 *
 * <p>spec: {@code doc/Project Analysis/sld-lew-conversion-fee-spec.md}. 진입점 E1(결제요청 시 LEW 선택,
 * {@code LewReviewService.requestPayment})·E2(사후 전환, {@code SldConversionController}) 공유.</p>
 *
 * <h3>타이밍 분기 (E2 {@link #convertToLewCreated})</h3>
 * <ul>
 *   <li>결제 전(PENDING_REVIEW/REVISION_REQUESTED/PENDING_PAYMENT): quote 갱신만. 정산 원장 없음.</li>
 *   <li>결제 후(PAID/IN_PROGRESS): quote 갱신 + 정산 원장(SLD_ADDED, PENDING) + 인보이스 재발행(보충 청구).</li>
 *   <li>COMPLETED/EXPIRED: 차단(409 SLD_CONVERT_NOT_ALLOWED).</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SldConversionService {

    private static final String SLD_ADDED_REASON =
            "SLD switched to LEW-created (applicant SLD unavailable/invalid)";

    private final ApplicationRepository applicationRepository;
    private final MasterPriceRepository masterPriceRepository;
    private final SldRequestRepository sldRequestRepository;
    private final KvaAdjustmentRepository kvaAdjustmentRepository;
    private final InvoiceRegenerationService invoiceRegenerationService;
    private final com.bluelight.backend.domain.user.UserRepository userRepository;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /**
     * E1, E2-pre: 결제 전 전환. 호출 측 트랜잭션의 managed {@link Application} 을 그대로 변경한다.
     * sldOption→REQUEST_LEW, sldFee 스냅샷, quote 갱신, SldRequest 생성. 원장 없음.
     *
     * @return 가산된 SLD 작성비 (SGD)
     */
    @Transactional
    public BigDecimal applyPrePaymentConversion(Application application) {
        return doSwitch(application).sldFee;
    }

    /**
     * E2: 독립 전환 액션 (LEW/ADMIN). status 에 따라 결제 전/후 분기.
     */
    @Transactional
    public SldConversionResponse convertToLewCreated(Long applicationSeq, Long actorSeq, ChangedByRole role) {
        Application application = applicationRepository.findById(applicationSeq)
                .orElseThrow(() -> new BusinessException(
                        "Application not found", HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND"));

        // COMPLETED/EXPIRED 는 전환 불가 (발급/마감 완료).
        switch (application.getStatus()) {
            case COMPLETED, EXPIRED -> throw new BusinessException(
                    "SLD conversion is not allowed for " + application.getStatus() + " applications",
                    HttpStatus.CONFLICT, "SLD_CONVERT_NOT_ALLOWED");
            default -> { /* proceed */ }
        }

        BigDecimal previousQuote = application.getQuoteAmount();
        Integer kva = application.getSelectedKva();
        SwitchResult r = doSwitch(application);

        boolean postPayment = application.isPostPaymentStatus(); // PAID/IN_PROGRESS (COMPLETED 위에서 차단)
        Long adjustmentSeq = null;

        if (postPayment) {
            // 결제 후 → 보충 청구 원장(SLD_ADDED, PENDING) 기록.
            KvaAdjustmentRecord record = kvaAdjustmentRepository.save(KvaAdjustmentRecord.builder()
                    .application(application)
                    .adjustmentType(AdjustmentType.SLD_ADDED)
                    .previousKva(kva)
                    .newKva(kva)               // kVA 불변
                    .proposedKva(null)
                    .reason(SLD_ADDED_REASON)
                    .status(KvaAdjustmentStatus.APPLIED)
                    .changedByRole(role)
                    .changedByUserSeq(actorSeq)
                    .previousQuoteAmount(previousQuote)
                    .newQuoteAmount(r.newQuote)
                    .amountDifference(r.sldFee)  // 양수 = 추가 청구
                    .masterPriceSeqUsed(r.masterPriceSeq)
                    .adminPaymentAdjustment(AdminPaymentAdjustment.PENDING)
                    .adminAdjustmentAt(LocalDateTime.now())
                    .build());
            adjustmentSeq = record.getAdjustmentSeq();
            invoiceRegenerationService.invalidateAndRegenerate(application,
                    "SLD_ADDED_" + adjustmentSeq,
                    "/api/applications/" + applicationSeq + "/sld/convert-to-lew");
            // 신청자 통보(A-58) + ADMIN 정산 요청(A-59). try-safe — AFTER_COMMIT 발송.
            SldConversionNotifier.dispatchPostPayment(eventPublisher, userRepository, application, r.sldFee);
            log.info("SLD post-payment conversion: applicationSeq={}, adjustmentSeq={}, sldFee={}, role={}",
                    applicationSeq, adjustmentSeq, r.sldFee, role);
        } else {
            // 결제 전 → quote 갱신만 (인보이스 재발행은 SUCCESS 결제 없으면 no-op).
            invoiceRegenerationService.invalidateAndRegenerate(application,
                    "SLD_ADDED_PREPAY",
                    "/api/applications/" + applicationSeq + "/sld/convert-to-lew");
            log.info("SLD pre-payment conversion (E2): applicationSeq={}, sldFee={}, role={}",
                    applicationSeq, r.sldFee, role);
        }

        return SldConversionResponse.builder()
                .applicationSeq(applicationSeq)
                .sldFee(r.sldFee)
                .newQuoteAmount(r.newQuote)
                .postPayment(postPayment)
                .adjustmentSeq(adjustmentSeq)
                .build();
    }

    // ── 내부 ──────────────────────────────────────────────

    private record SwitchResult(BigDecimal sldFee, BigDecimal newQuote, Long masterPriceSeq) {}

    /** 공통: 가격 조회 + 도메인 전환 + SldRequest 보장. */
    private SwitchResult doSwitch(Application application) {
        if (application.getSldOption() == SldOption.REQUEST_LEW) {
            throw new BusinessException(
                    "SLD is already assigned to the LEW (sldOption=REQUEST_LEW)",
                    HttpStatus.CONFLICT, "SLD_ALREADY_LEW");
        }
        MasterPrice masterPrice = masterPriceRepository.findByKva(application.getSelectedKva())
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + application.getSelectedKva() + " kVA",
                        HttpStatus.NOT_FOUND, "PRICE_TIER_NOT_FOUND"));
        BigDecimal sldFee = masterPrice.getSldPrice() != null ? masterPrice.getSldPrice() : BigDecimal.ZERO;
        // SLD 작성비만 기존 견적에 가산한다(다른 항목 재계산 X — amountDifference == sldFee 일관성 보장).
        BigDecimal previousQuote = application.getQuoteAmount() != null
                ? application.getQuoteAmount() : BigDecimal.ZERO;
        BigDecimal newQuote = previousQuote.add(sldFee);

        application.switchSldToLewCreated(sldFee, newQuote);
        ensureSldRequest(application);
        return new SwitchResult(sldFee, newQuote, masterPrice.getMasterPriceSeq());
    }

    /** REQUEST_LEW 작업을 위한 SldRequest 가 없으면 생성(REQUESTED). */
    void ensureSldRequest(Application application) {
        boolean exists = sldRequestRepository
                .findByApplicationApplicationSeq(application.getApplicationSeq())
                .isPresent();
        if (!exists) {
            sldRequestRepository.save(SldRequest.builder()
                    .application(application)
                    .applicantNote(null)
                    .build());
            log.info("SldRequest auto-created on SLD conversion: applicationSeq={}",
                    application.getApplicationSeq());
        }
    }
}
