package com.bluelight.backend.api.application.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD 작성 요청 DTO (신청자 → LEW)
 */
@Getter
@NoArgsConstructor
public class CreateSldRequestDto {

    @Size(max = 2000, message = "Note must be 2000 characters or less")
    private String note;
}
