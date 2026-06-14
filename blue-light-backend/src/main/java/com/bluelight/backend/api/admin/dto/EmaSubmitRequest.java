package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * EMA 제출(T1) 요청 DTO — ema-submission-tracking-spec.md §7.
 * ELISE 접수번호 필수(설비 행정번호, PII 아님 → 평문).
 */
@Getter
@NoArgsConstructor
public class EmaSubmitRequest {

    @NotBlank(message = "EMA reference number is required")
    @Size(max = 60, message = "EMA reference number must be 60 characters or less")
    private String emaReferenceNo;
}
