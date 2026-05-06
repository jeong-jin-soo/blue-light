package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Request for LEW Service 주문 수정 요청 DTO (신청자, PENDING_QUOTE 상태에서만 가능)
 */
@Getter
@NoArgsConstructor
public class UpdateLewServiceOrderRequest {

    @Size(max = 2000, message = "Applicant note must be 2000 characters or less")
    private String applicantNote;
    private Long sketchFileSeq;
}
