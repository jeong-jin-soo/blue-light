package com.bluelight.backend.api.application.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD 요청 수정 DTO — 신청자가 메모 + 스케치 파일을 업데이트
 */
@Getter
@NoArgsConstructor
public class UpdateSldRequestDto {

    @Size(max = 2000, message = "Note must be 2000 characters or less")
    private String note;

    private Long sketchFileSeq;
}
