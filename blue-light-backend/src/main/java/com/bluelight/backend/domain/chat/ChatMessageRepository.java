package com.bluelight.backend.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 챗봇 대화 메시지 Repository
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    /**
     * 세션별 대화 기록 조회 (시간순)
     */
    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    /**
     * 사용자별 대화 기록 조회 (최신순)
     */
    List<ChatMessage> findByUserSeqOrderByCreatedAtDesc(Long userSeq);
}
