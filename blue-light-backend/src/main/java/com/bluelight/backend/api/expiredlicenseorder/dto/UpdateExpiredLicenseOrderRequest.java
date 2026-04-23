package com.bluelight.backend.api.expiredlicenseorder.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class UpdateExpiredLicenseOrderRequest {

    @Size(max = 2000, message = "Applicant note must be 2000 characters or less")
    private String applicantNote;
}
