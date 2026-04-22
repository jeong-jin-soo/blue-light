package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 방문 일정 예약 요청 DTO
 * (LEW Service 방문형 리스키닝 PR 2)
 * <p>
 * Manager 가 POST /api/lew-service-manager/orders/{id}/schedule-visit 호출 시 사용.
 * 상태 전이는 유발하지 않음 — 일정 데이터만 세팅/수정.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleVisitRequest {

    /**
     * 합의된 방문 예정 일시 (필수)
     */
    @NotNull(message = "visitScheduledAt is required")
    private LocalDateTime visitScheduledAt;

    /**
     * 방문 관련 메모 (예: "현관 벨 고장, 전화 주세요"). nullable.
     */
    @Size(max = 2000, message = "visitScheduleNote must be 2000 chars or fewer")
    private String visitScheduleNote;
}
