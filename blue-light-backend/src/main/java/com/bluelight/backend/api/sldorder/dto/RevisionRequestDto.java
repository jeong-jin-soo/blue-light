package com.bluelight.backend.api.sldorder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD 주문 수정 요청 DTO (신청자가 SLD_UPLOADED 상태에서 수정 요청)
 */
@Getter
@NoArgsConstructor
public class RevisionRequestDto {

    @NotBlank(message = "Revision comment is required")
    @Size(max = 2000, message = "Comment must be 2000 characters or less")
    private String comment;
}
