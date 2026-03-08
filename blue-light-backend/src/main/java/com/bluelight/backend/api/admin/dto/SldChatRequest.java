package com.bluelight.backend.api.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * SLD AI 채팅 요청 DTO
 */
@Getter
@NoArgsConstructor
public class SldChatRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 2000, message = "Message must not exceed 2000 characters")
    private String message;

    /**
     * 첨부 파일 시퀀스 (회로 스케줄 Excel/CSV/이미지 등)
     * - 프런트엔드에서 파일 업로드 후 받은 fileSeq를 전달
     * - null이면 파일 첨부 없음
     */
    private Long attachedFileSeq;
}
