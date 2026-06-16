package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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

    /** LEW로 변경 시 필수: 면허번호 */
    @Size(max = 50, message = "Licence number must be 50 characters or less")
    private String lewLicenceNo;

    /** LEW로 변경 시 필수: 등급 (GRADE_7, GRADE_8, GRADE_9) */
    @Size(max = 20, message = "LEW grade must be 20 characters or less")
    private String lewGrade;
}
