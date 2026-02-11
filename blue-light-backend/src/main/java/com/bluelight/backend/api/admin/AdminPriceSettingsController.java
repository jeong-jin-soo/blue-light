package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminPriceResponse;
import com.bluelight.backend.api.admin.dto.UpdatePriceRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin 가격 및 시스템 설정 API 컨트롤러 (ADMIN only)
 */
@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class AdminPriceSettingsController {

    private final AdminPriceSettingsService adminPriceSettingsService;

    /**
     * Get all price tiers
     * GET /api/admin/prices
     */
    @GetMapping("/prices")
    public ResponseEntity<List<AdminPriceResponse>> getAllPrices() {
        log.info("Admin get all prices");
        List<AdminPriceResponse> prices = adminPriceSettingsService.getAllPrices();
        return ResponseEntity.ok(prices);
    }

    /**
     * Update price tier
     * PUT /api/admin/prices/:id
     */
    @PutMapping("/prices/{id}")
    public ResponseEntity<AdminPriceResponse> updatePrice(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePriceRequest request) {
        log.info("Admin update price: priceSeq={}, price={}", id, request.getPrice());
        AdminPriceResponse response = adminPriceSettingsService.updatePrice(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Get system settings
     * GET /api/admin/settings
     */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, String>> getSettings() {
        log.info("Admin get settings");
        Map<String, String> settings = adminPriceSettingsService.getSettings();
        return ResponseEntity.ok(settings);
    }

    /**
     * Update system settings
     * PATCH /api/admin/settings
     */
    @PatchMapping("/settings")
    public ResponseEntity<Map<String, String>> updateSettings(
            @RequestBody Map<String, String> updates,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Admin update settings: keys={}", updates.keySet());
        Map<String, String> settings = adminPriceSettingsService.updateSettings(updates, userSeq);
        return ResponseEntity.ok(settings);
    }
}
