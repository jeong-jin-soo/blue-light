package com.bluelight.backend.domain.sldchat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SLD AI 채팅 메시지 Entity
 * - 신청(Application)별 AI 대화 이력
 * - 일반 챗봇(chat_messages)과 분리: application_seq 기반
 */
@Entity
@Table(name = "sld_chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SldChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "sld_chat_message_seq")
    private Long sldChatMessageSeq;

    @Column(name = "application_seq", nullable = false)
    private Long applicationSeq;

    @Column(name = "user_seq", nullable = false)
    private Long userSeq;

    @Column(name = "role", nullable = false, length = 10)
    private String role;  // "user" or "assistant"

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "metadata", columnDefinition = "JSON")
    private String metadata;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    @Builder
    public SldChatMessage(Long applicationSeq, Long userSeq, String role, String content, String metadata) {
        this.applicationSeq = applicationSeq;
        this.userSeq = userSeq;
        this.role = role;
        this.content = content;
        this.metadata = metadata;
    }
}
