package com.bluelight.backend.api.admin.manualemail;

import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * ADMIN 수동 이메일 system_settings 로더 (PR-4).
 *
 * <p>스펙: {@code doc/Project Analysis/admin-manual-email-spec.md} §13.3, §8.4 / AC-A12.
 * 설계 원칙: CLAUDE.md "설정 우선" — daily cap / category suggestions 모두 하드코딩 금지.</p>
 *
 * <h3>키 목록</h3>
 * <ul>
 *   <li>{@code admin_manual_email_daily_cap} (기본 100): ADMIN 1인당 일 발송 한도. 정수.</li>
 *   <li>{@code admin_manual_email_category_suggestions} (기본 "PAYMENT_NOTICE,MAINTENANCE,INFO,MISC"):
 *       Compose UI 카테고리 추천 드롭다운 옵션 (CSV).</li>
 * </ul>
 *
 * <h3>로드 정책</h3>
 * <p>매 호출 시 DB 한 번 조회 — 캐싱은 도입하지 않는다. system_settings row 는 sub-millisecond
 * 단위로 조회되며, daily cap 가드는 분당 수 회 수준이라 부하가 없다. 운영자가 settings 를
 * 변경하면 즉시 반영된다 (캐시 무효화 로직 불필요).</p>
 *
 * <p>row 미존재/파싱 실패 시 기본값을 반환하고 WARN 로그만 남긴다 — 운영 중단보다
 * graceful fallback 우선.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ManualEmailSettings {

    /** system_settings 키 — daily cap (정수, 기본 100). */
    public static final String KEY_DAILY_CAP = "admin_manual_email_daily_cap";

    /** system_settings 키 — category suggestions (CSV 문자열). */
    public static final String KEY_CATEGORY_SUGGESTIONS = "admin_manual_email_category_suggestions";

    /** Daily cap 기본값 — system_settings 미존재/파싱 실패 시 폴백 (D5=B 추천 100). */
    public static final int DEFAULT_DAILY_CAP = 100;

    /** Daily cap 하한 — 음수/0 거부, 안전망. */
    static final int DAILY_CAP_MIN = 1;

    /** Category suggestions 기본값 — UI 가 동일 폴백을 사용하므로 양쪽 일치 보장. */
    static final List<String> DEFAULT_CATEGORY_SUGGESTIONS =
            List.of("PAYMENT_NOTICE", "MAINTENANCE", "INFO", "MISC");

    private final SystemSettingRepository systemSettingRepository;

    /**
     * Daily cap 로드. system_settings row 미존재/파싱 실패 시 {@link #DEFAULT_DAILY_CAP} 반환.
     */
    public int loadDailyCap() {
        return systemSettingRepository.findById(KEY_DAILY_CAP)
                .map(SystemSetting::getSettingValue)
                .map(this::parseDailyCap)
                .orElse(DEFAULT_DAILY_CAP);
    }

    /**
     * Category suggestions 로드 — CSV 를 trim + 빈 항목 제거 + 중복 제거 후 List 반환.
     * row 미존재/빈 값 시 {@link #DEFAULT_CATEGORY_SUGGESTIONS}.
     */
    public List<String> loadCategorySuggestions() {
        return systemSettingRepository.findById(KEY_CATEGORY_SUGGESTIONS)
                .map(SystemSetting::getSettingValue)
                .map(this::parseCsvSuggestions)
                .filter(list -> !list.isEmpty())
                .orElse(DEFAULT_CATEGORY_SUGGESTIONS);
    }

    private int parseDailyCap(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_DAILY_CAP;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < DAILY_CAP_MIN) {
                log.warn("Manual email daily cap value {} below minimum {} — using default {}",
                        parsed, DAILY_CAP_MIN, DEFAULT_DAILY_CAP);
                return DEFAULT_DAILY_CAP;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("Manual email daily cap parse failed: '{}' — using default {}",
                    raw, DEFAULT_DAILY_CAP);
            return DEFAULT_DAILY_CAP;
        }
    }

    private List<String> parseCsvSuggestions(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        // trim + non-blank + 중복 제거 (입력 순서 유지).
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(ordered::add);
        return List.copyOf(ordered);
    }
}
