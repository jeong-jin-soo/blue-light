package com.bluelight.backend.api.sldorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD 주문 수정 요청 DTO (신청자, PENDING_QUOTE 상태에서만 가능)
 */
@Getter
@NoArgsConstructor
public class UpdateSldOrderRequest {

    @Size(max = 2000, message = "Applicant note must be 2000 characters or less")
    private String applicantNote;

    private Long sketchFileSeq;
}
