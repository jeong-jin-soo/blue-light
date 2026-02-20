package com.bluelight.backend.api.admin.dto;

import com.bluelight.backend.domain.sldchat.SldChatMessage;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * SLD AI 채팅 메시지 응답 DTO
 */
@Getter
@Builder
public class SldChatMessageResponse {

    private Long sldChatMessageSeq;
    private Long applicationSeq;
    private String role;
    private String content;
    private String metadata;
    private LocalDateTime createdAt;

    public static SldChatMessageResponse from(SldChatMessage msg) {
        return SldChatMessageResponse.builder()
                .sldChatMessageSeq(msg.getSldChatMessageSeq())
                .applicationSeq(msg.getApplicationSeq())
                .role(msg.getRole())
                .content(msg.getContent())
                .metadata(msg.getMetadata())
                .createdAt(msg.getCreatedAt())
                .build();
    }
}
