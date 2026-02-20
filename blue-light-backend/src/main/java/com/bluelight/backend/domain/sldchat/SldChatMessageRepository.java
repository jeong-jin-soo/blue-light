package com.bluelight.backend.domain.sldchat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * SLD AI 채팅 메시지 Repository
 */
public interface SldChatMessageRepository extends JpaRepository<SldChatMessage, Long> {

    /**
     * 특정 신청의 채팅 이력 조회 (생성순)
     */
    List<SldChatMessage> findByApplicationSeqOrderByCreatedAtAsc(Long applicationSeq);

    /**
     * 특정 신청의 채팅 이력 삭제 (대화 초기화)
     */
    @Modifying
    @Query("DELETE FROM SldChatMessage m WHERE m.applicationSeq = :applicationSeq")
    void deleteByApplicationSeq(Long applicationSeq);

    /**
     * 특정 신청의 채팅 메시지 수 조회
     */
    long countByApplicationSeq(Long applicationSeq);
}
