package com.bluelight.backend.api.expiredlicenseorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class RevisitRequestDto {

    @NotBlank(message = "Revisit comment is required")
    @Size(max = 2000, message = "Comment must be 2000 characters or less")
    private String comment;
}
