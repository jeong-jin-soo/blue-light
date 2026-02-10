package com.bluelight.backend.api.price;

import com.bluelight.backend.api.price.dto.PriceCalculationResponse;
import com.bluelight.backend.api.price.dto.PriceResponse;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
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
 * - Calculate price for a given kVA (including service fee)
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceService {

    private final MasterPriceRepository masterPriceRepository;
    private final SystemSettingRepository systemSettingRepository;

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
     * Calculate price for a given kVA value (includes service fee)
     *
     * @param kva the kVA capacity
     * @return calculation result with tier, price, service fee, and total
     */
    public PriceCalculationResponse calculatePrice(Integer kva) {
        if (kva == null || kva < 1) {
            throw new BusinessException("kVA must be a positive number", HttpStatus.BAD_REQUEST, "INVALID_KVA");
        }

        MasterPrice masterPrice = masterPriceRepository.findByKva(kva)
                .orElseThrow(() -> new BusinessException(
                        "No price tier found for " + kva + " kVA",
                        HttpStatus.NOT_FOUND,
                        "PRICE_TIER_NOT_FOUND"
                ));

        BigDecimal serviceFee = getServiceFee();
        BigDecimal totalAmount = masterPrice.getPrice().add(serviceFee);

        log.info("Price calculated: kva={}, tier={}, price={}, serviceFee={}, total={}",
                kva, masterPrice.getDescription(), masterPrice.getPrice(), serviceFee, totalAmount);

        return PriceCalculationResponse.builder()
                .kva(kva)
                .tierDescription(masterPrice.getDescription())
                .price(masterPrice.getPrice())
                .serviceFee(serviceFee)
                .totalAmount(totalAmount)
                .build();
    }

    /**
     * system_settings에서 서비스 수수료 조회
     */
    public BigDecimal getServiceFee() {
        return systemSettingRepository.findById("service_fee")
                .map(s -> new BigDecimal(s.getSettingValue()))
                .orElse(BigDecimal.ZERO);
    }
}
