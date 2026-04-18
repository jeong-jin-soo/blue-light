package com.bluelight.backend.api.document.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 배치 생성 요청의 단일 항목
 *
 * - documentTypeCode: catalog 코드 (필수)
 * - customLabel: OTHER 타입일 때 필수 (서비스 계층에서 검증, 200자 제한)
 * - lewNote: LEW 메모 (선택, 1000자 제한)
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRequestItemRequest {

    @NotBlank(message = "documentTypeCode is required")
    @Size(max = 40)
    private String documentTypeCode;

    @Size(max = 200, message = "customLabel must be at most 200 characters")
    private String customLabel;

    @Size(max = 1000, message = "lewNote must be at most 1000 characters")
    private String lewNote;
}
