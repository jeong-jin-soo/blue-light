package com.bluelight.backend.api.admin;

import com.bluelight.backend.domain.setting.SystemSetting;
import com.bluelight.backend.domain.setting.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EMA 제출 추적 system_settings 로더.
 *
 * <p>스펙: {@code doc/Project Analysis/ema-submission-tracking-spec.md} §5.4.
 * 설계 원칙: CLAUDE.md "설정 우선" — {@code ema.ack.required} / {@code ema.reminder.days} 는
 * 운영 가변 값이라 하드코딩 금지, 반드시 system_settings 에서 조회한다.</p>
 *
 * <h3>키 목록</h3>
 * <ul>
 *   <li>{@code ema.ack.required} (기본 {@code false}): EMA_ACK 첨부 강제 여부. 제출 전이 가드(T1/T3/T10).
 *       운영이 {@code true} 로 바꾸면 코드 변경 없이 즉시 필수화(OQ-3).</li>
 *   <li>{@code ema.reminder.days} (기본 {@code 3}): SUBMITTED/RESUBMITTED 후 무변동 리마인더 임계 N일.
 *       리마인더 스케줄러(PR-E5)가 소비한다.</li>
 * </ul>
 *
 * <h3>로드 정책</h3>
 * <p>매 호출 시 DB 한 번 조회 — 캐싱 미도입({@link com.bluelight.backend.api.admin.manualemail.ManualEmailSettings}
 * 와 동일). row 미존재/파싱 실패 시 기본값 + WARN 로그(graceful fallback).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmaSubmissionSettings {

    /** system_settings 키 — EMA_ACK 첨부 강제 여부 (기본 false). */
    public static final String KEY_ACK_REQUIRED = "ema.ack.required";

    /** system_settings 키 — 리마인더 임계 N일 (기본 3). */
    public static final String KEY_REMINDER_DAYS = "ema.reminder.days";

    /** ack.required 기본값 — soft 도입(OQ-3). */
    public static final boolean DEFAULT_ACK_REQUIRED = false;

    /** reminder.days 기본값. */
    public static final int DEFAULT_REMINDER_DAYS = 3;

    /** reminder.days 하한 — 음수/0 거부, 안전망. */
    static final int REMINDER_DAYS_MIN = 1;

    private final SystemSettingRepository systemSettingRepository;

    /**
     * EMA_ACK 첨부 강제 여부 로드. row 미존재/파싱 실패 시 {@link #DEFAULT_ACK_REQUIRED}.
     */
    public boolean isAckRequired() {
        return systemSettingRepository.findById(KEY_ACK_REQUIRED)
                .map(SystemSetting::getSettingValue)
                .map(v -> "true".equalsIgnoreCase(v.trim()))
                .orElse(DEFAULT_ACK_REQUIRED);
    }

    /**
     * 리마인더 임계 N일 로드. row 미존재/파싱 실패/하한 미달 시 {@link #DEFAULT_REMINDER_DAYS}.
     */
    public int loadReminderDays() {
        return systemSettingRepository.findById(KEY_REMINDER_DAYS)
                .map(SystemSetting::getSettingValue)
                .map(this::parseReminderDays)
                .orElse(DEFAULT_REMINDER_DAYS);
    }

    private int parseReminderDays(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_REMINDER_DAYS;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < REMINDER_DAYS_MIN) {
                log.warn("EMA reminder.days value {} below minimum {} — using default {}",
                        parsed, REMINDER_DAYS_MIN, DEFAULT_REMINDER_DAYS);
                return DEFAULT_REMINDER_DAYS;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            log.warn("EMA reminder.days parse failed: '{}' — using default {}",
                    raw, DEFAULT_REMINDER_DAYS);
            return DEFAULT_REMINDER_DAYS;
        }
    }
}
