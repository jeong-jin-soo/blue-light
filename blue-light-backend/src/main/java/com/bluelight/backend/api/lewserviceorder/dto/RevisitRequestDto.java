package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 재방문 요청 DTO (LEW Service 방문형 리스키닝 PR 3 — 신청자가 VISIT_COMPLETED 상태에서 호출).
 * <p>기존 {@code RevisionRequestDto} 의 rename. 구 엔드포인트는 어댑터로 유지.
 */
@Getter
@Setter
@NoArgsConstructor
public class RevisitRequestDto {

    @NotBlank(message = "Revisit comment is required")
    @Size(max = 2000, message = "Comment must be 2000 characters or less")
    private String comment;
}
