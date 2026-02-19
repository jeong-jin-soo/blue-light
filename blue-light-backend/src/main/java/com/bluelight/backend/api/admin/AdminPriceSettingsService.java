package com.bluelight.backend.api.admin;

import com.bluelight.backend.api.admin.dto.AdminPriceResponse;
import com.bluelight.backend.api.admin.dto.BatchPriceTierItem;
import com.bluelight.backend.api.admin.dto.BatchUpdatePricesRequest;
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

import java.util.*;
import java.util.stream.Collectors;

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
            // 다른 티어와 kVA 범위 겹침 검증
            var overlapping = masterPriceRepository.findOverlappingTiers(
                    priceSeq, request.getKvaMin(), request.getKvaMax());
            if (!overlapping.isEmpty()) {
                throw new BusinessException(
                        "kVA range overlaps with existing tier: " + overlapping.get(0).getDescription(),
                        HttpStatus.BAD_REQUEST,
                        "KVA_RANGE_OVERLAP"
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
     * 가격 티어 일괄 수정 (생성/수정/삭제를 한번에 처리)
     */
    @Transactional
    public List<AdminPriceResponse> batchUpdatePrices(BatchUpdatePricesRequest request) {
        List<BatchPriceTierItem> tiers = request.getTiers();

        // 1. kvaMin 오름차순 정렬
        List<BatchPriceTierItem> sorted = tiers.stream()
                .sorted(Comparator.comparingInt(BatchPriceTierItem::getKvaMin))
                .toList();

        // 2. 개별 검증: kvaMin <= kvaMax
        for (BatchPriceTierItem tier : sorted) {
            if (tier.getKvaMin() > tier.getKvaMax()) {
                throw new BusinessException(
                        "kVA min cannot be greater than kVA max for tier: " +
                                (tier.getDescription() != null ? tier.getDescription() : tier.getKvaMin() + "-" + tier.getKvaMax()),
                        HttpStatus.BAD_REQUEST,
                        "INVALID_KVA_RANGE"
                );
            }
        }

        // 3. 교차 검증: 중복 및 빈 구간
        for (int i = 0; i < sorted.size() - 1; i++) {
            BatchPriceTierItem curr = sorted.get(i);
            BatchPriceTierItem next = sorted.get(i + 1);

            String currDesc = curr.getDescription() != null ? curr.getDescription() : curr.getKvaMin() + "-" + curr.getKvaMax();
            String nextDesc = next.getDescription() != null ? next.getDescription() : next.getKvaMin() + "-" + next.getKvaMax();

            // 중복 체크
            if (curr.getKvaMax() >= next.getKvaMin()) {
                throw new BusinessException(
                        "kVA range overlap detected between tiers: " + currDesc + " and " + nextDesc,
                        HttpStatus.BAD_REQUEST,
                        "KVA_RANGE_OVERLAP"
                );
            }

            // 빈 구간 체크
            if (curr.getKvaMax() + 1 != next.getKvaMin()) {
                throw new BusinessException(
                        "Gap detected between tiers: " + currDesc + " (max: " + curr.getKvaMax() + ") and " + nextDesc + " (min: " + next.getKvaMin() + ")",
                        HttpStatus.BAD_REQUEST,
                        "KVA_RANGE_GAP"
                );
            }
        }

        // 4. 요청에 포함된 기존 ID 수집
        Set<Long> requestedIds = tiers.stream()
                .filter(t -> t.getMasterPriceSeq() != null)
                .map(BatchPriceTierItem::getMasterPriceSeq)
                .collect(Collectors.toSet());

        // 5. DB의 모든 기존 엔티티 조회
        List<MasterPrice> existingPrices = masterPriceRepository.findAll();
        Map<Long, MasterPrice> existingMap = existingPrices.stream()
                .collect(Collectors.toMap(MasterPrice::getMasterPriceSeq, p -> p));

        // 6. 요청에 없는 기존 티어 → 소프트 삭제
        for (MasterPrice existing : existingPrices) {
            if (!requestedIds.contains(existing.getMasterPriceSeq())) {
                masterPriceRepository.deleteById(existing.getMasterPriceSeq());
                log.info("Price tier soft-deleted: priceSeq={}", existing.getMasterPriceSeq());
            }
        }

        // 7. 수정 및 생성 처리
        for (BatchPriceTierItem tier : tiers) {
            if (tier.getMasterPriceSeq() != null) {
                // 기존 티어 수정
                MasterPrice mp = existingMap.get(tier.getMasterPriceSeq());
                if (mp == null) {
                    throw new BusinessException(
                            "Price tier not found: id=" + tier.getMasterPriceSeq(),
                            HttpStatus.NOT_FOUND,
                            "PRICE_TIER_NOT_FOUND"
                    );
                }
                mp.updatePrice(tier.getPrice());
                mp.updateSldPrice(tier.getSldPrice());
                mp.updateKvaRange(tier.getKvaMin(), tier.getKvaMax(), tier.getDescription());
                mp.setActive(tier.getIsActive());
                log.info("Price tier updated: priceSeq={}, kvaMin={}, kvaMax={}, price={}, sldPrice={}",
                        mp.getMasterPriceSeq(), tier.getKvaMin(), tier.getKvaMax(), tier.getPrice(), tier.getSldPrice());
            } else {
                // 신규 생성
                MasterPrice newPrice = MasterPrice.builder()
                        .description(tier.getDescription())
                        .kvaMin(tier.getKvaMin())
                        .kvaMax(tier.getKvaMax())
                        .price(tier.getPrice())
                        .sldPrice(tier.getSldPrice())
                        .isActive(tier.getIsActive())
                        .build();
                masterPriceRepository.save(newPrice);
                log.info("Price tier created: kvaMin={}, kvaMax={}, price={}, sldPrice={}",
                        tier.getKvaMin(), tier.getKvaMax(), tier.getPrice(), tier.getSldPrice());
            }
        }

        log.info("Batch price update completed: {} tiers processed", tiers.size());
        return getAllPrices();
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
