package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.rolemetadata.RoleMetadata;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class RoleMetadataResponse {
    private String roleCode;
    private String displayLabel;
    private Boolean assignable;
    private Boolean filterable;
    private Integer sortOrder;

    public static RoleMetadataResponse from(RoleMetadata entity) {
        return RoleMetadataResponse.builder()
                .roleCode(entity.getRoleCode().name())
                .displayLabel(entity.getDisplayLabel())
                .assignable(entity.getAssignable())
                .filterable(entity.getFilterable())
                .sortOrder(entity.getSortOrder())
                .build();
    }
}
