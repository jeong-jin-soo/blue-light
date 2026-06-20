package com.bluelight.backend.api.price;

import com.bluelight.backend.domain.application.Application;
import com.bluelight.backend.domain.application.ApplicationType;
import com.bluelight.backend.domain.application.SldOption;
import com.bluelight.backend.domain.price.MasterPrice;

import java.math.BigDecimal;

/**
 * 신청 견적(quoteAmount) 재계산 공용 유틸 (SSOT).
 *
 * <p>견적 = tierPrice + calloutFee(NEW) + sldFee(REQUEST_LEW) + emaFee(스냅샷).
 * 결제 후 kVA 사후조정({@code KvaPostPaymentService})과 SLD 전환({@code SldConversionService})이
 * 동일 공식을 공유하도록 단일화한다. EMA 는 신청에 스냅샷된 값({@link Application#getEmaFee()})을 사용한다
 * (생성 시점 스냅샷 보존 — months 재계산이 아님).</p>
 */
public final class QuoteCalculator {

    private QuoteCalculator() {}

    /**
     * 현재 Application 의 필드(applicationType, sldOption, emaFee)와 주어진 master_prices 로 견적을 재계산.
     * 호출 측에서 sldOption 등을 먼저 갱신한 뒤 호출하면 그 상태가 반영된다.
     */
    public static BigDecimal recalculate(Application application, MasterPrice masterPrice) {
        BigDecimal tierPrice = (application.getApplicationType() == ApplicationType.RENEWAL)
                ? masterPrice.getRenewalPrice()
                : masterPrice.getPrice();
        BigDecimal quote = tierPrice;

        // 출장비(call-out fee): New License 에만 가산.
        if (application.getApplicationType() != ApplicationType.RENEWAL
                && masterPrice.getCalloutFee() != null) {
            quote = quote.add(masterPrice.getCalloutFee());
        }

        // SLD 작성비: REQUEST_LEW 일 때만.
        if (application.getSldOption() == SldOption.REQUEST_LEW
                && masterPrice.getSldPrice() != null) {
            quote = quote.add(masterPrice.getSldPrice());
        }

        // EMA 수수료: 신청 스냅샷 사용.
        if (application.getEmaFee() != null) {
            quote = quote.add(application.getEmaFee());
        }

        return quote;
    }
}
