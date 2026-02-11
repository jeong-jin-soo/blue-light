package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminPriceResponse;
import com.bluelight.backend.api.admin.dto.UpdatePriceRequest;
import com.bluelight.backend.common.exception.BusinessException;
import com.bluelight.backend.domain.price.MasterPrice;
import com.bluelight.backend.domain.price.MasterPriceRepository;
import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin 가격 및 시스템 설정 관리 서비스
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminPriceSettingsService {

    private final MasterPriceRepository masterPriceRepository;
    private final SystemSettingRepository systemSettingRepository;

    /**
     * 모든 가격 티어 조회 (kVA 최소값 오름차순)
     */
    public List<AdminPriceResponse> getAllPrices() {
        return masterPriceRepository.findAll().stream()
                .sorted((a, b) -> a.getKvaMin().compareTo(b.getKvaMin()))
                .map(AdminPriceResponse::from)
                .toList();
    }

    /**
     * 가격 티어 수정
     */
    @Transactional
    public AdminPriceResponse updatePrice(Long priceSeq, UpdatePriceRequest request) {
        MasterPrice masterPrice = masterPriceRepository.findById(priceSeq)
                .orElseThrow(() -> new BusinessException(
                        "Price tier not found",
                        HttpStatus.NOT_FOUND,
                        "PRICE_TIER_NOT_FOUND"
                ));

        // 가격 수정
        masterPrice.updatePrice(request.getPrice());

        // kVA 범위 및 설명 수정
        if (request.getKvaMin() != null && request.getKvaMax() != null) {
            if (request.getKvaMin() > request.getKvaMax()) {
                throw new BusinessException(
                        "kVA min cannot be greater than kVA max",
                        HttpStatus.BAD_REQUEST,
                        "INVALID_KVA_RANGE"
                );
            }
            masterPrice.updateKvaRange(
                    request.getKvaMin(),
                    request.getKvaMax(),
                    request.getDescription()
            );
        } else if (request.getDescription() != null) {
            masterPrice.updateKvaRange(
                    masterPrice.getKvaMin(),
                    masterPrice.getKvaMax(),
                    request.getDescription()
            );
        }

        // 활성화 상태 수정
        if (request.getIsActive() != null) {
            masterPrice.setActive(request.getIsActive());
        }

        log.info("Price tier updated: priceSeq={}, price={}, kvaMin={}, kvaMax={}, isActive={}",
                priceSeq, request.getPrice(), masterPrice.getKvaMin(),
                masterPrice.getKvaMax(), masterPrice.getIsActive());

        return AdminPriceResponse.from(masterPrice);
    }

    /**
     * 시스템 설정 조회
     */
    public Map<String, String> getSettings() {
        Map<String, String> settings = new HashMap<>();
        systemSettingRepository.findAll().forEach(s ->
                settings.put(s.getSettingKey(), s.getSettingValue()));
        return settings;
    }

    /**
     * 시스템 설정 변경
     */
    @Transactional
    public Map<String, String> updateSettings(Map<String, String> updates, Long updatedBy) {
        updates.forEach((key, value) -> {
            SystemSetting setting = systemSettingRepository.findById(key)
                    .orElseThrow(() -> new BusinessException(
                            "Setting not found: " + key, HttpStatus.NOT_FOUND, "SETTING_NOT_FOUND"));
            setting.updateValue(value, updatedBy);
            log.info("Setting updated: key={}, value={}, by={}", key, value, updatedBy);
        });
        return getSettings();
    }
}
