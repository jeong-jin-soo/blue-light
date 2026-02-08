package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LEW 할당 요청 DTO
 */
@Getter
@NoArgsConstructor
public class AssignLewRequest {

    @NotNull(message = "LEW user ID is required")
    private Long lewUserSeq;
}
