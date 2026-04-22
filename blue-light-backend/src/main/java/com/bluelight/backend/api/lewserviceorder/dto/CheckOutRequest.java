package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Check-out + 방문 보고서 제출 요청 DTO (LEW Service 방문형 리스키닝 PR 3).
 * <p>Manager 가 POST /api/lew-service-manager/orders/{id}/check-out 호출 시 사용.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CheckOutRequest {

    /**
     * 방문 보고서 파일 seq (files.file_seq). 필수.
     */
    @NotNull(message = "visitReportFileSeq is required")
    private Long visitReportFileSeq;

    /**
     * LEW 메모 (nullable)
     */
    @Size(max = 2000, message = "managerNote must be 2000 chars or fewer")
    private String managerNote;
}
