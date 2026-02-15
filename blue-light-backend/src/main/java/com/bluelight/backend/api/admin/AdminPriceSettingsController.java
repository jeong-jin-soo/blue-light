package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminPriceResponse;
import com.bluelight.backend.api.admin.dto.BatchUpdatePricesRequest;
import com.bluelight.backend.api.admin.dto.UpdatePriceRequest;
import com.bluelight.backend.api.file.FileStorageService;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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
    private final FileStorageService fileStorageService;
    private final SystemSettingRepository systemSettingRepository;

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
     * Batch update price tiers (create, update, delete in one request)
     * PUT /api/admin/prices/batch
     */
    @PutMapping("/prices/batch")
    public ResponseEntity<List<AdminPriceResponse>> batchUpdatePrices(
            @Valid @RequestBody BatchUpdatePricesRequest request) {
        log.info("Admin batch update prices: {} tiers", request.getTiers().size());
        List<AdminPriceResponse> response = adminPriceSettingsService.batchUpdatePrices(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Update price tier
     * PUT /api/admin/prices/:id
     */
    @PutMapping("/prices/{id:\\d+}")
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

    /**
     * PayNow QR 이미지 업로드
     * POST /api/admin/settings/payment-qr
     */
    @PostMapping("/settings/payment-qr")
    public ResponseEntity<Map<String, String>> uploadPaymentQr(
            @RequestParam("file") MultipartFile file,
            Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Admin upload PayNow QR image: filename={}, size={}", file.getOriginalFilename(), file.getSize());

        // 이미지 파일 검증
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only image files are allowed"));
        }

        // 기존 QR 파일 삭제
        systemSettingRepository.findById("payment_paynow_qr").ifPresent(setting -> {
            String oldPath = setting.getSettingValue();
            if (oldPath != null && !oldPath.isBlank()) {
                try {
                    fileStorageService.delete(oldPath);
                    log.info("Old QR image deleted: {}", oldPath);
                } catch (Exception e) {
                    log.warn("Failed to delete old QR image: {}", oldPath, e);
                }
            }
        });

        // 새 파일 저장
        String filePath = fileStorageService.store(file, "settings");

        // DB 설정 업데이트
        SystemSetting setting = systemSettingRepository.findById("payment_paynow_qr")
                .orElseGet(() -> new SystemSetting("payment_paynow_qr", "", "PayNow QR code image file path"));
        setting.updateValue(filePath, userSeq);
        systemSettingRepository.save(setting);

        log.info("PayNow QR image uploaded: {}", filePath);
        return ResponseEntity.ok(Map.of("filePath", filePath, "url", "/api/public/payment-qr"));
    }

    /**
     * PayNow QR 이미지 삭제
     * DELETE /api/admin/settings/payment-qr
     */
    @DeleteMapping("/settings/payment-qr")
    public ResponseEntity<Void> deletePaymentQr(Authentication authentication) {
        Long userSeq = (Long) authentication.getPrincipal();
        log.info("Admin delete PayNow QR image");

        systemSettingRepository.findById("payment_paynow_qr").ifPresent(setting -> {
            String oldPath = setting.getSettingValue();
            if (oldPath != null && !oldPath.isBlank()) {
                try {
                    fileStorageService.delete(oldPath);
                    log.info("QR image deleted: {}", oldPath);
                } catch (Exception e) {
                    log.warn("Failed to delete QR image file: {}", oldPath, e);
                }
            }
            setting.updateValue("", userSeq);
            systemSettingRepository.save(setting);
        });

        return ResponseEntity.noContent().build();
    }
}
