package com.bluelight.backend.api.price;

import com.bluelight.backend.api.price.dto.PriceCalculationResponse;
import com.bluelight.backend.api.price.dto.PriceResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Price API controller (public - no auth required)
 */
@Slf4j
@RestController
@RequestMapping("/api/prices")
@RequiredArgsConstructor
public class PriceController {

    private final PriceService priceService;

    /**
     * Get all active price tiers
     * GET /api/prices
     */
    @GetMapping
    public ResponseEntity<List<PriceResponse>> getPrices() {
        log.info("Price list requested");
        List<PriceResponse> prices = priceService.getActivePrices();
        return ResponseEntity.ok(prices);
    }

    /**
     * Calculate price for a given kVA (with optional licence period for EMA fee)
     * GET /api/prices/calculate?kva=100&months=12
     */
    @GetMapping("/calculate")
    public ResponseEntity<PriceCalculationResponse> calculatePrice(
            @RequestParam Integer kva,
            @RequestParam(required = false) Integer months) {
        log.info("Price calculation requested: kva={}, months={}", kva, months);
        PriceCalculationResponse result = priceService.calculatePrice(kva, months);
        return ResponseEntity.ok(result);
    }
}
