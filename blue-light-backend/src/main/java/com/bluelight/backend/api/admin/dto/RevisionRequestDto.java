package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * LEW 보완 요청 DTO
 */
@Getter
@NoArgsConstructor
public class RevisionRequestDto {

    @NotBlank(message = "Review comment is required")
    @Size(max = 2000, message = "Comment must be 2000 characters or less")
    private String comment;
}
