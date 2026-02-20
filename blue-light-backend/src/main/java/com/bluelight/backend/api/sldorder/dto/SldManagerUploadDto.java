package com.bluelight.backend.api.sldorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD Manager SLD 업로드 완료 DTO
 */
@Getter
@NoArgsConstructor
public class SldManagerUploadDto {

    @NotNull(message = "File ID is required")
    private Long fileSeq;

    @Size(max = 2000, message = "Manager note must be 2000 characters or less")
    private String managerNote;
}
