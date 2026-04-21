package com.bluelight.backend.api.powersocketorder.dto;

import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Power Socket 주문 수정 요청 DTO (신청자, PENDING_QUOTE 상태에서만 가능)
 */
@Getter
@NoArgsConstructor
public class UpdatePowerSocketOrderRequest {

    @Size(max = 2000, message = "Applicant note must be 2000 characters or less")
    private String applicantNote;

    @Null(message = "Sketch file upload not yet supported")
    private Long sketchFileSeq;
}
