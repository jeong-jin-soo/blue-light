package com.bluelight.backend.api.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ChatRequest {

    @NotBlank(message = "Message is required")
    @Size(max = 1000, message = "Message must be under 1000 characters")
    private String message;

    /** 대화 세션 ID (프론트에서 UUID 생성) */
    private String sessionId;

    /** 이전 대화 컨텍스트 (프론트에서 최근 N개 전송) */
    private List<ChatMessageDto> history;
}
