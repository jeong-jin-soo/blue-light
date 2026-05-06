package com.bluelight.backend.api.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * {@code POST /api/auth/login/request-activation} 요청 DTO
 * (★ Kaki Concierge v1.5, Phase 1 PR#2 Stage C).
 */
@Getter
@Setter
@NoArgsConstructor
public class ActivationLinkRequest {

    @NotBlank
    @Email
    @Size(max = 100)
    private String email;
}
