package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.application.ApplicationStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Update application status request DTO
 */
@Getter
@NoArgsConstructor
public class UpdateStatusRequest {

    @NotNull(message = "Status is required")
    private ApplicationStatus status;
}
