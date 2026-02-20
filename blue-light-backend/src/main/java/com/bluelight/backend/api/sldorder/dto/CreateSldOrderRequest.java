package com.bluelight.backend.api.sldorder.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD 전용 주문 생성 요청 DTO (신청자)
 */
@Getter
@NoArgsConstructor
public class CreateSldOrderRequest {

    @Size(max = 255, message = "Address must be 255 characters or less")
    private String address;

    @Size(max = 10, message = "Postal code must be 10 characters or less")
    private String postalCode;

    @Size(max = 50, message = "Building type must be 50 characters or less")
    private String buildingType;

    @Positive(message = "Selected kVA must be a positive number")
    private Integer selectedKva;

    @Size(max = 2000, message = "Applicant note must be 2000 characters or less")
    private String applicantNote;
}
