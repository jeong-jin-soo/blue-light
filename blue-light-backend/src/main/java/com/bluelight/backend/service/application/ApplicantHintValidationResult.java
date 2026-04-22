package com.bluelight.backend.service.application;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * {@link ApplicantHintValidator}의 반환값.
 *
 * <ul>
 *   <li>{@link #normalized} — DB에 저장 가능한 정규화된 hint 값</li>
 *   <li>{@link #warnings} — 부적합하여 저장되지 않은 필드들의 경고 목록</li>
 * </ul>
 */
@Getter
@Builder
public class ApplicantHintValidationResult {

    private final NormalizedHints normalized;
    private final List<ApplicantHintWarning> warnings;

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
