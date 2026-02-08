package com.bluelight.backend.api.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Update profile request DTO
 */
@Getter
@NoArgsConstructor
public class UpdateProfileRequest {

    @NotBlank(message = "Name is required")
    @Size(max = 50, message = "Name must be 50 characters or less")
    private String name;

    @Size(max = 20, message = "Phone number must be 20 characters or less")
    private String phone;
}
