package com.bluelight.backend.api.lewserviceorder.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LewService Manager Request for LEW Service 업로드 완료 DTO
 */
@Getter
@NoArgsConstructor
public class LewServiceManagerUploadDto {

    @NotNull(message = "File ID is required")
    private Long fileSeq;

    @Size(max = 2000, message = "Manager note must be 2000 characters or less")
    private String managerNote;
}
