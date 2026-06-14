package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * EMA 재제출(T3 QUERY_RAISED→ / T10 REJECTED→) 요청 DTO — ema-submission-tracking-spec.md §7.
 * 접수번호는 선택(갱신 시에만 전달). null/blank 면 기존 접수번호를 유지한다.
 */
@Getter
@NoArgsConstructor
public class EmaResubmitRequest {

    @Size(max = 60, message = "EMA reference number must be 60 characters or less")
    private String emaReferenceNo;
}
