package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * EMA 반려(T7) 요청 DTO — ema-submission-tracking-spec.md §7.
 * 반려 사유는 선택(권장). null/blank 면 기존 queryNote 를 유지한다.
 */
@Getter
@NoArgsConstructor
public class EmaRejectRequest {

    @Size(max = 1000, message = "Reason must be 1000 characters or less")
    private String reason;
}
