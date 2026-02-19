package com.bluelight.backend.api.price;

import com.bluelight.backend.api.price.dto.PriceCalculationResponse;
import com.bluelight.backend.api.price.dto.PriceResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Price service
 * - Retrieve active price tiers
 * - Calculate price for a given kVA (including optional SLD fee and EMA fee)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceService {

    private final MasterPriceRepository masterPriceRepository;

    /**
     * Get all active price tiers ordered by kVA min ascending
     */
    public List<PriceResponse> getActivePrices() {
        return masterPriceRepository.findByIsActiveTrueOrderByKvaMinAsc()
                .stream()
                .map(PriceResponse::from)
                .toList();
    }

    /**
     * Calculate price for a given kVA value
     *
     * @param kva       the kVA capacity
     * @param months    licence period in months (3 or 12), null if not applicable
     * @param sldOption SLD option ("REQUEST_LEW" or "SELF_UPLOAD"), null defaults to SELF_UPLOAD
     * @return calculation result with tier, price, SLD fee, EMA fee, and total
     */
    public PriceCalculationResponse calculatePrice(Integer kva, Integer months, String sldOption) {
        if (kva == null || kva < 1) {
            throw new BusinessException("kVA must be a positive number", HttpStatus.BAD_REQUEST, "INVALID_KVA");
        }

        MasterPrice masterPrice = masterPriceRepository.findByKva(kva)
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + kva + " kVA",
                        HttpStatus.NOT_FOUND,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // SLD fee: only when REQUEST_LEW
        BigDecimal sldFee = "REQUEST_LEW".equals(sldOption)
                ? masterPrice.getSldPrice()
                : BigDecimal.ZERO;

        BigDecimal emaFee = (months != null) ? calculateEmaFee(months) : BigDecimal.ZERO;
        BigDecimal totalAmount = masterPrice.getPrice().add(sldFee).add(emaFee);

        log.info("Price calculated: kva={}, tier={}, price={}, sldFee={}, emaFee={}, total={}",
                kva, masterPrice.getDescription(), masterPrice.getPrice(), sldFee, emaFee, totalAmount);

        return PriceCalculationResponse.builder()
                .kva(kva)
                .tierDescription(masterPrice.getDescription())
                .price(masterPrice.getPrice())
                .sldFee(sldFee)
                .emaFee(emaFee)
                .totalAmount(totalAmount)
                .build();
    }

    /**
     * Calculate price (backward compatible — no SLD option)
     */
    public PriceCalculationResponse calculatePrice(Integer kva) {
        return calculatePrice(kva, null, null);
    }

    /**
     * EMA 수수료 계산
     * - 3개월=$50, 12개월=$100
     */
    public BigDecimal calculateEmaFee(int months) {
        if (months == 3) {
            return new BigDecimal("50.00");
        }
        return new BigDecimal("100.00");
    }
}
