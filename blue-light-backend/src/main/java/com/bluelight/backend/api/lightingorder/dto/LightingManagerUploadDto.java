package com.bluelight.backend.api.lightingorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Lighting Manager Lighting Layout 업로드 완료 DTO
 */
@Getter
@NoArgsConstructor
public class LightingManagerUploadDto {

    @NotNull(message = "File ID is required")
    private Long fileSeq;

    @Size(max = 2000, message = "Manager note must be 2000 characters or less")
    private String managerNote;
}
