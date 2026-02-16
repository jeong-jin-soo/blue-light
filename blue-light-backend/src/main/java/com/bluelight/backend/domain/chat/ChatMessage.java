package com.bluelight.backend.domain.chat;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 챗봇 대화 메시지 Entity (append-only, soft delete 없음)
 */
@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "chat_message_seq")
    private Long chatMessageSeq;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Column(name = "user_seq")
    private Long userSeq;

    @Column(name = "role", nullable = false, length = 10)
    private String role;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Builder
    public ChatMessage(String sessionId, Long userSeq, String role, String content) {
        this.sessionId = sessionId;
        this.userSeq = userSeq;
        this.role = role;
        this.content = content;
        this.createdAt = LocalDateTime.now();
    }
}
