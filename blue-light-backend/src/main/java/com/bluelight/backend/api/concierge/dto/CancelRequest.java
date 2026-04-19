package com.bluelight.backend.api.concierge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Concierge 요청 취소 요청 DTO (★ Phase 1 PR#4 Stage A).
 */
@Getter
@Setter
@NoArgsConstructor
public class CancelRequest {

    @NotBlank
    @Size(max = 500)
    private String reason;
}
