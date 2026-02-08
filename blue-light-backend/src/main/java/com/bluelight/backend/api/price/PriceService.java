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

import java.util.List;

/**
 * Price service
 * - Retrieve active price tiers
 * - Calculate price for a given kVA
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
     * @param kva the kVA capacity
     * @return calculation result with tier and price
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

        log.info("Price calculated: kva={}, tier={}, price={}", kva, masterPrice.getDescription(), masterPrice.getPrice());

        return PriceCalculationResponse.builder()
                .kva(kva)
                .tierDescription(masterPrice.getDescription())
                .price(masterPrice.getPrice())
                .build();
    }
}
