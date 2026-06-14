package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * EMA 질의(T2/T4) 요청 DTO — ema-submission-tracking-spec.md §7.
 * 질의 내용 필수. 자유 텍스트라 PII 유입 가능 → UI 가이드에 "개인정보 기재 금지" 명시(OQ-4).
 */
@Getter
@NoArgsConstructor
public class EmaQueryRequest {

    @NotBlank(message = "EMA query note is required")
    @Size(max = 1000, message = "Query note must be 1000 characters or less")
    private String queryNote;
}
