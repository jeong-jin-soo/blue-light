package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 사용자 역할 변경 요청 DTO
 */
@Getter
@NoArgsConstructor
public class ChangeRoleRequest {

    @NotBlank(message = "Role is required")
    private String role;
}
