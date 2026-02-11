package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LEW가 SLD 업로드 완료 시 전달하는 DTO
 */
@Getter
@NoArgsConstructor
public class SldUploadedDto {

    @NotNull(message = "File ID is required")
    private Long fileSeq;

    @Size(max = 2000, message = "Note must be 2000 characters or less")
    private String lewNote;
}
