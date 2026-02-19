package com.bluelight.backend.domain.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    /**
     * 사용자별 대화 메시지 삭제 (계정 삭제 시)
     */
    void deleteByUserSeq(Long userSeq);

    /**
     * 보존 기간 초과 메시지 batch 삭제 (PDPA 보유 제한 의무)
     */
    @Modifying
    @Query(value = "DELETE FROM chat_messages WHERE created_at < :cutoff LIMIT :batchSize", nativeQuery = true)
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
