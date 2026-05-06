package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 역할 메타데이터 수정 요청 DTO — 모든 필드 optional (부분 업데이트).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRoleMetadataRequest {

    @Size(min = 1, max = 100, message = "Display label must be 1-100 chars")
    private String displayLabel;

    private Boolean assignable;

    private Boolean filterable;

    private Integer sortOrder;
}
